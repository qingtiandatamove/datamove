package com.ruoyi.datamove.engine.full;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ruoyi.datamove.util.AlertUtils;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.mapper.SyncDatasourceMapper;
import com.ruoyi.datamove.engine.SyncContext;
import com.ruoyi.datamove.engine.consts.SyncType;
import com.ruoyi.datamove.engine.log.SyncLogService;
import com.ruoyi.datamove.engine.metrics.TaskMetrics;
import com.ruoyi.datamove.engine.metrics.TaskMetricsRegistry;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskFieldMapping;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;
import com.ruoyi.datamove.task.mapper.SyncTaskFieldMappingMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskProgressMapper;
import com.ruoyi.datamove.util.JdbcUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.BatchUpdateException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全量同步引擎 (核心)
 *
 * 对应需求文档:
 *  3.2.1 全量同步任务 - 双同步模式 (按主键ID / 按时间)
 *  3.2.2 断点续传 - 整批写入成功才更新断点,失败自动重试
 *  3.2.3 幂等防重 - INSERT ... ON DUPLICATE KEY UPDATE
 *  3.2.5 字段映射 - 源/目标字段名不同时按 sync_task_field_mapping 配置重命名同步;
 *           支持部分字段映射 (只同步已配对的字段, 断点字段未映射时自动补进 SELECT)
 *
 * 支持:
 *  - 启动/暂停/继续/终止
 *  - 实时日志、钉钉告警
 *  - 同任务单实例保护
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FullSyncEngine {

    private final SyncTaskMapper taskMapper;
    private final SyncTaskProgressMapper progressMapper;
    private final SyncDatasourceMapper datasourceMapper;
    private final SyncTaskFieldMappingMapper fieldMappingMapper;
    private final SyncLogService logService;
    private final TaskMetricsRegistry metricsRegistry;

    /** 运行中的任务上下文 */
    private static final Map<Long, SyncContext> RUNNING = new HashMap<>();
    private static final Set<Long> RUNNING_TASK = new HashSet<>();

    private static final String DATE_FMT = "yyyy-MM-dd HH:mm:ss";

    /* ============================================================
     *                       启 动 / 控 制
     * ============================================================ */

    /** 启动 (同步会异步进行) */
    public synchronized void start(Long taskId) {
        if (RUNNING_TASK.contains(taskId)) {
            throw new RuntimeException("任务已在运行中,请勿重复启动");
        }
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null) throw new RuntimeException("任务不存在");

        SyncDatasource src = datasourceMapper.selectById(task.getSourceId());
        SyncDatasource tgt = datasourceMapper.selectById(task.getTargetId());
        if (src == null || tgt == null) throw new RuntimeException("任务关联的数据源不存在");

        // 连通性测试 - 触发告警(钉钉 + 邮件), 失败原因写入 sync_task_log 便于"日志"弹窗追溯
        String srcErr = JdbcUtils.getConnectError(src);
        if (srcErr != null) {
            String msg = "无法连接源数据库: " + srcErr;
            AlertUtils.alert(task, "启动失败:源库连接失败",
                    "【DataMove告警】任务[" + task.getTaskName() + "]启动失败:源库连接失败 - " + srcErr);
            logService.writeStartupFailureLog(task, msg);
            throw new RuntimeException(msg);
        }
        String tgtErr = JdbcUtils.getConnectError(tgt);
        if (tgtErr != null) {
            String msg = "无法连接目标数据库: " + tgtErr;
            AlertUtils.alert(task, "启动失败:目标库连接失败",
                    "【DataMove告警】任务[" + task.getTaskName() + "]启动失败:目标库连接失败 - " + tgtErr);
            logService.writeStartupFailureLog(task, msg);
            throw new RuntimeException(msg);
        }

        // 覆盖模式: 清空目标表 + 清除断点, 强制从头全量同步
        if (task.getOverwriteFlag() != null && task.getOverwriteFlag() == 1) {
            truncateTargetTable(tgt, task.getTableName(), task.getTaskName());
            progressMapper.delete(new QueryWrapper<SyncTaskProgress>().eq("task_id", taskId));
        }

        // 加载/初始化断点
        SyncTaskProgress progress = progressMapper.selectOne(
                new QueryWrapper<SyncTaskProgress>().eq("task_id", taskId));
        if (progress == null) {
            progress = new SyncTaskProgress();
            progress.setTaskId(taskId);
            progress.setLastSyncMaxId(task.getStartId() == null ? 0L : task.getStartId());
            progress.setLastSyncTime(task.getStartTime());
            progress.setTotalRows(0L);
            progress.setSuccessRows(0L);
            progress.setFailedRows(0L);
            progress.setStatus(SyncType.STATUS_STOP);
            progress.setCreateTime(new Date());
            progress.setUpdateTime(new Date());
            progressMapper.insert(progress);
        }

        // 实时指标: 记录本次运行起点(续传时为历史累计行数),
        // 总行数估算(COUNT(*))可能较慢, 放到任务线程里回填, 不阻塞启动接口
        TaskMetrics metrics = metricsRegistry.ensure(taskId,
                task.getBatchSize() == null || task.getBatchSize() <= 0 ? 1000 : task.getBatchSize());
        metrics.activate(-1L, progress.getSuccessRows() == null ? 0L : progress.getSuccessRows());

        SyncContext ctx = SyncContext.builder()
                .task(task)
                .source(src)
                .target(tgt)
                .batchNo(new AtomicLong(0))
                .pauseFlag(new AtomicBoolean(false))
                .stopFlag(new AtomicBoolean(false))
                .build();

        // 字段映射: 启动时一次性加载, 后续 doMainLoop 不再查库
        loadFieldMappings(ctx, task);

        RUNNING.put(taskId, ctx);
        RUNNING_TASK.add(taskId);

        // 更新状态为 RUNNING
        task.setStatus(SyncType.STATUS_RUNNING);
        taskMapper.updateById(task);
        progress.setStatus(SyncType.STATUS_RUNNING);
        progress.setStartTime(new Date());
        progress.setEndTime(null);
        progress.setUpdateTime(new Date());
        progressMapper.updateById(progress);

        log.info("[Sync] task[{}] start, mode={}, table={}, mappingEnabled={}",
                task.getTaskName(), task.getSyncMode(), task.getTableName(), ctx.isMappingEnabled());

        final SyncContext fCtx = ctx;
        final Long fTaskId = taskId;
        final SyncTaskProgress fProgress = progress;
        Thread t = new Thread(() -> doMainLoop(fCtx, fProgress), "datamove-task-" + fTaskId);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 加载字段映射到 SyncContext (按 sort_no, id 升序)
     * - 空配置 → orderedSourceFields/orderedTargetFields 留空 → 走同名兼容
     * - 有配置 → 准备 srcFieldToTarget / orderedSourceFields / orderedTargetFields
     */
    private void loadFieldMappings(SyncContext ctx, SyncTask task) {
        List<SyncTaskFieldMapping> mappings = fieldMappingMapper.selectList(
                new QueryWrapper<SyncTaskFieldMapping>()
                        .eq("task_id", task.getId())
                        .orderByAsc("sort_no", "id"));
        ctx.setFieldMappings(mappings);
        if (mappings == null || mappings.isEmpty()) return;

        Map<String, String> src2tgt = new LinkedHashMap<>();
        List<String> srcList = new ArrayList<>();
        List<String> tgtList = new ArrayList<>();
        for (SyncTaskFieldMapping m : mappings) {
            if (m == null || m.getSourceField() == null || m.getTargetField() == null) continue;
            src2tgt.put(m.getSourceField(), m.getTargetField());
            srcList.add(m.getSourceField());
            tgtList.add(m.getTargetField());
        }
        ctx.setSrcFieldToTarget(src2tgt);
        ctx.setOrderedSourceFields(srcList);
        ctx.setOrderedTargetFields(tgtList);
        log.info("[Sync] task[{}] 字段映射加载完成, 共 {} 条配对", task.getTaskName(), src2tgt.size());
    }

    /**
     * 覆盖模式: 清空目标表
     * 表不存在时不中断启动 (后续同步流程会自动建表)
     */
    private void truncateTargetTable(SyncDatasource tgt, String tableName, String taskName) {
        try (Connection c = JdbcUtils.getConnection(tgt);
             Statement st = c.createStatement()) {
            st.execute("TRUNCATE TABLE `" + tableName + "`");
            log.info("[Sync] task[{}] 覆盖模式: 已清空目标表 {}", taskName, tableName);
        } catch (Exception e) {
            log.warn("[Sync] task[{}] 覆盖模式清空目标表 {} 失败(表可能不存在,将由同步流程自动建表): {}",
                    taskName, tableName, e.getMessage());
        }
    }

    public synchronized void pause(Long taskId) {
        SyncContext ctx = RUNNING.get(taskId);
        if (ctx != null) ctx.requestPause();
        SyncTask task = taskMapper.selectById(taskId);
        if (task != null) {
            task.setStatus(SyncType.STATUS_PAUSE);
            taskMapper.updateById(task);
        }
    }

    public synchronized void resume(Long taskId) {
        SyncContext ctx = RUNNING.get(taskId);
        if (ctx != null) { ctx.resume(); return; }
        // worker 已退出,重新启动(用断点继续)
        SyncTask task = taskMapper.selectById(taskId);
        if (task != null && SyncType.STATUS_PAUSE.equals(task.getStatus())) {
            start(taskId);
        } else if (task != null) {
            task.setStatus(SyncType.STATUS_RUNNING);
            taskMapper.updateById(task);
        }
    }

    public synchronized void stop(Long taskId) {
        SyncContext ctx = RUNNING.get(taskId);
        if (ctx != null) ctx.requestStop();

        RUNNING_TASK.remove(taskId);
        RUNNING.remove(taskId);

        SyncTask task = taskMapper.selectById(taskId);
        if (task != null) {
            task.setStatus(SyncType.STATUS_STOP);
            taskMapper.updateById(task);
        }
        SyncTaskProgress p = progressMapper.selectOne(
                new QueryWrapper<SyncTaskProgress>().eq("task_id", taskId));
        if (p != null) {
            p.setStatus(SyncType.STATUS_STOP);
            p.setEndTime(new Date());
            p.setUpdateTime(new Date());
            progressMapper.updateById(p);
        }
    }

    public SyncTaskProgress progress(Long taskId) {
        return progressMapper.selectOne(new QueryWrapper<SyncTaskProgress>().eq("task_id", taskId));
    }

    /* ============================================================
     *                       主 循 环
     * ============================================================ */

    private void doMainLoop(SyncContext ctx, SyncTaskProgress progress) {
        SyncTask task = ctx.getTask();
        try {
            if (SyncType.MODE_ID.equalsIgnoreCase(task.getSyncMode())) {
                if (shouldShard(task, progress)) {
                    doShardedSync(ctx, progress);
                } else {
                    doSyncById(ctx, progress);
                }
            } else if (SyncType.MODE_TIME.equalsIgnoreCase(task.getSyncMode())) {
                doSyncByTime(ctx, progress);
            } else {
                throw new RuntimeException("不支持的同步模式: " + task.getSyncMode());
            }
        } catch (Throwable t) {
            log.error("[Sync] task[{}] sync error", task.getTaskName(), t);
            AlertUtils.alert(task, "同步异常",
                    "【DataMove告警】任务[" + task.getTaskName() + "]同步异常:\n" + t.getMessage());
            logService.writeLog(ctx, -1, null, null, 0, progress.getTotalRows(),
                    0L, SyncType.LOG_FAILED, t.getMessage());

            task.setStatus(SyncType.STATUS_FAILED);
            taskMapper.updateById(task);

            progress.setStatus(SyncType.STATUS_FAILED);
            progress.setEndTime(new Date());
            progress.setUpdateTime(new Date());
            progressMapper.updateById(progress);
        } finally {
            TaskMetrics m = metricsRegistry.get(task.getId());
            if (m != null) m.deactivate();
            RUNNING_TASK.remove(task.getId());
            RUNNING.remove(task.getId());
        }
    }

    /* ============ 按主键ID同步 ============ */

    /* ------------------------------------------------------------
     * 大数据分片并行同步 (shard_count > 1 时启用)
     *
     * 策略:
     *  - 按 MIN/MAX(主键) 均分 id 区间 (主键索引上聚合, 代价极小)
     *  - 每个分片独立线程 + 独立源/目标连接并行读 写
     *  - 区间互不重叠, INSERT ... ON DUPLICATE KEY UPDATE 天然无冲突
     *  - 进度/断点聚合写 sync_task_progress; 大盘吞吐 = 各分片之和
     *
     * 适用: 覆盖式全量 (overwrite=1) 或首次全量 (无历史断点)。
     * 存在断点的续传任务自动回退单线程, 保证游标语义不变。
     * 暂停后 resume 会整体重跑 (幂等写, 无脏数据, 代价是重新拉一遍)。
     * ------------------------------------------------------------ */

    /** 是否走分片并行 */
    private boolean shouldShard(SyncTask task, SyncTaskProgress progress) {
        int sc = task.getShardCount() == null ? 1 : task.getShardCount();
        if (sc <= 1) return false;
        if (!SyncType.MODE_ID.equalsIgnoreCase(task.getSyncMode())) return false;
        long done = progress.getTotalRows() == null ? 0L : progress.getTotalRows();
        if (done > 0) {
            log.info("[Sync] task[{}] 已有断点(累计 {} 行), 分片并行回退单线程续传", task.getTaskName(), done);
            return false;
        }
        return true;
    }

    /**
     * 按主键 MIN/MAX 均分为 shardCount 个 [lo, hi] 区间。
     * 稀疏主键(大量空洞)时分片行数不均, 自增主键下最均匀。
     * 空表/异常时返回空列表, 调用方回退单线程。
     */
    private List<long[]> splitByIdRange(SyncContext ctx, String table, String idField, int shardCount) {
        List<long[]> ranges = new ArrayList<>();
        String sql = "SELECT MIN(`" + idField + "`), MAX(`" + idField + "`) FROM `" + table + "`";
        try (Connection c = JdbcUtils.getConnection(ctx.getSource());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) return ranges;
            Object minO = rs.getObject(1);
            Object maxO = rs.getObject(2);
            if (!(minO instanceof Number) || !(maxO instanceof Number)) return ranges;
            long min = ((Number) minO).longValue();
            long max = ((Number) maxO).longValue();
            long width = (max - min) / shardCount + 1;
            if (width <= 0) {
                ranges.add(new long[]{min, max});
                return ranges;
            }
            for (int k = 0; k < shardCount; k++) {
                long lo = min + (long) k * width;
                if (lo > max) break;
                long hi = k == shardCount - 1 ? max : Math.min(lo + width - 1, max);
                ranges.add(new long[]{lo, hi});
            }
        } catch (Exception e) {
            log.warn("[Sync] task[{}] 分片区间划分失败, 回退单线程: {}", ctx.getTask().getTaskName(), e.getMessage());
        }
        return ranges;
    }

    /** 分片并行主流程 (跑在任务主线程上, join 等待全部分片结束) */
    private void doShardedSync(SyncContext ctx, SyncTaskProgress progress) throws Exception {
        SyncTask task = ctx.getTask();
        String table = task.getTableName();
        String idField = task.getIdField() == null ? "id" : task.getIdField();
        int batchSize = task.getBatchSize() == null || task.getBatchSize() <= 0 ? 1000 : task.getBatchSize();
        int shardCount = task.getShardCount();

        // 字段/映射/自动建表: 与单线程一致, 协调线程做一次即可
        List<String> srcFields = ensureFields(ctx, table);
        List<String> tgtFields = resolveTargetFields(ctx, srcFields);
        String srcIdField = idField;
        if (ctx.isMappingEnabled()) {
            String src = findSourceFieldByTarget(ctx, idField);
            srcIdField = src == null ? idField : src;
        }
        // lambda 捕获要求 effectively final
        final String fSrcIdField = srcIdField;
        // 部分字段映射: 断点字段(主键)未映射也要进 SELECT
        List<String> selectFields = withBreakpointFields(srcFields, srcIdField);
        String selectCols = joinBackticked(selectFields);
        String insertSql = buildInsertSql(table, tgtFields);

        List<long[]> ranges = splitByIdRange(ctx, table, srcIdField, shardCount);
        if (ranges.isEmpty()) {
            log.info("[Sync] task[{}] 空表或划分失败, 走单线程路径", task.getTaskName());
            doSyncById(ctx, progress);
            return;
        }
        if (ranges.size() == 1) {
            log.info("[Sync] task[{}] 数据量不足以分片, 走单线程路径", task.getTaskName());
            doSyncById(ctx, progress);
            return;
        }

        // 起始ID约束: 分片下界不低于任务配置的 startId
        long startId = task.getStartId() == null ? 0L : task.getStartId();
        for (long[] r : ranges) if (r[0] < startId) r[0] = startId;

        // 大盘: 总量估算 + 分片区间注册(实时分片监控)
        TaskMetrics metrics = metricsRegistry.get(task.getId());
        if (metrics != null) {
            metrics.setTotalEstimate(estimateTotalRows(ctx, progress));
            metrics.registerShards(ranges);
        }

        log.info("[Sync] task[{}] 分片并行启动: {} 分片, batchSize={}, 区间示例 [{} , {}] ~ [{} , {}]",
                task.getTaskName(), ranges.size(), batchSize,
                ranges.get(0)[0], ranges.get(0)[1],
                ranges.get(ranges.size() - 1)[0], ranges.get(ranges.size() - 1)[1]);
        // 启动说明属正常信息, 写入「同步内容」而非「异常信息」
        logService.writeLog(ctx, null, 0, "SPLIT", ranges.size() + "分片",
                0, 0, 0L, SyncType.LOG_SUCCESS, null,
                "分片并行启动: " + ranges.size() + " 个分片, batchSize=" + batchSize
                        + ", 区间 " + describeRanges(ranges));

        // 跨分片共享状态
        final Object progressLock = new Object();
        final AtomicLong totalRowsAll = new AtomicLong(progress.getTotalRows() == null ? 0L : progress.getTotalRows());
        final AtomicLong globalMaxId = new AtomicLong(progress.getLastSyncMaxId() == null ? 0L : progress.getLastSyncMaxId());
        final AtomicBoolean anyFailed = new AtomicBoolean(false);
        final List<String> fSrcFields = srcFields;
        final List<String> fTgtFields = tgtFields;

        List<Thread> threads = new ArrayList<>(ranges.size());
        for (int k = 0; k < ranges.size(); k++) {
            final int shardNo = k + 1;
            final long lo = ranges.get(k)[0];
            final long hi = ranges.get(k)[1];
            final String selectSql = "SELECT " + selectCols + " FROM `" + table
                    + "` WHERE `" + fSrcIdField + "` >= ? AND `" + fSrcIdField + "` <= ? AND `" + fSrcIdField
                    + "` > ? ORDER BY `" + fSrcIdField + "` ASC LIMIT " + batchSize;
            Thread t = new Thread(() ->
                    runShard(ctx, progress, shardNo, lo, hi, selectSql, insertSql,
                            fSrcFields, fTgtFields, fSrcIdField, batchSize,
                            progressLock, totalRowsAll, globalMaxId, anyFailed),
                    "datamove-task-" + task.getId() + "-shard" + shardNo);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();

        if (anyFailed.get()) {
            throw new RuntimeException("存在失败分片, 已终止本次同步, 详见同步日志 (重启任务将整体重跑)");
        }
        if (ctx.isPaused()) {
            // 分片模式无逐分片断点, 暂停 = 进度作废; resume 走 start 重新分片 (幂等写无脏数据)
            progress.setLastSyncMaxId(startId);
            progress.setTotalRows(0L);
            progress.setSuccessRows(0L);
            progress.setFailedRows(0L);
            progress.setUpdateTime(new Date());
            progressMapper.updateById(progress);
            updatePaused(task, progress);
            return;
        }
        if (ctx.isStopped()) {
            updateStopped(task, progress);
            return;
        }
        complete(task, progress, globalMaxId.get());
    }

    /** 单个分片: 区间 [lo, hi] 内按游标分批拉取写入, 逻辑与 doSyncById 一致 */
    private void runShard(SyncContext ctx, SyncTaskProgress progress, int shardNo,
                          long lo, long hi, String selectSql, String insertSql,
                          List<String> srcFields, List<String> tgtFields, String srcIdField, int batchSize,
                          Object progressLock, AtomicLong totalRowsAll, AtomicLong globalMaxId,
                          AtomicBoolean anyFailed) {
        SyncTask task = ctx.getTask();
        String tag = "[S" + shardNo + "]";
        long lastId = lo;
        TaskMetrics metrics = metricsRegistry.get(task.getId());
        try (Connection srcConn = JdbcUtils.newConnection(ctx.getSource());
             Connection tgt = JdbcUtils.newConnection(ctx.getTarget());
             PreparedStatement psSrc = srcConn.prepareStatement(selectSql);
             PreparedStatement psTgt = tgt.prepareStatement(insertSql)) {

            tgt.setAutoCommit(false);
            psSrc.setFetchSize(batchSize);

            while (!ctx.isStopped() && !ctx.isPaused()) {
                psSrc.setLong(1, lo);
                psSrc.setLong(2, hi);
                psSrc.setLong(3, lastId);
                long startMs = System.currentTimeMillis();
                int batchNo = (int) ctx.getBatchNo().incrementAndGet();
                int batchRows = 0;
                long newMaxId = lastId;
                List<Object[]> batchValues = new ArrayList<>();
                if (metrics != null) metrics.beginBatch(batchNo, batchSize);

                long readStartMs = System.currentTimeMillis();
                try (ResultSet rs = psSrc.executeQuery()) {
                    while (rs.next()) {
                        Object[] values = new Object[srcFields.size()];
                        for (int i = 0; i < srcFields.size(); i++) {
                            values[i] = rs.getObject(srcFields.get(i));
                        }
                        batchValues.add(values);
                        Object idObj = rs.getObject(srcIdField);
                        if (idObj instanceof Number) {
                            long id = ((Number) idObj).longValue();
                            if (id > newMaxId) newMaxId = id;
                        }
                        batchRows++;
                        if (metrics != null) metrics.readRows(batchRows);
                    }
                }
                long readMs = System.currentTimeMillis() - readStartMs;

                if (batchRows == 0) {
                    // 本分片到头
                    tgt.commit();
                    if (metrics != null) metrics.shardDone(shardNo);
                    logService.writeLog(ctx, shardNo, batchNo, tag + "EOF", String.valueOf(hi),
                            0, totalRowsAll.get(), System.currentTimeMillis() - startMs, SyncType.LOG_SUCCESS, null);
                    return;
                }

                long writeStartMs = System.currentTimeMillis();
                try {
                    for (Object[] v : batchValues) {
                        for (int i = 0; i < v.length; i++) psTgt.setObject(i + 1, v[i]);
                        psTgt.addBatch();
                    }
                    psTgt.executeBatch();
                    tgt.commit();
                    if (metrics != null) metrics.finishBatch(batchRows, readMs,
                            System.currentTimeMillis() - writeStartMs, System.currentTimeMillis() - startMs);
                    if (metrics != null) metrics.shardFinishBatch(shardNo, batchRows, newMaxId,
                            System.currentTimeMillis() - startMs);

                    long prevId = lastId;
                    lastId = newMaxId;
                    // 聚合进度: 断点取全局最大 id, 行数累加
                    synchronized (progressLock) {
                        long total = totalRowsAll.addAndGet(batchRows);
                        progress.setLastSyncMaxId(Math.max(
                                progress.getLastSyncMaxId() == null ? 0L : progress.getLastSyncMaxId(), newMaxId));
                        globalMaxId.accumulateAndGet(newMaxId, Math::max);
                        progress.setTotalRows(total);
                        progress.setSuccessRows((progress.getSuccessRows() == null ? 0L : progress.getSuccessRows()) + batchRows);
                        progress.setUpdateTime(new Date());
                        progressMapper.updateById(progress);
                        logService.writeLog(ctx, shardNo, batchNo, tag + prevId, tag + lastId, batchRows, total,
                                System.currentTimeMillis() - startMs, SyncType.LOG_SUCCESS, null);
                    }
                } catch (BatchUpdateException bue) {
                    tgt.rollback();
                    if (metrics != null) metrics.finishBatch(0, readMs,
                            System.currentTimeMillis() - writeStartMs, System.currentTimeMillis() - startMs);
                    synchronized (progressLock) {
                        progress.setFailedRows((progress.getFailedRows() == null ? 0L : progress.getFailedRows()) + batchRows);
                        progress.setUpdateTime(new Date());
                        progressMapper.updateById(progress);
                        logService.writeLog(ctx, shardNo, batchNo, tag + String.valueOf(lastId),
                                tag + String.valueOf(newMaxId), batchRows, totalRowsAll.get(),
                                System.currentTimeMillis() - startMs, SyncType.LOG_FAILED, bue.getMessage());
                    }
                    lastId = newMaxId; // 部分失败继续推进 (与单线程语义一致)
                }
            }
            tgt.commit();
        } catch (Exception e) {
            log.error("[Sync] task[{}] {} 分片异常: {}", task.getTaskName(), tag, e.getMessage(), e);
            anyFailed.set(true);
            if (metrics != null) metrics.shardFailed(shardNo, e.getMessage());
            // 快速失败: 让其他分片尽快退出, 整体按 FAILED 处理
            ctx.requestStop();
            logService.writeLog(ctx, shardNo, -1, tag + "FAIL", tag + String.valueOf(hi),
                    0, totalRowsAll.get(), 0L, SyncType.LOG_FAILED,
                    tag + " 分片异常: " + e.getMessage());
        }
    }

    /** 分片区间摘要 (日志用) */
    private static String describeRanges(List<long[]> ranges) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append('[').append(ranges.get(i)[0]).append(',').append(ranges.get(i)[1]).append(']');
        }
        return sb.toString();
    }

    private void doSyncById(SyncContext ctx, SyncTaskProgress progress) throws Exception {
        SyncTask task = ctx.getTask();
        String table = task.getTableName();
        String idField = task.getIdField() == null ? "id" : task.getIdField();
        int batchSize = task.getBatchSize() == null || task.getBatchSize() <= 0 ? 1000 : task.getBatchSize();

        // 字段映射模式下: idField 必须存在于源字段集里, 否则按源主键中断, 用源 idField
        // 同名模式: idField 是目标表 id 列, 源里用同名取数
        String srcIdField = idField;
        if (ctx.isMappingEnabled()) {
            // idField 看作「目标主键列」, 找它对应的源字段
            String src = findSourceFieldByTarget(ctx, idField);
            srcIdField = src == null ? idField : src;
        }

        List<String> srcFields = ensureFields(ctx, table);
        List<String> tgtFields = resolveTargetFields(ctx, srcFields);

        String insertSql = buildInsertSql(table, tgtFields);
        // 部分字段映射: 断点字段(主键)即使未映射也要进 SELECT, 否则按列名取值会报 Column not found
        List<String> selectFields = withBreakpointFields(srcFields, srcIdField);
        String selectSql = "SELECT " + joinBackticked(selectFields) + " FROM `" + table + "` WHERE `" + srcIdField + "` > ? ORDER BY `" + srcIdField + "` ASC LIMIT " + batchSize;

        long lastId = progress.getLastSyncMaxId() == null ? 0L : progress.getLastSyncMaxId();
        long totalRows = progress.getTotalRows() == null ? 0L : progress.getTotalRows();

        try (Connection srcConn = JdbcUtils.getConnection(ctx.getSource());
             Connection tgt = JdbcUtils.getConnection(ctx.getTarget());
             PreparedStatement psSrc = srcConn.prepareStatement(selectSql);
             PreparedStatement psTgt = tgt.prepareStatement(insertSql)) {

            tgt.setAutoCommit(false);
            psSrc.setFetchSize(batchSize);

            // 估算本次需同步总行数, 供大盘计算进度与 ETA
            TaskMetrics metrics = metricsRegistry.get(task.getId());
            if (metrics != null) metrics.setTotalEstimate(estimateTotalRows(ctx, progress));

            while (!ctx.isStopped()) {
                if (ctx.isPaused()) {
                    updatePaused(task, progress);
                    return;
                }
                psSrc.setLong(1, lastId);
                long startMs = System.currentTimeMillis();
                int batchNo = (int) ctx.getBatchNo().incrementAndGet();
                int batchRows = 0;
                long newMaxId = lastId;
                List<Object[]> batchValues = new ArrayList<>();
                if (metrics != null) metrics.beginBatch(batchNo, batchSize);

                // 源库读取耗时(查询 + 取数), 与写入耗时对比即瓶颈所在
                long readStartMs = System.currentTimeMillis();
                try (ResultSet rs = psSrc.executeQuery()) {
                    while (rs.next()) {
                        Object[] values = new Object[tgtFields.size()];
                        for (int i = 0; i < srcFields.size(); i++) {
                            values[i] = rs.getObject(srcFields.get(i));
                        }
                        batchValues.add(values);
                        Object idObj = rs.getObject(srcIdField);
                        if (idObj instanceof Number) {
                            long id = ((Number) idObj).longValue();
                            if (id > newMaxId) newMaxId = id;
                        }
                        batchRows++;
                        if (metrics != null) metrics.readRows(batchRows);
                    }
                }
                long readMs = System.currentTimeMillis() - readStartMs;

                if (batchRows == 0) {
                    tgt.commit();
                    complete(task, progress, newMaxId);
                    logService.writeLog(ctx, batchNo, String.valueOf(lastId), String.valueOf(lastId),
                            0, totalRows, System.currentTimeMillis() - startMs, SyncType.LOG_SUCCESS, null);
                    return;
                }

                long writeStartMs = System.currentTimeMillis();
                try {
                    for (Object[] v : batchValues) {
                        for (int i = 0; i < v.length; i++) psTgt.setObject(i + 1, v[i]);
                        psTgt.addBatch();
                    }
                    psTgt.executeBatch();
                    tgt.commit();
                    if (metrics != null) metrics.finishBatch(batchRows, readMs,
                            System.currentTimeMillis() - writeStartMs, System.currentTimeMillis() - startMs);

                    // 写入成功 - 更新断点 + 累计
                    lastId = newMaxId;
                    totalRows += batchRows;
                    progress.setLastSyncMaxId(lastId);
                    progress.setTotalRows(totalRows);
                    progress.setSuccessRows((progress.getSuccessRows() == null ? 0L : progress.getSuccessRows()) + batchRows);
                    progress.setUpdateTime(new Date());
                    progressMapper.updateById(progress);

                    logService.writeLog(ctx, batchNo, String.valueOf(lastId - batchRows + 1),
                            String.valueOf(lastId), batchRows, totalRows,
                            System.currentTimeMillis() - startMs, SyncType.LOG_SUCCESS, null);
                } catch (BatchUpdateException bue) {
                    tgt.rollback();
                    if (metrics != null) metrics.finishBatch(0, readMs,
                            System.currentTimeMillis() - writeStartMs, System.currentTimeMillis() - startMs);
                    int[] counts = bue.getUpdateCounts();
                    int success = 0;
                    if (counts != null) for (int c : counts) if (c > 0 || c == Statement.SUCCESS_NO_INFO) success++;
                    int failed = batchRows - success;
                    totalRows += success;
                    progress.setTotalRows(totalRows);
                    progress.setFailedRows((progress.getFailedRows() == null ? 0L : progress.getFailedRows()) + failed);
                    progress.setUpdateTime(new Date());
                    progressMapper.updateById(progress);
                    logService.writeLog(ctx, batchNo, String.valueOf(lastId),
                            String.valueOf(newMaxId), batchRows, totalRows,
                            System.currentTimeMillis() - startMs, SyncType.LOG_FAILED, bue.getMessage());
                    // 部分失败: 不抛 - 继续下一批(已在文档说明 批次失败断点不更新,本批次部分失败,其他失败)
                }
            }
            // 走到了 stop()
            tgt.commit();
            updateStopped(task, progress);
        }
    }

    /* ============ 按时间字段同步 ============ */

    private void doSyncByTime(SyncContext ctx, SyncTaskProgress progress) throws Exception {
        SyncTask task = ctx.getTask();
        String table = task.getTableName();
        String idField = task.getIdField() == null ? "id" : task.getIdField();
        String timeField = task.getTimeField() == null ? "update_time" : task.getTimeField();
        int batchSize = task.getBatchSize() == null || task.getBatchSize() <= 0 ? 1000 : task.getBatchSize();

        // 字段映射: idField / timeField 是目标列名, 找到对应源列名
        String srcIdField = idField;
        String srcTimeField = timeField;
        if (ctx.isMappingEnabled()) {
            String src = findSourceFieldByTarget(ctx, idField);
            srcIdField = src == null ? idField : src;
            src = findSourceFieldByTarget(ctx, timeField);
            srcTimeField = src == null ? timeField : src;
        }

        List<String> srcFields = ensureFields(ctx, table);
        List<String> tgtFields = resolveTargetFields(ctx, srcFields);

        String insertSql = buildInsertSql(table, tgtFields);

        // key 排序: 时间 ASC, ID ASC, 用于同秒拆分
        // 部分字段映射: 断点字段(时间/主键)即使未映射也要进 SELECT, 否则按列名取值会报 Column not found
        List<String> selectFields = withBreakpointFields(srcFields, srcTimeField, srcIdField);
        String selectSql = "SELECT " + joinBackticked(selectFields) + " FROM `" + table + "` WHERE `" + srcTimeField + "` > ? OR (`" + srcTimeField + "` = ? AND `" + srcIdField + "` > ?) ORDER BY `" + srcTimeField + "` ASC, `" + srcIdField + "` ASC LIMIT " + batchSize;

        Date lastTime = progress.getLastSyncTime();
        Long lastIdInBatch = progress.getLastSyncMaxId();
        long totalRows = progress.getTotalRows() == null ? 0L : progress.getTotalRows();

        try (Connection srcConn = JdbcUtils.getConnection(ctx.getSource());
             Connection tgt = JdbcUtils.getConnection(ctx.getTarget());
             PreparedStatement psSrc = srcConn.prepareStatement(selectSql);
             PreparedStatement psTgt = tgt.prepareStatement(insertSql)) {

            tgt.setAutoCommit(false);

            // 估算本次需同步总行数, 供大盘计算进度与 ETA
            TaskMetrics metrics = metricsRegistry.get(task.getId());
            if (metrics != null) metrics.setTotalEstimate(estimateTotalRows(ctx, progress));

            while (!ctx.isStopped()) {
                if (ctx.isPaused()) {
                    updatePaused(task, progress);
                    return;
                }
                Timestamp ts = lastTime == null ? new Timestamp(0L) : new Timestamp(lastTime.getTime());
                psSrc.setTimestamp(1, ts);
                psSrc.setTimestamp(2, ts);
                psSrc.setLong(3, lastIdInBatch == null ? 0L : lastIdInBatch);

                long startMs = System.currentTimeMillis();
                int batchNo = (int) ctx.getBatchNo().incrementAndGet();
                int batchRows = 0;
                Date newMaxTime = lastTime;
                long newMaxIdInBatch = lastIdInBatch == null ? 0L : lastIdInBatch;
                List<Object[]> batchValues = new ArrayList<>();
                if (metrics != null) metrics.beginBatch(batchNo, batchSize);

                // 源库读取耗时(查询 + 取数), 与写入耗时对比即瓶颈所在
                long readStartMs = System.currentTimeMillis();
                try (ResultSet rs = psSrc.executeQuery()) {
                    while (rs.next()) {
                        Object[] values = new Object[tgtFields.size()];
                        for (int i = 0; i < srcFields.size(); i++) {
                            values[i] = rs.getObject(srcFields.get(i));
                        }
                        batchValues.add(values);

                        // 时间断点必须用 getTimestamp() 读取: MySQL 8 驱动 getObject() 对
                        // datetime 返回 LocalDateTime, instanceof Timestamp 判断会失效,
                        // 导致断点不推进、反复同步同一批数据。
                        // getTimestamp() 按连接时区换算, 与 MyBatis 读取 last_sync_time 口径一致。
                        Timestamp rowTs = rs.getTimestamp(srcTimeField);
                        Object iObj = rs.getObject(srcIdField);
                        Date rowTime = rowTs == null ? null : new Date(rowTs.getTime());
                        long rowId = iObj instanceof Number ? ((Number) iObj).longValue() : 0L;

                        if (rowTime != null) {
                            if (newMaxTime == null || rowTime.after(newMaxTime)) {
                                newMaxTime = rowTime;
                                newMaxIdInBatch = rowId;
                            } else if (rowTime.equals(newMaxTime) && rowId > newMaxIdInBatch) {
                                newMaxIdInBatch = rowId;
                            }
                        }
                        batchRows++;
                        if (metrics != null) metrics.readRows(batchRows);
                    }
                }
                long readMs = System.currentTimeMillis() - readStartMs;

                if (batchRows == 0) {
                    tgt.commit();
                    complete(task, progress, null);
                    logService.writeLog(ctx, batchNo,
                            lastTime == null ? "" : new SimpleDateFormat(DATE_FMT).format(lastTime),
                            lastTime == null ? "" : new SimpleDateFormat(DATE_FMT).format(lastTime),
                            0, totalRows, System.currentTimeMillis() - startMs, SyncType.LOG_SUCCESS, null);
                    return;
                }

                // 读到数据但从数据里取不到时间值 => 断点无法推进, 继续跑只会反复同步同一批数据
                if (newMaxTime == lastTime && newMaxIdInBatch == (lastIdInBatch == null ? 0L : lastIdInBatch)) {
                    tgt.rollback();
                    throw new RuntimeException("时间字段 `" + srcTimeField + "` 无法解析为时间类型, 请检查任务配置");
                }

                long writeStartMs = System.currentTimeMillis();
                try {
                    for (Object[] v : batchValues) {
                        for (int i = 0; i < v.length; i++) psTgt.setObject(i + 1, v[i]);
                        psTgt.addBatch();
                    }
                    psTgt.executeBatch();
                    tgt.commit();
                    if (metrics != null) metrics.finishBatch(batchRows, readMs,
                            System.currentTimeMillis() - writeStartMs, System.currentTimeMillis() - startMs);

                    lastTime = newMaxTime;
                    lastIdInBatch = newMaxIdInBatch;
                    totalRows += batchRows;

                    progress.setLastSyncTime(lastTime);
                    progress.setLastSyncMaxId(lastIdInBatch);
                    progress.setTotalRows(totalRows);
                    progress.setSuccessRows((progress.getSuccessRows() == null ? 0L : progress.getSuccessRows()) + batchRows);
                    progress.setUpdateTime(new Date());
                    progressMapper.updateById(progress);

                    logService.writeLog(ctx, batchNo,
                            lastTime == null ? "" : new SimpleDateFormat(DATE_FMT).format(lastTime),
                            "", batchRows, totalRows,
                            System.currentTimeMillis() - startMs, SyncType.LOG_SUCCESS, null);
                } catch (BatchUpdateException bue) {
                    tgt.rollback();
                    if (metrics != null) metrics.finishBatch(0, readMs,
                            System.currentTimeMillis() - writeStartMs, System.currentTimeMillis() - startMs);
                    progress.setFailedRows((progress.getFailedRows() == null ? 0L : progress.getFailedRows()) + batchRows);
                    progress.setUpdateTime(new Date());
                    progressMapper.updateById(progress);
                    logService.writeLog(ctx, batchNo,
                            lastTime == null ? "" : new SimpleDateFormat(DATE_FMT).format(lastTime),
                            newMaxTime == null ? "" : new SimpleDateFormat(DATE_FMT).format(newMaxTime),
                            batchRows, totalRows, System.currentTimeMillis() - startMs,
                            SyncType.LOG_FAILED, bue.getMessage());
                }
            }
            tgt.commit();
            updateStopped(task, progress);
        }
    }

    /* ============ Helpers ============ */

    /** 估算本次运行还需同步的总行数, 仅用于大盘计算进度与 ETA */
    private long estimateTotalRows(SyncContext ctx, SyncTaskProgress progress) {
        SyncTask task = ctx.getTask();
        String table = task.getTableName();
        boolean byTime = SyncType.MODE_TIME.equals(task.getSyncMode());
        // 估算也要用源字段 (timeField/idField 可能是目标列名)
        String field;
        if (byTime) {
            field = task.getTimeField() == null ? "update_time" : task.getTimeField();
            if (ctx.isMappingEnabled()) {
                String src = findSourceFieldByTarget(ctx, field);
                field = src == null ? field : src;
            }
        } else {
            field = task.getIdField() == null ? "id" : task.getIdField();
            if (ctx.isMappingEnabled()) {
                String src = findSourceFieldByTarget(ctx, field);
                field = src == null ? field : src;
            }
        }
        if (table == null || table.isEmpty() || field == null || field.isEmpty()) return -1L;

        String sql = "SELECT COUNT(*) FROM `" + table + "` WHERE `" + field + "` > ?";
        try (Connection src = JdbcUtils.getConnection(ctx.getSource());
             PreparedStatement ps = src.prepareStatement(sql)) {
            if (byTime) {
                Date lastTime = progress.getLastSyncTime();
                ps.setTimestamp(1, lastTime == null ? new Timestamp(0L) : new Timestamp(lastTime.getTime()));
            } else {
                ps.setLong(1, progress.getLastSyncMaxId() == null ? 0L : progress.getLastSyncMaxId());
            }
            try (ResultSet rs = ps.executeQuery()) {
                long count = rs.next() ? rs.getLong(1) : -1L;
                log.info("[Sync] task[{}] 本次待同步行数估算: {}", task.getTaskName(), count);
                return count;
            }
        } catch (Exception e) {
            log.warn("[Sync] task[{}] 待同步行数估算失败, 大盘将不展示 ETA: {}",
                    task.getTaskName(), e.getMessage());
            return -1L;
        }
    }

    /** 拼接 `f1`,`f2`,... 用于 SELECT 列表 */
    private static String joinBackticked(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('`').append(fields.get(i)).append('`');
        }
        return sb.toString();
    }

    /**
     * 部分字段映射: 断点字段(id/time)即使未映射也追加到 SELECT 列表,
     * 只用于断点推进(读值), 不参与 INSERT 写入, 支持只同步部分字段
     */
    private static List<String> withBreakpointFields(List<String> srcFields, String... extraFields) {
        List<String> select = new ArrayList<>(srcFields);
        for (String f : extraFields) {
            if (f != null && !f.isEmpty() && !select.contains(f)) select.add(f);
        }
        return select;
    }

    /**
     * 解析"当前同步用的源/目标字段集"
     * - 映射模式: 用 ctx.orderedSourceFields (目标自动由映射决定)
     * - 同名模式: 取目标表实际列 (按 ORDINAL_POSITION), 同时 srcFields == tgtFields
     */
    private List<String> ensureFields(SyncContext ctx, String table) throws Exception {
        if (ctx.isMappingEnabled()) {
            // 映射模式: 源字段 = ctx.orderedSourceFields;
            // 自动建表: 用 renameMap 复制源 DDL 到目标 (renameMap 内 src->tgt)
            // 但仅当目标表不存在时才需要建表; 存在则跳过
            SyncDatasource tgt = ctx.getTarget();
            if (!isTableExists(tgt, table)) {
                JdbcUtils.ensureTableExists(ctx.getSource(), tgt, table, ctx.getSrcFieldToTarget());
            }
            return ctx.getOrderedSourceFields();
        } else {
            return ensureTargetTable(ctx, table);
        }
    }

    /** 由 srcFields 决定 tgtFields, 同名模式 = srcFields */
    private List<String> resolveTargetFields(SyncContext ctx, List<String> srcFields) {
        if (ctx.isMappingEnabled()) return ctx.getOrderedTargetFields();
        return srcFields;
    }

    /**
     * 字段映射模式下: 根据目标字段名反查源字段名 (idField/timeField 都是目标列名)
     * 找不到时返回 null (调用方按目标列名原样查源表)
     */
    private String findSourceFieldByTarget(SyncContext ctx, String targetField) {
        if (targetField == null) return null;
        if (ctx.getSrcFieldToTarget() == null) return null;
        for (Map.Entry<String, String> e : ctx.getSrcFieldToTarget().entrySet()) {
            if (targetField.equals(e.getValue())) return e.getKey();
        }
        return null;
    }

    private String buildInsertSql(String table, List<String> tgtCols) {
        StringBuilder sb = new StringBuilder("INSERT INTO `").append(table).append("` (");
        for (int i = 0; i < tgtCols.size(); i++) sb.append("`").append(tgtCols.get(i)).append("`").append(i < tgtCols.size() - 1 ? "," : "");
        sb.append(") VALUES (");
        for (int i = 0; i < tgtCols.size(); i++) sb.append("?").append(i < tgtCols.size() - 1 ? "," : "");
        sb.append(") ON DUPLICATE KEY UPDATE ");
        for (int i = 0; i < tgtCols.size(); i++)
            sb.append("`").append(tgtCols.get(i)).append("`=VALUES(`").append(tgtCols.get(i)).append("`)")
              .append(i < tgtCols.size() - 1 ? "," : "");
        return sb.toString();
    }

    /**
     * 确保目标表存在: 若不存在, 从源表复制 DDL 自动建表
     * 已存在则直接返回字段列表
     */
    private List<String> ensureTargetTable(SyncContext ctx, String tableName) {
        SyncDatasource tgt = ctx.getTarget();
        List<String> cols = fetchTableColumns(tgt, tableName);
        if (!cols.isEmpty()) return cols;

        log.info("[Sync] 目标表 {} 不存在, 尝试从源表自动建表", tableName);
        if (JdbcUtils.ensureTableExists(ctx.getSource(), tgt, tableName)) {
            cols = fetchTableColumns(tgt, tableName);
        }
        return cols;
    }

    private boolean isTableExists(SyncDatasource ds, String tableName) {
        try (Connection c = JdbcUtils.getConnection(ds);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? LIMIT 1")) {
            ps.setString(1, ds.getDbName());
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            log.warn("isTableExists failed: {}", e.getMessage());
            return false;
        }
    }

    private List<String> fetchTableColumns(SyncDatasource ds, String tableName) {
        List<String> cols = new ArrayList<>();
        try (Connection c = JdbcUtils.getConnection(ds);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COLUMN_NAME FROM information_schema.COLUMNS " +
                     "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, ds.getDbName());
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(rs.getString(1));
            }
        } catch (Exception e) {
            log.error("fetchTableColumns error", e);
        }
        return cols;
    }

    private void updatePaused(SyncTask task, SyncTaskProgress progress) {
        task.setStatus(SyncType.STATUS_PAUSE);
        taskMapper.updateById(task);
        progress.setStatus(SyncType.STATUS_PAUSE);
        progress.setUpdateTime(new Date());
        progressMapper.updateById(progress);
        log.info("[Sync] task[{}] paused", task.getTaskName());
    }

    private void updateStopped(SyncTask task, SyncTaskProgress progress) {
        task.setStatus(SyncType.STATUS_STOP);
        taskMapper.updateById(task);
        progress.setStatus(SyncType.STATUS_STOP);
        progress.setEndTime(new Date());
        progress.setUpdateTime(new Date());
        progressMapper.updateById(progress);
        log.info("[Sync] task[{}] stopped", task.getTaskName());
    }

    private void complete(SyncTask task, SyncTaskProgress progress, Long newMaxId) {
        if (newMaxId != null) progress.setLastSyncMaxId(newMaxId);
        progress.setStatus(SyncType.STATUS_COMPLETED);
        progress.setEndTime(new Date());
        progress.setUpdateTime(new Date());
        progressMapper.updateById(progress);
        task.setStatus(SyncType.STATUS_COMPLETED);
        taskMapper.updateById(task);
        log.info("[Sync] task[{}] completed", task.getTaskName());
    }
}