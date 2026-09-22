package com.ruoyi.datamove.engine.verify;

import com.ruoyi.datamove.util.JdbcUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 行值比较工具 (纯函数, 无副作用, 便于单测)
 *
 * <p>数据校验走「源/目标两个游标按主键顺序归并」的算法, 依赖两个前提:
 * <ol>
 *   <li>两边按同一主键列升序返回 —— 故两侧 SQL 都显式 {@code ORDER BY <主键> ASC}。
 *       主键列类型在同步链路里一般一致(目标表由源表 DDL 复制而来), 排序结果相同。</li>
 *   <li>比较的是「值」而不是「Java 类型」—— 同一列在两侧可能被驱动映射成不同包装类型
 *       (Integer / Long / BigDecimal), 直接 equals 会把 1 和 1L 判成差异, 所以统一
 *       归一化成字符串再比。</li>
 * </ol>
 *
 * <p>归一化的两个关键点, 漏了都会导致「同步完立刻校验出一堆假差异」:
 * <ul>
 *   <li>{@link BigDecimal}: 必须 {@code stripTrailingZeros()}, 否则 {@code 1.0} 与
 *       {@code 1.00} 判为不等(DECIMAL 列在两侧的标度未必一致)。</li>
 *   <li>浮点整数值: {@code 1.0} 归一化成 {@code 1}, 与 INT 列的 {@code 1} 对齐。</li>
 * </ul>
 */
public final class RowDiffUtils {

    private RowDiffUtils() {
    }

    /* ==================== 主键 ==================== */

    /**
     * 按主键排序比较: 负数 = a 在前, 0 = 相等, 正数 = b 在前。
     * 两侧都是数字时按数值比(避免 "10" < "9" 的字符串序), 否则归一化成字符串比。
     */
    public static int compareKey(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number && b instanceof Number) {
            return Long.compare(((Number) a).longValue(), ((Number) b).longValue());
        }
        String na = normalize(a);
        String nb = normalize(b);
        if (na == null && nb == null) return 0;
        if (na == null) return -1;
        if (nb == null) return 1;
        return na.compareTo(nb);
    }

    /* ==================== 值 ==================== */

    /**
     * 值是否相等(按归一化后的字符串比)。
     * 两侧都为 NULL 视为相等 —— 这是同步场景的正确语义: 源库该列是 NULL, 目标库也应是 NULL。
     */
    public static boolean valueEquals(Object a, Object b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na == null) return nb == null;
        return na.equals(nb);
    }

    /**
     * 把值归一化成可比较的字符串; 值为 NULL 时返回 null(与空字符串区分开)。
     */
    public static String normalize(Object v) {
        if (v == null) return null;
        // 时间类型统一成字符串(datetime -> yyyy-MM-dd HH:mm:ss)
        Object n = JdbcUtils.normalizeValue(v);
        if (n == null) return null;
        // 二进制列: 按内容比, 不能走 toString(那比的是对象地址)
        if (n instanceof byte[]) return "b64:" + Base64.getEncoder().encodeToString((byte[]) n);
        if (n instanceof BigDecimal) return ((BigDecimal) n).stripTrailingZeros().toPlainString();
        if (n instanceof Double || n instanceof Float) {
            double d = ((Number) n).doubleValue();
            // 整数值的浮点数与 INT 列取到的整数对齐: 1.0 与 1 是同值, 不该判成差异
            if (!Double.isNaN(d) && !Double.isInfinite(d)
                    && d == Math.rint(d) && Math.abs(d) < 1e15D) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        if (n instanceof Boolean) return ((Boolean) n) ? "1" : "0";
        return String.valueOf(n);
    }

    /* ==================== 整行 ==================== */

    /**
     * 找出两行中值不一致的列名。
     *
     * @param srcVals    源行值(列顺序与 tgtVals 一致, 已按字段映射对齐)
     * @param tgtVals    目标行值
     * @param compareIdx 参与比较的列下标(应排除主键列与被忽略的列)
     * @param colNames   列名(用于输出差异列名, 下标与上面的数组一致)
     * @return 值不一致的列名; 无差异时返回空列表
     */
    public static List<String> diffColumns(Object[] srcVals, Object[] tgtVals,
                                           List<Integer> compareIdx, List<String> colNames) {
        List<String> diffs = new ArrayList<>();
        if (srcVals == null || tgtVals == null) return diffs;
        for (Integer idx : compareIdx) {
            if (idx == null || idx < 0 || idx >= srcVals.length || idx >= tgtVals.length) continue;
            if (!valueEquals(srcVals[idx], tgtVals[idx])) {
                diffs.add(idx < colNames.size() ? colNames.get(idx) : ("col" + idx));
            }
        }
        return diffs;
    }

    /** 列名列表拼成逗号分隔串(差异字段落库用) */
    public static String join(List<String> names) {
        if (names == null || names.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(names.get(i));
        }
        return sb.toString();
    }
}
