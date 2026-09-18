package com.ruoyi.datamove.task.service;

import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskLog;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;

import java.util.List;

/**
 * 同步任务服务
 */
public interface ISyncTaskService {

    PageResult<SyncTask> page(String keyword, String taskType, String status,
                              String orderByColumn, String isAsc,
                              int pageNum, int pageSize);

    SyncTask detail(Long id);

    Long add(SyncTask t);

    void update(SyncTask t);

    void remove(Long id);

    /* 同步生命周期 */
    void start(Long id);
    void pause(Long id);
    void resume(Long id);
    void stop(Long id);

    /**
     * 重置任务同步进度 (清断点): 仅 FULL 任务, 且非 RUNNING 状态可重置
     * 重置后状态回到 STOP, 下次启动会从头全量同步; 历史日志保留
     */
    void reset(Long id);

    /* 进度 */
    SyncTaskProgress progress(Long id);

    /* 日志 */
    PageResult<SyncTaskLog> logs(Long taskId, String status, int pageNum, int pageSize);
    void clearLog(Long taskId);
    void clearAllLog();
}
