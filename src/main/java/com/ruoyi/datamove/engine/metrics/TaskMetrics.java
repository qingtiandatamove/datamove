package com.ruoyi.datamove.engine.metrics;

import lombok.Getter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

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

    /* ============================================================
     *                     分片并行实时状态
     * 仅分片任务(shard_count > 1)使用; 单线程任务该 map 为空。
     * 每个 shard 独立线程写入自己的条目, 大盘按需读取快照。
     * ============================================================ */

    /** 分片状态: RUNNING / DONE / FAILED */
    public static final String SHARD_RUNNING = "RUNNING";
    public static final String SHARD_DONE     = "DONE";
    public static final String SHARD_FAILED   = "FAILED";

    private final Map<Integer, ShardState> shards = new ConcurrentHashMap<>();

    /**
     * 单个分片的实时状态 (由分片线程写入, 大盘线程读取)
     */
    public static final class ShardState {
        @Getter private final int shardNo;
        /** 分片负责的主键区间 [rangeLo, rangeHi] */
        @Getter private final long rangeLo;
        @Getter private final long rangeHi;
        /** 本分片已同步行数 */
        @Getter private volatile long rows;
        /** 本分片游标当前位置(最近一批的最大 id) */
        @Getter private volatile long currentId;
        /** 本分片实时速率(行/秒, 10s 窗口) */
        @Getter private volatile double rowsPerSec;
        /** 本分片已完成批次数 */
        @Getter private volatile int batches;
        /** RUNNING / DONE / FAILED */
        @Getter private volatile String state = SHARD_RUNNING;
        /** 失败原因(仅 FAILED 时) */
        @Getter private volatile String error;

        /** 本分片窗口采样: {结束时间戳, 行数, 耗时} */
        private final Deque<long[]> samples = new ArrayDeque<>();

        ShardState(int shardNo, long rangeLo, long rangeHi) {
            this.shardNo = shardNo;
            this.rangeLo = rangeLo;
            this.rangeHi = rangeHi;
            this.currentId = rangeLo;
        }

        /** 拷贝构造: 固化实时速率, 供大盘读取快照时使用, 避免序列化过程中读到中间态 */
        private ShardState(ShardState s, double rate) {
            this.shardNo = s.shardNo;
            this.rangeLo = s.rangeLo;
            this.rangeHi = s.rangeHi;
            this.rows = s.rows;
            this.currentId = s.currentId;
            this.rowsPerSec = rate;
            this.batches = s.batches;
            this.state = s.state;
            this.error = s.error;
        }

        /** 一批结束: 更新游标与窗口速率 */
        private synchronized void finishBatch(int batchRows, long newMaxId, long costMs) {
            long now = System.currentTimeMillis();
            samples.addLast(new long[]{now, batchRows, Math.max(costMs, 0L)});
            while (!samples.isEmpty() && now - samples.peekFirst()[0] > WINDOW_MS) {
                samples.pollFirst();
            }
            while (samples.size() > MAX_SAMPLES) {
                samples.pollFirst();
            }
            this.rows += batchRows;
            this.batches++;
            if (newMaxId > this.currentId) this.currentId = newMaxId;
            long r = 0L, c = 0L;
            for (long[] s : samples) { r += s[1]; c += s[2]; }
            this.rowsPerSec = r * 1000D / Math.max(c, 1L);
        }

        /** 实时速率: 窗口长时间无采样时按 0 处理 */
        private double liveRowsPerSec() {
            long[] last = samples.peekLast();
            if (last == null) return 0D;
            if (System.currentTimeMillis() - last[0] > WINDOW_MS + 2000L) return 0D;
            return rowsPerSec;
        }
    }

    /** 分片任务启动: 注册各分片区间(清空历史) */
    public synchronized void registerShards(List<long[]> ranges) {
        shards.clear();
        for (int k = 0; k < ranges.size(); k++) {
            shards.put(k + 1, new ShardState(k + 1, ranges.get(k)[0], ranges.get(k)[1]));
        }
    }

    /** 分片一批写入成功: 更新行数/游标/速率 */
    public void shardFinishBatch(int shardNo, int batchRows, long newMaxId, long costMs) {
        ShardState s = shards.get(shardNo);
        if (s != null) s.finishBatch(batchRows, newMaxId, costMs);
    }

    /** 分片完成(游标到头) */
    public void shardDone(int shardNo) {
        ShardState s = shards.get(shardNo);
        if (s != null) s.state = SHARD_DONE;
    }

    /** 分片失败 */
    public void shardFailed(int shardNo, String error) {
        ShardState s = shards.get(shardNo);
        if (s != null) { s.state = SHARD_FAILED; s.error = error; }
    }

    /** 是否有分片在跑 */
    public boolean hasShards() {
        return !shards.isEmpty();
    }

    /** 按分片号排序的分片状态快照(含实时速率归零判断) */
    public Collection<ShardState> shardSnapshot() {
        Map<Integer, ShardState> sorted = new TreeMap<>(shards);
        List<ShardState> out = new ArrayList<>(sorted.size());
        for (ShardState s : sorted.values()) {
            if (!SHARD_RUNNING.equals(s.state) || !active) {
                // 非运行中的分片速率置 0, 避免大盘展示过期速率
                out.add(new ShardState(s, 0D));
            } else {
                out.add(new ShardState(s, s.liveRowsPerSec()));
            }
        }
        return out;
    }

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
        shards.clear();
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
