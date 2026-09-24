package com.ruoyi.datamove.auth.service.impl;

import com.ruoyi.common.utils.MailUtils;
import com.ruoyi.datamove.auth.service.EmailCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 邮箱验证码服务实现
 *
 * 行为:
 *   - sync.mail.enabled=false (dev 默认): 仅写入本地日志, 不消耗邮件配额
 *   - sync.mail.enabled=true: 走 MailUtils 真实发送
 *
 * 注意:
 *   - MailUtils.send() 在 enabled=false 或配置缺失时直接返回 false 静默跳过;
 *     登录验证码场景必须确保发出, 因此 enabled=false 时降级到本地日志,
 *     enabled=true 但发送失败时直接抛异常, 让 Controller 返回错误给前端
 */
@Slf4j
@Service
public class EmailCodeServiceImpl implements EmailCodeService {

    private static final String CODE_KEY_PREFIX  = "email:code:";
    private static final String COUNT_KEY_PREFIX = "email:count:";
    private static final long   CODE_TTL_SECONDS = 300;     // 5 分钟
    private static final int    MAX_PER_HOUR     = 5;       // 1h 最多 5 次

    @Value("${sync.mail.enabled:false}")
    private boolean mailEnabled;

    private final StringRedisTemplate redis;

    public EmailCodeServiceImpl(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void sendCode(String email) {
        // ---------- 1. 频率限制 ----------
        String countKey = COUNT_KEY_PREFIX + email;
        Long count = redis.opsForValue().increment(countKey);
        if (count != null && count == 1L) {
            redis.expire(countKey, Duration.ofHours(1));
        }
        if (count != null && count > MAX_PER_HOUR) {
            log.warn("[EmailCode] 邮箱 {} 1 小时内发送 {} 次, 触发限流", maskEmail(email), count);
            throw new RuntimeException("发送过于频繁, 请 1 小时后再试");
        }

        // ---------- 2. 生成 6 位数字验证码 ----------
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1000000));

        // ---------- 3. 发送 ----------
        if (mailEnabled && MailUtils.isAvailable()) {
            String subject = "【DataMove】登录验证码";
            String content =
                "<div style=\"font-family:-apple-system,'PingFang SC',sans-serif;max-width:520px;margin:0 auto;padding:24px;\">" +
                "  <h2 style=\"color:#1f2937;font-size:20px;margin:0 0 16px;\">DataMove 登录验证码</h2>" +
                "  <p style=\"color:#4b5563;font-size:14px;line-height:1.6;margin:0 0 16px;\">你正在登录 DataMove 工作台, 验证码如下:</p>" +
                "  <div style=\"background:#f3f4f6;border-radius:8px;padding:20px;text-align:center;margin:16px 0;\">" +
                "    <span style=\"font-size:32px;font-weight:700;letter-spacing:6px;color:#2563eb;\">" + code + "</span>" +
                "  </div>" +
                "  <p style=\"color:#9ca3af;font-size:12px;margin:16px 0 0;\">验证码 5 分钟内有效, 请尽快使用。如非本人操作, 请忽略此邮件。</p>" +
                "</div>";
            boolean ok = MailUtils.send(email, subject, content, true);
            if (!ok) {
                throw new RuntimeException("邮件发送失败, 请检查 SMTP 配置或稍后重试");
            }
            log.info("[EmailCode] 已发送验证码至 {}", maskEmail(email));
        } else {
            // dev 模式: 仅本地日志
            log.info("[EmailCode-DEV] 邮箱 {} 的验证码是: {}", maskEmail(email), code);
        }

        // ---------- 4. 存入 Redis ----------
        redis.opsForValue().set(CODE_KEY_PREFIX + email, code, Duration.ofSeconds(CODE_TTL_SECONDS));
    }

    @Override
    public boolean verifyCode(String email, String code) {
        String key = CODE_KEY_PREFIX + email;
        String saved = redis.opsForValue().get(key);
        if (saved == null) {
            throw new RuntimeException("验证码已过期, 请重新获取");
        }
        if (!saved.equals(code)) {
            throw new RuntimeException("验证码错误");
        }
        redis.delete(key);
        return true;
    }

    private static String maskEmail(String email) {
        if (email == null || email.isEmpty()) return email;
        int at = email.indexOf('@');
        if (at <= 1) return email;
        if (at <= 3) return email.charAt(0) + "***" + email.substring(at);
        return email.substring(0, 2) + "***" + email.substring(at - 2) + email.substring(at);
    }
}