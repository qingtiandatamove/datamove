-- =====================================================================
-- 升级脚本: 同步任务调度方式 (Cron 定时 / 手动 / 事件触发 三选一)
-- 日期: 2026-09-25
--
-- 说明:
--   trigger_type = CRON   : 按 cron_expr 定时自动启动任务 (Spring 6 位表达式: 秒 分 时 日 月 周)
--   trigger_type = MANUAL : 手动启动 (默认, 兼容存量任务)
--   trigger_type = EVENT  : 通过 HTTP 事件回调触发
--                           POST /sync/task/event/{event_token}
--                           token 在任务保存时自动生成, 即改即生效
-- =====================================================================

ALTER TABLE `sync_task`
  ADD COLUMN `trigger_type` varchar(16) NOT NULL DEFAULT 'MANUAL'
    COMMENT '调度方式(CRON=定时 MANUAL=手动 EVENT=事件触发)' AFTER `binlog_dml_types`,
  ADD COLUMN `cron_expr` varchar(64) DEFAULT NULL
    COMMENT 'CRON表达式(Spring 6位:秒 分 时 日 月 周, trigger_type=CRON时必填)' AFTER `trigger_type`,
  ADD COLUMN `event_token` varchar(64) DEFAULT NULL
    COMMENT '事件触发令牌(URL中的密钥, trigger_type=EVENT时自动生成)' AFTER `cron_expr`,
  ADD UNIQUE KEY `uk_event_token` (`event_token`);
