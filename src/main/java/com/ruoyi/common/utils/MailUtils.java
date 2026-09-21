package com.ruoyi.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Date;
import java.util.Properties;

/**
 * 邮件告警工具 (javax.mail)
 *
 * 设计要点:
 *  - 与 {@link DingTalkUtils} 保持一致的"静态调用"风格, 业务代码零侵入;
 *  - 所有配置来自配置文件的 sync.mail.* 段, 由 Spring 静态 setter 注入;
 *  - 未开启 / 未配置 host / 无收件人时静默跳过;
 *  - 发送失败只记录日志, 绝不抛出异常影响同步主流程。
 *
 * application.yml 示例:
 * <pre>
 * sync:
 *   mail:
 *     enabled: true
 *     host: smtp.qq.com
 *     port: 465
 *     protocol: smtps
 *     username: alert@xxx.com
 *     password: 邮箱授权码(非登录密码)
 * </pre>
 */
@Slf4j
@Component
public class MailUtils {

    /** 邮件告警总开关 */
    private static volatile boolean enabled = false;
    private static String host = "";
    private static int port = 465;
    private static String protocol = "smtps";
    private static String username = "";
    private static String password = "";
    private static String from = "";
    private static boolean ssl = true;
    private static boolean starttls = false;
    private static int timeout = 8000;

    /* ==================== 配置注入 (静态 setter) ==================== */

    @Value("${sync.mail.enabled:false}")
    public void setEnabled(boolean v) {
        MailUtils.enabled = v;
    }

    @Value("${sync.mail.host:}")
    public void setHost(String v) {
        MailUtils.host = v == null ? "" : v.trim();
    }

    @Value("${sync.mail.port:465}")
    public void setPort(int v) {
        MailUtils.port = v;
    }

    @Value("${sync.mail.protocol:smtps}")
    public void setProtocol(String v) {
        MailUtils.protocol = (v == null || v.trim().isEmpty()) ? "smtps" : v.trim();
    }

    @Value("${sync.mail.username:}")
    public void setUsername(String v) {
        MailUtils.username = v == null ? "" : v.trim();
    }

    @Value("${sync.mail.password:}")
    public void setPassword(String v) {
        MailUtils.password = v == null ? "" : v;
    }

    @Value("${sync.mail.from:}")
    public void setFrom(String v) {
        MailUtils.from = v == null ? "" : v.trim();
    }

    @Value("${sync.mail.ssl:true}")
    public void setSsl(boolean v) {
        MailUtils.ssl = v;
    }

    @Value("${sync.mail.starttls:false}")
    public void setStarttls(boolean v) {
        MailUtils.starttls = v;
    }

    @Value("${sync.mail.timeout:8000}")
    public void setTimeout(int v) {
        MailUtils.timeout = v;
    }

    /* ==================== 对外接口 ==================== */

    /** 邮件告警是否可用 (开关打开且配置了 SMTP host) */
    public static boolean isAvailable() {
        return enabled && host != null && !host.isEmpty();
    }

    public static boolean send(String to, String subject, String content) {
        return send(to, subject, content, false);
    }

    /**
     * 发送纯文本邮件
     *
     * @param to       收件人, 多个用英文逗号/分号分隔, 为空则跳过
     * @param subject  邮件主题
     * @param content  邮件正文
     * @param html     正文是否为 HTML
     * @return 是否发送成功
     */
    public static boolean send(String to, String subject, String content, boolean html) {
        if (!enabled) {
            log.debug("mail alert disabled (sync.mail.enabled=false), skip send");
            return false;
        }
        if (to == null || to.trim().isEmpty()) {
            log.debug("mail receivers is empty, skip send");
            return false;
        }
        if (host == null || host.isEmpty()) {
            log.warn("mail alert enabled but sync.mail.host not configured, skip send");
            return false;
        }
        try {
            Session session = buildSession();
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from.isEmpty() ? username : from));
            // 支持逗号/分号分隔的多个收件人
            message.setRecipients(Message.RecipientType.TO,
                    InternetAddress.parse(to.replace(';', ','), false));
            message.setSubject(subject == null ? "DataMove通知" : subject, "UTF-8");
            message.setSentDate(new Date());
            if (html) {
                message.setContent(content, "text/html;charset=UTF-8");
            } else {
                message.setContent(content, "text/plain;charset=UTF-8");
            }
            message.saveChanges();

            // 【务必不要改成静态的 Transport.send(message)】
            // Transport.send(msg) 内部走的是 session.getTransport(address), 即按「收件人地址类型」
            // 解析协议: InternetAddress.getType() 返回 "rfc822", 而 JavaMail 的 addressMap 把
            // rfc822 静态映射为 "smtp"。结果:
            //   - mail.transport.protocol=smtps 被完全忽略;
            //   - 实际读取的是 mail.smtp.host / mail.smtp.port, 而本类只设置了 mail.smtps.*;
            //   - 最终退回 smtp 默认值 localhost:25, 报 MailConnectException。
            // 因此这里必须显式按配置的 protocol 取 transport。
            Transport transport = session.getTransport(protocol);
            try {
                transport.connect();
                transport.sendMessage(message, message.getAllRecipients());
            } finally {
                try {
                    transport.close();
                } catch (Exception ignore) {
                    // 关闭失败不影响发送结果
                }
            }
            log.info("mail alert sent to [{}], subject={}", to, subject);
            return true;
        } catch (Exception e) {
            log.error("mail alert send error, to={}, subject={}", to, subject, e);
            return false;
        }
    }

    /* ==================== 内部实现 ==================== */

    private static Session buildSession() {
        final String proto = protocol;
        Properties props = new Properties();
        props.put("mail.transport.protocol", proto);
        props.put("mail." + proto + ".host", host);
        props.put("mail." + proto + ".port", String.valueOf(port));
        props.put("mail." + proto + ".connectiontimeout", String.valueOf(timeout));
        props.put("mail." + proto + ".timeout", String.valueOf(timeout));
        props.put("mail." + proto + ".writetimeout", String.valueOf(timeout));

        Authenticator authenticator = null;
        if (!username.isEmpty()) {
            props.put("mail." + proto + ".auth", "true");
            authenticator = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            };
        }
        if (ssl) {
            props.put("mail." + proto + ".ssl.enable", "true");
            props.put("mail." + proto + ".socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail." + proto + ".socketFactory.fallback", "false");
        }
        if (starttls) {
            props.put("mail." + proto + ".starttls.enable", "true");
            props.put("mail." + proto + ".starttls.required", "false");
        }
        return Session.getInstance(props, authenticator);
    }
}
