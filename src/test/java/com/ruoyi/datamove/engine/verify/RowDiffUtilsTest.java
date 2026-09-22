package com.ruoyi.datamove.engine.verify;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RowDiffUtils 单测 — 纯函数, 无 Spring 无 DB
 *
 * <p>重点钉住「假差异」这一类缺陷: 校验功能一旦把同值判成差异, 用户就会看到
 * 满屏差异并点下「一键同步」, 白白全表回写一遍。这里把各种类型归一化场景固定住。
 */
class RowDiffUtilsTest {

    /* ==================== compareKey ==================== */

    @Test
    void compareKeyUsesNumericOrderNotStringOrder() {
        // 字符串序会把 "10" 排在 "9" 前面, 主键归并一旦错序, 整个比对结果就废了
        assertTrue(RowDiffUtils.compareKey(10, 9) > 0);
        assertTrue(RowDiffUtils.compareKey(9, 10) < 0);
        assertEquals(0, RowDiffUtils.compareKey(10, 10L));
    }

    @Test
    void compareKeyHandlesMixedNumberTypes() {
        // 同一主键列在两侧可能被映射成 Integer / Long, 必须按数值相等
        assertEquals(0, RowDiffUtils.compareKey(7, 7L));
        assertEquals(0, RowDiffUtils.compareKey(7, new BigDecimal("7")));
    }

    @Test
    void compareKeyHandlesNulls() {
        assertEquals(0, RowDiffUtils.compareKey(null, null));
        assertTrue(RowDiffUtils.compareKey(null, 1) < 0);
        assertTrue(RowDiffUtils.compareKey(1, null) > 0);
    }

    @Test
    void compareKeyFallsBackToStringForNonNumericKey() {
        assertEquals(0, RowDiffUtils.compareKey("abc", "abc"));
        assertTrue(RowDiffUtils.compareKey("abc", "abd") < 0);
    }

    /* ==================== normalize ==================== */

    @Test
    void normalizeStripsBigDecimalTrailingZeros() {
        // DECIMAL 列两侧标度可能不同, 1.0 与 1.00 是同一个值
        assertEquals(RowDiffUtils.normalize(new BigDecimal("1.0")),
                RowDiffUtils.normalize(new BigDecimal("1.00")));
        assertEquals("1", RowDiffUtils.normalize(new BigDecimal("1.000")));
    }

    @Test
    void normalizeAlignsIntegralFloatingPointWithInteger() {
        // DOUBLE 列取到 1.0, INT 列取到 1 —— 同值, 不应报差异
        assertEquals(RowDiffUtils.normalize(1), RowDiffUtils.normalize(1.0D));
        assertEquals(RowDiffUtils.normalize(1), RowDiffUtils.normalize(1.0F));
    }

    @Test
    void normalizeKeepsRealFractionalDifference() {
        assertNotEquals(RowDiffUtils.normalize(1.5D), RowDiffUtils.normalize(1));
        assertNotEquals(RowDiffUtils.normalize(2), RowDiffUtils.normalize(3));
    }

    @Test
    void normalizeNullStaysNullAndDiffersFromEmptyString() {
        assertNull(RowDiffUtils.normalize(null));
        assertNotEquals(RowDiffUtils.normalize(null), RowDiffUtils.normalize(""));
    }

    @Test
    void normalizeBooleanToNumericFlag() {
        // MySQL 的 tinyint(1) 在不同驱动版本下可能给 Boolean, 目标是 0/1
        assertEquals("1", RowDiffUtils.normalize(Boolean.TRUE));
        assertEquals("0", RowDiffUtils.normalize(Boolean.FALSE));
    }

    @Test
    void normalizeBinaryByContent() {
        byte[] a = {1, 2, 3};
        byte[] b = {1, 2, 3};
        byte[] c = {1, 2, 4};
        // 不走 toString(对象地址), 同内容必须相等
        assertEquals(RowDiffUtils.normalize(a), RowDiffUtils.normalize(b));
        assertNotEquals(RowDiffUtils.normalize(a), RowDiffUtils.normalize(c));
    }

    @Test
    void normalizeTimestampToString() {
        assertEquals("2026-09-22 10:30:00",
                RowDiffUtils.normalize(Timestamp.valueOf("2026-09-22 10:30:00")));
    }

    /* ==================== valueEquals ==================== */

    @Test
    void bothNullIsEqual() {
        // 源库该列是 NULL, 目标库也应是 NULL —— 这是同步正确的语义
        assertTrue(RowDiffUtils.valueEquals(null, null));
    }

    @Test
    void nullVersusValueIsDifferent() {
        assertFalse(RowDiffUtils.valueEquals(null, "x"));
        assertFalse(RowDiffUtils.valueEquals("", null));
    }

    @Test
    void numericEqualityAcrossWrapperTypes() {
        assertTrue(RowDiffUtils.valueEquals(1, 1L));
        assertTrue(RowDiffUtils.valueEquals(new BigDecimal("2.50"), new BigDecimal("2.5")));
        assertFalse(RowDiffUtils.valueEquals(1, 2));
    }

    /* ==================== diffColumns ==================== */

    @Test
    void diffColumnsReportsOnlyChangedColumns() {
        Object[] src = {1, "alice", 30, "13800000000"};
        Object[] tgt = {1, "alice", 31, "13800000000"};
        List<String> cols = Arrays.asList("id", "name", "age", "phone");
        // 下标 0 = 主键列, 归并阶段已确认相等, 不参与比较
        List<String> diffs = RowDiffUtils.diffColumns(src, tgt,
                Arrays.asList(1, 2, 3), cols);
        assertEquals(Collections.singletonList("age"), diffs);
    }

    @Test
    void diffColumnsReturnsEmptyWhenIdentical() {
        Object[] src = {1, "alice"};
        Object[] tgt = {1L, "alice"};
        assertTrue(RowDiffUtils.diffColumns(src, tgt, Arrays.asList(0, 1),
                Arrays.asList("id", "name")).isEmpty());
    }

    @Test
    void diffColumnsToleratesOutOfRangeIndex() {
        // 忽略列/比较列来自配置, 配错列名不应让整个校验崩掉
        Object[] src = {1, "a"};
        Object[] tgt = {1, "b"};
        List<String> diffs = RowDiffUtils.diffColumns(src, tgt, Arrays.asList(1, 99),
                Arrays.asList("id", "name"));
        assertEquals(Collections.singletonList("name"), diffs);
    }

    /* ==================== join ==================== */

    @Test
    void joinColumnsForStorage() {
        assertEquals("a,b", RowDiffUtils.join(Arrays.asList("a", "b")));
        assertNull(RowDiffUtils.join(Collections.emptyList()));
        assertNull(RowDiffUtils.join(null));
    }
}
