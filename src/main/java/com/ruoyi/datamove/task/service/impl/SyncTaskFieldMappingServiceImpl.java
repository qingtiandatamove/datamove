package com.ruoyi.datamove.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ruoyi.datamove.task.domain.SyncTaskFieldMapping;
import com.ruoyi.datamove.task.mapper.SyncTaskFieldMappingMapper;
import com.ruoyi.datamove.task.service.ISyncTaskFieldMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTaskFieldMappingServiceImpl implements ISyncTaskFieldMappingService {

    private final SyncTaskFieldMappingMapper mapper;

    @Override
    public List<SyncTaskFieldMapping> listByTaskId(Long taskId) {
        return mapper.selectList(new QueryWrapper<SyncTaskFieldMapping>()
                .eq("task_id", taskId)
                .orderByAsc("sort_no", "id"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replace(Long taskId, List<SyncTaskFieldMapping> mappings) {
        mapper.delete(new QueryWrapper<SyncTaskFieldMapping>().eq("task_id", taskId));
        if (mappings == null || mappings.isEmpty()) {
            log.info("[fieldMapping] task[{}] 清空映射", taskId);
            return;
        }
        Date now = new Date();
        int idx = 0;
        for (SyncTaskFieldMapping m : mappings) {
            if (m == null) continue;
            if (m.getSourceField() == null || m.getSourceField().isEmpty()
                    || m.getTargetField() == null || m.getTargetField().isEmpty()) {
                throw new IllegalArgumentException("映射存在空字段: src=" + m.getSourceField() + ", tgt=" + m.getTargetField());
            }
            m.setId(null);                       // 强制新增, 避免前端误传主键
            m.setTaskId(taskId);
            m.setSortNo(m.getSortNo() == null ? idx : m.getSortNo());
            m.setCreateTime(now);
            m.setUpdateTime(now);
            mapper.insert(m);
            idx++;
        }
        log.info("[fieldMapping] task[{}] 替换映射完成, 共 {} 条", taskId, mappings.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clear(Long taskId) {
        mapper.delete(new QueryWrapper<SyncTaskFieldMapping>().eq("task_id", taskId));
    }
}