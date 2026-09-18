package com.ruoyi.datamove.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.mapper.SyncDatasourceMapper;
import com.ruoyi.datamove.datasource.service.ISyncDatasourceService;
import com.ruoyi.datamove.task.mapper.SyncTaskMapper;
import com.ruoyi.datamove.util.JdbcUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncDatasourceServiceImpl implements ISyncDatasourceService {

    @Autowired
    private SyncDatasourceMapper datasourceMapper;

    @Autowired
    private SyncTaskMapper taskMapper;

    @Override
    public PageResult<SyncDatasource> page(String keyword, int pageNum, int pageSize) {
        Page<SyncDatasource> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SyncDatasource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SyncDatasource::getDelFlag, "0");
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(SyncDatasource::getDatasourceName, keyword)
                    .or().like(SyncDatasource::getHost, keyword)
                    .or().like(SyncDatasource::getDbName, keyword));
        }
        wrapper.orderByDesc(SyncDatasource::getId);
        Page<SyncDatasource> result = datasourceMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal());
    }

    @Override
    public List<SyncDatasource> listAll() {
        LambdaQueryWrapper<SyncDatasource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SyncDatasource::getDelFlag, "0");
        return datasourceMapper.selectList(wrapper);
    }

    @Override
    public SyncDatasource getById(Long id) {
        return datasourceMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(SyncDatasource ds) {
        // 唯一名校验
        Long exists = datasourceMapper.selectCount(new LambdaQueryWrapper<SyncDatasource>()
                .eq(SyncDatasource::getDatasourceName, ds.getDatasourceName())
                .eq(SyncDatasource::getDelFlag, "0"));
        if (exists > 0) {
            throw new RuntimeException("数据源名称已存在: " + ds.getDatasourceName());
        }
        // 同时测试一次连通性,避免添加无效
        if (!testConnection(ds)) {
            throw new RuntimeException("新增失败,无法连接到目标数据库,请检查IP/端口/账号密码");
        }
        datasourceMapper.insert(ds);
        return ds.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SyncDatasource ds) {
        SyncDatasource db = datasourceMapper.selectById(ds.getId());
        if (db == null) {
            throw new RuntimeException("数据源不存在");
        }
        db.setDatasourceName(ds.getDatasourceName());
        db.setHost(ds.getHost());
        db.setPort(ds.getPort());
        db.setDbName(ds.getDbName());
        db.setUsername(ds.getUsername());
        db.setRemark(ds.getRemark());
        // 密码非空才更新(setPassword 已做加密)
        if (ds.getPassword() != null && !ds.getPassword().isEmpty()) {
            db.setPassword(ds.getPassword());
        }
        datasourceMapper.updateById(db);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        // 文档: 无任务关联才可删除
        if (taskMapper.existsUsingDatasource(id) != null && taskMapper.existsUsingDatasource(id) > 0) {
            throw new RuntimeException("该数据源被任务引用,无法删除");
        }
        SyncDatasource db = datasourceMapper.selectById(id);
        if (db != null) {
            db.setDelFlag("1");
            datasourceMapper.updateById(db);
        }
    }

    @Override
    public boolean testConnection(SyncDatasource ds) {
        try {
            return JdbcUtils.testConnection(ds.getHost(), ds.getPort(),
                    ds.getDbName(), ds.getUsername(), ds.getPlainPassword());
        } catch (Exception e) {
            log.warn("test connection failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean testConnectionRaw(String host, Integer port, String dbName, String username, String plainPassword) {
        try {
            return JdbcUtils.testConnection(host, port, dbName, username, plainPassword);
        } catch (Exception e) {
            log.warn("test connection failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean testConnection(Long id) {
        SyncDatasource db = datasourceMapper.selectById(id);
        return db != null && testConnection(db);
    }
}
