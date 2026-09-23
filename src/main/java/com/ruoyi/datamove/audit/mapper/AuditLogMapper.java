package com.ruoyi.datamove.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.datamove.audit.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}