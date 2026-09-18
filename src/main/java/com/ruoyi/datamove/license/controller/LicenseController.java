package com.ruoyi.datamove.license.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.utils.MacUtils;
import com.ruoyi.datamove.license.LicenseService;
import com.ruoyi.datamove.license.domain.SyncLicense;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Api(tags = "License 授权管理")
@RestController
@RequestMapping("/sync/license")
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
