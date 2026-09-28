package com.ruoyi.datamove.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ruoyi.common.core.domain.LoginUser;
import com.ruoyi.common.utils.JwtUtils;
import com.ruoyi.datamove.auth.domain.SysUser;
import com.ruoyi.datamove.auth.mapper.SysUserMapper;
import com.ruoyi.datamove.auth.service.EmailCodeService;
import com.ruoyi.datamove.auth.service.IAuthService;
import com.ruoyi.datamove.auth.service.SmsService;
import com.ruoyi.datamove.auth.service.SysPermissionService;
import com.ruoyi.datamove.util.IpUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements IAuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SmsService smsService;
    private final EmailCodeService emailCodeService;
    private final SysPermissionService permissionService;

    @Value("${token.secret}")
    private String secret;

    @Value("${token.expireTime}")
    private long expireTime;

    @Override
    public Map<String, Object> login(String userName, String password) {
        SysUser user = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("user_name", userName).eq("del_flag", "0"));
        if (user == null) throw new RuntimeException("账号或密码错误");
        if ("1".equals(user.getStatus())) throw new RuntimeException("账号已停用");
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("账号或密码错误");
        }
        return buildLoginResult(user);
    }

    @Override
    public Map<String, Object> loginByPhone(String phone, String code) {
        // 1. 校验短信验证码 (一次性, 校验成功自动从 Redis 删除)
        smsService.verifyCode(phone, code);
        // 2. 查用户
        SysUser user = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("phonenumber", phone).eq("del_flag", "0"));
        if (user == null) throw new RuntimeException("该手机号未绑定账号");
        if ("1".equals(user.getStatus())) throw new RuntimeException("账号已停用");
        // 3. 与账号密码登录走同一套 token + 权限逻辑
        return buildLoginResult(user);
    }

    @Override
    public Map<String, Object> loginByEmail(String email, String code) {
        // 1. 校验邮件验证码
        emailCodeService.verifyCode(email, code);
        // 2. 查用户
        SysUser user = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("email", email).eq("del_flag", "0"));
        if (user == null) throw new RuntimeException("该邮箱未绑定账号");
        if ("1".equals(user.getStatus())) throw new RuntimeException("账号已停用");
        // 3. 与账号密码登录走同一套 token + 权限逻辑
        return buildLoginResult(user);
    }

    @Override
    public LoginUser currentUser() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object p = auth.getPrincipal();
        return p instanceof LoginUser ? (LoginUser) p : null;
    }

    @Override
    public void updatePassword(Long userId, String oldPwd, String newPwd) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) throw new RuntimeException("用户不存在");
        if (!passwordEncoder.matches(oldPwd, user.getPassword())) throw new RuntimeException("原密码错误");
        user.setPassword(passwordEncoder.encode(newPwd));
        userMapper.updateById(user);
    }

    @Override
    public void resetPassword(Long userId, String newPwd) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) throw new RuntimeException("用户不存在");
        user.setPassword(passwordEncoder.encode(newPwd));
        userMapper.updateById(user);
    }

    /**
     * 回填 sys_user.login_ip / login_date —— 用户管理页能看到「谁最后一次什么时候从哪登录」。
     * 这属于锦上添花的信息, 写失败不能影响登录。
     */
    private void touchLoginTrace(SysUser user) {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            String ip = IpUtils.clientIp(attrs == null ? null : attrs.getRequest());
            if (cn.hutool.core.util.StrUtil.isNotBlank(ip)) user.setLoginIp(ip);
            user.setLoginDate(new Date());
            userMapper.updateById(user);
        } catch (Exception ignored) {
            // 登录追踪失败不影响本次登录
        }
    }

    /**
     * 抽公共: 账号密码 / 短信 / 邮箱三种登录方式共用
     *
     * <p>角色与权限从 sys_user_role -> sys_role -> sys_role_menu -> sys_menu.perms 实时算出,
     * 不再写死 admin / *:*:* —— 否则用户管理里配的授权不生效。
     * 这里返回给前端的是「登录那一刻」的快照, 用于按钮级显隐;
     * 后端真正的鉴权依据是 JwtAuthenticationFilter 每次请求重算的权限。
     */
    private Map<String, Object> buildLoginResult(SysUser user) {
        String token = JwtUtils.generate(user.getUserId(), user.getUserName(), secret, expireTime);
        // 回填最后登录 IP / 时间 (sys_user 本来就有这两列, 之前一直是空的)
        touchLoginTrace(user);
        Set<String> roles = permissionService.roleKeysOfUser(user.getUserId());
        Set<String> permissions = permissionService.permissionsOfUser(user.getUserId(), roles);
        Map<String, Object> res = new HashMap<>();
        res.put("token", token);
        res.put("userId", user.getUserId());
        res.put("userName", user.getUserName());
        res.put("nickName", user.getNickName());
        res.put("expireIn", expireTime);
        res.put("roles", roles);
        res.put("permissions", permissions);
        return res;
    }
}
