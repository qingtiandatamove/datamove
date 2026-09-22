package com.ruoyi.datamove.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.mapper.SyncDatasourceMapper;
import com.ruoyi.datamove.engine.consts.SyncType;
import com.ruoyi.datamove.engine.full.DdlSyncEngine;
import com.ruoyi.datamove.engine.full.FullSyncEngine;
import com.ruoyi.datamove.engine.incr.CanalSyncEngine;
import com.ruoyi.datamove.engine.metrics.TaskMetrics;
import com.ruoyi.datamove.engine.metrics.TaskMetricsRegistry;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskLog;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;
import com.ruoyi.datamove.task.domain.SyncTaskRun;
import com.ruoyi.datamove.task.domain.TaskDashboardVO;
import com.ruoyi.datamove.task.mapper.SyncTaskLogMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskProgressMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskRunMapper;
import com.ruoyi.datamove.task.service.ISyncTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTaskServiceImpl implements ISyncTaskService {

    private final SyncTaskMapper          taskMapper;
    private final SyncTaskProgressMapper  progressMapper;
    private final SyncTaskLogMapper       logMapper;
    private final SyncTaskRunMapper       runMapper;
    private final SyncDatasourceMapper    datasourceMapper;
    private final FullSyncEngine          fullSyncEngine;
    private final CanalSyncEngine         canalSyncEngine;
    private final DdlSyncEngine           ddlSyncEngine;
    private final TaskMetricsRegistry     metricsRegistry;

    @Override
    public PageResult<SyncTask> page(String keyword, String taskType, String status,
                                    String orderByColumn, String isAsc,
                                    int pageNum, int pageSize) {
        Page<SyncTask> page = new Page<>(pageNum, pageSize);
        QueryWrapper<SyncTask> wrapper = new QueryWrapper<>();
        wrapper.eq("del_flag", "0");
        if (keyword != null && !keyword.isEmpty())
            wrapper.and(w -> w.like("task_name", keyword).or().like("table_name", keyword));
        if (taskType != null && !taskType.isEmpty()) wrapper.eq("task_type", taskType);
        if (status != null && !status.isEmpty()) wrapper.eq("status", status);
        // 排序字段白名单: 仅允许 id / create_time, 防止前端传入任意列名拼到 SQL 里
        String col = (orderByColumn == null || orderByColumn.isEmpty()) ? "id" : orderByColumn;
        if (!"id".equals(col) && !"create_time".equals(col)) col = "id";
        boolean asc = "asc".equalsIgnoreCase(isAsc);
        wrapper.orderBy(true, asc, col);
        Page<SyncTask> result = taskMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal());
    }

    @Override
    public SyncTask detail(Long id) {
        return taskMapper.selectById(id);
    }

    @Override
    @Transactional
    public Long add(SyncTask t) {
        // 任务类型校验
        if (t.getTaskType() == null
                || !(SyncType.TASK_FULL.equalsIgnoreCase(t.getTaskType())
                  || SyncType.TASK_INCR.equalsIgnoreCase(t.getTaskType())
                  || SyncType.TASK_DDL.equalsIgnoreCase(t.getTaskType()))) {
            throw new RuntimeException("不支持的任务类型: " + t.getTaskType());
        }
        // DDL 类型必须填一个 syncMode 占位 (syncMode 字段 NOT NULL)
        if (SyncType.TASK_DDL.equalsIgnoreCase(t.getTaskType())
                && (t.getSyncMode() == null || t.getSyncMode().isEmpty())) {
            t.setSyncMode(SyncType.MODE_DDL);
        }
        // 唯一名校验
        Long exists = taskMapper.selectCount(
                new QueryWrapper<SyncTask>().eq("task_name", t.getTaskName()).eq("del_flag", "0"));
        if (exists > 0) throw new RuntimeException("任务名称已存在: " + t.getTaskName());
        t.setStatus(SyncType.STATUS_STOP);
        taskMapper.insert(t);
        // 初始化进度
        SyncTaskProgress p = new SyncTaskProgress();
        p.setTaskId(t.getId());
        p.setLastSyncMaxId(t.getStartId() == null ? 0L : t.getStartId());
        p.setLastSyncTime(t.getStartTime());
        p.setTotalRows(0L);
        p.setStatus(SyncType.STATUS_STOP);
        p.setCreateTime(new Date());
        p.setUpdateTime(new Date());
        progressMapper.insert(p);
        return t.getId();
    }

    @Override
    @Transactional
    public void update(SyncTask t) {
        SyncTask db = taskMapper.selectById(t.getId());
        if (db == null) throw new RuntimeException("任务不存在");
        if (SyncType.STATUS_RUNNING.equals(db.getStatus())) {
            throw new RuntimeException("运行中的任务不允许编辑,请先停止任务");
        }
        db.setTaskName(t.getTaskName());
        db.setTaskType(t.getTaskType());
        db.setSyncMode(t.getSyncMode());
        db.setSourceId(t.getSourceId());
        db.setTargetId(t.getTargetId());
        db.setTableName(t.getTableName());
        db.setIdField(t.getIdField());
        db.setTimeField(t.getTimeField());
        db.setStartId(t.getStartId());
        db.setStartTime(t.getStartTime());
        db.setBatchSize(t.getBatchSize());
        db.setShardCount(t.getShardCount() == null ? 1 : Math.max(1, t.getShardCount()));
        db.setOverwriteFlag(t.getOverwriteFlag() == null ? 0 : t.getOverwriteFlag());
        // 数据校验忽略字段: 这里是逐字段白名单赋值, 漏一行配置就永远存不进去
        db.setIgnoreFields(t.getIgnoreFields());
        db.setDingtalkWebhook(t.getDingtalkWebhook());
        db.setAlertEmail(t.getAlertEmail());
        db.setCanalHost(t.getCanalHost());
        db.setCanalPort(t.getCanalPort());
        db.setCanalDestination(t.getCanalDestination());
        db.setRemark(t.getRemark());
        taskMapper.updateById(db);
    }

    @Override
    @Transactional
    public void remove(Long id) {
        SyncTask db = taskMapper.selectById(id);
        if (db == null) throw new RuntimeException("任务不存在");
        if (SyncType.STATUS_RUNNING.equals(db.getStatus())) {
            throw new RuntimeException("运行中的任务不允许删除,请先停止任务");
        }
        db.setDelFlag("1");
        taskMapper.updateById(db);
        // 同步删除日志、断点进度与运行历史
        logMapper.delete(new QueryWrapper<SyncTaskLog>().eq("task_id", id));
        runMapper.delete(new QueryWrapper<SyncTaskRun>().eq("task_id", id));
        progressMapper.delete(new QueryWrapper<SyncTaskProgress>().eq("task_id", id));
        // 清理运行期实时指标
        metricsRegistry.remove(id);
    }

    @Override
    public void start(Long id) {
        SyncTask db = taskMapper.selectById(id);
        if (db == null) throw new RuntimeException("任务不存在");
        if (SyncType.TASK_FULL.equalsIgnoreCase(db.getTaskType())) {
            fullSyncEngine.start(id);
        } else if (SyncType.TASK_INCR.equalsIgnoreCase(db.getTaskType())) {
            canalSyncEngine.start(id);
        } else if (SyncType.TASK_DDL.equalsIgnoreCase(db.getTaskType())) {
            ddlSyncEngine.start(id);
        } else {
            throw new RuntimeException("不支持的任务类型: " + db.getTaskType());
        }
    }

    @Override
    public void pause(Long id) {
        SyncTask db = taskMapper.selectById(id);
        if (db == null) throw new RuntimeException("任务不存在");
        if (!SyncType.TASK_FULL.equalsIgnoreCase(db.getTaskType())) {
            throw new RuntimeException("仅全量任务支持暂停");
        }
        fullSyncEngine.pause(id);
    }

    @Override
    public void resume(Long id) {
        SyncTask db = taskMapper.selectById(id);
        if (db == null) throw new RuntimeException("任务不存在");
        if (!SyncType.TASK_FULL.equalsIgnoreCase(db.getTaskType())) {
            throw new RuntimeException("仅全量任务支持继续");
        }
        fullSyncEngine.resume(id);
    }

    @Override
    public void stop(Long id) {
        SyncTask db = taskMapper.selectById(id);
        if (db == null) throw new RuntimeException("任务不存在");
        if (SyncType.TASK_FULL.equalsIgnoreCase(db.getTaskType())) {
            fullSyncEngine.stop(id);
        } else if (SyncType.TASK_INCR.equalsIgnoreCase(db.getTaskType())) {
            canalSyncEngine.stop(id);
        }
        // DDL 同步是单次秒级操作, 不需要 stop
    }

    @Override
    public void reset(Long id) {
        SyncTask db = taskMapper.selectById(id);
        if (db == null) throw new RuntimeException("任务不存在");
        if (!SyncType.TASK_FULL.equalsIgnoreCase(db.getTaskType())) {
            throw new RuntimeException("仅全量任务支持重置");
        }
        if (SyncType.STATUS_RUNNING.equalsIgnoreCase(db.getStatus())) {
            throw new RuntimeException("运行中的任务不可重置, 请先停止");
        }
        // 1) 清空断点进度 (下次 start 会自动重建空记录, 从头全量)
        progressMapper.delete(new QueryWrapper<SyncTaskProgress>().eq("task_id", id));
        // 2) 任务状态回到 STOP
        db.setStatus(SyncType.STATUS_STOP);
        taskMapper.updateById(db);
        log.info("[reset] taskId={} 已重置同步进度", id);
    }

    @Override
    public SyncTaskProgress progress(Long id) {
        return fullSyncEngine.progress(id);
    }

    @Override
    public PageResult<SyncTaskLog> logs(Long taskId, String status, int pageNum, int pageSize) {
        Page<SyncTaskLog> page = new Page<>(pageNum, pageSize);
        QueryWrapper<SyncTaskLog> wrapper = new QueryWrapper<>();
        wrapper.eq("task_id", taskId);
        if (status != null && !status.isEmpty()) wrapper.eq("status", status);
        wrapper.orderByDesc("id");
        Page<SyncTaskLog> result = logMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal());
    }

    @Override
    public int clearLog(Long taskId, Integer beforeDays) {
        if (taskId == null) throw new RuntimeException("任务 ID 不能为空");
        QueryWrapper<SyncTaskLog> wrapper = new QueryWrapper<>();
        wrapper.eq("task_id", taskId);
        if (beforeDays != null && beforeDays > 0) {
            // 保留最近 N 天: 删除 (N-1) 天前零点之前的日志, 即今天与最近 N-1 天保留
            wrapper.lt("create_time", LocalDate.now().minusDays(beforeDays - 1).atStartOfDay());
        }
        int deleted = logMapper.delete(wrapper);
        log.info("[clearLog] taskId={} beforeDays={} deleted={}", taskId, beforeDays, deleted);
        return deleted;
    }

    @Override
    public int clearAllLog() {
        int deleted = logMapper.delete(new QueryWrapper<>());
        log.warn("[clearLog] 已清空全部任务日志, deleted={}", deleted);
        return deleted;
    }

    /* ============ 任务大盘 ============ */

    @Override
    public List<TaskDashboardVO> dashboard() {
        List<SyncTask> tasks = taskMapper.selectList(
                new QueryWrapper<SyncTask>().eq("del_flag", "0").orderByDesc("id"));
        if (tasks.isEmpty()) return new ArrayList<>();

        Map<Long, SyncTaskProgress> progressMap = new HashMap<>();
        for (SyncTaskProgress p : progressMapper.selectList(null)) {
            if (p.getTaskId() != null) progressMap.put(p.getTaskId(), p);
        }
        Map<Long, SyncDatasource> dsMap = new HashMap<>();
        for (SyncDatasource ds : datasourceMapper.selectList(null)) {
            dsMap.put(ds.getId(), ds);
        }

        long now = System.currentTimeMillis();

        // 历史运行: 每个任务最近一次运行记录 + 累计运行次数 (sync_task_run)
        Map<Long, SyncTaskRun> lastRunMap = new HashMap<>();
        for (SyncTaskRun r : runMapper.selectLatestPerTask()) {
            if (r.getTaskId() != null) lastRunMap.put(r.getTaskId(), r);
        }
        Map<Long, Long> runCountMap = new HashMap<>();
        for (Map<String, Object> row : runMapper.countGroupByTask()) {
            Object tid = row.get("taskId") != null ? row.get("taskId") : row.get("task_id");
            Object cnt = row.get("cnt") != null ? row.get("cnt") : row.get("CNT");
            if (tid instanceof Number) {
                runCountMap.put(((Number) tid).longValue(), cnt instanceof Number ? ((Number) cnt).longValue() : 0L);
            }
        }

        List<TaskDashboardVO> list = new ArrayList<>(tasks.size());
        for (SyncTask task : tasks) {
            TaskDashboardVO vo = toDashboardVO(task, progressMap.get(task.getId()), dsMap, now);
            fillRunHistory(vo, lastRunMap.get(task.getId()), runCountMap.get(task.getId()));
            list.add(vo);
        }
        // 运行中 / 暂停 的排前面, 其余按最近更新倒序
        list.sort((a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return ra - rb;
            if (a.getUpdateTime() == null || b.getUpdateTime() == null) return 0;
            return b.getUpdateTime().compareTo(a.getUpdateTime());
        });
        return list;
    }

    /** 组装单条大盘数据: 任务 + 断点进度 + 运行期实时指标 */
    private TaskDashboardVO toDashboardVO(SyncTask task, SyncTaskProgress p,
                                          Map<Long, SyncDatasource> dsMap, long now) {
        TaskDashboardVO vo = new TaskDashboardVO();
        vo.setTaskId(task.getId());
        vo.setTaskName(task.getTaskName());
        vo.setTaskType(task.getTaskType());
        vo.setSyncMode(task.getSyncMode());
        vo.setTableName(task.getTableName());
        vo.setStatus(task.getStatus());
        vo.setBatchSize(task.getBatchSize());
        vo.setShardCount(task.getShardCount() == null ? 1 : task.getShardCount());

        SyncDatasource src = dsMap.get(task.getSourceId());
        SyncDatasource tgt = dsMap.get(task.getTargetId());
        vo.setSourceName(src == null ? null : src.getDatasourceName());
        vo.setTargetName(tgt == null ? null : tgt.getDatasourceName());

        // 缺省值: 无法估算时不展示进度条与 ETA
        vo.setTotalEstimate(-1L);
        vo.setEtaSeconds(-1L);
        vo.setProgress(-1);

        boolean running = SyncType.STATUS_RUNNING.equals(task.getStatus());
        vo.setRunning(running);

        long successRows = p == null || p.getSuccessRows() == null ? 0L : p.getSuccessRows();
        vo.setTotalRows(p == null || p.getTotalRows() == null ? 0L : p.getTotalRows());
        vo.setFailedRows(p == null || p.getFailedRows() == null ? 0L : p.getFailedRows());
        if (p != null) {
            vo.setStartTime(p.getStartTime());
            vo.setUpdateTime(p.getUpdateTime());
        }

        // 本次运行已同步 = 累计行数 - 启动时的历史累计(续传场景)
        TaskMetrics m = metricsRegistry.get(task.getId());
        long baseline = m == null ? 0L : m.getBaselineRows();
        long syncRows = Math.max(0L, successRows - baseline);
        vo.setSyncRows(syncRows);

        // 运行时长与平均速率
        if (p != null && p.getStartTime() != null) {
            long end = p.getEndTime() == null ? now : p.getEndTime().getTime();
            long costSeconds = Math.max(0L, (end - p.getStartTime().getTime()) / 1000L);
            vo.setCostSeconds(costSeconds);
            if (costSeconds > 0) vo.setAvgRowsPerSec(round1(syncRows * 1.0D / costSeconds));
        }

        if (m == null) {
            // 进程重启或本次未启动过: 无实时指标, 已完成的任务直接按 100% 展示
            if (SyncType.STATUS_COMPLETED.equals(task.getStatus())) vo.setProgress(100);
            return vo;
        }

        long estimate = m.getTotalEstimate();
        vo.setTotalEstimate(estimate);
        if (estimate > 0) {
            vo.setRemainRows(Math.max(0L, estimate - syncRows));
            vo.setProgress((int) Math.min(100L, syncRows * 100L / estimate));
        } else if (SyncType.STATUS_COMPLETED.equals(task.getStatus())) {
            vo.setProgress(100);
        }

        double rate = m.liveRowsPerSec();
        vo.setRowsPerSec(round1(rate));
        vo.setCurrentBatch(m.getCurrentBatch());
        vo.setCurrentBatchRows(m.getCurrentBatchRows());
        vo.setLastBatchCostMs(m.getLastBatchCostMs());
        vo.setReadMs(round1(m.getReadMs()));
        vo.setWriteMs(round1(m.getWriteMs()));
        vo.setBottleneck(m.getBottleneck());
        vo.setBottleneckText(bottleneckText(m.getBottleneck(), task.getTaskType()));

        // ETA: 只在运行中、有实时速率、且已知总量时给出
        if (running && rate > 0 && estimate > 0) {
            long eta = (long) Math.ceil(Math.max(0L, estimate - syncRows) / rate);
            vo.setEtaSeconds(eta);
            vo.setEtaText(formatEta(eta));
        }

        // 分片实时监控: 仅分片任务(本次运行注册过 shard)时输出
        if (m.hasShards()) {
            Collection<TaskMetrics.ShardState> snapshot = m.shardSnapshot();
            List<TaskDashboardVO.ShardVO> shardVOs = new ArrayList<>(snapshot.size());
            for (TaskMetrics.ShardState s : snapshot) {
                TaskDashboardVO.ShardVO sv = new TaskDashboardVO.ShardVO();
                sv.setShardNo(s.getShardNo());
                sv.setRangeLo(s.getRangeLo());
                sv.setRangeHi(s.getRangeHi());
                sv.setRows(s.getRows());
                sv.setCurrentId(s.getCurrentId());
                sv.setRowsPerSec(round1(s.getRowsPerSec()));
                sv.setBatches(s.getBatches());
                sv.setState(s.getState());
                sv.setError(s.getError());
                shardVOs.add(sv);
            }
            vo.setShards(shardVOs);
        }
        return vo;
    }

    /**
     * 大盘补充历史运行信息: 累计运行次数 + 最近一次运行结果 (sync_task_run)
     */
    private void fillRunHistory(TaskDashboardVO vo, SyncTaskRun last, Long runCount) {
        vo.setRunCount(runCount == null ? 0 : runCount.intValue());
        if (last == null) return;
        vo.setLastRunStatus(last.getStatus());
        vo.setLastRunTime(last.getStartTime());
        vo.setLastRunEndTime(last.getEndTime());
        vo.setLastRunCostSeconds(last.getCostSeconds());
        vo.setLastRunRows(last.getSuccessRows());
        vo.setLastRunFailedRows(last.getFailedRows());
    }

    /** 大盘排序权重: 运行中 > 暂停 > 其他 */
    private static int rank(TaskDashboardVO vo) {
        if (SyncType.STATUS_RUNNING.equals(vo.getStatus())) return 0;
        if (SyncType.STATUS_PAUSE.equals(vo.getStatus())) return 1;
        return 2;
    }

    private static Double round1(double v) {
        return Math.round(v * 10D) / 10D;
    }

    /** 瓶颈可读文本, 无法判定时返回 null */
    private static String bottleneckText(String bottleneck, String taskType) {
        if (TaskMetrics.SOURCE.equals(bottleneck)) return "源库";
        if (TaskMetrics.TARGET.equals(bottleneck)) return "目标库";
        if (TaskMetrics.BALANCED.equals(bottleneck)) {
            // 增量任务: 大部分时间在等 binlog 事件, 写入耗时没超过等待时间即无瓶颈
            return SyncType.TASK_INCR.equalsIgnoreCase(taskType) ? "无瓶颈" : "读写均衡";
        }
        return null;
    }

    /** ETA 可读文本: 1h2m3s */
    private static String formatEta(long seconds) {
        if (seconds < 0) return null;
        if (seconds > 99L * 3600L) return ">99h";
        long h = seconds / 3600L;
        long mi = seconds % 3600L / 60L;
        long s = seconds % 60L;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h");
        if (h > 0 || mi > 0) sb.append(mi).append("m");
        return sb.append(s).append("s").toString();
    }
}
