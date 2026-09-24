package com.ruoyi.datamove.auth.controller;

import com.ruoyi.common.core.domain.LoginUser;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.auth.service.EmailCodeService;
import com.ruoyi.datamove.auth.service.IAuthService;
import com.ruoyi.datamove.auth.service.SmsService;
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

    @Autowired
    private SmsService smsService;

    @Autowired
    private EmailCodeService emailCodeService;

    @ApiOperation("登录 - 返回 JWT Token")
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) return R.fail("账号或密码不能为空");
        return R.ok(authService.login(username, password));
    }

    @ApiOperation("发送短信验证码 (登录用)")
    @PostMapping("/sms-code")
    public R<Void> sendSmsCode(@RequestBody Map<String, String> body) {
        String phone = body.get("phone");
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            return R.fail("手机号格式不正确");
        }
        smsService.sendCode(phone);
        return R.ok();
    }

    @ApiOperation("短信验证码登录")
    @PostMapping("/login-sms")
    public R<Map<String, Object>> loginBySms(@RequestBody Map<String, String> body) {
        String phone = body.get("phone");
        String code = body.get("code");
        if (phone == null || code == null) return R.fail("手机号和验证码不能为空");
        return R.ok(authService.loginByPhone(phone, code));
    }

    @ApiOperation("发送邮件验证码 (登录用)")
    @PostMapping("/email-code")
    public R<Void> sendEmailCode(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || !email.matches("^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$")) {
            return R.fail("邮箱格式不正确");
        }
        emailCodeService.sendCode(email);
        return R.ok();
    }

    @ApiOperation("邮件验证码登录")
    @PostMapping("/login-email")
    public R<Map<String, Object>> loginByEmail(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String code = body.get("code");
        if (email == null || code == null) return R.fail("邮箱和验证码不能为空");
        return R.ok(authService.loginByEmail(email, code));
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
