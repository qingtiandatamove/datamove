package com.ruoyi.datamove.license.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.utils.MacUtils;
import com.ruoyi.datamove.license.LicenseService;
import com.ruoyi.datamove.license.domain.SyncLicense;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/**
 * License 管理接口 (opt-in)。
 *
 * <p>仅在 {@code sync.license.enabled=true} 时注册; 否则路由不存在 (404),
 * 前端菜单会进入「模块未启用」占位提示。
 */
@Api(tags = "License 授权管理")
@RestController
@RequestMapping("/sync/license")
@ConditionalOnProperty(name = "sync.license.enabled", havingValue = "true")
public class LicenseController {

    @Autowired
    private LicenseService licenseService;

    @ApiOperation("查询当前授权")
    @GetMapping
    public R<SyncLicense> get() {
        SyncLicense l = licenseService.get();
        if (l != null) l.setMacAddress(MacUtils.getLocalMac());
        return R.ok(l);
    }

    @ApiOperation("修改过期时间 / License Key (超级管理员)")
    @PutMapping
    public R<Void> update(@RequestBody SyncLicense license) {
        licenseService.update(license);
        return R.ok();
    }
}
