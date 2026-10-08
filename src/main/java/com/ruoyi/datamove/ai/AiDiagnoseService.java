package com.ruoyi.datamove.ai;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.datamove.ai.domain.AiDiagnosis;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.service.ISyncDatasourceService;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskLog;
import com.ruoyi.datamove.task.mapper.SyncTaskLogMapper;
import com.ruoyi.datamove.task.service.ISyncTaskService;
import com.ruoyi.datamove.util.JdbcUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 失败任务诊断
 *
 * <p>把「任务配置 + 最近失败日志 + 源/目标表结构」喂给模型, 让它像 DBA 一样给结论:
 * 原因是什么、分哪一类、具体怎么改。
 *
 * <p>与配置助手同一套脾气:
 * <ul>
 *   <li>AI 不可用时降级为「错误关键字 → 预案」的本地诊断, 照样能给出有用的建议;</li>
 *   <li>只分析不改数据: 不会碰任务配置、不会改库, 建议里的 SQL 也只是给用户看的文本。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiDiagnoseService {

    private final AiProperties props;
    private final AiChatClient chatClient;
    private final ISyncTaskService taskService;
    private final ISyncDatasourceService datasourceService;
    private final SyncTaskLogMapper logMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 日志正文喂给模型时的最大长度: 堆栈通常几百行, 全塞进去既烧 token 又没用 */
    private static final int MAX_ERROR_LEN = 800;

    public AiDiagnosis diagnose(Long taskId) {
        if (taskId == null) throw new RuntimeException("缺少任务ID");
        SyncTask task = taskService.detail(taskId);
        if (task == null) throw new RuntimeException("任务不存在: " + taskId);

        List<SyncTaskLog> failed = logMapper.selectList(new QueryWrapper<SyncTaskLog>()
                .eq("task_id", taskId).eq("status", "FAILED")
                .orderByDesc("id").last("limit 3"));
        if (failed.isEmpty()) {
            failed = logMapper.selectList(new QueryWrapper<SyncTaskLog>()
                    .eq("task_id", taskId).orderByDesc("id").last("limit 3"));
        }
        if (failed.isEmpty()) {
            throw new RuntimeException("这个任务还没有同步日志, 先跑一次再诊断");
        }

        SyncDatasource src = task.getSourceId() == null ? null : datasourceService.getById(task.getSourceId());
        SyncDatasource tgt = task.getTargetId() == null ? null : datasourceService.getById(task.getTargetId());

        AiDiagnosis res = new AiDiagnosis();
        res.setTaskId(task.getId());
        res.setTaskName(task.getTaskName());
        res.setEvidence(buildEvidence(task, src, tgt, failed));

        boolean ai = false;
        if (props.usable()) {
            try {
                String content = chatClient.chat(systemPrompt(),
                        userPrompt(task, src, tgt, failed));
                JsonNode root = objectMapper.readTree(AiChatClient.stripFence(content));
                res.setCategory(root.path("category").asText("其它").trim());
                res.setSummary(root.path("summary").asText("").trim());
                res.setCause(root.path("cause").asText("").trim());
                res.setSuggestions(readSuggestions(root.path("suggestions")));
                res.setEngine("AI");
                res.setProvider(props.providerLabel());
                res.setModel(props.getModel());
                ai = res.getSuggestions() != null && !res.getSuggestions().isEmpty();
            } catch (Exception e) {
                log.warn("[AI] 任务诊断失败, 降级本地规则: {}", e.getMessage());
                res.setFallbackNote("AI 调用失败(" + e.getMessage() + "), 已改用本地错误关键字诊断");
            }
        } else {
            res.setFallbackNote("未配置 sync.ai.api-key, 当前使用本地错误关键字诊断");
        }

        if (!ai) {
            res.setEngine("RULE");
            String err = firstError(failed);
            res.setCategory(ruleCategory(err));
            res.setCause(ruleCause(err));
            res.setSuggestions(ruleSuggestions(task, err));
            if (res.getSummary() == null || res.getSummary().isEmpty()) {
                res.setSummary("本地诊断: " + ruleCategory(err) + " —— " + abbreviate(err, 120));
            }
        }
        return res;
    }

    /* ==================== 上下文 ==================== */

    private List<String> buildEvidence(SyncTask task, SyncDatasource src, SyncDatasource tgt,
                                       List<SyncTaskLog> logs) {
        List<String> ev = new ArrayList<>();
        ev.add("任务: " + task.getTaskName() + " (#" + task.getId() + ")");
        ev.add("类型: " + task.getTaskType() + " / 模式: " + task.getSyncMode() + " / 状态: " + task.getStatus());
        ev.add("源: " + (src == null ? "未知" : src.getDatasourceName() + "(" + src.getDbName() + ")")
                + " → 目标: " + (tgt == null ? "未知" : tgt.getDatasourceName() + "(" + tgt.getDbName() + ")"));
        ev.add("表: " + task.getTableName() + " / 主键: " + task.getIdField() + " / 时间字段: " + task.getTimeField());
        ev.add("批次: " + task.getBatchSize() + " / 分片: " + task.getShardCount() + " / 限速: " + task.getRateLimit()
                + " / 过滤条件: " + (task.getWhereCondition() == null ? "无" : task.getWhereCondition()));
        for (SyncTaskLog l : logs) {
            ev.add("[" + l.getStatus() + "] 批次#" + l.getBatchNo() + " 分片" + l.getShardNo()
                    + " 起始ID " + l.getBatchStartId() + " 结束ID " + l.getBatchEndId()
                    + " 耗时 " + l.getCostMs() + "ms: " + abbreviate(l.getErrorMsg(), 200));
        }
        return ev;
    }

    /** 源表结构: 只在能读到时附带, 读不到(表被删/没权限)不影响诊断 */
    private String tableStructure(SyncTask task, SyncDatasource src) {
        if (src == null || task.getTableName() == null || task.getTableName().trim().isEmpty()) return "";
        try {
            List<Map<String, String>> cols = JdbcUtils.listColumns(src, task.getTableName().trim());
            if (cols.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("源表 ").append(task.getTableName()).append(" 结构:\n");
            for (Map<String, String> c : cols) {
                sb.append("- ").append(c.get("columnName")).append(" ").append(c.get("columnType"))
                        .append(c.get("columnKey") == null || c.get("columnKey").isEmpty()
                                ? "" : " (" + c.get("columnKey") + ")")
                        .append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /* ==================== Prompt ==================== */

    private String systemPrompt() {
        return "你是一个资深 DBA, 负责诊断 MySQL 数据同步任务的失败原因。\n"
                + "你会拿到任务配置、源/目标库信息(不含密码)、源表结构和最近几条失败日志。\n"
                + "只能输出 JSON, 不要任何解释性文字, 结构如下:\n"
                + "{\"category\":\"连接权限|表结构|数据冲突|类型不兼容|资源超时|配置问题|其它\",\n"
                + " \"summary\":\"一句话结论\",\n"
                + " \"cause\":\"详细原因, 结合日志里的报错信息说明\",\n"
                + " \"suggestions\":[{\"title\":\"建议标题\",\"detail\":\"为什么这么改\",\"action\":\"具体怎么做, 可以是配置改法或一段 SQL\"}]}\n"
                + "要求:\n"
                + "1. 结论必须基于给定的日志报错, 不许臆测;\n"
                + "2. 给 2-4 条建议, 按优先级排序, 每条都要能直接照着做;\n"
                + "3. 涉及改配置时写清改哪个字段(如 batchSize 调小到 500);\n"
                + "4. 你只做分析, 不要输出任何会修改数据库的操作指令以外的闲话。";
    }

    private String userPrompt(SyncTask task, SyncDatasource src, SyncDatasource tgt, List<SyncTaskLog> logs) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("taskName", task.getTaskName());
        ctx.put("taskType", task.getTaskType());
        ctx.put("syncMode", task.getSyncMode());
        ctx.put("status", task.getStatus());
        ctx.put("tableName", task.getTableName());
        ctx.put("idField", task.getIdField());
        ctx.put("timeField", task.getTimeField());
        ctx.put("batchSize", task.getBatchSize());
        ctx.put("shardCount", task.getShardCount());
        ctx.put("rateLimit", task.getRateLimit());
        ctx.put("whereCondition", task.getWhereCondition());
        ctx.put("overwriteFlag", task.getOverwriteFlag());
        ctx.put("sourceDb", src == null ? "未知" : src.getDbName() + "@" + src.getHost() + ":" + src.getPort());
        ctx.put("targetDb", tgt == null ? "未知" : tgt.getDbName() + "@" + tgt.getHost() + ":" + tgt.getPort());

        StringBuilder sb = new StringBuilder("任务配置:\n");
        ctx.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v == null ? "空" : v).append('\n'));
        sb.append('\n').append(tableStructure(task, src));
        sb.append("\n最近日志:\n");
        for (SyncTaskLog l : logs) {
            sb.append("- [").append(l.getStatus()).append("] 批次#").append(l.getBatchNo())
                    .append(" 分片").append(l.getShardNo())
                    .append(" 区间 ").append(l.getBatchStartId()).append("~").append(l.getBatchEndId())
                    .append(" 耗时 ").append(l.getCostMs()).append("ms\n  error: ")
                    .append(abbreviate(l.getErrorMsg(), MAX_ERROR_LEN)).append('\n');
        }
        sb.append("\n请诊断(只输出 JSON)。");
        return sb.toString();
    }

    private List<AiDiagnosis.Suggestion> readSuggestions(JsonNode arr) {
        List<AiDiagnosis.Suggestion> list = new ArrayList<>();
        if (arr == null || !arr.isArray()) return list;
        for (JsonNode n : arr) {
            String title = n.path("title").asText("").trim();
            if (title.isEmpty()) continue;
            list.add(new AiDiagnosis.Suggestion(title,
                    n.path("detail").asText("").trim(),
                    n.path("action").asText("").trim()));
        }
        return list;
    }

    /* ==================== 本地规则诊断 ==================== */

    private String firstError(List<SyncTaskLog> logs) {
        for (SyncTaskLog l : logs) {
            if (l.getErrorMsg() != null && !l.getErrorMsg().trim().isEmpty()) return l.getErrorMsg().trim();
        }
        return "";
    }

    private String ruleCategory(String err) {
        String e = err.toLowerCase(Locale.ROOT);
        if (e.contains("access denied") || e.contains("not allowed to connect") || e.contains("using password")) {
            return "连接权限";
        }
        if (e.contains("unknown column") || e.contains("doesn't exist") || e.contains("table ")) {
            return "表结构";
        }
        if (e.contains("duplicate entry")) return "数据冲突";
        if (e.contains("data truncation") || e.contains("incorrect") || e.contains("invalid")
                || e.contains("out of range") || e.contains("cannot be null")) {
            return "类型不兼容";
        }
        if (e.contains("timeout") || e.contains("timed out") || e.contains("lock wait")
                || e.contains("deadlock") || e.contains("too many connections")) {
            return "资源超时";
        }
        if (e.contains("max_allowed_packet") || e.contains("packet") || e.contains("batch")) return "配置问题";
        if (e.contains("communications link failure") || e.contains("connection refused")
                || e.contains("connect timed out")) {
            return "连接权限";
        }
        return "其它";
    }

    private String ruleCause(String err) {
        if (err.isEmpty()) return "日志里没有记录错误信息, 可能是任务被手动停止或进程重启导致中断。";
        String e = err.toLowerCase(Locale.ROOT);
        if (e.contains("access denied")) return "数据库账号权限不足或密码错误, 连接被拒绝。";
        if (e.contains("not allowed to connect")) return "数据库服务器限制了来源 IP, 当前机器不在白名单里。";
        if (e.contains("unknown column")) return "SQL 里出现了表里没有的字段: 源表改过结构, 或字段映射配了不存在的列。";
        if (e.contains("doesn't exist")) return "表不存在: 目标表可能没建, 或表名大小写/前缀不一致。";
        if (e.contains("duplicate entry")) return "目标表已存在相同主键的数据, 插入时被唯一键拦下。";
        if (e.contains("data truncation") || e.contains("out of range")) {
            return "目标列放不下源数据: 字段长度不够、类型精度不足或字符集不兼容。";
        }
        if (e.contains("lock wait") || e.contains("deadlock")) return "目标库并发写入互相等待锁, 事务被回滚。";
        if (e.contains("max_allowed_packet")) return "单个批次的数据包超过了数据库 max_allowed_packet 限制。";
        if (e.contains("timeout")) return "操作超时: 可能是查询太慢、网络抖动或目标库负载过高。";
        if (e.contains("too many connections")) return "数据库连接数打满, 新连接被拒。";
        return "未匹配到已知错误模式, 原文: " + abbreviate(err, 200);
    }

    private List<AiDiagnosis.Suggestion> ruleSuggestions(SyncTask task, String err) {
        List<AiDiagnosis.Suggestion> list = new ArrayList<>();
        String e = err.toLowerCase(Locale.ROOT);
        if (e.contains("access denied") || e.contains("not allowed to connect")) {
            list.add(new AiDiagnosis.Suggestion("检查账号权限", "同步账号至少要有源库 SELECT、目标库 INSERT/UPDATE 权限",
                    "SHOW GRANTS FOR '用户名'@'%';"));
            list.add(new AiDiagnosis.Suggestion("检查来源 IP 白名单", "MySQL 按 host 授权, 换机器部署就会出现这类报错",
                    "在目标库执行: GRANT ALL ON 库名.* TO '用户'@'新机器IP' IDENTIFIED BY '密码';"));
        } else if (e.contains("unknown column")) {
            list.add(new AiDiagnosis.Suggestion("核对字段映射", "任务里配置的字段在表里不存在",
                    "到任务编辑页重新拉取表结构, 或清空字段映射回到同名同步"));
            list.add(new AiDiagnosis.Suggestion("确认源表结构没变", "源表被加/删列后旧任务会一直报这个错",
                    "SHOW COLUMNS FROM " + task.getTableName() + ";"));
        } else if (e.contains("doesn't exist")) {
            list.add(new AiDiagnosis.Suggestion("确认目标表已建", "全量同步会自动建表, 增量/指定表不会",
                    "先跑一次全量同步让系统自动建表, 或在目标库手工建表"));
        } else if (e.contains("duplicate entry")) {
            list.add(new AiDiagnosis.Suggestion("改用覆盖写入", "目标表已有数据时追加插入会撞主键",
                    "把任务的「写入方式」改成「覆盖写入(先清空目标表)」"));
            list.add(new AiDiagnosis.Suggestion("或跳过已同步区间", "把起始 ID 往后挪, 只同步新增部分",
                    "修改任务的起始 ID, 或直接点「重置」后重新全量同步"));
        } else if (e.contains("data truncation") || e.contains("out of range") || e.contains("incorrect")) {
            list.add(new AiDiagnosis.Suggestion("对齐字段类型", "源与目标列的类型/长度/字符集不一致",
                    "SHOW COLUMNS FROM 源表; SHOW COLUMNS FROM 目标表; 对比后调整目标表"));
            list.add(new AiDiagnosis.Suggestion("把无法对齐的列排除", "目标库自维护的列不必同步",
                    "在任务的「忽略字段」里填上这些列名"));
        } else if (e.contains("max_allowed_packet")) {
            list.add(new AiDiagnosis.Suggestion("调小批次", "单批数据太大, 超过数据库包大小限制",
                    "把批次大小(batchSize)从 " + task.getBatchSize() + " 降到 500 左右"));
        } else if (e.contains("lock wait") || e.contains("deadlock") || e.contains("too many connections")) {
            list.add(new AiDiagnosis.Suggestion("降低并发", "并发写同一批行时容易锁等待",
                    "把分片数降到 1, 并给任务加限速(如 1000 行/秒)"));
        } else if (e.contains("timeout")) {
            list.add(new AiDiagnosis.Suggestion("调小批次 + 加索引", "大表无索引分页会越跑越慢",
                    "给 " + (task.getIdField() == null ? "主键" : task.getIdField()) + " 建索引, 批次调到 1000~2000"));
            list.add(new AiDiagnosis.Suggestion("避开业务高峰", "目标库负载高时会拖慢写入",
                    "把 CRON 改到凌晨执行"));
        } else {
            list.add(new AiDiagnosis.Suggestion("先看完整报错", "本条建议来自本地规则兜底, 只做了关键字匹配",
                    "打开同步日志复制完整 error 信息, 或配置 sync.ai.api-key 后用 AI 诊断"));
            list.add(new AiDiagnosis.Suggestion("缩小范围重试", "先用小批次验证配置本身没问题",
                    "把批次大小改成 100 手动跑一次, 通过后再调回去"));
        }
        return list;
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
