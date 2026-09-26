package com.ruoyi.datamove.ai;

import com.ruoyi.datamove.ai.domain.AiTaskDraft;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地规则解析器 (没有配 AI Key 时的兜底, 也是 AI 结果的校验补充)
 *
 * <p>思路: 中文需求里真正有信息量的就那几类词 —— 库、表、条件、脱敏、限速、定时、全量/增量。
 * 用关键词 + 正则把它们挑出来, 挑不出来的留给默认值。识别不了的句子不会瞎配,
 * 只填识别到的字段, 其余交给用户在预览里改。
 */
@Slf4j
@Component
public class AiRuleParser {

    /** 表名: 覆盖 "user 表" / "orders 大表" / "user表" 三种口语写法 */
    private static final Pattern TABLE_A = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*(?:大|小|的)?表");
    private static final Pattern TABLE_B = Pattern.compile("表\\s*[:：]?\\s*([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern TABLE_C = Pattern.compile("(?i)(?:from|表)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern TABLE_D = Pattern.compile("同步\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private static final Pattern COND_EXPR = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*\\s*(>=|<=|!=|=|>|<)\\s*('[^']*'|\\d+|[A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern RATE = Pattern.compile("(\\d+)\\s*(?:条|行)\\s*(?:/|每|每秒|/s|/秒)");
    private static final Pattern RATE_SIMPLE = Pattern.compile("限速\\D{0,4}(\\d+)");
    private static final Pattern RECENT = Pattern.compile("(?:近|最近|最近)\\s*(\\d+)\\s*(个月|月|天|周|年|小时)");
    private static final Pattern CRON_HOUR = Pattern.compile("(\\d{1,2})\\s*[点時时]");
    private static final Pattern ID_FIELD = Pattern.compile("主键\\s*[:：]?\\s*([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern TIME_FIELD = Pattern.compile("([A-Za-z_]*time[A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);

    /** 数据源角色关键词: 谁更像源库, 谁更像目标库 */
    private static final List<String> SRC_WORDS = Arrays.asList("生产", "正式", "线上", "prod", "主库", "业务库", "源库");
    private static final List<String> TGT_WORDS = Arrays.asList("测试", "test", "数仓", "dw", "报表", "归档", "备份", "目标库", "从库");

    /**
     * @param text   用户自然语言
     * @param dsList 当前环境的数据源列表 (用于按名称/角色匹配源与目标)
     * @param base   基线草稿: 新建场景传空草稿, 修改场景传「当前任务配置」—— 只有规则识别到的字段才会被覆盖
     * @param notes  出参: 规则命中的解释, 直接展示给用户复核
     */
    public AiTaskDraft parse(String text, List<SyncDatasource> dsList, AiTaskDraft base, List<String> notes) {
        AiTaskDraft d = base != null ? base : new AiTaskDraft();
        String t = text == null ? "" : text;
        String lower = t.toLowerCase(Locale.ROOT);

        /* ---------- 数据源 ---------- */
        pickDatasource(t, dsList, d, notes);

        /* ---------- 表名 ---------- */
        String table = findTable(t);
        if (table != null) {
            d.setTableName(table);
            notes.add("同步表识别为 `" + table + "`");
        }

        /* ---------- 全量 / 增量 ---------- */
        boolean incr = t.contains("增量") || lower.contains("binlog") || t.contains("实时同步") || t.contains("订阅");
        boolean full = t.contains("全量");
        if (incr) {
            d.setTaskType("INCR");
            d.setSyncMode("BINLOG");
            notes.add("识别为增量任务: 订阅 binlog 实时同步" + (full ? " (你同时提到了全量, 历史数据建议先用全量任务搬一次)" : ""));
            if (d.getCanalHost() == null) {
                d.setCanalHost("127.0.0.1");
                d.setCanalPort(11111);
                d.setCanalDestination("example");
            }
        } else if (full) {
            d.setTaskType("FULL");
            notes.add("识别为全量任务: 按游标分批搬迁");
        }

        /* ---------- 游标模式 / 字段 ---------- */
        Matcher idm = ID_FIELD.matcher(t);
        if (idm.find()) {
            d.setIdField(idm.group(1));
            notes.add("主键字段识别为 `" + idm.group(1) + "`");
        }
        Matcher tf = TIME_FIELD.matcher(t);
        if (tf.find()) {
            d.setTimeField(tf.group(1));
            notes.add("时间字段识别为 `" + tf.group(1) + "`");
        }
        if (t.contains("按时间") || t.contains("时间增量") || d.getTimeField() != null) {
            if (!incr) {
                d.setSyncMode("TIME");
                if (d.getTimeField() == null) d.setTimeField("update_time");
                notes.add("按时间字段做增量拉取 (逐批推进时间游标)");
            }
        } else if (!incr && d.getSyncMode() == null) {
            d.setSyncMode("ID");
            if (d.getIdField() == null) d.setIdField("id");
            notes.add("按主键 ID 分批拉取 (断点续传, 中断后可继续)");
        }

        /* ---------- 过滤条件 ---------- */
        String where = findWhere(t);
        if (where != null) {
            d.setWhereCondition(where);
            notes.add("过滤条件: `" + where + "` (只同步满足条件的数据)");
        }

        /* ---------- 脱敏 ---------- */
        if (t.contains("脱敏") || t.contains("打码") || t.contains("掩码") || t.contains("敏感")) {
            Set<String> mask = new LinkedHashSet<>(d.getMaskFields() == null ? new ArrayList<>() : d.getMaskFields());
            addMask(mask, t, "手机号", "手机", "电话", "mobile", "phone");
            addMask(mask, t, "身份证", "id_card", "idcard");
            addMask(mask, t, "邮箱", "邮件", "email");
            addMask(mask, t, "银行卡", "bank_card");
            addMask(mask, t, "地址", "address");
            addMask(mask, t, "密码", "password");
            if (mask.isEmpty()) {
                mask.add("phone");
                mask.add("id_card");
                notes.add("提到脱敏但未指明字段, 默认排除常见敏感列 phone / id_card");
            }
            d.setMaskFields(new ArrayList<>(mask));
            // 当前引擎的脱敏实现 = 「同步时不写这些列」, 落到 ignore_fields
            Set<String> ignored = new LinkedHashSet<>();
            if (d.getIgnoreFields() != null) {
                for (String s : d.getIgnoreFields().split(",")) {
                    if (s != null && !s.trim().isEmpty()) ignored.add(s.trim());
                }
            }
            ignored.addAll(mask);
            d.setIgnoreFields(String.join(",", ignored));
            notes.add("脱敏字段 " + mask + " 写入「忽略字段」: 目标库对应列留空(需允许为 NULL)");
        }

        /* ---------- 限速 ---------- */
        Matcher rm = RATE.matcher(t);
        Integer rate = null;
        if (rm.find()) {
            rate = Integer.parseInt(rm.group(1));
        } else {
            Matcher rs = RATE_SIMPLE.matcher(t);
            if (rs.find() && (t.contains("限速") || t.contains("限流"))) rate = Integer.parseInt(rs.group(1));
        }
        if (rate != null && rate > 0) {
            d.setRateLimit(rate);
            notes.add("限速 " + rate + " 行/秒: 同步引擎按批次节奏休眠, 避免打满源库/目标库");
        }

        /* ---------- 定时 ---------- */
        if (t.contains("每天") || t.contains("每日") || t.contains("定时") || t.contains("每晚") || t.contains("每小时")) {
            String cron = buildCron(t);
            d.setTriggerType("CRON");
            d.setCronExpr(cron);
            notes.add("定时调度: CRON `" + cron + "` (Spring 6 位: 秒 分 时 日 月 周)");
        }

        /* ---------- 覆盖写入 ---------- */
        if (t.contains("覆盖") || t.contains("清空目标") || t.contains("重置目标")) {
            d.setOverwriteFlag(1);
            notes.add("覆盖写入: 启动时会先清空目标表再同步");
        }

        /* ---------- 大表 / 小批次 ---------- */
        if (t.contains("大表") || t.contains("千万") || t.contains("亿") || t.contains("并行") || d.getShardCount() == null && t.contains("快点")) {
            d.setShardCount(8);
            d.setBatchSize(5000);
            notes.add("大表策略: 8 分片并行 + 每批 5000 行(要求主键为数值型)");
        } else if (t.contains("小批次") || t.contains("慢一点") || t.contains("别压垮")) {
            d.setBatchSize(500);
            notes.add("小批次 500 行: 降低对源库的单次读取压力");
        }
        if (d.getBatchSize() == null) d.setBatchSize(1000);
        if (d.getShardCount() == null) d.setShardCount(1);

        /* ---------- 任务名 ---------- */
        if (d.getTaskName() == null && d.getTableName() != null) {
            d.setTaskName("AI-" + d.getTableName() + ("INCR".equalsIgnoreCase(d.getTaskType()) ? "-增量" : "-全量"));
        }
        return d;
    }

    /* ==================== 数据源匹配 ==================== */

    private void pickDatasource(String t, List<SyncDatasource> dsList, AiTaskDraft d, List<String> notes) {
        if (dsList == null || dsList.isEmpty()) return;
        String lower = t.toLowerCase(Locale.ROOT);

        // 1) 文本里直接点名了数据源
        SyncDatasource namedSrc = null;
        SyncDatasource namedTgt = null;
        for (SyncDatasource ds : dsList) {
            String name = ds.getDatasourceName();
            if (name == null || name.isEmpty()) continue;
            int idx = lower.indexOf(name.toLowerCase(Locale.ROOT));
            if (idx < 0) continue;
            // 「从 A ... 到 B」: A 更靠前 = 源, 更靠后 = 目标
            if (namedSrc == null) {
                namedSrc = ds;
            } else if (namedTgt == null) {
                namedTgt = ds;
            } else {
                break;
            }
        }
        if (namedSrc != null && namedTgt != null && !namedSrc.getId().equals(namedTgt.getId())) {
            d.setSourceDatasourceId(namedSrc.getId());
            d.setSourceDatasourceName(namedSrc.getDatasourceName());
            d.setTargetDatasourceId(namedTgt.getId());
            d.setTargetDatasourceName(namedTgt.getDatasourceName());
            notes.add("数据源: " + namedSrc.getDatasourceName() + " → " + namedTgt.getDatasourceName());
            return;
        }
        if (namedSrc != null && namedTgt == null) {
            // 只点名了一个: 按角色词猜它是源还是目标
            boolean asTarget = containsAny(lower, TGT_WORDS) && !containsAny(lower, SRC_WORDS);
            if (asTarget) {
                d.setTargetDatasourceId(namedSrc.getId());
                d.setTargetDatasourceName(namedSrc.getDatasourceName());
                notes.add("目标数据源: " + namedSrc.getDatasourceName());
            } else {
                d.setSourceDatasourceId(namedSrc.getId());
                d.setSourceDatasourceName(namedSrc.getDatasourceName());
                notes.add("源数据源: " + namedSrc.getDatasourceName());
            }
        }

        // 2) 角色关键词兜底: 生产库 → 源, 测试/数仓 → 目标
        if (d.getSourceDatasourceId() == null) {
            for (SyncDatasource ds : dsList) {
                if (matchRole(ds, SRC_WORDS)) {
                    d.setSourceDatasourceId(ds.getId());
                    d.setSourceDatasourceName(ds.getDatasourceName());
                    notes.add("源数据源按角色词匹配: " + ds.getDatasourceName());
                    break;
                }
            }
        }
        if (d.getTargetDatasourceId() == null) {
            for (SyncDatasource ds : dsList) {
                if (matchRole(ds, TGT_WORDS)) {
                    d.setTargetDatasourceId(ds.getId());
                    d.setTargetDatasourceName(ds.getDatasourceName());
                    notes.add("目标数据源按角色词匹配: " + ds.getDatasourceName());
                    break;
                }
            }
        }
    }

    private static boolean matchRole(SyncDatasource ds, List<String> words) {
        String n = (ds.getDatasourceName() == null ? "" : ds.getDatasourceName()).toLowerCase(Locale.ROOT);
        String db = (ds.getDbName() == null ? "" : ds.getDbName()).toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (n.contains(w) || db.contains(w)) return true;
        }
        return false;
    }

    private static boolean containsAny(String lower, List<String> words) {
        for (String w : words) if (lower.contains(w)) return true;
        return false;
    }

    /* ==================== 小工具 ==================== */

    private static void addMask(Set<String> mask, String text, String... keywords) {
        for (String k : keywords) {
            if (text.toLowerCase(Locale.ROOT).contains(k.toLowerCase(Locale.ROOT))) {
                mask.addAll(maskFieldsOf(keywords[0]));
                return;
            }
        }
    }

    private static List<String> maskFieldsOf(String key) {
        switch (key) {
            case "手机号":
            case "手机":
            case "电话":
                return Arrays.asList("phone", "mobile");
            case "身份证":
                return Arrays.asList("id_card", "idcard");
            case "邮箱":
            case "邮件":
                return Arrays.asList("email");
            case "银行卡":
                return Arrays.asList("bank_card");
            case "地址":
                return Arrays.asList("address");
            case "密码":
                return Arrays.asList("password");
            default:
                return new ArrayList<>();
        }
    }

    private static String findTable(String t) {
        String s = null;
        Matcher m = TABLE_A.matcher(t);
        if (m.find()) s = m.group(1);
        if (s == null) {
            m = TABLE_B.matcher(t);
            if (m.find()) s = m.group(1);
        }
        if (s == null) {
            m = TABLE_C.matcher(t);
            if (m.find()) s = m.group(1);
        }
        if (s == null) {
            m = TABLE_D.matcher(t);
            if (m.find()) s = m.group(1);
        }
        if (s == null) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        if (Arrays.asList("mysql", "库", "table", "select", "and", "or").contains(lower)) return null;
        return s;
    }

    /** 抽取过滤条件: 时间窗口 > 显性条件表达式 */
    private static String findWhere(String t) {
        Matcher recent = RECENT.matcher(t);
        if (recent.find()) {
            String unit = recent.group(2);
            String sqlUnit = unit.startsWith("月") || unit.equals("个月") ? "MONTH"
                    : unit.equals("天") ? "DAY"
                    : unit.equals("周") ? "WEEK"
                    : unit.equals("年") ? "YEAR" : "HOUR";
            return "update_time >= DATE_SUB(NOW(), INTERVAL " + recent.group(1) + " " + sqlUnit + ")";
        }
        int kw = indexOfAny(t, "只同步", "只要", "仅同步", "只迁移", "过滤条件", "条件", "where", "WHERE");
        if (kw >= 0) {
            String tail = t.substring(kw + keywordLen(t, kw));
            int end = tail.length();
            for (int i = 0; i < tail.length(); i++) {
                char c = tail.charAt(i);
                if (c == '，' || c == ',' || c == '。' || c == ';' || c == '；' || c == '\n') {
                    end = i;
                    break;
                }
            }
            String clause = tail.substring(0, end).trim();
            // 口语残留: "只同步 status=1 的数据" → 真正要的只是 "status=1"
            clause = clause.replaceAll("^(?:的数据|数据|的行|记录|的)", "").trim();
            clause = clause.replaceAll("(?:的数据|数据|的行|记录)$", "").trim();
            // 只要含有比较表达式就接受
            Matcher cm = COND_EXPR.matcher(clause);
            if (cm.find()) return clause;
        }
        // 兜底: 句子里直接写了 status=1 这种表达式
        Matcher cm = COND_EXPR.matcher(t);
        if (cm.find() && (t.contains("同步") || t.contains("迁移"))) return cm.group(0);
        return null;
    }

    private static int keywordLen(String t, int from) {
        String[] kws = {"只同步", "只要", "仅同步", "只迁移", "过滤条件", "条件", "where", "WHERE"};
        for (String k : kws) {
            if (t.startsWith(k, from)) return k.length();
        }
        return 0;
    }

    private static int indexOfAny(String t, String... kws) {
        int best = -1;
        for (String k : kws) {
            int i = t.indexOf(k);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    /** 每天 H 点 → 0 0 H * * ?; 每小时 → 0 0 * * * ? */
    private static String buildCron(String t) {
        if (t.contains("每小时")) return "0 0 * * * ?";
        Matcher m = CRON_HOUR.matcher(t);
        int hour = 2;
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            if (t.contains("下午") || t.contains("晚上")) h = (h % 12) + 12;
            if (h >= 0 && h <= 23) hour = h;
        }
        return "0 0 " + hour + " * * ?";
    }
}
