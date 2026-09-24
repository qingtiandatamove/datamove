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

    /**
     * 克隆一个任务: 复制源任务的全部业务配置(数据源/同步模式/批次/分片/过滤/告警/Canal 配置/字段映射等),
     * 重置运行态字段(状态→STOP、清空起始位点), 同步表名沿用源任务, 任务名自动加 .copy 后缀去重, 备注开头追加「克隆自任务#X」。
     * 返回新任务 ID。前端克隆后会自动打开编辑弹窗, 引导用户修改表名后再保存。
     * 仅允许克隆 STOP / PAUSE / COMPLETED / FAILED 状态的任务, RUNNING 不允许克隆(避免状态错乱)。
     */
    Long clone(Long sourceId);

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
