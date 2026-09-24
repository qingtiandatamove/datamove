package com.ruoyi.datamove.auth.service;

/**
 * 短信服务 (登录验证码 + 未来可扩展通知类短信)
 */
public interface SmsService {

    /**
     * 向指定手机号发送 6 位数字验证码.
     * 验证码会写入 Redis (TTL=codeTtl), 供 verifyCode 校验.
     *
     * @param phone 11 位手机号, 调用方需保证格式合法
     * @throws RuntimeException 发送失败 / 超过频率限制
     */
    void sendCode(String phone);

    /**
     * 校验手机号 + 验证码, 校验成功后会从 Redis 删除 (一次性).
     *
     * @param phone 手机号
     * @param code  用户输入的验证码
     * @return true
     * @throws RuntimeException 验证码错误或已过期
     */
    boolean verifyCode(String phone, String code);
}