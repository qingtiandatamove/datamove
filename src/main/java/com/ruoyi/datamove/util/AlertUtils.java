package com.ruoyi.datamove.util;

import com.ruoyi.datamove.alert.service.AlertCenterService;
import com.ruoyi.datamove.task.domain.SyncTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 统一告警入口
 *
 * <p>一条失败消息会同时投递到:
 *  1) 钉钉机器人  - 任务上配置的 dingtalkWebhook
 *  2) 邮件       - 任务上配置的 alertEmail (SMTP 服务器为全局配置 sync.mail.*)
 *
 * <p>本类只是「门面」: 真正的落库与投递在 {@link AlertCenterService},
 * 这样每条告警都会留下记录, 失败可以在「告警中心」页面重试。
 *
 * <p>保留静态方法签名是为了不动引擎里的 10 处调用点
 * (FullSyncEngine / DdlSyncEngine / CanalSyncEngine / DataVerifyEngine)。
 */
@Slf4j
@Component
public class AlertUtils {

    private static AlertCenterService alertCenterService;

    @Resource
    public void setAlertCenterService(AlertCenterService service) {
        AlertUtils.alertCenterService = service;
    }

    /**
     * 发送告警 (异步, 不阻塞同步线程)
     *
     * @param task    同步任务, 读取其中的钉钉 Webhook 与告警邮箱
     * @param subject 告警场景, 用于邮件标题, 例如 "任务启动失败:源库连接失败"
     * @param content 告警正文, 钉钉原样发送(沿用调用方既有文案)
     */
    public static void alert(SyncTask task, String subject, String content) {
        if (task == null) return;
        if (alertCenterService == null) {
            // Spring 尚未注入(极端情况: 启动早期就告警), 只记日志, 绝不抛异常影响业务
            log.warn("[Alert] alert service not ready, drop alert: {}", subject);
            return;
        }
        try {
            alertCenterService.dispatch(task, subject, content);
        } catch (Exception e) {
            log.error("[Alert] dispatch error", e);
        }
    }
}
