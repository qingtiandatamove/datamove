package com.ruoyi.datamove.auth.service;

import com.ruoyi.common.core.domain.LoginUser;

import java.util.Map;

public interface IAuthService {
    /**
     * 登录
     */
    Map<String, Object> login(String userName, String password);

    /**
     * 当前登录用户
     */
    LoginUser currentUser();

    /**
     * 修改密码
     */
    void updatePassword(Long userId, String oldPwd, String newPwd);

    /**
     * 重置密码(超管)
     */
    void resetPassword(Long userId, String newPwd);
}
