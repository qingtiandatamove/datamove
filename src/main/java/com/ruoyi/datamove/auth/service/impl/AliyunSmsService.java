package com.ruoyi.datamove.auth.service.impl;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import com.ruoyi.datamove.auth.config.SmsProperties;
import com.ruoyi.datamove.auth.service.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 阿里云短信服务实现
 *
 * 行为:
 *   - enabled=false (dev 默认): 验证码仅写入本地日志 (datamove-backend.log)
 *   - enabled=true: 调用阿里云短信网关 SendSms API
 *
 * 防刷:
 *   - Redis Key: sms:count:{phone}  1 小时 TTL, INCR 计数
 *   - 超过 rate-limit-per-hour 直接拒绝
 *
 * 验证码存储:
 *   - Key: sms:code:{phone}   Value: 6 位字符串
 *   - TTL = codeTtl (默认 300s)
 *   - 校验成功后立即删除 (防止重放)
 */
@Slf4j
@Service
public class AliyunSmsService implements SmsService {

    private static final String CODE_KEY_PREFIX = "sms:code:";
    private static final String COUNT_KEY_PREFIX = "sms:count:";

    @Autowired
    private SmsProperties props;

    @Autowired
    private StringRedisTemplate redis;

    /** 阿里云短信客户端, enabled=false 时为 null */
    private Client client;

    @PostConstruct
    void init() {
        if (!props.isEnabled()) {
            log.info("[SMS] 阿里云短信未启用, 验证码仅写入本地日志 (开发模式)");
            return;
        }
        if (isBlank(props.getAccessKeyId()) || isBlank(props.getAccessKeySecret())) {
            log.warn("[SMS] enabled=true 但 access-key-id/access-key-secret 为空, 自动降级到 dev 模式");
            return;
        }
        try {
            Config cfg = new Config()
                    .setAccessKeyId(props.getAccessKeyId())
                    .setAccessKeySecret(props.getAccessKeySecret())
                    .setEndpoint(props.getEndpoint());
            this.client = new Client(cfg);
            log.info("[SMS] 阿里云短信客户端初始化成功, signName={}, templateCode={}",
                    props.getSignName(), props.getTemplateCode());
        } catch (Exception e) {
            log.error("[SMS] 阿里云短信客户端初始化失败", e);
            this.client = null;
        }
    }

    @Override
    public void sendCode(String phone) {
        // ---------- 1. 频率限制 ----------
        String countKey = COUNT_KEY_PREFIX + phone;
        Long count = redis.opsForValue().increment(countKey);
        if (count != null && count == 1L) {
            // 首次写入, 设 1 小时 TTL
            redis.expire(countKey, Duration.ofHours(1));
        }
        if (count != null && count > props.getRateLimitPerHour()) {
            log.warn("[SMS] 手机号 {} 1 小时内发送 {} 次, 触发限流", phone, count);
            throw new RuntimeException("发送过于频繁, 请 1 小时后再试");
        }

        // ---------- 2. 生成 6 位数字验证码 ----------
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1000000));

        // ---------- 3. 发送 ----------
        if (props.isEnabled() && client != null) {
            try {
                SendSmsRequest req = new SendSmsRequest()
                        .setPhoneNumbers(phone)
                        .setSignName(props.getSignName())
                        .setTemplateCode(props.getTemplateCode())
                        .setTemplateParam("{\"code\":\"" + code + "\"}");
                SendSmsResponse resp = client.sendSms(req);
                if (resp == null || resp.getBody() == null
                        || !"OK".equalsIgnoreCase(resp.getBody().getCode())) {
                    String errMsg = resp != null && resp.getBody() != null
                            ? resp.getBody().getMessage() : "empty response";
                    log.error("[SMS] 阿里云发送失败: {}", errMsg);
                    throw new RuntimeException("短信发送失败: " + errMsg);
                }
                log.info("[SMS] 已发送验证码至 {}", maskPhone(phone));
            } catch (Exception e) {
                log.error("[SMS] 阿里云发送异常", e);
                throw new RuntimeException("短信发送失败: " + e.getMessage());
            }
        } else {
            // dev 模式: 仅日志输出 (不消耗短信配额)
            log.info("[SMS-DEV] 手机号 {} 的验证码是: {}", maskPhone(phone), code);
        }

        // ---------- 4. 存入 Redis ----------
        redis.opsForValue().set(CODE_KEY_PREFIX + phone, code, Duration.ofSeconds(props.getCodeTtl()));
    }

    @Override
    public boolean verifyCode(String phone, String code) {
        String key = CODE_KEY_PREFIX + phone;
        String saved = redis.opsForValue().get(key);
        if (saved == null) {
            throw new RuntimeException("验证码已过期, 请重新获取");
        }
        if (!saved.equals(code)) {
            throw new RuntimeException("验证码错误");
        }
        // 一次性使用, 立即删除
        redis.delete(key);
        return true;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}