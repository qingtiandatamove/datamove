package com.ruoyi.datamove.engine.metrics;

import lombok.Getter;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 单个同步任务的实时运行指标 (纯内存, 不落库)
 *
 * 由同步引擎每批写入, 大盘接口按需读取:
 *  - rowsPerSec  : 最近 10s 窗口内的实时速率 (行/秒), 用窗口内 Σ行数 / Σ批次耗时 计算
 *  - readMs      : 窗口内「源库读取」平均单批耗时 (查询 + 取数)
 *  - writeMs     : 窗口内「目标库写入」平均单批耗时 (batch 执行 + commit)
 *  - bottleneck  : 两者对比得出的瓶颈方
 *
 * 写入方是单个任务线程(串行), 读取方是大盘接口线程, 故内部加锁保证可见性与一致性。
 */
public class TaskMetrics {

    /** 速率/读写耗时统计窗口 */
    private static final long WINDOW_MS = 10_000L;

    /** 采样上限, 防止批次极小且密集时窗口内条数过多 */
    private static final int MAX_SAMPLES = 2000;

    /** 单批读/写耗时相差超过该倍数才判定为瓶颈, 避免抖动误判 */
    private static final double BOTTLENECK_RATIO = 1.3D;

    /** 窗口内读写耗时之和低于该值(ms)时不做瓶颈判定, 避免样本过小 */
    private static final long MIN_COST_MS = 100L;

    /** 瓶颈判定结果 */
    public static final String SOURCE   = "SOURCE";
    public static final String TARGET   = "TARGET";
    public static final String BALANCED = "BALANCED";
    public static final String UNKNOWN  = "UNKNOWN";

    /** 耗时对比模式 */
    public static final String MODE_FULL   = "FULL";    // 全量: 源库读取 对比 目标库写入
    public static final String MODE_STREAM = "STREAM";  // 增量流式: 等待事件 对比 应用变更

    @Getter
    private final Long taskId;

    /** 窗口内批次采样: {结束时间戳, 行数, 读取耗时, 写入耗时, 批次总耗时} */
    private final Deque<long[]> samples = new ArrayDeque<>();

    @Getter
    private volatile boolean active;

    @Getter
    private volatile int batchSize;

    /** 本次运行起点的历史累计行数(续传场景), 用于算「本次运行已同步」 */
    @Getter
    private volatile long baselineRows;

    /** 本次运行需同步的总行数估算, -1 = 未知(不展示 ETA) */
    @Getter
    private volatile long totalEstimate = -1L;

    /** 当前正在处理的批次号 */
    @Getter
    private volatile int currentBatch;

    /** 当前批次已读取行数(读取过程中实时刷新) */
    @Getter
    private volatile int currentBatchRows;

    /** 最近一批总耗时 */
    @Getter
    private volatile long lastBatchCostMs;

    /** 窗口内实时速率(行/秒) */
    @Getter
    private volatile double rowsPerSec;

    /** 窗口内平均读/写耗时(ms) */
    @Getter
    private volatile double readMs;

    @Getter
    private volatile double writeMs;

    @Getter
    private volatile String bottleneck = UNKNOWN;

    /** 读写耗时对比模式, 默认全量 */
    @Getter
    private volatile String compareMode = MODE_FULL;

    public void setCompareMode(String compareMode) {
        this.compareMode = compareMode == null ? MODE_FULL : compareMode;
    }

    public TaskMetrics(Long taskId, int batchSize) {
        this.taskId = taskId;
        this.batchSize = batchSize <= 0 ? 1000 : batchSize;
    }

    /* ============================================================
     *                       生命 周期
     * ============================================================ */

    /**
     * 任务启动 / 重新启动: 清空窗口重新统计
     *
     * @param totalEstimate 本次运行需同步的总行数估算, -1 表示未知
     * @param baselineRows  启动前已累计同步的行数
     */
    public synchronized void activate(long totalEstimate, long baselineRows) {
        samples.clear();
        this.rowsPerSec = 0D;
        this.readMs = 0D;
        this.writeMs = 0D;
        this.bottleneck = UNKNOWN;
        this.currentBatch = 0;
        this.currentBatchRows = 0;
        this.lastBatchCostMs = 0L;
        this.baselineRows = Math.max(0L, baselineRows);
        this.totalEstimate = totalEstimate;
        this.active = true;
    }

