package com.ruoyi.datamove.datasource.controller;

import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.service.ISyncDatasourceService;
import com.ruoyi.datamove.util.JdbcUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@Api(tags = "数据源管理")
@RestController
@RequestMapping("/sync/datasource")
public class SyncDatasourceController {

    @Autowired
    private ISyncDatasourceService datasourceService;

    @ApiOperation("分页查询")
    @GetMapping("/page")
    public R<PageResult<SyncDatasource>> page(@RequestParam(defaultValue = "1") int pageNum,
                                               @RequestParam(defaultValue = "10") int pageSize,
                                               @RequestParam(required = false) String keyword) {
        return R.ok(datasourceService.page(keyword, pageNum, pageSize));
    }

    @ApiOperation("列表(下拉)")
    @GetMapping("/list")
    public R<List<SyncDatasource>> list() {
        return R.ok(datasourceService.listAll());
    }

    @ApiOperation("详情")
    @GetMapping("/{id}")
    public R<SyncDatasource> detail(@PathVariable Long id) {
        return R.ok(datasourceService.getById(id));
    }

    @ApiOperation("新增")
    @PostMapping
    public R<Long> add(@RequestBody @Valid SyncDatasource ds) {
        return R.ok(datasourceService.add(ds));
    }

    @ApiOperation("修改")
    @PutMapping
    public R<Void> update(@RequestBody @Valid SyncDatasource ds) {
        datasourceService.update(ds);
        return R.ok();
    }

    @ApiOperation("删除")
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        datasourceService.remove(id);
        return R.ok();
    }

    @ApiOperation("测试连接")
    @PostMapping("/test")
    public R<Boolean> test(@RequestBody Map<String, String> body) {
        String host = body.get("host");
        Integer port = body.get("port") == null ? null : Integer.valueOf(body.get("port"));
        String dbName = body.get("dbName");
        String username = body.get("username");
        // 密码可能是明文,直接拿原文去连(避开 setPassword 自动加密)
        String password = body.get("password");
        if (password != null && !password.isEmpty()) {
            try {
                String dec = com.ruoyi.common.utils.AesUtils.decrypt(password);
                if (dec != null && !dec.isEmpty()) password = dec;
            } catch (Exception ignore) { /* 明文直接用 */ }
        }
        return R.ok(datasourceService.testConnectionRaw(host, port, dbName, username, password));
    }

    @ApiOperation("列出数据库中所有表")
    @GetMapping("/{id}/tables")
    public R<List<String>> listTables(@PathVariable Long id) {
        SyncDatasource ds = datasourceService.getById(id);
        if (ds == null) return R.fail("数据源不存在");
        return R.ok(JdbcUtils.listTables(ds));
    }

    @ApiOperation("获取表字段结构")
    @GetMapping("/{id}/columns")
    public R<List<Map<String, String>>> listColumns(@PathVariable Long id, @RequestParam String table) {
        SyncDatasource ds = datasourceService.getById(id);
        if (ds == null) return R.fail("数据源不存在");
        return R.ok(JdbcUtils.listColumns(ds, table));
    }
}
