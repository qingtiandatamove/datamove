package com.ruoyi.datamove.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分片区间计算（纯函数）
 *
 * 全量同步按主键 MIN/MAX 均分区间时使用, 与数据库无关,
 * 抽出来便于在不开 DB 的情况下做单元测试。
 *
 * <p>规则:
 * <ul>
 *   <li>把 [min, max] 等宽切成 shardCount 段, 第 k 段为 [min + k*w, min + (k+1)*w - 1]</li>
 *   <li>最后一段以 max 为上限(包含 max)</li>
 *   <li>区间宽度不足 1 时整段返回 [min, max]</li>
 *   <li>min &gt; max 或 shardCount <= 0 返回空列表, 调用方按"空表/异常"回退单线程</li>
 * </ul>
 */
public final class RangeSplitter {

    private RangeSplitter() {}

    /**
     * @param min        主键最小值(来自源库 MIN(id))
     * @param max        主键最大值(来自源库 MAX(id))
     * @param shardCount 期望分片数
     * @return 区间列表 [{lo, hi}, ...], 包含两端; 返回空 = 让调用方走单线程
     */
    public static List<long[]> split(long min, long max, int shardCount) {
        if (shardCount <= 0 || min > max) {
            return Collections.emptyList();
        }
        long width = (max - min) / shardCount + 1;
        if (width <= 0) {
            List<long[]> single = new ArrayList<>(1);
            single.add(new long[]{min, max});
            return single;
        }
        List<long[]> ranges = new ArrayList<>(shardCount);
        for (int k = 0; k < shardCount; k++) {
            long lo = min + (long) k * width;
            if (lo > max) break;
            long hi = k == shardCount - 1 ? max : Math.min(lo + width - 1, max);
            ranges.add(new long[]{lo, hi});
        }
        return ranges;
    }
}