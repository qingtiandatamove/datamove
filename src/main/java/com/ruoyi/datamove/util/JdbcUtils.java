package com.ruoyi.datamove.util;

import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 动态 JDBC 连接工具
 * - 维护动态数据源连接池(简单)
 * - 提供数据库连通性测试、查询表列表、查询表结构
 */
@Slf4j
public class JdbcUtils {

    private static final ReentrantLock LOCK = new ReentrantLock();

    /** 简单缓存: 数据源配置 -> DataSource */
    private static final Map<String, DataSourceHolder> POOL = new ConcurrentHashMap<>();

    /** 连接统一使用东八区(buildUrl serverTimezone), Date 格式化保持一致 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static String buildUrl(String host, int port, String db) {
        return "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }

    /**
     * 测试连接
     */
    public static boolean testConnection(String host, int port, String db, String user, String pass) {
        try (Connection c = DriverManager.getConnection(buildUrl(host, port, db), user, pass)) {
            return c != null && !c.isClosed();
        } catch (Exception e) {
            log.warn("test connection fail {}:{} db={} err={}", host, port, db, e.getMessage());
            return false;
        }
    }

    /**
     * 重载: 直接传 SyncDatasource
     */
    public static boolean testConnection(SyncDatasource ds) {
        return testConnection(ds.getHost(), ds.getPort(), ds.getDbName(),
                ds.getUsername(), ds.getPlainPassword());
    }

    /**
     * 测试连接并返回底层错误信息, 用于启动失败场景透出真实原因
     *  - 连接成功返回 null
     *  - 失败返回 SQLException.getMessage(), 例如:
     *      - Access denied for user 'root'@'127.0.0.1' (using password: YES)
     *      - Communications link failure
     *      - Unknown database 'xxx'
     * 比 boolean testConnection() 多保留了底层原因, 不至于只报 "无法连接数据库"
     */
    public static String getConnectError(String host, int port, String db, String user, String pass) {
        try (Connection c = DriverManager.getConnection(buildUrl(host, port, db), user, pass)) {
            return null;
        } catch (Exception e) {
            log.warn("test connection fail {}:{} db={} err={}", host, port, db, e.getMessage());
            return e.getMessage();
        }
    }

    /** 重载: 直接传 SyncDatasource */
    public static String getConnectError(SyncDatasource ds) {
        return getConnectError(ds.getHost(), ds.getPort(), ds.getDbName(),
                ds.getUsername(), ds.getPlainPassword());
    }

    /**
     * 获取连接(从简易缓存)
     */
    public static Connection getConnection(SyncDatasource ds) throws SQLException {
        return getConnection(ds.getHost(), ds.getPort(), ds.getDbName(),
                ds.getUsername(), ds.getPlainPassword());
    }

    public static Connection getConnection(String host, int port, String db, String user, String pass) throws SQLException {
        String key = host + ":" + port + "/" + db + "@" + user;
        DataSourceHolder holder = POOL.computeIfAbsent(key, k -> new DataSourceHolder());
        Connection c = null;
        try {
            c = holder.cp.getConnection();
        } catch (Exception ignored) {
        }
        if (c == null || c.isClosed()) {
            c = DriverManager.getConnection(buildUrl(host, port, db), user, pass);
        }
        return c;
    }

