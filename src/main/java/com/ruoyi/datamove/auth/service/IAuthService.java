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

    /**
     * 注册账号 (自助注册)
     *
     * <p>注册成功直接返回 token —— 省掉"注册完还要再登录一次"这一步。
     * 新用户的数据与老用户完全隔离: 只能看到自己创建的数据源 / 任务 / 日志。
     *
     * @return 与 login() 相同的返回结构 (含 token)
     */
    Map<String, Object> register(String userName, String password, String nickName, String email, String phonenumber);

    /**
     * 手机号 + 短信验证码登录
     *
     * @param phone 11 位手机号
     * @param code  6 位验证码 (调用方需保证已通过 SmsService 校验, 此方法不再重复校验)
     * @return 与 login() 相同的返回结构
     */
    Map<String, Object> loginByPhone(String phone, String code);

    /**
     * 邮箱 + 邮件验证码登录
     *
     * @param email 邮箱地址
     * @param code  6 位验证码 (调用方需保证已通过 EmailCodeService 校验)
     * @return 与 login() 相同的返回结构
     */
    Map<String, Object> loginByEmail(String email, String code);
}
