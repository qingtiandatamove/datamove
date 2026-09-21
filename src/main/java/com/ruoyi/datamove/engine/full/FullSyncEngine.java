package com.ruoyi.datamove.engine.full;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ruoyi.datamove.util.AlertUtils;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.mapper.SyncDatasourceMapper;
import com.ruoyi.datamove.engine.SyncContext;
import com.ruoyi.datamove.engine.consts.SyncType;
import com.ruoyi.datamove.engine.log.SyncLogService;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;
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
    private final SyncLogService logService;

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

        // 连通性测试 - 触发告警(钉钉+邮件)
        if (!JdbcUtils.testConnection(src)) {
            AlertUtils.alert(task, "启动失败:源库连接失败",
                    "【DataMove告警】任务[" + task.getTaskName() + "]启动失败:源库连接失败");
            throw new RuntimeException("无法连接源数据库");
        }
        if (!JdbcUtils.testConnection(tgt)) {
            AlertUtils.alert(task, "启动失败:目标库连接失败",
                    "【DataMove告警】任务[" + task.getTaskName() + "]启动失败:目标库连接失败");
            throw new RuntimeException("无法连接目标数据库");
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

        SyncContext ctx = SyncContext.builder()
                .task(task)
                .source(src)
                .target(tgt)
                .batchNo(new AtomicLong(0))
                .pauseFlag(new AtomicBoolean(false))
                .stopFlag(new AtomicBoolean(false))
                .build();
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

        log.info("[Sync] task[{}] start, mode={}, table={}",
                task.getTaskName(), task.getSyncMode(), task.getTableName());

        final SyncContext fCtx = ctx;
        final Long fTaskId = taskId;
        final SyncTaskProgress fProgress = progress;
        Thread t = new Thread(() -> doMainLoop(fCtx, fProgress), "datamove-task-" + fTaskId);
        t.setDaemon(true);
        t.start();
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
                doSyncById(ctx, progress);
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
            RUNNING_TASK.remove(task.getId());
            RUNNING.remove(task.getId());
        }
    }

    /* ============ 按主键ID同步 ============ */

    private void doSyncById(SyncContext ctx, SyncTaskProgress progress) throws Exception {
        SyncTask task = ctx.getTask();
        String table = task.getTableName();
        String idField = task.getIdField() == null ? "id" : task.getIdField();
        int batchSize = task.getBatchSize() == null || task.getBatchSize() <= 0 ? 1000 : task.getBatchSize();

        List<String> cols = ensureTargetTable(ctx, table);
        if (cols.isEmpty()) throw new RuntimeException("目标表 " + table + " 无字段,请先建表");

        String insertSql = buildInsertSql(table, cols);
        String selectSql = "SELECT * FROM `" + table + "` WHERE `" + idField + "` > ? ORDER BY `" + idField + "` ASC LIMIT " + batchSize;

        long lastId = progress.getLastSyncMaxId() == null ? 0L : progress.getLastSyncMaxId();
        long totalRows = progress.getTotalRows() == null ? 0L : progress.getTotalRows();

        try (Connection src = JdbcUtils.getConnection(ctx.getSource());
             Connection tgt = JdbcUtils.getConnection(ctx.getTarget());
             PreparedStatement psSrc = src.prepareStatement(selectSql);
             PreparedStatement psTgt = tgt.prepareStatement(insertSql)) {

            tgt.setAutoCommit(false);
            psSrc.setFetchSize(batchSize);

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

                try (ResultSet rs = psSrc.executeQuery()) {
                    while (rs.next()) {
                        Object[] values = new Object[cols.size()];
                        for (int i = 0; i < cols.size(); i++) values[i] = rs.getObject(cols.get(i));
                        batchValues.add(values);
                        Object idObj = rs.getObject(idField);
                        if (idObj instanceof Number) {
                            long id = ((Number) idObj).longValue();
                            if (id > newMaxId) newMaxId = id;
                        }
                        batchRows++;
                    }
                }

                if (batchRows == 0) {
                    tgt.commit();
                    complete(task, progress, newMaxId);
                    logService.writeLog(ctx, batchNo, String.valueOf(lastId), String.valueOf(lastId),
                            0, totalRows, System.currentTimeMillis() - startMs, SyncType.LOG_SUCCESS, null);
                    return;
                }

                try {
                    for (Object[] v : batchValues) {
                        for (int i = 0; i < v.length; i++) psTgt.setObject(i + 1, v[i]);
                        psTgt.addBatch();
                    }
                    psTgt.executeBatch();
                    tgt.commit();

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

        List<String> cols = ensureTargetTable(ctx, table);
        if (cols.isEmpty()) throw new RuntimeException("目标表 " + table + " 无字段,请先建表");

        String insertSql = buildInsertSql(table, cols);

        // key 排序: 时间 ASC, ID ASC, 用于同秒拆分
        String selectSql = "SELECT * FROM `" + table + "` WHERE `" + timeField + "` > ? " +
                "OR (`" + timeField + "` = ? AND `" + idField + "` > ?) " +
                "ORDER BY `" + timeField + "` ASC, `" + idField + "` ASC LIMIT " + batchSize;

        Date lastTime = progress.getLastSyncTime();
        Long lastIdInBatch = progress.getLastSyncMaxId();
        long totalRows = progress.getTotalRows() == null ? 0L : progress.getTotalRows();

        try (Connection src = JdbcUtils.getConnection(ctx.getSource());
             Connection tgt = JdbcUtils.getConnection(ctx.getTarget());
             PreparedStatement psSrc = src.prepareStatement(selectSql);
             PreparedStatement psTgt = tgt.prepareStatement(insertSql)) {

            tgt.setAutoCommit(false);

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

                try (ResultSet rs = psSrc.executeQuery()) {
                    while (rs.next()) {
                        Object[] values = new Object[cols.size()];
                        for (int i = 0; i < cols.size(); i++) values[i] = rs.getObject(cols.get(i));
                        batchValues.add(values);

                        Object tObj = rs.getObject(timeField);
                        Object iObj = rs.getObject(idField);
                        Date rowTime = null;
                        if (tObj instanceof Timestamp) rowTime = new Date(((Timestamp) tObj).getTime());
                        else if (tObj instanceof Date) rowTime = (Date) tObj;
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
                    }
                }

                if (batchRows == 0) {
                    tgt.commit();
                    complete(task, progress, null);
                    logService.writeLog(ctx, batchNo,
                            lastTime == null ? "" : new SimpleDateFormat(DATE_FMT).format(lastTime),
                            lastTime == null ? "" : new SimpleDateFormat(DATE_FMT).format(lastTime),
                            0, totalRows, System.currentTimeMillis() - startMs, SyncType.LOG_SUCCESS, null);
                    return;
                }

                try {
                    for (Object[] v : batchValues) {
                        for (int i = 0; i < v.length; i++) psTgt.setObject(i + 1, v[i]);
                        psTgt.addBatch();
                    }
                    psTgt.executeBatch();
                    tgt.commit();

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

    private String buildInsertSql(String table, List<String> cols) {
        StringBuilder sb = new StringBuilder("INSERT INTO `").append(table).append("` (");
        for (int i = 0; i < cols.size(); i++) sb.append("`").append(cols.get(i)).append("`").append(i < cols.size() - 1 ? "," : "");
        sb.append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) sb.append("?").append(i < cols.size() - 1 ? "," : "");
        sb.append(") ON DUPLICATE KEY UPDATE ");
        for (int i = 0; i < cols.size(); i++)
            sb.append("`").append(cols.get(i)).append("`=VALUES(`").append(cols.get(i)).append("`)")
              .append(i < cols.size() - 1 ? "," : "");
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
