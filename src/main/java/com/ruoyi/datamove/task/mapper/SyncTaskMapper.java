package com.ruoyi.datamove.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.datamove.task.domain.SyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SyncTaskMapper extends BaseMapper<SyncTask> {

    /**
     * 检查数据源是否被任务引用
     */
    @Select("SELECT COUNT(1) FROM sync_task WHERE del_flag='0' AND (source_id=#{datasourceId} OR target_id=#{datasourceId})")
    Long existsUsingDatasource(Long datasourceId);

    /**
     * 统计某种状态的任务数(用于 License 并发数量校验)
     */
    @Select("SELECT COUNT(1) FROM sync_task WHERE del_flag='0' AND status='RUNNING'")
    int countRunning();
}
