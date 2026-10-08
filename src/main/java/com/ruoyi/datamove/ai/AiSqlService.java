package com.ruoyi.datamove.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.datamove.ai.domain.AiSqlResult;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.service.ISyncDatasourceService;
import com.ruoyi.datamove.engine.consts.ProtectedTable;
import com.ruoyi.datamove.util.JdbcUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 自然语言 → SQL (Text-to-SQL)
 *
 * <p>把用户选中的库里真实的表结构交给模型, 让它写出可以直接执行的 SQL。
 *
 * <p>安全边界(跟手写 SQL 一样严, AI 不享受特权):
 * <ol>
 *   <li>生成完必须过 {@link ProtectedTable#checkStatement}: 任何碰系统表(sys_ / sync_ 前缀)的语句直接拒;</li>
 *   <li>首词白名单: 只放行 SELECT / SHOW / DESC / EXPLAIN / WITH;</li>
 *   <li>只生成不执行: SQL 填进工作台, 用户自己看过再点执行。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSqlService {

    /** 一次最多把几张表的结构喂给模型: 超了只给表名, 否则 prompt 会炸 */
    private static final int MAX_DETAIL_TABLES = 10;

    private static final Set<String> ALLOWED_HEAD = new HashSet<>(Arrays.asList(
            "SELECT", "SHOW", "DESC", "DESCRIBE", "EXPLAIN", "WITH"));

    private final AiProperties props;
    private final AiChatClient chatClient;
    private final ISyncDatasourceService datasourceService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiSqlResult generate(Long dsId, String question, String table) {
        if (dsId == null) throw new RuntimeException("请选择数据源");
        if (question == null || question.trim().isEmpty()) throw new RuntimeException("请先描述你想查什么");
        SyncDatasource ds = datasourceService.getById(dsId);
        if (ds == null) throw new RuntimeException("数据源不存在: " + dsId);

        List<String> allTables = safeListTables(ds);
        if (allTables.isEmpty()) throw new RuntimeException("读不到该库的表(连接失败或账号无权限)");

        List<String> detail = pickTables(allTables, question, table);

        AiSqlResult res = new AiSqlResult();
        res.setTables(detail);

        if (props.usable()) {
            try {
                String content = chatClient.chat(systemPrompt(), userPrompt(ds, question, table, allTables, detail));
                JsonNode root = objectMapper.readTree(AiChatClient.stripFence(content));
                String sql = root.path("sql").asText("").trim();
                res.setExplanation(root.path("explanation").asText("").trim());
                readArray(root.path("warnings"), res.getWarnings());
                res.setEngine("AI");
                res.setProvider(props.providerLabel());
                res.setModel(props.getModel());
                return validate(res, sql);
            } catch (Exception e) {
                log.warn("[AI] 生成 SQL 失败, 降级本地模板: {}", e.getMessage());
                res.setFallbackNote("AI 调用失败(" + e.getMessage() + "), 已改用本地模板");
            }
        } else {
            res.setFallbackNote("未配置 sync.ai.api-key, 当前只能按表名生成简单查询模板");
        }

        res.setEngine("RULE");
        String t = table != null && !table.trim().isEmpty() ? table.trim() : guessTable(allTables, question);
        if (t == null) {
            res.setRejected("没听出来要查哪张表, 请在描述里写上表名, 或先选中表再生成");
            return res;
        }
        res.setSql("SELECT * FROM `" + t + "` LIMIT 100;");
        res.setExplanation("本地模板: 查 " + t + " 前 100 行。配置 sync.ai.api-key 后可按描述生成带条件/聚合的 SQL。");
        res.getWarnings().add("这只是最简单模板, 条件与排序需要你自己补");
        res.setTables(new ArrayList<>(Arrays.asList(t)));
        return res;
    }

    /** 生成完的 SQL 必须过只读校验, 不合格就原样返回原因 */
    private AiSqlResult validate(AiSqlResult res, String sql) {
        if (sql.isEmpty()) {
            res.setRejected("模型没有返回 SQL, 换个说法再试一次");
            return res;
        }
        sql = sql.trim();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
        if (sql.contains(";")) {
            res.setRejected("一次只能生成一条语句, 模型返回了多条: " + abbreviate(sql, 120));
            return res;
        }
        String head = sql.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        if (!ALLOWED_HEAD.contains(head)) {
            res.setRejected("只允许生成查询语句(SELECT/SHOW/DESC/EXPLAIN/WITH), 模型返回的是 " + head);
            return res;
        }
        String reject = ProtectedTable.checkStatement(sql);
        if (reject != null) {
            res.setRejected(reject);
            return res;
        }
        res.setSql(sql + ";");
        if (res.getWarnings().isEmpty() && !sql.toUpperCase(Locale.ROOT).contains("LIMIT")) {
            res.getWarnings().add("这条 SQL 没有 LIMIT, 大表查询建议加行数限制");
        }
        return res;
    }

    /* ==================== 表选择 ==================== */

    private List<String> pickTables(List<String> all, String question, String table) {
        List<String> out = new ArrayList<>();
        if (table != null && !table.trim().isEmpty()) {
            out.add(table.trim());
            return out;
        }
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        for (String t : all) {
            if (q.contains(t.toLowerCase(Locale.ROOT)) && out.size() < MAX_DETAIL_TABLES) out.add(t);
        }
        if (out.isEmpty()) {
            for (String t : all) {
                if (out.size() >= MAX_DETAIL_TABLES) break;
                out.add(t);
            }
        }
        return out;
    }

    private String guessTable(List<String> all, String question) {
        if (question == null) return null;
        String q = question.toLowerCase(Locale.ROOT);
        for (String t : all) {
            if (q.contains(t.toLowerCase(Locale.ROOT))) return t;
        }
        return null;
    }

    private List<String> safeListTables(SyncDatasource ds) {
        try {
            List<String> t = JdbcUtils.listTables(ds);
            return t == null ? new ArrayList<>() : t;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /* ==================== Prompt ==================== */

    private String systemPrompt() {
        return "你是一个 MySQL 8 查询生成助手。\n"
                + "用户会用中文描述想查什么, 你会拿到库里真实的表名和字段结构, 请写出对应的 SQL。\n"
                + "只能输出 JSON, 不要任何解释性文字, 结构如下:\n"
                + "{\"sql\":\"一条 SQL 语句\",\"explanation\":\"这句 SQL 在干什么\",\"tables\":[\"用到的表\"],\"warnings\":[\"需要注意的点\"]}\n"
                + "硬性要求:\n"
                + "1. 只能生成查询语句: SELECT / SHOW / DESC / EXPLAIN / WITH, 绝对不许出现 INSERT/UPDATE/DELETE/DROP/ALTER;\n"
                + "2. 只能用我给你的表名和字段名, 禁止编造;\n"
                + "3. 只写一条语句, 不要分号拼接多条;\n"
                + "4. 查明细时默认加 LIMIT(不超过 1000);\n"
                + "5. 涉及时间范围用标准 MySQL 函数(如 DATE_SUB(NOW(), INTERVAL 7 DAY));\n"
                + "6. 不要碰 sys_ 或 sync_ 开头的系统表。";
    }

    private String userPrompt(SyncDatasource ds, String question, String table,
                              List<String> allTables, List<String> detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据库: ").append(ds.getDbName()).append(" (MySQL 8)\n\n");
        sb.append("全部表名: ").append(String.join(", ", allTables)).append("\n\n");
        sb.append("相关表结构:\n");
        for (String t : detail) {
            sb.append("表 ").append(t).append(":\n");
            List<Map<String, String>> cols = safeListColumns(ds, t);
            if (cols.isEmpty()) {
                sb.append("  (读不到字段结构)\n");
                continue;
            }
            for (Map<String, String> c : cols) {
                sb.append("  - ").append(c.get("columnName")).append(" ").append(c.get("columnType"));
                String cm = c.get("columnComment");
                if (cm != null && !cm.trim().isEmpty()) sb.append(" -- ").append(cm.trim());
                sb.append('\n');
            }
        }
        if (table != null && !table.trim().isEmpty()) {
            sb.append("\n用户指定查这张表: ").append(table.trim()).append('\n');
        }
        sb.append("\n用户需求: ").append(question.trim()).append("\n请生成 SQL(只输出 JSON)。");
        return sb.toString();
    }

    private List<Map<String, String>> safeListColumns(SyncDatasource ds, String table) {
        try {
            return JdbcUtils.listColumns(ds, table);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void readArray(JsonNode arr, List<String> target) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode n : arr) {
            String v = n.asText("").trim();
            if (!v.isEmpty()) target.add(v);
        }
    }

    private String abbreviate(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
