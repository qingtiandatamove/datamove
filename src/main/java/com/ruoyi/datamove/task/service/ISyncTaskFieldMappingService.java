package com.ruoyi.datamove.task.service;

import com.ruoyi.datamove.task.domain.SyncTaskFieldMapping;

import java.util.List;

public interface ISyncTaskFieldMappingService {

    /** 列出该任务全部映射 (按 sort_no 升序, sort_no 相同的按 id 升序) */
    List<SyncTaskFieldMapping> listByTaskId(Long taskId);

    /** 全量替换式保存: 先删后插 (事务) */
    void replace(Long taskId, List<SyncTaskFieldMapping> mappings);

    /** 清空映射 (回到同名兼容模式) */
    void clear(Long taskId);
}