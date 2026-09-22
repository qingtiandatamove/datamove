package com.ruoyi.datamove.task.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.engine.consts.SyncType;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;
import com.ruoyi.datamove.task.domain.SyncTaskRun;
import com.ruoyi.datamove.task.mapper.SyncTaskRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务运行历史服务
 *
 * 生命周期:
 *   begin()     任务启动时写一条 RUNNING 记录, 返回运行ID(挂在 SyncContext.runId 上)
 *   heartbeat() 运行过程中按 (最多 5s 一次) 回填累计行数与批次数, 服务重启也能看到跑到哪
 *   finish()    结束(完成/失败/停止/暂停)时回填状态、结束时间、耗时、平均速率与异常信息
 *
 * 所有方法都做了异常兜底: 运行历史写入失败不能影响同步主流程。
 * finish 只更新仍处于 RUNNING 的记录, 天然幂等(停止与线程内的收口可能各调一次)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRunService {

    private final SyncTaskRunMapper runMapper;

    /** 心跳最小落库间隔(ms): 避免每批次都 UPDATE */
    private static final long HEARTBEAT_MS = 5000L;

    /** 异常信息最大保存长度 */
    private static final int MAX_ERROR_LEN = 2000;

    /** runId -> 上次心跳落库时间 */
    private final Map<Long, Long> lastFlush = new ConcurrentHashMap<>();

    /**
     * 开始一次运行
     *
     * @return 运行ID; 写库失败返回 null (调用方继续同步, 只是没有历史记录)
     */
    public Long begin(SyncTask task, SyncDatasource src, SyncDatasource tgt) {
        try {
            // 上一次运行没正常收口(服务重启/任务被重新启动) → 自动标记为已终止, 避免 RUNNING 记录悬挂
            closeStaleRunning(task.getId(), "运行记录未正常结束(服务重启或任务被重新启动), 已自动标记为终止");

            SyncTaskRun r = new SyncTaskRun();
            r.setTaskId(task.getId());
            r.setTaskName(task.getTaskName());
            r.setTaskType(task.getTaskType());
            r.setSyncMode(task.getSyncMode());
            r.setTableName(task.getTableName());
            r.setSourceName(src == null ? null : src.getDatasourceName());
            r.setTargetName(tgt == null ? null : tgt.getDatasourceName());
            r.setShardCount(task.getShardCount() == null ? 1 : Math.max(1, task.getShardCount()));
            r.setStatus(SyncType.STATUS_RUNNING);
            r.setStartTime(new Date());
            r.setCostSeconds(0L);
            r.setTotalRows(0L);
            r.setSuccessRows(0L);
            r.setFailedRows(0L);
            r.setBatchCount(0);
            r.setAvgRowsPerSec(0.0D);
            r.setCreateTime(new Date());
            r.setUpdateTime(new Date());
            runMapper.insert(r);
            lastFlush.put(r.getId(), System.currentTimeMillis());
            return r.getId();
        } catch (Exception e) {
            log.error("[run] task[{}] 写运行历史失败(忽略)", task == null ? null : task.getTaskName(), e);
            return null;
        }
    }

    /**
     * 进度心跳: 更新本次运行的累计行数与批次数 (最多 5s 落一次库)
     */
    public void heartbeat(Long runId, SyncTaskProgress p, int batchCount) {
        if (runId == null || p == null) return;
        long now = System.currentTimeMillis();
        Long last = lastFlush.get(runId);
        if (last != null && now - last < HEARTBEAT_MS) return;
        lastFlush.put(runId, now);
        try {
            SyncTaskRun u = new SyncTaskRun();
            u.setId(runId);
            u.setSuccessRows(nvl(p.getSuccessRows()));
            u.setFailedRows(nvl(p.getFailedRows()));
            u.setTotalRows(nvl(p.getTotalRows()));
            u.setBatchCount(batchCount);
            u.setUpdateTime(new Date());
            runMapper.updateById(u);
        } catch (Exception e) {
            log.warn("[run] runId={} 心跳更新失败: {}", runId, e.getMessage());
        }
    }

    /** 结束运行: 行数取自断点进度 */
    public void finish(Long runId, String status, SyncTaskProgress p, int batchCount, String errorMsg) {
        finish(runId, status,
                p == null ? 0L : nvl(p.getSuccessRows()),
                p == null ? 0L : nvl(p.getFailedRows()),
                p == null ? 0L : nvl(p.getTotalRows()),
                batchCount, errorMsg);
    }

    /**
     * 结束运行(只更新 RUNNING 状态之外的记录会被跳过, 幂等)
     */
    public void finish(Long runId, String status, long successRows, long failedRows,
                       long totalRows, int batchCount, String errorMsg) {
        if (runId == null) return;
        try {
            SyncTaskRun db = runMapper.selectById(runId);
            if (db == null || !SyncType.STATUS_RUNNING.equals(db.getStatus())) return;

            Date end = new Date();
            long cost = db.getStartTime() == null
                    ? 0L : Math.max(0L, (end.getTime() - db.getStartTime().getTime()) / 1000L);
            long rows = Math.max(successRows, 0L);
            SyncTaskRun u = new SyncTaskRun();
            u.setId(runId);
            u.setStatus(status);
            u.setEndTime(end);
            u.setCostSeconds(cost);
            u.setSuccessRows(rows);
            u.setFailedRows(Math.max(failedRows, 0L));
            u.setTotalRows(Math.max(totalRows, 0L));
            u.setBatchCount(batchCount);
            u.setAvgRowsPerSec(cost > 0 ? Math.round(rows * 10.0D / cost) / 10.0D : 0.0D);
            u.setErrorMsg(truncate(errorMsg));
            u.setUpdateTime(end);
            runMapper.updateById(u);
        } catch (Exception e) {
            log.error("[run] runId={} 结束运行历史失败: {}", runId, e.getMessage());
        } finally {
            lastFlush.remove(runId);
        }
    }

    /**
     * 收口某任务下所有仍为 RUNNING 的运行记录 (拿不到 runId 时用: 服务重启后停止任务等)
     */
    public void finishRunning(Long taskId, String status, String errorMsg) {
        if (taskId == null) return;
        try {
            List<SyncTaskRun> list = runMapper.selectList(new QueryWrapper<SyncTaskRun>()
                    .eq("task_id", taskId)
                    .eq("status", SyncType.STATUS_RUNNING));
            for (SyncTaskRun r : list) {
                finish(r.getId(), status, nvl(r.getSuccessRows()), nvl(r.getFailedRows()),
                        nvl(r.getTotalRows()), r.getBatchCount() == null ? 0 : r.getBatchCount(), errorMsg);
            }
        } catch (Exception e) {
            log.warn("[run] taskId={} 收口运行历史失败: {}", taskId, e.getMessage());
        }
    }

    private void closeStaleRunning(Long taskId, String errorMsg) {
        List<SyncTaskRun> list = runMapper.selectList(new QueryWrapper<SyncTaskRun>()
                .eq("task_id", taskId)
                .eq("status", SyncType.STATUS_RUNNING));
        for (SyncTaskRun r : list) {
            finish(r.getId(), SyncType.STATUS_STOP, nvl(r.getSuccessRows()), nvl(r.getFailedRows()),
                    nvl(r.getTotalRows()), r.getBatchCount() == null ? 0 : r.getBatchCount(), errorMsg);
        }
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN) + "...(已截断)";
    }
}
