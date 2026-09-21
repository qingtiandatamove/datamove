package com.ruoyi.datamove.browse.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.mapper.SyncDatasourceMapper;
import com.ruoyi.datamove.log.service.SqlLogService;
import com.ruoyi.datamove.util.JdbcUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.sql.*;
import java.util.*;

/**
 * SQL 工作台: 直接输入 SQL 操作数据源的库/表/数据
 *
 *  - 支持多语句, 分号分隔 (尊重引号与注释内的分号)
 *  - SELECT/SHOW/DESC/EXPLAIN 等返回结果集 (最多 1000 行)
 *  - INSERT/UPDATE/DELETE/DDL 返回影响行数
 *  - 单语句超时 30s, 防止慢查询卡死
 */
@Api(tags = "SQL 工作台")
@RestController
@RequestMapping("/sync/sql")
public class SqlConsoleController {

    @Autowired
    private SyncDatasourceMapper datasourceMapper;

    @Autowired
    private SqlLogService sqlLogService;

    /** 操作日志来源标识 */
    private static final String SOURCE = "SQL_CONSOLE";

    /** 结果集最大返回行数 */
    private static final int MAX_ROWS = 1000;
    /** 单语句超时(秒) */
    private static final int TIMEOUT_SECONDS = 30;

    @ApiOperation("EXPLAIN 执行计划 (SELECT/WITH/SHOW/TABLE/DESCRIBE/VALUES)")
    @PostMapping("/{dsId}/explain")
    public R<Map<String, Object>> explain(@PathVariable Long dsId, @RequestBody Map<String, Object> body) {
        SyncDatasource ds = datasourceMapper.selectById(dsId);
        if (ds == null) return R.fail("数据源不存在: " + dsId);
        String sql = body.get("sql") == null ? "" : String.valueOf(body.get("sql")).trim();
        if (sql.isEmpty()) return R.fail("SQL 不能为空");
        Boolean analyze = Boolean.TRUE.equals(body.get("analyze"));

        // 仅允许单条
        List<String> stmts = splitStatements(sql);
        if (stmts.isEmpty()) return R.fail("没有可执行的语句");
        if (stmts.size() > 1) return R.fail("EXPLAIN 仅支持单条 SELECT / WITH / SHOW / TABLE / VALUES / DESCRIBE");
        String one = stmts.get(0);

        // 首词白名单
        String head = stripComments(one).trim();
        String first = head.isEmpty() ? "" : head.split("\\s+", 2)[0].toUpperCase();
        boolean allowed = first.equals("SELECT") || first.equals("WITH") || first.equals("SHOW")
                       || first.equals("EXPLAIN") || first.equals("TABLE") || first.equals("VALUES")
                       || first.equals("DESC") || first.equals("DESCRIBE");
        if (!allowed) return R.fail("EXPLAIN 仅支持 SELECT / WITH / SHOW / TABLE / VALUES / DESCRIBE (当前首词: " + first + ")");

        // EXPLAIN ANALYZE 二次确认: 客户端必须显式传 analyze=true, 默认 false
        String explainSql = first.equals("EXPLAIN") ? one
                : (analyze ? "EXPLAIN ANALYZE " + one : "EXPLAIN " + one);

        long start = System.currentTimeMillis();
        Map<String, Object> result;
        try (Connection c = JdbcUtils.getConnection(ds)) {
            result = executeOne(c, explainSql);
        } catch (SQLException e) {
            sqlLogService.record(dsId, ds.getDatasourceName(), ds.getDbName(), "SQL_EXPLAIN",
                    sql, 1, 0, 0, System.currentTimeMillis() - start, "FAILED", e.getMessage());
            return R.fail("执行失败: " + e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;
        long rows = toLong(result.get("total"));
        sqlLogService.record(dsId, ds.getDatasourceName(), ds.getDbName(), "SQL_EXPLAIN",
                sql, 1, rows, 0, elapsed, "SUCCESS", null);

        Map<String, Object> out = new HashMap<>();
        out.put("result", result);
        out.put("analyze", analyze);
        out.put("elapsed", elapsed);
        out.put("sql", explainSql);
        return R.ok(out);
    }

    /** 去除行注释/块注释, 用于识别首词 (不影响原 splitStatements 的拆语句行为) */
    private String stripComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            if (ch == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
                continue;
            }
            if (ch == '#') {
                while (i < n && sql.charAt(i) != '\n') i++;
                continue;
            }
            if (ch == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                i = Math.min(n, i + 2);
                continue;
            }
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }

    @ApiOperation("执行 SQL (支持多语句)")
    @PostMapping("/{dsId}/execute")
    public R<Map<String, Object>> execute(@PathVariable Long dsId, @RequestBody Map<String, String> body) {
        SyncDatasource ds = datasourceMapper.selectById(dsId);
        if (ds == null) return R.fail("数据源不存在: " + dsId);
        String sql = body.get("sql") == null ? "" : body.get("sql").trim();
        if (sql.isEmpty()) return R.fail("SQL 不能为空");

        List<String> statements = splitStatements(sql);
        if (statements.isEmpty()) return R.fail("没有可执行的语句");
        if (statements.size() > 20) {
            sqlLogService.record(dsId, ds.getDatasourceName(), ds.getDbName(), SOURCE,
                    sql, statements.size(), 0, 0, 0, "FAILED", "单次最多执行 20 条语句");
            return R.fail("单次最多执行 20 条语句");
        }

        long start = System.currentTimeMillis();
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection c = JdbcUtils.getConnection(ds)) {
            for (String stmt : statements) {
                results.add(executeOne(c, stmt));
            }
        } catch (SQLException e) {
            sqlLogService.record(dsId, ds.getDatasourceName(), ds.getDbName(), SOURCE,
                    sql, statements.size(), 0, 0, System.currentTimeMillis() - start, "FAILED", e.getMessage());
            return R.fail("执行失败: " + e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - start;
        // 汇总: 结果集行数 / 增删改影响行数
        long resultRows = 0L, affectedRows = 0L;
        for (Map<String, Object> r : results) {
            if ("query".equals(r.get("type"))) {
                resultRows += toLong(r.get("total"));
            } else {
                affectedRows += Math.max(0L, toLong(r.get("affected")));
            }
        }
        sqlLogService.record(dsId, ds.getDatasourceName(), ds.getDbName(), SOURCE,
                sql, statements.size(), resultRows, affectedRows, elapsed, "SUCCESS", null);

        Map<String, Object> out = new HashMap<>();
        out.put("results", results);
        out.put("elapsed", elapsed);
        out.put("count", statements.size());
        return R.ok(out);
    }

    /** Object -> long 安全转换 */
    private static long toLong(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    /** 执行单条语句: 有结果集返回 columns+rows, 否则返回 affected */
    private Map<String, Object> executeOne(Connection c, String stmt) throws SQLException {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sql", stmt.length() > 200 ? stmt.substring(0, 200) + "..." : stmt);
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(TIMEOUT_SECONDS);
            st.setMaxRows(MAX_ROWS);
            boolean isQuery = st.execute(stmt);
            if (isQuery) {
                List<String> columns = new ArrayList<>();
                List<Map<String, Object>> rows = new ArrayList<>();
                try (ResultSet rs = st.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    for (int i = 1; i <= n; i++) columns.add(md.getColumnLabel(i));
                    while (rs.next() && rows.size() < MAX_ROWS) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= n; i++) {
                            // 时间类型先统一格式化(避免带 T 的 ISO 串), 再转字符串防止序列化异常
                            Object v = JdbcUtils.normalizeValue(rs.getObject(i));
                            row.put(md.getColumnLabel(i), v == null ? null : String.valueOf(v));
                        }
                        rows.add(row);
                    }
                }
                r.put("type", "query");
                r.put("columns", columns);
                r.put("rows", rows);
                r.put("total", rows.size());
            } else {
                r.put("type", "update");
                r.put("affected", st.getUpdateCount());
            }
        }
        return r;
    }

