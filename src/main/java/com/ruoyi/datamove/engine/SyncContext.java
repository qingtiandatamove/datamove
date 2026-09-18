package com.ruoyi.datamove.engine;

import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.task.domain.SyncTask;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个任务的执行上下文,封装运行期共享数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncContext {

    /** 任务定义 */
    private SyncTask task;

    /** 解析后的源数据库 */
    private SyncDatasource source;

    /** 目标数据库 */
    private SyncDatasource target;

    /** 批次计数,从0开始 */
    private AtomicLong batchNo;

    /** 暂停标记 - 线程池外层检查 */
    private AtomicBoolean pauseFlag;

    /** 终止标记 */
    private AtomicBoolean stopFlag;

    /** 总条数估算缓存 */
    private long totalEstimate;

    /** 临时缓存: 用户运行时记录字段值等 */
    private Map<String, Object> attr = new HashMap<>();

    public boolean isPaused() { return pauseFlag.get(); }

    public boolean isStopped() { return stopFlag.get(); }

    public void requestPause() { pauseFlag.set(true); }

    public void requestStop() { stopFlag.set(true); }

    public void resume() {
        pauseFlag.set(false);
    }
}
