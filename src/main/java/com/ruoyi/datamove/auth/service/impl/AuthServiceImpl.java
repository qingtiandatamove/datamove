package com.ruoyi.datamove.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ruoyi.common.core.domain.LoginUser;
import com.ruoyi.common.utils.JwtUtils;
import com.ruoyi.datamove.auth.domain.SysUser;
import com.ruoyi.datamove.auth.mapper.SysUserMapper;
import com.ruoyi.datamove.auth.service.IAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements IAuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

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
        String token = JwtUtils.generate(user.getUserId(), user.getUserName(), secret, expireTime);
        Map<String, Object> res = new HashMap<>();
        res.put("token", token);
        res.put("userId", user.getUserId());
        res.put("userName", user.getUserName());
        res.put("nickName", user.getNickName());
        res.put("expireIn", expireTime);
        res.put("roles", Collections.singletonList("admin"));
        res.put("permissions", Collections.singletonList("*:*:*"));
        return res;
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
}