    /**
     * 按分号拆分多条语句:
     *  - 跳过单引号/双引号/反引号字符串内的分号
     *  - 跳过 -- 行注释、# 行注释、块注释 内的分号
     */
    private List<String> splitStatements(String sql) {
        List<String> list = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false, inBacktick = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            // 行注释 -- 或 #
            if (!inSingle && !inDouble && !inBacktick && (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-' || ch == '#')) {
                while (i < sql.length() && sql.charAt(i) != '\n') cur.append(sql.charAt(i++));
                if (i < sql.length()) cur.append('\n');
                continue;
            }
            // 块注释 /* */
            if (!inSingle && !inDouble && !inBacktick && ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                cur.append("/*");
                i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) cur.append(sql.charAt(i++));
                if (i + 1 < sql.length()) { cur.append("*/"); i++; }
                continue;
            }
            if (ch == '\'' && !inDouble && !inBacktick) inSingle = !inSingle;
            else if (ch == '"' && !inSingle && !inBacktick) inDouble = !inDouble;
            else if (ch == '`' && !inSingle && !inDouble) inBacktick = !inBacktick;
            if (ch == ';' && !inSingle && !inDouble && !inBacktick) {
                String s = cur.toString().trim();
                if (!s.isEmpty()) list.add(s);
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        String s = cur.toString().trim();
        if (!s.isEmpty()) list.add(s);
        return list;
    }
}
