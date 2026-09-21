package com.ruoyi.datamove.engine.metrics;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务实时指标注册表 (进程内)
 *
 * 引擎启动任务时注册, 每批更新; 任务结束只置为 inactive(保留总数估算, 便于展示进度),
 * 任务被删除或应用重启后自然消失。
 */
@Component
public class TaskMetricsRegistry {

    private final Map<Long, TaskMetrics> METRICS = new ConcurrentHashMap<>();

    /** 取(没有则创建)指定任务的指标对象 */
    public TaskMetrics ensure(Long taskId, int batchSize) {
        return METRICS.computeIfAbsent(taskId, id -> new TaskMetrics(id, batchSize));
    }

    /** 取指定任务指标, 不存在返回 null */
    public TaskMetrics get(Long taskId) {
        return taskId == null ? null : METRICS.get(taskId);
    }

    /** 任务删除时清理 */
    public void remove(Long taskId) {
        if (taskId != null) METRICS.remove(taskId);
    }

    public Collection<TaskMetrics> values() {
        return METRICS.values();
    }
}
