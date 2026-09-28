package com.ruoyi.datamove.loginlog.controller;

import com.ruoyi.common.core.domain.LoginUser;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.loginlog.domain.LoginLog;
import com.ruoyi.datamove.loginlog.service.LoginLogService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Set;

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
        return R.ok(loginLogService.page(keyword, status, loginType, beginTime, endTime,
                orderByColumn, isAsc, pageNum, pageSize));
    }

    @ApiOperation("删除单条登录日志")
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        requirePerm();
        loginLogService.remove(id);
        return R.ok();
    }

    @ApiOperation("清空登录日志")
    @DeleteMapping
    public R<Void> clear() {
        requirePerm();
        loginLogService.clear();
        return R.ok();
    }

    /**
     * 删除/清空需要 sync:loginlog:remove 权限 (admin 直通)
     *
     * <p>本项目没有 RuoYi 的 @ss 权限表达式, 权限是 JwtAuthenticationFilter 每次请求算好放进
     * LoginUser 的, 这里直接读即可; 前端按钮同样按 $hasPerm 显隐, 后端是最终拦截。
     */
    private void requirePerm() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth == null ? null : auth.getPrincipal();
        Set<String> perms = principal instanceof LoginUser
                ? ((LoginUser) principal).getPermissions()
                : Collections.emptySet();
        if (perms == null) perms = Collections.emptySet();
        if (!perms.contains("*:*:*") && !perms.contains("sync:loginlog:remove")) {
            throw new RuntimeException("没有该操作权限 (sync:loginlog:remove)");
        }
    }
}
