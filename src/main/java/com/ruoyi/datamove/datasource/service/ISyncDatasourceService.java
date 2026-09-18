package com.ruoyi.datamove.datasource.service;

import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;

import java.util.List;

/**
 * 数据源服务接口
 */
public interface ISyncDatasourceService {

    PageResult<SyncDatasource> page(String keyword, int pageNum, int pageSize);

    List<SyncDatasource> listAll();

    SyncDatasource getById(Long id);

    Long add(SyncDatasource ds);

    void update(SyncDatasource ds);

    void remove(Long id);

    /**
     * 测试数据库连通性
     */
    boolean testConnection(SyncDatasource ds);

    boolean testConnection(Long id);

    /** 测试连接(直接传明文密码,不走 DTO 加密) */
    boolean testConnectionRaw(String host, Integer port, String dbName, String username, String plainPassword);
}