    /**
     * 列出数据库中所有表
     */
    public static List<String> listTables(SyncDatasource ds) {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME";
        try (Connection c = getConnection(ds);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ds.getDbName());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tables.add(rs.getString(1));
            }
        } catch (SQLException e) {
            log.error("listTables failed", e);
        }
        return tables;
    }

    /**
     * 列出表的字段定义
     */
    public static List<Map<String, String>> listColumns(SyncDatasource ds, String tableName) {
        List<Map<String, String>> list = new ArrayList<>();
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, COLUMN_KEY, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT " +
                "FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (Connection c = getConnection(ds);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ds.getDbName());
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> map = new HashMap<>();
                    map.put("columnName", rs.getString("COLUMN_NAME"));
                    map.put("dataType", rs.getString("DATA_TYPE"));
                    map.put("columnType", rs.getString("COLUMN_TYPE"));
                    map.put("columnKey", rs.getString("COLUMN_KEY"));
                    map.put("nullable", rs.getString("IS_NULLABLE"));
                    map.put("defaultValue", rs.getString("COLUMN_DEFAULT"));
                    map.put("extra", rs.getString("EXTRA"));
                    map.put("columnComment", rs.getString("COLUMN_COMMENT"));
                    list.add(map);
                }
            }
        } catch (SQLException e) {
            log.error("listColumns failed", e);
        }
        return list;
    }

    /**
     * 检查目标表是否存在, 不存在则从源表 SHOW CREATE TABLE 拿 DDL 自动建表
     * @return true=表已存在(或建表成功), false=失败
     */
    public static boolean ensureTableExists(SyncDatasource src, SyncDatasource tgt, String tableName) {
        return ensureTableExists(src, tgt, tableName, null);
    }

    /**
     * 同上, 但支持把 DDL 里的源字段名重命名为目标字段名 (用于"字段映射"场景自动建表)
     * <p>只动 backtick 包起来的列名, 不会误伤表名/库名/索引名
     *
     * @param renameMap 源字段 -> 目标字段; 为 null/空 表示不重命名
     */
    public static boolean ensureTableExists(SyncDatasource src, SyncDatasource tgt, String tableName,
                                            Map<String, String> renameMap) {
        if (tableName == null || tableName.isEmpty()) return false;
        // 防止注入 (虽然来源是配置)
        if (!tableName.matches("[A-Za-z0-9_]+")) {
            log.warn("[DDL] 非法表名 {}", tableName);
            return false;
        }
        // 1. 目标表已存在 -> 跳过
        if (isTableExists(tgt, tableName)) {
            return true;
        }
        // 2. 拿源表 DDL
        String ddl = getShowCreateTable(src, tableName);
        if (ddl == null || ddl.isEmpty()) {
            log.warn("[DDL] 无法获取源表 DDL: {}", tableName);
            return false;
        }
        // 3. 规范化为 CREATE TABLE IF NOT EXISTS `tableName` ...
        //    屏蔽源库的库名前缀, 防止 CREATE TABLE `db`.`tbl` 在目标库失败
        String normalized = ddl.replaceFirst(
                "(?i)CREATE\\s+TABLE\\s+(IF NOT EXISTS\\s+)?(`[^`]+`\\.)?`" + tableName + "`",
                "CREATE TABLE IF NOT EXISTS `" + tableName + "`");
        // 4. 字段映射: DDL 里出现的源列名替换为目标列名 (含列定义/索引引用)
        if (renameMap != null && !renameMap.isEmpty()) {
            for (Map.Entry<String, String> e : renameMap.entrySet()) {
                String from = e.getKey();
                String to = e.getValue();
                if (from == null || from.isEmpty() || to == null || to.isEmpty()) continue;
                if (from.equals(to)) continue;
                normalized = normalized.replace("`" + from + "`", "`" + to + "`");
            }
        }
        // 5. 在目标库执行
        try (Connection c = getConnection(tgt);
             Statement s = c.createStatement()) {
            s.executeUpdate(normalized);
            log.info("[DDL] 自动建表成功 {}.{} (rename={})", tgt.getDbName(), tableName, renameMap == null ? 0 : renameMap.size());
            return true;
        } catch (SQLException e) {
            log.error("[DDL] 自动建表失败 {}.{} : {}", tgt.getDbName(), tableName, e.getMessage());
            return false;
        }
    }

    /** 判断某表是否存在 */
    public static boolean isTableExists(SyncDatasource ds, String tableName) {
        String sql = "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? LIMIT 1";
        try (Connection c = getConnection(ds);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ds.getDbName());
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("isTableExists failed", e);
            return false;
        }
    }

    /** SHOW CREATE TABLE - 第2列是 Create Table 语句 */
    public static String getShowCreateTable(SyncDatasource ds, String tableName) {
        // SHOW CREATE TABLE 不能用 prepareStatement 占位, 但 tableName 已通过正则过滤
        String sql = "SHOW CREATE TABLE `" + tableName + "`";
        try (Connection c = getConnection(ds);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return rs.getString(2);
        } catch (SQLException e) {
            log.error("getShowCreateTable failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 取表元信息: engine / collation / tableComment
     */
    public static Map<String, String> getTableMeta(SyncDatasource ds, String tableName) {
        Map<String, String> meta = new LinkedHashMap<>();
        String sql = "SELECT ENGINE, TABLE_COLLATION, IF(TABLE_COMMENT='', NULL, TABLE_COMMENT) AS TABLE_COMMENT " +
                "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (Connection c = getConnection(ds);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ds.getDbName());
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    meta.put("engine", rs.getString("ENGINE"));
                    meta.put("collation", rs.getString("TABLE_COLLATION"));
                    meta.put("tableComment", rs.getString("TABLE_COMMENT"));
                }
            }
        } catch (SQLException e) {
            log.error("getTableMeta failed", e);
        }
        return meta;
    }

    /**
     * 取表的索引信息 (SHOW INDEX FROM)
     */
    public static List<Map<String, String>> getIndexes(SyncDatasource ds, String tableName) {
        List<Map<String, String>> list = new ArrayList<>();
        if (tableName == null || !tableName.matches("[A-Za-z0-9_]+")) return list;
        String sql = "SHOW INDEX FROM `" + tableName + "`";
        try (Connection c = getConnection(ds);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("keyName", rs.getString("Key_name"));
                m.put("columnName", rs.getString("Column_name"));
                m.put("seqInIndex", String.valueOf(rs.getInt("Seq_in_index")));
                m.put("nonUnique", rs.getString("Non_unique"));
                m.put("indexType", rs.getString("Index_type"));
                list.add(m);
            }
        } catch (SQLException e) {
            log.error("getIndexes failed", e);
        }
        return list;
    }

    /**
     * 查询结果取值统一处理: 时间类型转成字符串, 其余类型原样返回
     *  - datetime/timestamp -> yyyy-MM-dd HH:mm:ss
     *  - date               -> yyyy-MM-dd
     *  - time               -> HH:mm:ss
     *
     * MySQL 8 驱动 getObject() 对时间列返回 LocalDateTime/LocalDate/LocalTime,
     * 直接交给 Jackson 会输出 "2026-09-16T16:22:24" 这种带 T 的 ISO 串,
     * 表格展示不友好, 日期控件(value-format=yyyy-MM-dd HH:mm:ss)也解析不到值。
     */
    public static Object normalizeValue(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime) return ((LocalDateTime) v).format(DATETIME_FMT);
        if (v instanceof LocalDate) return ((LocalDate) v).format(DATE_FMT);
        if (v instanceof LocalTime) return ((LocalTime) v).format(TIME_FMT);
        // java.sql.Timestamp / java.sql.Date 都是 java.util.Date 子类, 需先判断
        if (v instanceof Timestamp) return ((Timestamp) v).toLocalDateTime().format(DATETIME_FMT);
        if (v instanceof java.sql.Date) return ((java.sql.Date) v).toLocalDate().format(DATE_FMT);
        if (v instanceof java.sql.Time) return ((java.sql.Time) v).toLocalTime().format(TIME_FMT);
        if (v instanceof java.util.Date) {
            return ((java.util.Date) v).toInstant().atZone(ZONE).toLocalDateTime().format(DATETIME_FMT);
        }
        return v;
    }

    /**
     * 关闭并移除缓存中的连接
     */
    public static void closeQuietly(AutoCloseable... cs) {
        for (AutoCloseable c : cs) {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** 极简连接缓存 holder */
    private static class DataSourceHolder {
        final ConnectionCache cp = new ConnectionCache();
    }

    /** 极简连接缓存 - 仅缓存一个长连接,简单实现 */
    private static class ConnectionCache {
        Connection conn;
        long lastUsed;

        synchronized Connection getConnection() throws SQLException {
            // 5分钟没使用则重建
            if (conn == null || conn.isClosed() || (System.currentTimeMillis() - lastUsed) > 300_000) {
                if (conn != null) try { conn.close(); } catch (Exception ignored) {}
                conn = null;
            }
            lastUsed = System.currentTimeMillis();
            return conn;
        }
    }
}
