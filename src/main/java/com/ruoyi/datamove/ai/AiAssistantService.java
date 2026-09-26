package com.ruoyi.datamove.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.datamove.ai.domain.AiModifyResult;
import com.ruoyi.datamove.ai.domain.AiParseResult;
import com.ruoyi.datamove.ai.domain.AiTaskDraft;
import com.ruoyi.datamove.auth.service.IAuthService;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.service.ISyncDatasourceService;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskFieldMapping;
import com.ruoyi.datamove.task.service.ISyncTaskFieldMappingService;
import com.ruoyi.datamove.task.service.ISyncTaskService;
import com.ruoyi.datamove.util.JdbcUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI 任务配置助手
 *
 * <p>职责: 把一句人话 → 任务配置草稿 → (用户确认) → 一个普通任务。
 *
 * <p>两条硬原则:
 * <ol>
 *   <li>AI 只是"帮你把表单填好", 生成出来的就是普通 sync_task, 不引入任何新的任务类型;</li>
 *   <li>AI 不可用(未配 Key / 超时 / 返回脏数据)时自动降级本地规则解析, 功能不中断。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAssistantService {

    /** 过滤条件里出现的这些词一律拒绝: 这是拼进 SQL 的片段, 不能给人注入的机会 */
    private static final Pattern UNSAFE_WHERE = Pattern.compile(
            "(?i).*(;|--|/\\*|\\*/|\\b(drop|delete|truncate|alter|create|grant|union|sleep|benchmark|information_schema|into\\s+outfile)\\b).*");

    private static final int MAX_RATE = 200000;

    private final AiProperties props;
    private final AiChatClient chatClient;
    private final AiRuleParser ruleParser;
    private final ISyncDatasourceService datasourceService;
    private final ISyncTaskService taskService;
    private final ISyncTaskFieldMappingService mappingService;
    private final IAuthService authService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /* ==================== 1. 自然语言 → 任务草稿 ==================== */

    public AiParseResult parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new RuntimeException("请先描述你的同步需求");
        }
        List<SyncDatasource> dsList = datasourceService.listAll();
        AiParseResult res = new AiParseResult();

        AiTaskDraft draft = null;
        String summary = null;
        List<String> explanations = new ArrayList<>();

        if (props.usable()) {
            try {
                String content = chatClient.chat(buildSystemPrompt(dsList), buildUserPrompt(text));
                draft = parseDraftJson(content, explanations);
                JsonNode root = objectMapper.readTree(AiChatClient.stripFence(content));
                summary = root.path("summary").asText(null);
                res.setEngine("AI");
                res.setModel(props.getModel());
                res.setProvider(props.providerLabel());
            } catch (Exception e) {
                log.warn("[AI] 解析失败, 降级本地规则: {}", e.getMessage());
                res.setFallbackNote("AI 调用失败(" + e.getMessage() + "), 已自动改用本地规则解析");
                draft = null;
            }
        } else {
            res.setFallbackNote("未配置 sync.ai.api-key, 当前使用本地规则解析");
        }

        // 规则解析永远跑一遍: AI 漏掉的字段用它补, AI 不可用时直接作为结果
        List<String> ruleNotes = new ArrayList<>();
        AiTaskDraft ruleDraft = ruleParser.parse(text, dsList, null, ruleNotes);

        if (draft == null) {
            draft = ruleDraft;
            explanations.addAll(ruleNotes);
            explanations.add(0, "本次由本地规则解析产出: 关键词 + 正则, 只填识别到的字段(库/表/条件/脱敏/限速/定时/分片); "
                    + "配好 sync.ai.api-key 后会改由大模型理解整句话");
            res.setEngine("RULE");
        } else {
            if (props.usable()) {
                explanations.add(0, "本次由 AI 模型(" + props.providerLabel() + " / " + props.getModel() + ")解析, "
                        + "本地规则仅用于补 AI 未填的字段");
            }
            copyNulls(draft, ruleDraft);
            explanations.addAll(ruleNotes);
        }

        normalize(draft);
        resolveDatasource(draft, dsList);

        res.setDraft(draft);
        res.setExplanations(explanations);
        res.setSummary(summary != null && !summary.isEmpty() ? summary : buildSummary(draft));
        res.setRisks(analyzeRisks(draft, dsList));
        res.setMissing(findMissing(draft));
        return res;
    }

    /* ==================== 2. 草稿 → 建任务 ==================== */

    public Long apply(AiTaskDraft draft) {
        normalize(draft);
        List<SyncDatasource> dsList = datasourceService.listAll();
        resolveDatasource(draft, dsList);
        List<String> missing = findMissing(draft);
        if (!missing.isEmpty()) {
            throw new RuntimeException("还缺少必填项: " + String.join("、", missing));
        }
        SyncTask t = toTask(draft);
        Long taskId = taskService.add(t);
        if (Boolean.TRUE.equals(draft.getAutoMapping())) {
            try {
                autoMapping(taskId, draft);
            } catch (Exception e) {
                log.warn("[AI] 自动生成字段映射失败, taskId={}: {}", taskId, e.getMessage());
            }
        }
        return taskId;
    }

    /* ==================== 3. AI 修改已有任务 ==================== */

    /** 预览: 只算差异, 不落库 */
    public AiModifyResult previewModify(Long taskId, String text) {
        SyncTask task = taskService.detail(taskId);
        if (task == null) throw new RuntimeException("任务不存在: " + taskId);
        AiTaskDraft base = draftOf(task);

        AiModifyResult res = new AiModifyResult();
        res.setTaskId(task.getId());
        res.setTaskName(task.getTaskName());

        AiTaskDraft draft = null;
        String summary = null;
        List<String> explanations = new ArrayList<>();

        if (props.usable()) {
            try {
                String content = chatClient.chat(buildSystemPrompt(datasourceService.listAll()),
                        buildModifyPrompt(task, base, text));
                draft = parseDraftJson(content, explanations);
                JsonNode root = objectMapper.readTree(AiChatClient.stripFence(content));
                summary = root.path("summary").asText(null);
                res.setEngine("AI");
            } catch (Exception e) {
                log.warn("[AI] 修改解析失败, 降级本地规则: {}", e.getMessage());
                draft = null;
            }
        }

        AiTaskDraft ruleDraft;
        if (draft == null) {
            ruleDraft = ruleParser.parse(text, datasourceService.listAll(), copyDraft(base), explanations);
            draft = ruleDraft;
            res.setEngine("RULE");
            explanations.add(0, "本次由本地规则解析修改: 以当前任务配置为基线, 只改规则识别到的那几项; "
                    + "配好 sync.ai.api-key 后会改由大模型理解整句话");
            if (summary == null) summary = "按规则解析: " + text;
        } else {
            // AI 只允许改它明确输出的字段: 以当前任务配置为基线, 用 AI 结果覆盖非 null 项
            AiTaskDraft merged = copyDraft(base);
            copyNonNulls(merged, draft);
            draft = merged;
        }

        normalize(draft);
        res.setSummary(summary == null || summary.isEmpty() ? "按指令调整任务配置" : summary);
        res.setChanges(diff(base, draft));
        res.setRisks(analyzeRisks(draft, datasourceService.listAll()));
        res.setDraft(draft);
        return res;
    }

    /** 应用: 把差异写回任务 (走 taskService.update, 审计日志照常记录) */
    public AiModifyResult applyModify(Long taskId, AiTaskDraft draft) {
        SyncTask db = taskService.detail(taskId);
        if (db == null) throw new RuntimeException("任务不存在: " + taskId);
        AiTaskDraft base = draftOf(db);
        normalize(draft);

        SyncTask patch = new SyncTask();
        patch.setId(taskId);
        patch.setTaskName(db.getTaskName());
        patch.setTaskType(db.getTaskType());
        patch.setSyncMode(db.getSyncMode());
        patch.setSourceId(db.getSourceId());
        patch.setTargetId(db.getTargetId());
        patch.setTableName(db.getTableName());
        patch.setIdField(db.getIdField());
        patch.setTimeField(db.getTimeField());
        patch.setStartId(db.getStartId());
        patch.setStartTime(db.getStartTime());
        patch.setBatchSize(db.getBatchSize());
        patch.setShardCount(db.getShardCount());
        patch.setOverwriteFlag(db.getOverwriteFlag());
        patch.setIgnoreFields(db.getIgnoreFields());
        patch.setWhereCondition(db.getWhereCondition());
        patch.setRateLimit(db.getRateLimit());
        patch.setDingtalkWebhook(db.getDingtalkWebhook());
        patch.setAlertEmail(db.getAlertEmail());
        patch.setCanalHost(db.getCanalHost());
        patch.setCanalPort(db.getCanalPort());
        patch.setCanalDestination(db.getCanalDestination());
        patch.setBinlogDmlTypes(db.getBinlogDmlTypes());
        patch.setTriggerType(db.getTriggerType());
        patch.setCronExpr(db.getCronExpr());
        patch.setEventToken(db.getEventToken());
        patch.setRemark(db.getRemark());
        // 只覆盖草稿里明确给了的字段
        copyNonNulls(patch, draft, "sourceId", "targetId", "taskName", "tableName", "taskType", "syncMode",
                "idField", "timeField", "batchSize", "shardCount", "overwriteFlag", "ignoreFields",
                "whereCondition", "rateLimit", "triggerType", "cronExpr",
                "canalHost", "canalPort", "canalDestination", "binlogDmlTypes");
        // 定时表达式与调度方式必须成对, 否则 update 里归一化会报错
        if ("CRON".equalsIgnoreCase(patch.getTriggerType())
                && (patch.getCronExpr() == null || patch.getCronExpr().trim().isEmpty())) {
            patch.setCronExpr("0 0 2 * * ?");
        }
        taskService.update(patch);

        AiModifyResult res = new AiModifyResult();
        res.setTaskId(taskId);
        res.setTaskName(patch.getTaskName());
        res.setChanges(diff(base, draftOf(taskService.detail(taskId))));
        res.setRisks(analyzeRisks(draft, datasourceService.listAll()));
        return res;
    }

    /** 当前 AI 状态: 前端用来提示"未配置 AI, 走规则解析" */
    public AiParseResult status() {
        AiParseResult res = new AiParseResult();
        res.setEngine(props.usable() ? "AI" : "RULE");
        res.setModel(props.usable() ? props.getModel() : null);
        res.setProvider(props.usable() ? props.providerLabel() : null);
        res.setSummary(props.usable()
                ? "AI 已启用: " + props.providerLabel() + " / " + props.getModel()
                : "未配置 AI(sync.ai.api-key), 当前使用本地规则解析");
        return res;
    }

    /* ==================== 提示词 ==================== */

    private String buildSystemPrompt(List<SyncDatasource> dsList) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 DataMove(MySQL 数据同步工具)的配置助手。把用户的自然语言需求转换成同步任务配置。\n");
        sb.append("只输出一个 JSON 对象, 不要解释, 不要代码围栏, 不要多余文字。\n\n");
        sb.append("JSON 字段(未提及的字段输出 null, 不要臆造):\n");
        sb.append("{\n");
        sb.append("  \"summary\": \"一句话中文总结你理解到的需求\",\n");
        sb.append("  \"taskName\": \"任务名, 简洁\",\n");
        sb.append("  \"sourceDatasourceId\": 源数据源ID(数字),\n");
        sb.append("  \"targetDatasourceId\": 目标数据源ID(数字),\n");
        sb.append("  \"tableName\": \"同步表名\",\n");
        sb.append("  \"taskType\": \"FULL 或 INCR\",\n");
        sb.append("  \"syncMode\": \"ID 或 TIME 或 BINLOG\",\n");
        sb.append("  \"idField\": \"主键字段名\",\n");
        sb.append("  \"timeField\": \"时间字段名\",\n");
        sb.append("  \"batchSize\": 每批行数(数字),\n");
        sb.append("  \"shardCount\": 并行分片数(数字, 默认1),\n");
        sb.append("  \"overwriteFlag\": 0或1(1=先清空目标表再写入),\n");
        sb.append("  \"ignoreFields\": \"逗号分隔的字段名(不同步这些列)\",\n");
        sb.append("  \"whereCondition\": \"源表过滤条件, 不含 WHERE 关键字的 SQL 片段\",\n");
        sb.append("  \"rateLimit\": 限速(行/秒, 数字),\n");
        sb.append("  \"triggerType\": \"MANUAL 或 CRON 或 EVENT\",\n");
        sb.append("  \"cronExpr\": \"Spring 6位 CRON: 秒 分 时 日 月 周\",\n");
        sb.append("  \"canalHost\": \"Canal 地址\",\n");
        sb.append("  \"canalPort\": Canal端口(数字),\n");
        sb.append("  \"canalDestination\": \"Canal destination\",\n");
        sb.append("  \"binlogDmlTypes\": \"INSERT,UPDATE,DELETE 的子集\",\n");
        sb.append("  \"maskFields\": [\"需要脱敏的字段名\"],\n");
        sb.append("  \"explanations\": [\"每条中文解释: 为什么这么配\"]\n");
        sb.append("}\n\n");
        sb.append("硬性规则:\n");
        sb.append("1. 提到增量/binlog/实时 → taskType=INCR, syncMode=BINLOG; 提到全量 → taskType=FULL(按 ID 或 TIME 游标)。\n");
        sb.append("2. whereCondition 只允许列与常量比较(如 status=1、create_time >= '2026-01-01'), 禁止分号、注释、子查询、DDL/DML。\n");
        sb.append("3. 脱敏需求: 引擎目前只支持「整字段排除」, 把要脱敏的字段同时放进 maskFields 和 ignoreFields。\n");
        sb.append("4. 数据源只能从下面列表里选, 用它们的 id, 不要自己取名。\n");
        sb.append("5. 定时需求用 Spring 6 位 CRON(秒 分 时 日 月 周), 如每天凌晨2点 = \"0 0 2 * * ?\"。\n");
        sb.append("6. 不确定就留 null, 不要瞎猜。\n\n");
        sb.append("可用数据源:\n");
        if (dsList == null || dsList.isEmpty()) {
            sb.append("[]\n");
        } else {
            for (SyncDatasource ds : dsList) {
                sb.append("- id=").append(ds.getId())
                        .append(", name=").append(ds.getDatasourceName())
                        .append(", db=").append(ds.getDbName())
                        .append(" (").append(ds.getHost()).append(":").append(ds.getPort()).append(")\n");
            }
        }
        return sb.toString();
    }

    private String buildUserPrompt(String text) {
        return "用户需求: " + text + "\n\n请输出配置 JSON。";
    }

    private String buildModifyPrompt(SyncTask task, AiTaskDraft current, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("这是任务 #").append(task.getId()).append(" (").append(task.getTaskName()).append(") 的当前配置:\n");
        try {
            sb.append(objectMapper.writeValueAsString(current)).append("\n\n");
        } catch (Exception ignore) {
            sb.append(current.toString()).append("\n\n");
        }
        sb.append("用户的修改指令: ").append(text).append("\n\n");
        sb.append("请只输出需要修改的字段(JSON, 同上结构), 没提到的字段一律输出 null —— 不要改动用户没要求改的配置。\n");
        return sb.toString();
    }

    /* ==================== JSON 解析 ==================== */

    private AiTaskDraft parseDraftJson(String content, List<String> explanations) {
        try {
            JsonNode root = objectMapper.readTree(AiChatClient.stripFence(content));
            JsonNode node = root;
            if (root.has("config") && root.get("config").isObject()) node = root.get("config");
            AiTaskDraft d = objectMapper.treeToValue(node, AiTaskDraft.class);
            JsonNode exps = root.path("explanations");
            if (exps.isArray()) {
                for (JsonNode e : exps) {
                    String s = e.asText(null);
                    if (s != null && !s.trim().isEmpty()) explanations.add(s.trim());
                }
            }
            return d;
        } catch (Exception e) {
            throw new RuntimeException("AI 返回的内容不是合法 JSON: " + e.getMessage());
        }
    }

    /* ==================== 归一化 / 校验 ==================== */

    private void normalize(AiTaskDraft d) {
        if (d.getTaskType() != null) d.setTaskType(d.getTaskType().trim().toUpperCase(Locale.ROOT));
        if (d.getSyncMode() != null) d.setSyncMode(d.getSyncMode().trim().toUpperCase(Locale.ROOT));
        if (d.getTriggerType() != null) d.setTriggerType(d.getTriggerType().trim().toUpperCase(Locale.ROOT));
        if (d.getTaskType() == null) d.setTaskType("FULL");
        if ("INCR".equals(d.getTaskType())) {
            d.setSyncMode("BINLOG");
            if (d.getCanalHost() == null) d.setCanalHost("127.0.0.1");
            if (d.getCanalPort() == null) d.setCanalPort(11111);
            if (d.getCanalDestination() == null) d.setCanalDestination("example");
        } else {
            if (d.getSyncMode() == null || "BINLOG".equals(d.getSyncMode())) {
                d.setSyncMode(d.getTimeField() != null ? "TIME" : "ID");
            }
            if ("ID".equals(d.getSyncMode()) && d.getIdField() == null) d.setIdField("id");
            if ("TIME".equals(d.getSyncMode()) && d.getTimeField() == null) d.setTimeField("update_time");
        }
        if (d.getBatchSize() == null || d.getBatchSize() <= 0) d.setBatchSize(1000);
        if (d.getBatchSize() > 50000) d.setBatchSize(50000);
        if (d.getShardCount() == null || d.getShardCount() < 1) d.setShardCount(1);
        if (d.getShardCount() > 32) d.setShardCount(32);
        if (d.getOverwriteFlag() == null) d.setOverwriteFlag(0);
        if (d.getTriggerType() == null) d.setTriggerType("MANUAL");
        if ("CRON".equals(d.getTriggerType()) && (d.getCronExpr() == null || d.getCronExpr().trim().isEmpty())) {
            d.setCronExpr("0 0 2 * * ?");
        }
        if (!"CRON".equals(d.getTriggerType())) d.setCronExpr(null);
        if (d.getRateLimit() != null && (d.getRateLimit() <= 0 || d.getRateLimit() > MAX_RATE)) d.setRateLimit(null);
        if (d.getWhereCondition() != null) {
            String w = d.getWhereCondition().trim();
            // 模型偶尔会带上 WHERE 关键字, 去掉
            w = w.replaceAll("(?i)^where\\s+", "").replaceAll(";+\\s*$", "").trim();
            if (w.isEmpty()) {
                d.setWhereCondition(null);
            } else if (UNSAFE_WHERE.matcher(w).matches()) {
                throw new RuntimeException("过滤条件含不允许的内容(分号/注释/DDL/DML): " + w);
            } else {
                d.setWhereCondition(w);
            }
        }
        if (d.getTableName() != null) d.setTableName(d.getTableName().trim());
        if (d.getTaskName() != null) d.setTaskName(d.getTaskName().trim());
    }

    private void resolveDatasource(AiTaskDraft d, List<SyncDatasource> dsList) {
        if (dsList == null || dsList.isEmpty()) return;
        if (d.getSourceDatasourceId() == null && StringUtils.hasText(d.getSourceDatasourceName())) {
            SyncDatasource ds = findByName(dsList, d.getSourceDatasourceName());
            if (ds != null) d.setSourceDatasourceId(ds.getId());
        }
        if (d.getTargetDatasourceId() == null && StringUtils.hasText(d.getTargetDatasourceName())) {
            SyncDatasource ds = findByName(dsList, d.getTargetDatasourceName());
            if (ds != null) d.setTargetDatasourceId(ds.getId());
        }
        for (SyncDatasource ds : dsList) {
            if (ds.getId().equals(d.getSourceDatasourceId())) d.setSourceDatasourceName(ds.getDatasourceName());
            if (ds.getId().equals(d.getTargetDatasourceId())) d.setTargetDatasourceName(ds.getDatasourceName());
        }
    }

    private SyncDatasource findByName(List<SyncDatasource> dsList, String name) {
        for (SyncDatasource ds : dsList) {
            if (ds.getDatasourceName() != null && ds.getDatasourceName().equalsIgnoreCase(name.trim())) return ds;
        }
        for (SyncDatasource ds : dsList) {
            if (ds.getDatasourceName() != null && ds.getDatasourceName().toLowerCase(Locale.ROOT)
                    .contains(name.trim().toLowerCase(Locale.ROOT))) return ds;
        }
        return null;
    }

    private List<String> findMissing(AiTaskDraft d) {
        List<String> missing = new ArrayList<>();
        if (d.getSourceDatasourceId() == null) missing.add("源数据源");
        if (d.getTargetDatasourceId() == null) missing.add("目标数据源");
        if (d.getTableName() == null || d.getTableName().trim().isEmpty()) missing.add("同步表");
        if (d.getSourceDatasourceId() != null && d.getSourceDatasourceId().equals(d.getTargetDatasourceId())) {
            missing.add("源库与目标库不能是同一个");
        }
        if (d.getTaskName() == null || d.getTaskName().trim().isEmpty()) {
            d.setTaskName("AI-" + (d.getTableName() == null ? "任务" : d.getTableName()));
        }
        return missing;
    }

    /* ==================== 风险提示 ==================== */

    private List<AiParseResult.Risk> analyzeRisks(AiTaskDraft d, List<SyncDatasource> dsList) {
        List<AiParseResult.Risk> risks = new ArrayList<>();
        if (d.getWhereCondition() != null) {
            risks.add(new AiParseResult.Risk("INFO", "过滤条件已生效",
                    "同步时源表查询会追加 AND (" + d.getWhereCondition() + "), 建议该条件里的字段有索引"));
        }
        if (d.getRateLimit() != null) {
            risks.add(new AiParseResult.Risk("INFO", "限速 " + d.getRateLimit() + " 行/秒",
                    "引擎按批次节奏休眠控速; 限速越低总耗时越长, 大表请预留足够时间"));
        }
        if (Integer.valueOf(1).equals(d.getOverwriteFlag())) {
            risks.add(new AiParseResult.Risk("WARN", "覆盖写入会清空目标表",
                    "任务启动时会先清空目标表再写入, 目标库原有数据会被删除"));
        }
        if (d.getIgnoreFields() != null && !d.getIgnoreFields().trim().isEmpty()) {
            risks.add(new AiParseResult.Risk("WARN", "忽略字段 = 目标列留空",
                    "被忽略的列(" + d.getIgnoreFields() + ")不会写入目标库, 目标表这些列必须允许为 NULL, 否则整批插入会失败"));
        }
        if ("INCR".equals(d.getTaskType())) {
            risks.add(new AiParseResult.Risk("WARN", "增量任务依赖 Canal",
                    "需先启动 Canal 服务, 且 instance filter 必须包含「源库.源表」, 否则收不到 binlog 事件"));
        }
        if (d.getShardCount() != null && d.getShardCount() > 1) {
            risks.add(new AiParseResult.Risk("WARN", "分片并行要求数值型主键",
                    "按主键 MIN/MAX 均分 " + d.getShardCount() + " 段并行, 主键必须数值型且分布均匀; 已有断点时会自动回退单线程"));
        }
        // 真去源库看一眼: 表在不在、主键/时间字段在不在
        if (d.getSourceDatasourceId() != null && StringUtils.hasText(d.getTableName())) {
            SyncDatasource src = null;
            if (dsList != null) {
                for (SyncDatasource ds : dsList) {
                    if (ds.getId().equals(d.getSourceDatasourceId())) src = ds;
                }
            }
            if (src != null) {
                try {
                    List<Map<String, String>> cols = JdbcUtils.listColumns(src, d.getTableName());
                    if (cols == null || cols.isEmpty()) {
                        risks.add(new AiParseResult.Risk("WARN", "源表不存在或读不到字段",
                                "数据源「" + src.getDatasourceName() + "」里没有表 `" + d.getTableName() + "`, 请检查表名/库"));
                    } else {
                        Set<String> names = new HashSet<>();
                        for (Map<String, String> c : cols) {
                            String n = c.get("columnName");
                            if (n != null) names.add(n.toLowerCase(Locale.ROOT));
                        }
                        if ("ID".equals(d.getSyncMode()) && d.getIdField() != null
                                && !names.contains(d.getIdField().toLowerCase(Locale.ROOT))) {
                            risks.add(new AiParseResult.Risk("WARN", "主键字段在源表里不存在",
                                    "源表没有列 `" + d.getIdField() + "`, 请改成真实主键列名"));
                        }
                        if ("TIME".equals(d.getSyncMode()) && d.getTimeField() != null
                                && !names.contains(d.getTimeField().toLowerCase(Locale.ROOT))) {
                            risks.add(new AiParseResult.Risk("WARN", "时间字段在源表里不存在",
                                    "源表没有列 `" + d.getTimeField() + "`, 请改成真实时间列名"));
                        }
                    }
                } catch (Exception e) {
                    risks.add(new AiParseResult.Risk("WARN", "源库连通性检查未通过",
                            "读取源表结构失败: " + e.getMessage()));
                }
            }
        }
        return risks;
    }

    /* ==================== 任务转换 ==================== */

    private SyncTask toTask(AiTaskDraft d) {
        SyncTask t = new SyncTask();
        t.setTaskName(d.getTaskName());
        t.setTaskType(d.getTaskType());
        t.setSyncMode(d.getSyncMode());
        t.setSourceId(d.getSourceDatasourceId());
        t.setTargetId(d.getTargetDatasourceId());
        t.setTableName(d.getTableName());
        t.setIdField("ID".equals(d.getSyncMode()) ? d.getIdField() : (d.getIdField() == null ? "id" : d.getIdField()));
        t.setTimeField(d.getTimeField());
        t.setStartId(0L);
        t.setBatchSize(d.getBatchSize());
        t.setShardCount(d.getShardCount());
        t.setOverwriteFlag(d.getOverwriteFlag());
        t.setIgnoreFields(d.getIgnoreFields());
        t.setWhereCondition(d.getWhereCondition());
        t.setRateLimit(d.getRateLimit());
        t.setTriggerType(d.getTriggerType());
        t.setCronExpr(d.getCronExpr());
        if ("INCR".equals(d.getTaskType())) {
            t.setCanalHost(d.getCanalHost());
            t.setCanalPort(d.getCanalPort());
            t.setCanalDestination(d.getCanalDestination());
            t.setBinlogDmlTypes(d.getBinlogDmlTypes());
        }
        t.setRemark(appendRemark(d.getRemark(), "由 AI 配置助手创建"));
        try {
            if (authService.currentUser() != null) t.setCreateBy(authService.currentUser().getUserName());
        } catch (Exception ignore) {
            /* 取不到操作人就留空 */
        }
        return t;
    }

    private static String appendRemark(String old, String add) {
        return (old == null || old.trim().isEmpty()) ? add : old + "\n" + add;
    }

    /** 同名列自动配对: 源表列 ∩ 目标表列 - 忽略字段 */
    private void autoMapping(Long taskId, AiTaskDraft d) {
        SyncDatasource src = datasourceService.getById(d.getSourceDatasourceId());
        SyncDatasource tgt = datasourceService.getById(d.getTargetDatasourceId());
        Set<String> ignored = new HashSet<>();
        if (StringUtils.hasText(d.getIgnoreFields())) {
            for (String s : d.getIgnoreFields().split(",")) {
                if (StringUtils.hasText(s)) ignored.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        Set<String> tgtCols = new HashSet<>();
        for (Map<String, String> c : JdbcUtils.listColumns(tgt, d.getTableName())) {
            String n = c.get("columnName");
            if (n != null) tgtCols.add(n.toLowerCase(Locale.ROOT));
        }
        List<SyncTaskFieldMapping> mappings = new ArrayList<>();
        int sort = 0;
        for (Map<String, String> c : JdbcUtils.listColumns(src, d.getTableName())) {
            String name = c.get("columnName");
            if (name == null) continue;
            if (ignored.contains(name.toLowerCase(Locale.ROOT))) continue;
            if (!tgtCols.contains(name.toLowerCase(Locale.ROOT))) continue;
            SyncTaskFieldMapping m = new SyncTaskFieldMapping();
            m.setTaskId(taskId);
            m.setSourceField(name);
            m.setTargetField(name);
            m.setSortNo(sort++);
            mappings.add(m);
        }
        if (!mappings.isEmpty()) mappingService.replace(taskId, mappings);
    }

    /* ==================== 修改场景: 草稿与差异 ==================== */

    private AiTaskDraft draftOf(SyncTask t) {
        AiTaskDraft d = new AiTaskDraft();
        d.setTaskName(t.getTaskName());
        d.setTableName(t.getTableName());
        d.setTaskType(t.getTaskType());
        d.setSyncMode(t.getSyncMode());
        d.setSourceDatasourceId(t.getSourceId());
        d.setTargetDatasourceId(t.getTargetId());
        d.setIdField(t.getIdField());
        d.setTimeField(t.getTimeField());
        d.setBatchSize(t.getBatchSize());
        d.setShardCount(t.getShardCount());
        d.setOverwriteFlag(t.getOverwriteFlag());
        d.setIgnoreFields(t.getIgnoreFields());
        d.setWhereCondition(t.getWhereCondition());
        d.setRateLimit(t.getRateLimit());
        d.setTriggerType(t.getTriggerType());
        d.setCronExpr(t.getCronExpr());
        d.setCanalHost(t.getCanalHost());
        d.setCanalPort(t.getCanalPort());
        d.setCanalDestination(t.getCanalDestination());
        d.setBinlogDmlTypes(t.getBinlogDmlTypes());
        return d;
    }

    private List<AiModifyResult.Change> diff(AiTaskDraft before, AiTaskDraft after) {
        List<AiModifyResult.Change> changes = new ArrayList<>();
        addChange(changes, "taskName", "任务名称", before.getTaskName(), after.getTaskName());
        addChange(changes, "sourceDatasourceId", "源数据源", before.getSourceDatasourceId(), after.getSourceDatasourceId());
        addChange(changes, "targetDatasourceId", "目标数据源", before.getTargetDatasourceId(), after.getTargetDatasourceId());
        addChange(changes, "tableName", "同步表", before.getTableName(), after.getTableName());
        addChange(changes, "taskType", "任务类型", before.getTaskType(), after.getTaskType());
        addChange(changes, "syncMode", "同步模式", before.getSyncMode(), after.getSyncMode());
        addChange(changes, "idField", "主键字段", before.getIdField(), after.getIdField());
        addChange(changes, "timeField", "时间字段", before.getTimeField(), after.getTimeField());
        addChange(changes, "batchSize", "批次大小", before.getBatchSize(), after.getBatchSize());
        addChange(changes, "shardCount", "分片数", before.getShardCount(), after.getShardCount());
        addChange(changes, "overwriteFlag", "覆盖写入", before.getOverwriteFlag(), after.getOverwriteFlag());
        addChange(changes, "ignoreFields", "忽略字段", before.getIgnoreFields(), after.getIgnoreFields());
        addChange(changes, "whereCondition", "过滤条件", before.getWhereCondition(), after.getWhereCondition());
        addChange(changes, "rateLimit", "限速(行/秒)", before.getRateLimit(), after.getRateLimit());
        addChange(changes, "triggerType", "调度方式", before.getTriggerType(), after.getTriggerType());
        addChange(changes, "cronExpr", "CRON 表达式", before.getCronExpr(), after.getCronExpr());
        addChange(changes, "canalHost", "Canal 地址", before.getCanalHost(), after.getCanalHost());
        addChange(changes, "canalPort", "Canal 端口", before.getCanalPort(), after.getCanalPort());
        addChange(changes, "canalDestination", "Canal destination", before.getCanalDestination(), after.getCanalDestination());
        addChange(changes, "binlogDmlTypes", "binlog DML 过滤", before.getBinlogDmlTypes(), after.getBinlogDmlTypes());
        return changes;
    }

    private static void addChange(List<AiModifyResult.Change> changes, String field, String label, Object oldV, Object newV) {
        String o = oldV == null ? "" : String.valueOf(oldV);
        String n = newV == null ? "" : String.valueOf(newV);
        if (!o.equals(n)) changes.add(new AiModifyResult.Change(field, label, o, n));
    }

    private String buildSummary(AiTaskDraft d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.getSourceDatasourceName() == null ? "源库?" : d.getSourceDatasourceName())
                .append(" → ")
                .append(d.getTargetDatasourceName() == null ? "目标库?" : d.getTargetDatasourceName())
                .append(", 表 `").append(d.getTableName() == null ? "?" : d.getTableName()).append("`, ")
                .append("INCR".equals(d.getTaskType()) ? "增量(binlog)" : "全量(" + d.getSyncMode() + "游标)");
        if (d.getWhereCondition() != null) sb.append(", 过滤 ").append(d.getWhereCondition());
        if (d.getRateLimit() != null) sb.append(", 限速 ").append(d.getRateLimit()).append(" 行/秒");
        return sb.toString();
    }

    /* ==================== 反射小工具: 空值合并 ==================== */

    /** target 里为 null 的字段, 用 source 的值补上 (AI 漏填 → 规则补) */
    private void copyNulls(AiTaskDraft target, AiTaskDraft source) {
        for (Field f : AiTaskDraft.class.getDeclaredFields()) {
            if (f.getName().equals("serialVersionUID")) continue;
            f.setAccessible(true);
            try {
                if (f.get(target) == null) f.set(target, f.get(source));
            } catch (Exception ignore) {
                /* 静态/终态字段跳过 */
            }
        }
    }

    /** source 里非 null 的字段覆盖到 target (AI 只改它明确输出的项) */
    private void copyNonNulls(AiTaskDraft target, AiTaskDraft source) {
        for (Field f : AiTaskDraft.class.getDeclaredFields()) {
            if (f.getName().equals("serialVersionUID")) continue;
            f.setAccessible(true);
            try {
                Object v = f.get(source);
                if (v != null) f.set(target, v);
            } catch (Exception ignore) {
                /* 同上 */
            }
        }
    }

    /** 把草稿里非 null 的指定字段覆盖到任务对象 */
    private void copyNonNulls(SyncTask target, AiTaskDraft source, String... fields) {
        Set<String> allow = new LinkedHashSet<>(Arrays.asList(fields));
        for (String name : allow) {
            try {
                Field sf = AiTaskDraft.class.getDeclaredField(name);
                sf.setAccessible(true);
                Object v = sf.get(source);
                if (v == null) continue;
                Field tf = SyncTask.class.getDeclaredField(name);
                tf.setAccessible(true);
                tf.set(target, v);
            } catch (Exception ignore) {
                /* 字段名对不上就跳过 */
            }
        }
    }

    private AiTaskDraft copyDraft(AiTaskDraft src) {
        AiTaskDraft d = new AiTaskDraft();
        copyNonNulls(d, src);
        return d;
    }
}
