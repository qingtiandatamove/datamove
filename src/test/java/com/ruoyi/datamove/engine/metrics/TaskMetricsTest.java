package com.ruoyi.datamove.engine.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskMetrics 单测 — 速率窗口 / 瓶颈判定 / 实时速率 staleness
 *
 * 注意: finishBatch 内部用 System.currentTimeMillis(), 因此 staleness 用例需要 sleep
 *       真实机器跑会偏慢, 但作为引擎正确性的基线已足够。
 */
class TaskMetricsTest {

    private static TaskMetrics newActive(long taskId) {
        TaskMetrics m = new TaskMetrics(taskId, 1000);
        m.activate(-1L, 0L);     // totalEstimate=-1 = 未知, 不展示 ETA
        return m;
    }

    @Test
    void emptyMetricsReportZeroRate() {
        TaskMetrics m = newActive(1L);
        assertEquals(0.0, m.getRowsPerSec(), 1e-9);
        assertEquals(0.0, m.liveRowsPerSec(), 1e-9);
        assertEquals(TaskMetrics.UNKNOWN, m.getBottleneck());
    }

    @Test
    void singleSampleYieldsReadAndWriteAverages() {
        TaskMetrics m = newActive(2L);
        m.finishBatch(100, /*readMs*/ 60, /*writeMs*/ 30, /*costMs*/ 100);
        // 一条采样, 平均读 60 写 30
        assertEquals(60.0, m.getReadMs(), 1e-9);
        assertEquals(30.0, m.getWriteMs(), 1e-9);
        // costSum=100, rows=100 -> 100*1000/100 = 1000 行/秒
        assertEquals(1000.0, m.getRowsPerSec(), 1e-9);
        // 总和 60+30 = 90 < MIN_COST_MS(100) -> UNKNOWN, 避免抖动误判
        assertEquals(TaskMetrics.UNKNOWN, m.getBottleneck());
    }

    @Test
    void classifySourceBottleneckWhenReadMuchSlower() {
        TaskMetrics m = newActive(3L);
        // readSum=900, writeSum=100, 总和=1000 >= MIN_COST_MS, 9 倍 > BOTTLENECK_RATIO
        m.finishBatch(1000, 900, 100, 1000);
        assertEquals(TaskMetrics.SOURCE, m.getBottleneck());
    }

    @Test
    void classifyTargetBottleneckWhenWriteMuchSlower() {
        TaskMetrics m = newActive(4L);
        m.finishBatch(1000, 100, 900, 1000);
        assertEquals(TaskMetrics.TARGET, m.getBottleneck());
    }

    @Test
    void balancedWhenReadAndWriteClose() {
        TaskMetrics m = newActive(5L);
        // 100 vs 110 -> 1.1 倍, 不到 BOTTLENECK_RATIO(1.3)
        m.finishBatch(1000, 100, 110, 210);
        assertEquals(TaskMetrics.BALANCED, m.getBottleneck());
    }

    @Test
    void streamModeTreatsTargetAsOnlyBottleneck() {
        TaskMetrics m = newActive(6L);
        m.setCompareMode(TaskMetrics.MODE_STREAM);
        // 流式: read=等待事件, write=应用变更; write <= read 时算 BALANCED
        m.finishBatch(1000, 500, 200, 700);
        assertEquals(TaskMetrics.BALANCED, m.getBottleneck());
    }

    @Test
    void streamModeFlagsTargetWhenWriteExceedsWait() {
        TaskMetrics m = newActive(7L);
        m.setCompareMode(TaskMetrics.MODE_STREAM);
        m.finishBatch(1000, 200, 500, 700);
        assertEquals(TaskMetrics.TARGET, m.getBottleneck());
    }

    @Test
    void rowsPerSecZeroWhenInactive() {
        TaskMetrics m = new TaskMetrics(8L, 1000);
        // 未激活: activate() 没被调用 -> active=false -> liveRowsPerSec 归 0
        m.finishBatch(100, 50, 50, 100);
        assertTrue(m.getRowsPerSec() > 0);  // 内部窗口仍计算
        assertEquals(0.0, m.liveRowsPerSec(), 1e-9);  // 但实时展示关闭
    }
}