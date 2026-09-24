package com.ruoyi.datamove.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云短信配置
 * 对应 application.yml 的 sms.aliyun 节点
 */
@Data
@Component
@ConfigurationProperties(prefix = "sms.aliyun")
public class SmsProperties {

    /** 是否启用真实短信下发, false 时仅本地日志输出验证码 */
    private boolean enabled;

    /** 阿里云 AccessKeyId (RAM 子账号 AK, 仅需 SMS 权限) */
    private String accessKeyId;

    /** 阿里云 AccessKeySecret */
    private String accessKeySecret;

    /** 短信签名, 必须在阿里云「短信服务 > 国内短信 > 签名管理」已审核通过 */
    private String signName;

    /** 短信模板 CODE, 模板内容必须含一个 ${code} 变量 */
    private String templateCode;

    /** API 接入地址, 默认公网 */
    private String endpoint = "dysmsapi.aliyuncs.com";

    /** 验证码在 Redis 中的有效期 (秒) */
    private long codeTtl = 300;

    /** 单手机号 1 小时内最大发送次数 (防刷) */
    private int rateLimitPerHour = 5;
}