package com.ruoyi.datamove.engine;

import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskFieldMapping;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /** 本次运行历史ID (sync_task_run.id), 用于回填运行结果; null = 未记录历史 */
    private Long runId;

    /** 暂停标记 - 线程池外层检查 */
    private AtomicBoolean pauseFlag;

    /** 终止标记 */
    private AtomicBoolean stopFlag;

    /** 总条数估算缓存 */
    private long totalEstimate;

    /** 临时缓存: 用户运行时记录字段值等 */
    private Map<String, Object> attr = new HashMap<>();

    /* ============ 字段映射 (可选, 启动时一次性加载) ============ */

    /** 任务配置的字段映射列表 (空 = 同名同步, 向后兼容老任务) */
    @Builder.Default
    private List<SyncTaskFieldMapping> fieldMappings = new ArrayList<>();

    /** 源字段名 -> 目标字段名 (按 sort_no 升序) */
    private Map<String, String> srcFieldToTarget;

    /** 目标字段名 (按 sort_no 升序) - 用于 INSERT 列顺序 */
    private List<String> orderedTargetFields;

    /** 源字段名 (按 sort_no 升序) - 用于 SELECT 列顺序 */
    private List<String> orderedSourceFields;

    /** 是否启用字段映射 (有映射配置时为 true) */
    public boolean isMappingEnabled() {
        return srcFieldToTarget != null && !srcFieldToTarget.isEmpty();
    }

    public boolean isPaused() { return pauseFlag.get(); }

    public boolean isStopped() { return stopFlag.get(); }

    public void requestPause() { pauseFlag.set(true); }

    public void requestStop() { stopFlag.set(true); }

    public void resume() {
        pauseFlag.set(false);
    }
}
