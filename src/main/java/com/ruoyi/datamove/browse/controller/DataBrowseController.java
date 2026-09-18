package com.ruoyi.datamove.browse.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.mapper.SyncDatasourceMapper;
import com.ruoyi.datamove.util.JdbcUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.sql.*;
import java.util.*;

/**
 * 数据浏览: 查看数据源表结构 / 数据, 并支持数据增删改
 *
 * 安全策略:
 *  - 表名/列名仅允许 [A-Za-z0-9_], 且必须是该表已存在的列 (从 information_schema 校验)
 *  - 所有值统一走 PreparedStatement 参数绑定, 防注入
 *  - 仅支持单表简单操作
 */
@Api(tags = "数据浏览")
@RestController
@RequestMapping("/sync/browse")
public class DataBrowseController {

    @Autowired
    private SyncDatasourceMapper datasourceMapper;

    private static final String IDENT_PATTERN = "[A-Za-z0-9_]+";

    /* ============ 查询 ============ */

    @ApiOperation("分页查询表数据 (支持 orderBy/orderDir 排序)")
    @GetMapping("/{dsId}/table/{table}/data")
    public R<Map<String, Object>> data(@PathVariable Long dsId,
                                       @PathVariable String table,
                                       @RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "10") int pageSize,
                                       @RequestParam(required = false) String orderBy,
                                       @RequestParam(required = false, defaultValue = "asc") String orderDir) {
        SyncDatasource ds = mustGetDatasource(dsId);
        checkTable(ds, table);
        if (pageNum < 1) pageNum = 1;
        if (pageSize < 1 || pageSize > 200) pageSize = 10;
        long offset = (long) (pageNum - 1) * pageSize;

        // 排序: 列名必须是该表真实存在的列(白名单), 方向仅 asc/desc, 防注入
        String orderClause = "";
        if (orderBy != null && !orderBy.isEmpty()) {
            if (!orderBy.matches(IDENT_PATTERN) || !legalColumns(ds, table).contains(orderBy)) {
                return R.fail("非法排序列: " + orderBy);
            }
            String dir = "desc".equalsIgnoreCase(orderDir) ? "DESC" : "ASC";
            orderClause = " ORDER BY `" + orderBy + "` " + dir;
        }

        Map<String, Object> result = new HashMap<>();
        try (Connection c = JdbcUtils.getConnection(ds)) {
            // 总数
            long total = 0;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                if (rs.next()) total = rs.getLong(1);
            }
            result.put("total", total);

            // 数据 (行用 LinkedHashMap 保持列顺序)
            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM `" + table + "`" + orderClause + " LIMIT ? OFFSET ?")) {
                ps.setInt(1, pageSize);
                ps.setLong(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= n; i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                }
            }
            result.put("rows", rows);
            return R.ok(result);
        } catch (SQLException e) {
            return R.fail("查询失败: " + e.getMessage());
        }
    }

    /* ============ 写操作 ============ */

    @ApiOperation("新增一行数据")
    @PostMapping("/{dsId}/table/{table}")
    public R<Void> insert(@PathVariable Long dsId, @PathVariable String table,
                          @RequestBody Map<String, Object> values) {
        SyncDatasource ds = mustGetDatasource(dsId);
        Set<String> legalCols = legalColumns(ds, table);
        // 过滤: 只保留合法且用户填了值的列
        Map<String, Object> cols = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getValue() == null || String.valueOf(e.getValue()).isEmpty()) continue;
            if (e.getKey().matches(IDENT_PATTERN) && legalCols.contains(e.getKey())) {
                cols.put(e.getKey(), e.getValue());
            }
        }
        if (cols.isEmpty()) return R.fail("没有可插入的字段");

        String sql = "INSERT INTO `" + table + "` ("
                + String.join(", ", cols.keySet().stream().map(k -> "`" + k + "`").toArray(String[]::new))
                + ") VALUES (" + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")";
        try (Connection c = JdbcUtils.getConnection(ds);
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (Object v : cols.values()) ps.setObject(i++, v);
            ps.executeUpdate();
            return R.ok();
        } catch (SQLException e) {
            return R.fail("插入失败: " + e.getMessage());
        }
    }

    @ApiOperation("修改一行数据 (按主键定位)")
    @PutMapping("/{dsId}/table/{table}")
    public R<Void> update(@PathVariable Long dsId, @PathVariable String table,
                          @RequestBody Map<String, Map<String, Object>> body) {
        SyncDatasource ds = mustGetDatasource(dsId);
        Map<String, Object> pk = body.get("pk");      // {主键列: 旧值}
        Map<String, Object> values = body.get("values"); // {列: 新值}
        if (pk == null || pk.isEmpty()) return R.fail("缺少主键定位条件");
        if (values == null || values.isEmpty()) return R.fail("缺少修改内容");

        Set<String> legalCols = legalColumns(ds, table);
        // 校验主键列
        String pkCol = pk.keySet().iterator().next();
        if (!pkCol.matches(IDENT_PATTERN) || !legalCols.contains(pkCol)) {
            return R.fail("非法主键列: " + pkCol);
        }
        // 过滤值列 (跳过自增主键列本身)
        Map<String, Object> cols = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getKey().equals(pkCol)) continue;
            if (e.getKey().matches(IDENT_PATTERN) && legalCols.contains(e.getKey())) {
                cols.put(e.getKey(), e.getValue());
            }
        }
        if (cols.isEmpty()) return R.fail("没有可修改的字段");

        String sql = "UPDATE `" + table + "` SET "
                + String.join(", ", cols.keySet().stream().map(k -> "`" + k + "`=?").toArray(String[]::new))
                + " WHERE `" + pkCol + "`=?";
        try (Connection c = JdbcUtils.getConnection(ds);
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (Object v : cols.values()) ps.setObject(i++, v);
            ps.setObject(i, pk.get(pkCol));
            int affected = ps.executeUpdate();
            if (affected == 0) return R.fail("未找到该行(主键值可能已被修改)");
            return R.ok();
        } catch (SQLException e) {
            return R.fail("修改失败: " + e.getMessage());
        }
    }

    @ApiOperation("删除一行数据 (按主键定位)")
    @DeleteMapping("/{dsId}/table/{table}")
    public R<Void> delete(@PathVariable Long dsId, @PathVariable String table,
                          @RequestBody Map<String, Object> pk) {
        SyncDatasource ds = mustGetDatasource(dsId);
        if (pk == null || pk.isEmpty()) return R.fail("缺少主键定位条件");
        Set<String> legalCols = legalColumns(ds, table);
        String pkCol = pk.keySet().iterator().next();
        if (!pkCol.matches(IDENT_PATTERN) || !legalCols.contains(pkCol)) {
            return R.fail("非法主键列: " + pkCol);
        }

        try (Connection c = JdbcUtils.getConnection(ds);
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM `" + table + "` WHERE `" + pkCol + "`=?")) {
            ps.setObject(1, pk.get(pkCol));
            int affected = ps.executeUpdate();
            if (affected == 0) return R.fail("未找到该行");
            return R.ok();
        } catch (SQLException e) {
            return R.fail("删除失败: " + e.getMessage());
        }
    }

    /* ============ DDL: 表结构管理 ============ */

    private static final Set<String> SAFE_TYPES = new HashSet<>(Arrays.asList(
            "int", "integer", "bigint", "smallint", "tinyint", "decimal", "numeric", "float", "double",
            "varchar", "char", "varbinary", "binary", "text", "tinytext", "mediumtext", "longtext",
            "date", "datetime", "timestamp", "time", "year", "json", "bit", "boolean",
            "blob", "tinyblob", "mediumblob", "longblob"));
    private static final Set<String> INT_TYPES = new HashSet<>(Arrays.asList("int", "integer", "bigint", "smallint", "tinyint"));
    private static final Set<String> NUM_TYPES = new HashSet<>(Arrays.asList("decimal", "numeric", "float", "double"));
    private static final Set<String> LEN_TYPES = new HashSet<>(Arrays.asList("varchar", "char", "varbinary", "binary"));
    private static final Set<String> TIME_TYPES = new HashSet<>(Arrays.asList("date", "datetime", "timestamp", "time"));

    @ApiOperation("新增字段")
    @PostMapping("/{dsId}/table/{table}/ddl/column")
    public R<Void> addColumn(@PathVariable Long dsId, @PathVariable String table,
                             @RequestBody Map<String, Object> body) {
        SyncDatasource ds = mustGetDatasource(dsId);
        checkTable(ds, table);
        String def = buildColumnDef(body);
        if (def == null) return R.fail("字段定义不合法");
        try (Connection c = JdbcUtils.getConnection(ds);
             Statement st = c.createStatement()) {
            st.executeUpdate("ALTER TABLE `" + table + "` ADD COLUMN " + def);
            return R.ok();
        } catch (SQLException e) {
            return R.fail("新增字段失败: " + e.getMessage());
        }
    }

    @ApiOperation("修改字段(名称/类型/长度/默认值/注释等)")
    @PutMapping("/{dsId}/table/{table}/ddl/column")
    public R<Void> modifyColumn(@PathVariable Long dsId, @PathVariable String table,
                                @RequestBody Map<String, Object> body) {
        SyncDatasource ds = mustGetDatasource(dsId);
        checkTable(ds, table);
        String oldName = (String) body.get("oldColumnName");
        if (oldName == null || !oldName.matches(IDENT_PATTERN)) return R.fail("非法原字段名");
        String def = buildColumnDef(body);
        if (def == null) return R.fail("字段定义不合法");
        String newName = (String) body.get("columnName");
        String sql = oldName.equals(newName)
                ? "ALTER TABLE `" + table + "` MODIFY COLUMN " + def
                : "ALTER TABLE `" + table + "` CHANGE COLUMN `" + oldName + "` " + def;
        try (Connection c = JdbcUtils.getConnection(ds);
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
            return R.ok();
        } catch (SQLException e) {
            return R.fail("修改字段失败: " + e.getMessage());
        }
    }

    @ApiOperation("删除字段")
    @DeleteMapping("/{dsId}/table/{table}/ddl/column")
    public R<Void> dropColumn(@PathVariable Long dsId, @PathVariable String table,
                              @RequestBody Map<String, Object> body) {
        SyncDatasource ds = mustGetDatasource(dsId);
        checkTable(ds, table);
        String col = (String) body.get("columnName");
        if (col == null || !col.matches(IDENT_PATTERN)) return R.fail("非法字段名");
        try (Connection c = JdbcUtils.getConnection(ds);
             Statement st = c.createStatement()) {
            st.executeUpdate("ALTER TABLE `" + table + "` DROP COLUMN `" + col + "`");
            return R.ok();
        } catch (SQLException e) {
            return R.fail("删除字段失败: " + e.getMessage());
        }
    }

    @ApiOperation("索引列表")
    @GetMapping("/{dsId}/table/{table}/ddl/index")
    public R<List<Map<String, Object>>> listIndex(@PathVariable Long dsId, @PathVariable String table) {
        SyncDatasource ds = mustGetDatasource(dsId);
        checkTable(ds, table);
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = JdbcUtils.getConnection(ds);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW INDEX FROM `" + table + "`")) {
            Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
            while (rs.next()) {
                String name = rs.getString("Key_name");
                Map<String, Object> idx = byName.computeIfAbsent(name, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("indexName", k);
                    m.put("unique", true);
                    m.put("columns", new ArrayList<String>());
                    return m;
                });
                if (rs.getInt("Non_unique") == 1) idx.put("unique", false);
                @SuppressWarnings("unchecked")
                List<String> cols = (List<String>) idx.get("columns");
                cols.add(rs.getString("Column_name"));
            }
            list.addAll(byName.values());
            return R.ok(list);
        } catch (SQLException e) {
            return R.fail("查询索引失败: " + e.getMessage());
        }
    }

    @ApiOperation("新增索引(可多字段/唯一)")
    @PostMapping("/{dsId}/table/{table}/ddl/index")
    public R<Void> addIndex(@PathVariable Long dsId, @PathVariable String table,
                            @RequestBody Map<String, Object> body) {
        SyncDatasource ds = mustGetDatasource(dsId);
        Set<String> legal = legalColumns(ds, table);
        List<?> cols = (List<?>) body.get("columns");
        if (cols == null || cols.isEmpty()) return R.fail("请选择索引字段");
        for (Object o : cols) {
            String cn = String.valueOf(o);
            if (!cn.matches(IDENT_PATTERN) || !legal.contains(cn)) return R.fail("非法索引字段: " + cn);
        }
        String name = body.get("indexName") == null ? "" : String.valueOf(body.get("indexName")).trim();
        if (name.isEmpty()) {
            name = "idx_" + String.join("_", cols.stream().map(String::valueOf).toArray(String[]::new));
        }
        if (!name.matches(IDENT_PATTERN)) return R.fail("非法索引名");
        if ("primary".equalsIgnoreCase(name)) return R.fail("索引名不可为 PRIMARY");
        boolean unique = Boolean.TRUE.equals(body.get("unique"));
        String sql = "CREATE " + (unique ? "UNIQUE " : "") + "INDEX `" + name + "` ON `" + table + "` ("
                + String.join(", ", cols.stream().map(s -> "`" + s + "`").toArray(String[]::new)) + ")";
        try (Connection c = JdbcUtils.getConnection(ds);
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
            return R.ok();
        } catch (SQLException e) {
            return R.fail("创建索引失败: " + e.getMessage());
        }
    }

    @ApiOperation("删除索引")
    @DeleteMapping("/{dsId}/table/{table}/ddl/index")
    public R<Void> dropIndex(@PathVariable Long dsId, @PathVariable String table,
                             @RequestBody Map<String, Object> body) {
        SyncDatasource ds = mustGetDatasource(dsId);
        checkTable(ds, table);
        String name = (String) body.get("indexName");
        if (name == null || !name.matches(IDENT_PATTERN)) return R.fail("非法索引名");
        if ("primary".equalsIgnoreCase(name)) return R.fail("主键索引不可删除");
        try (Connection c = JdbcUtils.getConnection(ds);
             Statement st = c.createStatement()) {
            st.executeUpdate("DROP INDEX `" + name + "` ON `" + table + "`");
            return R.ok();
        } catch (SQLException e) {
            return R.fail("删除索引失败: " + e.getMessage());
        }
    }

    /**
     * 由结构化参数拼装安全的列定义 (防注入: 名称/类型走白名单, 值转义并格式校验)
     * @return 列定义 SQL 片段; 不合法返回 null
     */
    private String buildColumnDef(Map<String, Object> body) {
        String name = (String) body.get("columnName");
        if (name == null || !name.matches(IDENT_PATTERN)) return null;
        String type = String.valueOf(body.get("dataType") == null ? "" : body.get("dataType")).toLowerCase();
        if (!SAFE_TYPES.contains(type)) return null;
        StringBuilder sb = new StringBuilder("`" + name + "` ").append(type);
        Integer len = toInt(body.get("length"));
        Integer dec = toInt(body.get("decimal"));
        if (LEN_TYPES.contains(type)) {
            if (len == null || len < 1 || len > 16000) return null;
            sb.append("(").append(len).append(")");
        } else if (NUM_TYPES.contains(type)) {
            if (len != null && (len < 1 || len > 65)) return null;
            if (dec != null && (dec < 0 || dec > 30)) return null;
            if (len != null) {
                sb.append("(").append(len);
                if (dec != null) sb.append(",").append(dec);
                sb.append(")");
            }
        }
        boolean unsigned = Boolean.TRUE.equals(body.get("unsigned"));
        if (unsigned && (INT_TYPES.contains(type) || NUM_TYPES.contains(type))) sb.append(" unsigned");
        boolean nullable = Boolean.TRUE.equals(body.get("nullable"));
        sb.append(nullable ? " NULL" : " NOT NULL");
        String def = body.get("defaultValue") == null ? null : String.valueOf(body.get("defaultValue")).trim();
        if (def != null && !def.isEmpty()) {
            if (TIME_TYPES.contains(type) && def.matches("(?i)current_timestamp")) {
                sb.append(" DEFAULT CURRENT_TIMESTAMP");
            } else {
                if (INT_TYPES.contains(type) && !def.matches("-?\\d+")) return null;
                if (NUM_TYPES.contains(type) && !def.matches("-?\\d+(\\.\\d+)?")) return null;
                sb.append(" DEFAULT '").append(def.replace("'", "''")).append("'");
            }
        }
        String comment = body.get("comment") == null ? "" : String.valueOf(body.get("comment"))
                .replace("'", "''").replace("\n", " ").trim();
        if (comment.length() > 500) comment = comment.substring(0, 500);
        if (!comment.isEmpty()) sb.append(" COMMENT '").append(comment).append("'");
        return sb.toString();
    }

    private Integer toInt(Object o) {
        if (o == null) return null;
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* ============ 工具 ============ */

    private SyncDatasource mustGetDatasource(Long id) {
        SyncDatasource ds = datasourceMapper.selectById(id);
        if (ds == null) throw new RuntimeException("数据源不存在: " + id);
        return ds;
    }

    private void checkTable(SyncDatasource ds, String table) {
        if (!table.matches(IDENT_PATTERN)) throw new RuntimeException("非法表名");
        if (!JdbcUtils.isTableExists(ds, table)) throw new RuntimeException("表不存在: " + table);
    }

    /** 返回该表全部列名 (一次 DB 查询, 同时完成了白名单校验) */
    private Set<String> legalColumns(SyncDatasource ds, String table) {
        checkTable(ds, table);
        Set<String> cols = new HashSet<>();
        for (Map<String, String> col : JdbcUtils.listColumns(ds, table)) {
            cols.add(col.get("columnName"));
        }
        return cols;
    }
}
