package com.ruoyi.datamove.auth.service;

/**
 * 邮箱验证码服务 (登录用)
 *
 * 实现: {@link com.ruoyi.datamove.auth.service.impl.EmailCodeServiceImpl}
 *
 * 设计:
 *   - 复用 MailUtils 发送 (沿用 sync.mail.* 配置)
 *   - Redis key: email:code:{email}  验证码 5 分钟 TTL
 *   - 防刷: email:count:{email}  1 小时最多 5 次
 *   - 校验一次性: verifyCode 成功后立即 delete
 */
public interface EmailCodeService {

    /**
     * 向指定邮箱发送 6 位数字验证码
     */
    void sendCode(String email);

    /**
     * 校验邮箱 + 验证码, 校验成功立即从 Redis 删除 (一次性)
     */
    boolean verifyCode(String email, String code);
}