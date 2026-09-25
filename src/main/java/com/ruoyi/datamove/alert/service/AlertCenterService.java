package com.ruoyi.datamove.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.utils.DingTalkUtils;
import com.ruoyi.common.utils.MailUtils;
import com.ruoyi.datamove.alert.domain.SyncAlertRecord;
import com.ruoyi.datamove.alert.mapper.SyncAlertRecordMapper;
import com.ruoyi.datamove.task.domain.SyncTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 告警中心: 负责告警的落库、投递、重试与查询
 *
 * <p>与之前的 AlertUtils 相比多了三件事:
 * <ol>
 *   <li>每条告警按通道落一条记录, 能在页面上看到"到底发没发出去";</li>
 *   <li>失败的可以重试(钉钉没配好、SMTP 抖动之类);</li>
 *   <li>可以发测试告警, 配完通道先验一遍。</li>
 * </ol>
 *
 * <p>投递仍然异步: 告警绝不能拖慢甚至拖垮同步线程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertCenterService {

    private static final String DATE_FMT = "yyyy-MM-dd HH:mm:ss";
    private static final int ERR_MSG_MAX = 450;

    private final SyncAlertRecordMapper recordMapper;

    /** 告警投递线程池: 单线程足够(告警频率低), 队列满了丢弃最旧的 —— 宁可少发一条也不能堆爆内存 */
    private static final ExecutorService POOL = new ThreadPoolExecutor(
            1, 2, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r, "datamove-alert");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    /**
     * 业务告警入口: 按任务上配置的通道各落一条记录并异步投递
     *
     * @param task    同步任务(读取钉钉 webhook / 告警邮箱)
     * @param subject 告警场景标题
     * @param content 告警正文
     */
    public void dispatch(SyncTask task, String subject, String content) {
        if (task == null) return;

        final String webhook = task.getDingtalkWebhook();
        final String email = task.getAlertEmail();
        boolean hasWebhook = StringUtils.hasText(webhook);
        boolean hasEmail = StringUtils.hasText(email);
        boolean needDingTalk = hasWebhook;
        boolean needMail = hasEmail && MailUtils.isAvailable();

        /* 一个可用通道都没有时, 也必须留下记录:
           否则任务明明失败了, 告警中心却空空如也, 让人分不清
           "告警没触发" 还是 "触发了但没通道发出去" —— 后者才是运维最该知道的:
           出了事却没人收到通知。 */
        if (!needDingTalk && !needMail) {
            String channel;
            String reason;
            if (hasEmail) {
                // 配了邮箱但邮件通道整体没启用: 最常见也最容易踩的坑, 提示要精确到配置项
                channel = "MAIL";
                reason = "任务已配置告警邮箱, 但邮件通道未启用(sync.mail.enabled 未打开或缺 SMTP 配置), 告警未发出";
            } else if (hasWebhook) {
                channel = "DINGTALK";
                reason = "钉钉通道不可用, 告警未发出";
            } else {
                channel = "NONE";
                reason = "任务未配置钉钉 Webhook 与告警邮箱, 告警未发出";
            }
            log.warn("[Alert] task[{}] alert not delivered: {}", task.getTaskName(), reason);
            insertSkipped(task, subject, content, channel,
                    hasEmail ? email : (hasWebhook ? webhook : null), reason);
            return;
        }

        String type = classify(task, subject);
        String mailSubject = "【DataMove告警】任务[" + task.getTaskName() + "] " + subject;
        String mailBody = content + "\n\n"
                + "-----------------------------\n"
                + "任务ID   : " + (task.getId() == null ? "-" : task.getId()) + "\n"
                + "任务名称 : " + task.getTaskName() + "\n"
                + "任务类型 : " + task.getTaskType() + "\n"
                + "同步表   : " + task.getTableName() + "\n"
                + "告警时间 : " + new SimpleDateFormat(DATE_FMT).format(new Date()) + "\n"
                + "-----------------------------\n"
                + "本邮件由 DataMove 数据同步平台自动发送, 请勿回复。";

        if (needDingTalk) {
            Long id = insert(task.getId(), task.getTaskName(), type, "DINGTALK", subject, content, webhook);
            submit(id, () -> DingTalkUtils.sendText(webhook, content));
        }
        if (needMail) {
            Long id = insert(task.getId(), task.getTaskName(), type, "MAIL", mailSubject, mailBody, email);
            submit(id, () -> MailUtils.send(email, mailSubject, mailBody));
        }
    }

    /**
     * 重试一条失败(或滞留待发送)的告警 —— 同步执行, 结果立即反馈给前端
     */
    public SyncAlertRecord retry(Long id) {
        SyncAlertRecord r = recordMapper.selectById(id);
        if (r == null) throw new RuntimeException("告警记录不存在");
        if (SyncAlertRecord.STATUS_SUCCESS.equals(r.getStatus())) {
            throw new RuntimeException("该告警已发送成功, 无需重试");
        }
        if ("NONE".equals(r.getChannel())) {
            throw new RuntimeException("该告警当时没有可用通道, 请先在任务上配置钉钉 Webhook 或告警邮箱, 再重新触发任务");
        }

        r.setRetryCount((r.getRetryCount() == null ? 0 : r.getRetryCount()) + 1);
        r.setSendTime(new Date());
        try {
            boolean ok = "DINGTALK".equals(r.getChannel())
                    ? DingTalkUtils.sendText(r.getTarget(), r.getContent())
                    : MailUtils.send(r.getTarget(), r.getSubject(), r.getContent());
            if (ok) {
                r.setStatus(SyncAlertRecord.STATUS_SUCCESS);
                r.setErrorMsg(null);
            } else {
                r.setStatus(SyncAlertRecord.STATUS_FAILED);
                r.setErrorMsg("渠道返回失败: 未配置、被禁用或地址无效");
            }
        } catch (Exception e) {
            r.setStatus(SyncAlertRecord.STATUS_FAILED);
            r.setErrorMsg(truncate(e.toString()));
        }
        recordMapper.updateById(r);
        return r;
    }

    /**
     * 发送测试告警: 不关联任务, 用于配置完通道后先验一遍能不能收到
     */
    public SyncAlertRecord test(String channel, String target, String subject, String content) {
        if (!"DINGTALK".equals(channel) && !"MAIL".equals(channel)) {
            throw new RuntimeException("通道只能是 DINGTALK 或 MAIL");
        }
        if (!StringUtils.hasText(target)) throw new RuntimeException("投递目标不能为空");

        String realSubject = StringUtils.hasText(subject) ? subject : "告警通道测试";
        String realContent = StringUtils.hasText(content)
                ? content
                : "这是一条 DataMove 测试告警, 收到说明通道配置正确。\n发送时间: "
                  + new SimpleDateFormat(DATE_FMT).format(new Date());

        SyncAlertRecord r = new SyncAlertRecord();
        r.setTaskId(null);
        r.setTaskName("测试告警");
        r.setAlertType("TEST");
        r.setChannel(channel);
        r.setSubject(realSubject);
        r.setContent(realContent);
        r.setTarget(target);
        r.setStatus(SyncAlertRecord.STATUS_PENDING);
        r.setRetryCount(0);
        r.setCreateTime(new Date());
        recordMapper.insert(r);

        try {
            boolean ok = "DINGTALK".equals(channel)
                    ? DingTalkUtils.sendText(target, realContent)
                    : MailUtils.send(target, realSubject, realContent);
            r.setStatus(ok ? SyncAlertRecord.STATUS_SUCCESS : SyncAlertRecord.STATUS_FAILED);
            if (!ok) r.setErrorMsg("渠道返回失败: 未配置、被禁用或地址无效");
        } catch (Exception e) {
            r.setStatus(SyncAlertRecord.STATUS_FAILED);
            r.setErrorMsg(truncate(e.toString()));
        }
        r.setSendTime(new Date());
        recordMapper.updateById(r);
        return r;
    }

    /** 分页查询告警记录 */
    public PageResult<SyncAlertRecord> page(int pageNum, int pageSize, String status, String channel,
                                             String alertType, String keyword) {
        Page<SyncAlertRecord> page = new Page<>(pageNum, pageSize);
        QueryWrapper<SyncAlertRecord> w = new QueryWrapper<>();
        if (StringUtils.hasText(status)) w.eq("status", status);
        if (StringUtils.hasText(channel)) w.eq("channel", channel);
        if (StringUtils.hasText(alertType)) w.eq("alert_type", alertType);
        if (StringUtils.hasText(keyword)) w.like("task_name", keyword);
        w.orderByDesc("id");
        Page<SyncAlertRecord> res = recordMapper.selectPage(page, w);
        return PageResult.of(res.getRecords(), res.getTotal());
    }

    /** 顶部统计: 总数 / 成功 / 失败 / 待发送 / 今日 */
    public Map<String, Object> stats() {
        Map<String, Object> m = new HashMap<>();
        m.put("total", recordMapper.selectCount(null));
        m.put("success", recordMapper.selectCount(new QueryWrapper<SyncAlertRecord>().eq("status", SyncAlertRecord.STATUS_SUCCESS)));
        m.put("failed", recordMapper.selectCount(new QueryWrapper<SyncAlertRecord>().eq("status", SyncAlertRecord.STATUS_FAILED)));
        m.put("pending", recordMapper.selectCount(new QueryWrapper<SyncAlertRecord>().eq("status", SyncAlertRecord.STATUS_PENDING)));
        m.put("skipped", recordMapper.selectCount(new QueryWrapper<SyncAlertRecord>().eq("status", SyncAlertRecord.STATUS_SKIPPED)));
        m.put("today", recordMapper.selectCount(new QueryWrapper<SyncAlertRecord>()
                .apply("DATE(create_time) = CURDATE()")));
        return m;
    }

    /** 清理 N 天前的记录 */
    public int clear(int days) {
        if (days < 1) throw new RuntimeException("清理天数必须大于 0");
        return recordMapper.delete(new QueryWrapper<SyncAlertRecord>()
                .apply("create_time < DATE_SUB(NOW(), INTERVAL {0} DAY)", days));
    }

    /** 删除单条记录 */
    public void delete(Long id) {
        recordMapper.deleteById(id);
    }

    /* ==================== 内部 ==================== */

    private Long insert(Long taskId, String taskName, String type, String channel,
                        String subject, String content, String target) {
        SyncAlertRecord r = new SyncAlertRecord();
        r.setTaskId(taskId);
        r.setTaskName(taskName);
        r.setAlertType(type);
        r.setChannel(channel);
        r.setSubject(subject);
        r.setContent(content);
        r.setTarget(target);
        r.setStatus(SyncAlertRecord.STATUS_PENDING);
        r.setRetryCount(0);
        r.setCreateTime(new Date());
        recordMapper.insert(r);
        return r.getId();
    }

    /** 落一条「未发送」记录: 没有可用通道时也要留下痕迹 */
    private void insertSkipped(SyncTask task, String subject, String content,
                               String channel, String target, String reason) {
        SyncAlertRecord r = new SyncAlertRecord();
        r.setTaskId(task.getId());
        r.setTaskName(task.getTaskName());
        r.setAlertType(classify(task, subject));
        r.setChannel(channel);
        r.setSubject(subject);
        r.setContent(content);
        r.setTarget(target);
        r.setStatus(SyncAlertRecord.STATUS_SKIPPED);
        r.setRetryCount(0);
        r.setErrorMsg(truncate(reason));
        r.setCreateTime(new Date());
        recordMapper.insert(r);
    }

    /** 异步投递并按结果回写记录状态 */
    private void submit(Long id, Supplier<Boolean> sender) {
        try {
            POOL.execute(() -> {
                SyncAlertRecord upd = new SyncAlertRecord();
                upd.setId(id);
                upd.setSendTime(new Date());
                try {
                    boolean ok = sender.get();
                    upd.setStatus(ok ? SyncAlertRecord.STATUS_SUCCESS : SyncAlertRecord.STATUS_FAILED);
                    if (!ok) upd.setErrorMsg("渠道返回失败: 未配置、被禁用或地址无效");
                } catch (Exception e) {
                    upd.setStatus(SyncAlertRecord.STATUS_FAILED);
                    upd.setErrorMsg(truncate(e.toString()));
                    log.error("[Alert] send error, recordId={}", id, e);
                }
                recordMapper.updateById(upd);
            });
        } catch (Exception e) {
            log.error("[Alert] submit error, recordId={}", id, e);
        }
    }

    /**
     * 告警场景归类: 调用方只传了文案, 这里按任务类型 + 文案关键字归到 TASK/DDL/CANAL/VERIFY,
     * 便于告警中心按场景筛选(比如只看增量同步的问题)
     */
    private String classify(SyncTask task, String subject) {
        String t = task.getTaskType() == null ? "" : task.getTaskType().toUpperCase();
        String s = subject == null ? "" : subject;
        if ("DDL".equals(t) || s.contains("表结构")) return "DDL";
        if ("INCR".equals(t) || s.contains("增量")) return "CANAL";
        if (s.contains("校验") || s.contains("修复")) return "VERIFY";
        return "TASK";
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() <= ERR_MSG_MAX ? s : s.substring(0, ERR_MSG_MAX);
    }
}