    /** 估算结果回填(COUNT(*) 在任务线程里执行, 不阻塞启动接口) */
    public void setTotalEstimate(long totalEstimate) {
        this.totalEstimate = totalEstimate;
    }

    /**
     * 任务不在运行中(完成/失败/暂停/停止): 停止统计并释放采样
     * 保留 totalEstimate / baselineRows, 便于暂停后仍能展示进度
     */
    public synchronized void deactivate() {
        this.active = false;
        this.samples.clear();
        this.rowsPerSec = 0D;
        this.readMs = 0D;
        this.writeMs = 0D;
        this.bottleneck = UNKNOWN;
        this.currentBatchRows = 0;
    }

    /* ============================================================
     *                       批次 采样
     * ============================================================ */

    /** 新批次开始(读取前调用), 让大盘能显示「当前处理到第几批」 */
    public void beginBatch(int batchNo, int batchSize) {
        this.currentBatch = batchNo;
        if (batchSize > 0) this.batchSize = batchSize;
        this.currentBatchRows = 0;
    }

    /** 读取过程中实时刷新本批已读行数 */
    public void readRows(int rows) {
        this.currentBatchRows = rows;
    }

    /**
     * 一批结束: 采样并刷新速率 / 读写耗时 / 瓶颈
     *
     * @param rows    本批行数(失败批次传 0, 只统计耗时)
     * @param readMs  源库读取耗时
     * @param writeMs 目标库写入耗时
     * @param costMs  本批总耗时
     */
    public synchronized void finishBatch(int rows, long readMs, long writeMs, long costMs) {
        long now = System.currentTimeMillis();
        samples.addLast(new long[]{now, rows, readMs, writeMs, Math.max(costMs, 0L)});
        while (!samples.isEmpty() && now - samples.peekFirst()[0] > WINDOW_MS) {
            samples.pollFirst();
        }
        while (samples.size() > MAX_SAMPLES) {
            samples.pollFirst();
        }
        this.lastBatchCostMs = costMs;
        refresh();
    }

    /** 依据窗口内采样刷新速率与瓶颈判定 */
    private void refresh() {
        long rows = 0L, readSum = 0L, writeSum = 0L, costSum = 0L;
        for (long[] s : samples) {
            rows += s[1];
            readSum += s[2];
            writeSum += s[3];
            costSum += s[4];
        }
        this.rowsPerSec = rows * 1000D / Math.max(costSum, 1L);
        double n = Math.max(samples.size(), 1);
        this.readMs = readSum / n;
        this.writeMs = writeSum / n;
        this.bottleneck = classify(readSum, writeSum);
    }

    /**
     * 瓶颈判定: 读取耗时明显大于写入 → 源库(读取)瓶颈; 反之 → 目标库(写入)瓶颈
     */
    private String classify(long readSum, long writeSum) {
        if (samples.isEmpty()) return UNKNOWN;
        if (MODE_STREAM.equals(compareMode)) {
            // 流式任务: readMs 是等待事件的时间, 只有写入耗时超过等待时间才算目标库扛不住
            return writeSum > readSum ? TARGET : BALANCED;
        }
        if (readSum + writeSum < MIN_COST_MS) return UNKNOWN;
        if (readSum > writeSum * BOTTLENECK_RATIO) return SOURCE;
        if (writeSum > readSum * BOTTLENECK_RATIO) return TARGET;
        return BALANCED;
    }

    /**
     * 实时速率: 窗口内长时间无新采样(暂停 / 卡住)时按 0 处理, 避免展示过期速率
     */
    public double liveRowsPerSec() {
        long[] last = samples.peekLast();
        if (!active || last == null) return 0D;
        if (System.currentTimeMillis() - last[0] > WINDOW_MS + 2000L) return 0D;
        return rowsPerSec;
    }
}
