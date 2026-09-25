-- =====================================================================
-- 升级脚本: 告警中心 (告警记录表 + 菜单权限)
-- 日期: 2026-09-25
--
-- 背景:
--   之前告警只是"发出去就完了": AlertUtils 异步调钉钉/邮件, 成功失败都只进日志,
--   没有记录、不能重试、也没有地方看"到底发没发出去"。
--   这里新增告警记录表, 每条告警按通道落一条记录(PENDING -> SUCCESS/FAILED),
--   支持在页面上重试失败记录、发送测试告警、按条件清理。
--
-- 权限标识:
--   sync:alert:list   查看告警记录
--   sync:alert:retry  重试失败告警
--   sync:alert:test   发送测试告警
--   sync:alert:remove 删除/清理告警记录
-- =====================================================================

DROP TABLE IF EXISTS `sync_alert_record`;
CREATE TABLE `sync_alert_record` (
  `id`           bigint(20)  NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id`      bigint(20)  DEFAULT NULL COMMENT '关联任务ID(测试发送为空)',
  `task_name`    varchar(100) DEFAULT NULL COMMENT '任务名称(冗余, 任务删除后仍可追溯)',
  `alert_type`   varchar(32) NOT NULL DEFAULT 'TASK' COMMENT '告警场景: TASK/DDL/CANAL/VERIFY/TEST',
  `channel`      varchar(16) NOT NULL COMMENT '通道: DINGTALK/MAIL',
  `subject`      varchar(200) DEFAULT NULL COMMENT '告警标题',
  `content`      text COMMENT '告警正文',
  `target`       varchar(500) DEFAULT NULL COMMENT '投递目标: webhook 地址或收件邮箱',
  `status`       char(1)     NOT NULL DEFAULT '0' COMMENT '状态: 0待发送 1成功 2失败',
  `retry_count`  int(11)     NOT NULL DEFAULT 0 COMMENT '重试次数',
  `error_msg`    varchar(500) DEFAULT NULL COMMENT '失败原因',
  `create_time`  datetime    DEFAULT NULL COMMENT '创建时间',
  `send_time`    datetime    DEFAULT NULL COMMENT '最后发送时间',
  PRIMARY KEY (`id`),
  KEY `idx_alert_task` (`task_id`),
  KEY `idx_alert_status` (`status`),
  KEY `idx_alert_create` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警发送记录';

-- 菜单: 挂在「数据同步」目录下, 排在审计日志之后
INSERT IGNORE INTO `sys_menu`
  (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`)
VALUES
  (109, '告警中心', 100, 6, '/sync/alert', 'sync/alert', '1', 'C', '0', '0', 'sync:alert:list', 'el-icon-bell');

INSERT INTO `sys_menu`
  (`menu_name`, `parent_id`, `order_num`, `path`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`)
SELECT t.* FROM (
  SELECT '告警重试' menu_name, 109 parent_id, 1 order_num, '' path, '1' is_frame, 'F' menu_type, '0' visible, '0' status, 'sync:alert:retry' perms, '#' icon
  UNION ALL SELECT '告警测试', 109, 2, '', '1', 'F', '0', '0', 'sync:alert:test',  '#'
  UNION ALL SELECT '告警清理', 109, 3, '', '1', 'F', '0', '0', 'sync:alert:remove', '#'
) t
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` m WHERE m.perms = t.perms);

-- 授予超级管理员
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, menu_id FROM `sys_menu`
WHERE perms IN ('sync:alert:list', 'sync:alert:retry', 'sync:alert:test', 'sync:alert:remove')
   OR menu_id = 109;
