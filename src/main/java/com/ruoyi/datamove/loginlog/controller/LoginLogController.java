package com.ruoyi.datamove.loginlog.controller;

import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.Perms;
import com.ruoyi.datamove.loginlog.domain.LoginLog;
import com.ruoyi.datamove.loginlog.service.LoginLogService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Api(tags = "登录日志")
@RestController
@RequestMapping("/sync/login-log")
@RequiredArgsConstructor
public class LoginLogController {

    private final LoginLogService loginLogService;

    @ApiOperation("登录日志分页 (谁在什么时候从哪个 IP/地点登录, 成功还是失败)")
    @GetMapping("/page")
    public R<PageResult<LoginLog>> page(@RequestParam(defaultValue = "1") int pageNum,
                                        @RequestParam(defaultValue = "20") int pageSize,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String loginType,
                                        @RequestParam(required = false) String beginTime,
                                        @RequestParam(required = false) String endTime,
                                        @RequestParam(required = false, defaultValue = "id") String orderByColumn,
                                        @RequestParam(required = false, defaultValue = "desc") String isAsc) {
        Perms.require("sync:loginlog:list");
        return R.ok(loginLogService.page(keyword, status, loginType, beginTime, endTime,
                orderByColumn, isAsc, pageNum, pageSize));
    }

    @ApiOperation("删除单条登录日志")
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        Perms.require("sync:loginlog:remove");
        loginLogService.remove(id);
        return R.ok();
    }

    @ApiOperation("清空登录日志")
    @DeleteMapping
    public R<Void> clear() {
        Perms.require("sync:loginlog:remove");
        loginLogService.clear();
        return R.ok();
    }
}
