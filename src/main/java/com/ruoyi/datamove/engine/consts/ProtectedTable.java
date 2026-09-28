package com.ruoyi.datamove.engine.consts;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DataMove 自身元数据表 (系统表) 的保护规则
 *
 * <p>背景: 「数据中心」可以像 Excel 一样改行/删行/改表结构, 「SQL 工作台」可以直接执行任意 SQL,
 * 两者都能碰到 DataMove 自己的库 —— 误删一张 sync_task 就是整个系统的任务全没了。
 * 系统表的维护应该走对应的业务页面 (用户管理 / 角色管理 / 任务列表 ...), 而不是在通用工具里裸改。
 *
 * <p>保护范围: 只允许读 (SELECT / 浏览), 禁止任何写操作
 * (INSERT / UPDATE / DELETE / ALTER / DROP / TRUNCATE / CREATE INDEX ...)。
 *
 * <p>为什么按前缀匹配而不是枚举表名: 以后新增系统表 (比如 sync_xxx) 自动纳入保护,
 * 不会因为漏改这张清单而被绕过。
 */
public final class ProtectedTable {

    private ProtectedTable() {}

    /** 元数据表名前缀 (小写): 命中即受保护 */
    private static final String[] PREFIXES = { "sys_", "sync_" };

    /** 会改动数据/结构的语句首词 */
    private static final Set<String> MUTATING_HEAD = new HashSet<>(Arrays.asList(
            "INSERT", "UPDATE", "DELETE", "REPLACE", "DROP", "TRUNCATE", "ALTER", "CREATE",
            "RENAME", "GRANT", "REVOKE", "LOAD", "CALL", "SET", "LOCK", "UNLOCK",
            "OPTIMIZE", "REPAIR", "ANALYZE", "FLUSH", "KILL"));

    /**
     * 表名出现位置的关键词: 只有紧跟在这些词后面的标识符才当作表名,
     * 避免把 `UPDATE t SET sync_flag=1` 里的列名 sync_flag 误判成表名
     */
    private static final Set<String> TABLE_POSITION = new HashSet<>(Arrays.asList(
            "FROM", "INTO", "UPDATE", "TABLE", "JOIN", "EXISTS"));

    /** SQL 里的标识符 (含反引号内) */
    private static final Pattern IDENT = Pattern.compile("[A-Za-z0-9_$]+");

    /** 表名是否受保护 (兼容 db.tbl / `tbl` / 'tbl' 等写法) */
    public static boolean isProtected(String table) {
        if (table == null) return false;
        String t = normalize(table);
        if (t.isEmpty()) return false;
        for (String p : PREFIXES) {
            if (t.startsWith(p)) return true;
        }
        return false;
    }

    /** 统一提示语, 前端直接展示 */
    public static String guardMessage(String table) {
        return "「" + table + "」是 DataMove 系统表, 不允许在数据中心 / SQL 工作台中修改或删除"
             + " (只允许查看; 需要维护请到对应的功能页面操作)";
    }

    /**
     * SQL 工作台用的语句体检: 改动了受保护表的语句返回错误原因, 安全语句返回 null
     *
     * <p>这是 best-effort 的字符串层拦截 (项目没有引入 SQL 解析器):
     * 先去注释再判断首词 + 表名位置, 能挡住绝大多数误操作与随手写的破坏性语句;
     * 不追求挡住所有花式绕过 —— 真要防恶意, 应该给工作台单独配一个只读账号。
     *
     * @param stmt 单条语句 (多语句请先拆分)
     * @return null = 允许执行; 非 null = 拒绝原因
     */
    public static String checkStatement(String stmt) {
        if (stmt == null || stmt.trim().isEmpty()) return null;
        String body = stripComments(stmt).trim();
        if (body.isEmpty()) return null;

        // 首词: 不是改动类语句直接放行 (SELECT / SHOW / EXPLAIN ...)
        String first = body.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        // `/*c*/DELETE` 之类在去注释后首词会变, 这里以去注释后的为准
        if (!MUTATING_HEAD.contains(first)) return null;

        // 改动类语句: 找它到底动了哪张表
        String hit = findProtectedTable(body);
        if (hit == null) return null;
        return "拒绝执行: " + guardMessage(hit);
    }

    /** 从语句里找出第一个受保护表名 (看表名位置 + db.tbl 写法), 没有返回 null */
    private static String findProtectedTable(String body) {
        Matcher m = IDENT.matcher(body);
        String prev = "";
        while (m.find()) {
            String tok = m.group();
            // db.tbl / `db`.`tbl`: 点号后面的那个才是表名
            boolean afterDot = m.start() > 0 && body.charAt(m.start() - 1) == '.';
            if (afterDot || TABLE_POSITION.contains(prev.toUpperCase(Locale.ROOT))) {
                if (isProtected(tok)) return tok;
            }
            prev = tok;
        }
        return null;
    }

    /** 去掉行注释与块注释, 防止 `/*x*​/DROP TABLE ...` 绕过首词判断 */
    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0, n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            if ((ch == '-' && i + 1 < n && sql.charAt(i + 1) == '-') || ch == '#') {
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

    /** 表名归一: 去反引号/引号/分号, 取库名之后的最后一段 */
    private static String normalize(String table) {
        String t = table.trim().toLowerCase(Locale.ROOT);
        int dot = t.lastIndexOf('.');
        if (dot >= 0) t = t.substring(dot + 1);
        return t.replace("`", "").replace("\"", "").replace("'", "").replace(";", "").replace(" ", "");
    }
}
