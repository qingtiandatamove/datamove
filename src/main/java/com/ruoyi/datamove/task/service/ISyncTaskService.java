package com.ruoyi.datamove.task.service;

import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskLog;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;
import com.ruoyi.datamove.task.domain.TaskDashboardVO;

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

    /**
     * 任务大盘: 全部任务的进度 + 运行期实时指标(行/秒、ETA、当前批次、瓶颈库)
     * 运行中/暂停的任务排在前面
     */
    List<TaskDashboardVO> dashboard();

    /* 日志 */
    PageResult<SyncTaskLog> logs(Long taskId, String status, int pageNum, int pageSize);

    /**
     * 清理某个任务的同步日志
     *
     * @param taskId     任务 ID
     * @param beforeDays 为空或 <=0 时清理该任务全部日志; 否则只清理 N 天前(更早)的历史日志
     * @return 实际删除条数
     */
    int clearLog(Long taskId, Integer beforeDays);

    /** 清空全部任务的同步日志 (慎用), 返回删除条数 */
    int clearAllLog();
}
