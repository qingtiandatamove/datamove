package com.ruoyi.datamove.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RangeSplitter 单测 — 纯函数, 无 Spring 无 DB
 */
class RangeSplitterTest {

    @Test
    void evenSplitInto3Ranges() {
        List<long[]> r = RangeSplitter.split(1, 9, 3);
        assertEquals(3, r.size());
        assertArrayEquals(new long[]{1, 3}, r.get(0));
        assertArrayEquals(new long[]{4, 6}, r.get(1));
        assertArrayEquals(new long[]{7, 9}, r.get(2));
    }

    @Test
    void lastSegmentClampedToMax() {
        // 10 个值切 3 段: width = 9/3 + 1 = 4
        // k=0 [1,4], k=1 [5,8], k=2 (last) [9,10] —— 末段被 max=10 钉住
        List<long[]> r = RangeSplitter.split(1, 10, 3);
        assertEquals(3, r.size());
        assertArrayEquals(new long[]{1, 4}, r.get(0));
        assertArrayEquals(new long[]{5, 8}, r.get(1));
        assertArrayEquals(new long[]{9, 10}, r.get(2));
    }

    @Test
    void singleValueDomainProducesOneRange() {
        // min==max 时 width=0/N+1=1, 切多段但只有一段 lo<=max, 故返回 1 个区间
        List<long[]> r = RangeSplitter.split(42, 42, 5);
        assertEquals(1, r.size());
        assertArrayEquals(new long[]{42, 42}, r.get(0));
    }

    @Test
    void coversEntireDomainWithNoGapsAndNoOverlap() {
        long min = 100, max = 199;
        int n = 7;
        List<long[]> r = RangeSplitter.split(min, max, n);
        assertEquals(n, r.size());
        // 首段从 min 起
        assertEquals(min, r.get(0)[0]);
        // 末段以 max 止
        assertEquals(max, r.get(r.size() - 1)[1]);
        // 相邻段首尾相等(无空隙)
        for (int i = 0; i < r.size() - 1; i++) {
            assertEquals(r.get(i)[1] + 1, r.get(i + 1)[0],
                    "gap or overlap between segment " + i + " and " + (i + 1));
        }
        // lo <= hi
        for (long[] s : r) assertTrue(s[0] <= s[1]);
    }

    @Test
    void minGreaterThanMaxReturnsEmpty() {
        assertTrue(RangeSplitter.split(10, 5, 3).isEmpty());
    }

    @Test
    void zeroShardCountReturnsEmpty() {
        assertTrue(RangeSplitter.split(1, 100, 0).isEmpty());
        assertTrue(RangeSplitter.split(1, 100, -1).isEmpty());
    }

    @Test
    void singleValueDomain() {
        List<long[]> r = RangeSplitter.split(42, 42, 3);
        // width = 0/3 + 1 = 1; 切 3 段, 但只有一段 lo<=max
        assertFalse(r.isEmpty());
        for (long[] s : r) assertArrayEquals(new long[]{42, 42}, s);
    }
}