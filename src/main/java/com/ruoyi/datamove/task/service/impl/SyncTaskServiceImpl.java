package com.ruoyi.datamove.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.datamove.engine.consts.SyncType;
import com.ruoyi.datamove.engine.full.DdlSyncEngine;
import com.ruoyi.datamove.engine.full.FullSyncEngine;
import com.ruoyi.datamove.engine.incr.CanalSyncEngine;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskLog;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;
import com.ruoyi.datamove.task.mapper.SyncTaskLogMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskProgressMapper;
import com.ruoyi.datamove.task.service.ISyncTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTaskServiceImpl implements ISyncTaskService {

    private final SyncTaskMapper          taskMapper;
    private final SyncTaskProgressMapper  progressMapper;
    private final SyncTaskLogMapper       logMapper;
    private final FullSyncEngine          fullSyncEngine;
    private final CanalSyncEngine         canalSyncEngine;
    private final DdlSyncEngine           ddlSyncEngine;

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
        db.setOverwriteFlag(t.getOverwriteFlag() == null ? 0 : t.getOverwriteFlag());
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
        // 同步删除日志
        logMapper.delete(new QueryWrapper<SyncTaskLog>().eq("task_id", id));
        progressMapper.delete(new QueryWrapper<SyncTaskProgress>().eq("task_id", id));
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
    public void clearLog(Long taskId) {
        logMapper.delete(new QueryWrapper<SyncTaskLog>().eq("task_id", taskId));
    }

    @Override
    public void clearAllLog() {
        logMapper.delete(new QueryWrapper<>());
    }
}
