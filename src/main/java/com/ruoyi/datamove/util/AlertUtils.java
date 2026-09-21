package com.ruoyi.datamove.util;

import com.ruoyi.common.utils.DingTalkUtils;
import com.ruoyi.common.utils.MailUtils;
import com.ruoyi.datamove.task.domain.SyncTask;
import lombok.extern.slf4j.Slf4j;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 统一告警入口
 *
 * 一条失败消息会同时投递到:
 *  1) 钉钉机器人  - 任务上配置的 dingtalkWebhook
 *  2) 邮件       - 任务上配置的 alertEmail (SMTP 服务器为全局配置 sync.mail.*)
 *
 * 特性:
 *  - 任一渠道未配置则自动跳过, 互不影响;
 *  - 异步发送, 不阻塞同步线程; 队列满了直接丢弃, 绝不拖垮同步;
 *  - 任何异常只记录日志, 不影响任务本身的状态流转。
 */
@Slf4j
public class AlertUtils {

    /** 告警发送线程池: 单线程足够(告警频率低), 队列满则丢弃最旧消息 */
    private static final ExecutorService POOL = new ThreadPoolExecutor(
            1, 2, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r, "datamove-alert");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    private static final String DATE_FMT = "yyyy-MM-dd HH:mm:ss";

    /**
     * 发送告警 (异步)
     *
     * @param task    同步任务, 读取其中的钉钉 Webhook 与告警邮箱
     * @param subject 告警场景, 用于邮件标题, 例如 "任务启动失败:源库连接失败"
     * @param content 告警正文, 钉钉原样发送(沿用调用方既有文案)
     */
    public static void alert(SyncTask task, String subject, String content) {
        if (task == null) {
            return;
        }
        final String webhook = task.getDingtalkWebhook();
        final String email = task.getAlertEmail();
        final String taskName = task.getTaskName();
        final String taskId = task.getId() == null ? "-" : String.valueOf(task.getId());

        boolean needDingTalk = webhook != null && !webhook.trim().isEmpty();
        boolean needMail = email != null && !email.trim().isEmpty() && MailUtils.isAvailable();
        if (!needDingTalk && !needMail) {
            log.debug("[Alert] task[{}] no channel configured, skip", taskName);
            return;
        }

        final String mailSubject = "【DataMove告警】任务[" + taskName + "] " + subject;
        final String mailBody = content + "\n\n"
                + "-----------------------------\n"
                + "任务ID   : " + taskId + "\n"
                + "任务名称 : " + taskName + "\n"
                + "任务类型 : " + task.getTaskType() + "\n"
                + "同步表   : " + task.getTableName() + "\n"
                + "告警时间 : " + new SimpleDateFormat(DATE_FMT).format(new Date()) + "\n"
                + "-----------------------------\n"
                + "本邮件由 DataMove 数据同步平台自动发送, 请勿回复。";

        try {
            POOL.execute(() -> {
                if (needDingTalk) {
                    safeDingTalk(webhook, content);
                }
                if (needMail) {
                    safeMail(email, mailSubject, mailBody);
                }
            });
        } catch (Exception e) {
            log.error("[Alert] submit alert task error", e);
        }
    }

    private static void safeDingTalk(String webhook, String content) {
        try {
            DingTalkUtils.sendText(webhook, content);
        } catch (Exception e) {
            log.error("[Alert] dingtalk send error", e);
        }
    }

    private static void safeMail(String to, String subject, String body) {
        try {
            MailUtils.send(to, subject, body);
        } catch (Exception e) {
            log.error("[Alert] mail send error, to={}", to, e);
        }
    }
}
