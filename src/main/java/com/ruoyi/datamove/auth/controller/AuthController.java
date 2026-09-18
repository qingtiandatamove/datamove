package com.ruoyi.datamove.auth.controller;

import com.ruoyi.common.core.domain.LoginUser;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.auth.service.IAuthService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Api(tags = "用户认证")
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private IAuthService authService;

    @ApiOperation("登录 - 返回 JWT Token")
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) return R.fail("账号或密码不能为空");
        return R.ok(authService.login(username, password));
    }

    @ApiOperation("获取当前登录用户信息")
    @GetMapping("/info")
    public R<LoginUser> info() {
        return R.ok(authService.currentUser());
    }

    @ApiOperation("退出登录")
    @PostMapping("/logout")
    public R<Void> logout() {
        return R.ok();
    }

    @ApiOperation("修改自己的密码")
    @PutMapping("/password")
    public R<Void> changePassword(@RequestBody Map<String, String> body) {
        Long userId = Long.valueOf(String.valueOf(body.get("userId")));
        String oldPwd = body.get("oldPassword");
        String newPwd = body.get("newPassword");
        authService.updatePassword(userId, oldPwd, newPwd);
        return R.ok();
    }
}
