package com.ruoyi.datamove.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.datamove.task.domain.SyncTaskFieldMapping;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SyncTaskFieldMappingMapper extends BaseMapper<SyncTaskFieldMapping> {

    /** 按 taskId 升序读取全部映射; 不带锁 */
    List<SyncTaskFieldMapping> selectByTaskId(@Param("taskId") Long taskId);

    /** 删除该任务的所有映射 (事务内由 Service 调用) */
    int deleteByTaskId(@Param("taskId") Long taskId);
}