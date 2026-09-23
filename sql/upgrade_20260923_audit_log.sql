-- ============================================================
-- DataMove 增量升级脚本
-- 日期: 2026-09-23
-- 内容: 审计日志 (字段级变更追踪, 企业客户合规审计必备)
--   sync_audit_log 表: 每次「新增/修改/删除」任务写一批行 (按字段拆, 同一次请求共享 revision_id)
--     - 谁 (operator_id / operator_name / ip / user_agent)
--     - 什么时候 (create_time, 毫秒精度)
--     - 改了什么 (op_type + field_name + old_value -> new_value)
--     - 是哪个任务 (entity_type + entity_id + entity_name 快照)
-- 幂等: 可重复执行 (建表走 IF NOT EXISTS)
-- 执行: mysql -uroot -p datamove < upgrade_20260923_audit_log.sql
-- ============================================================

SET NAMES utf8mb4;

-- 审计日志表
CREATE TABLE IF NOT EXISTS `sync_audit_log` (
  `id`            bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `revision_id`   bigint(20)   NOT NULL                COMMENT '请求内分组 (同一请求多个字段变更共享 revision_id)',
  `entity_type`   varchar(32)  NOT NULL DEFAULT 'sync_task' COMMENT '实体类型 (当前仅 sync_task)',
  `entity_id`     bigint(20)   NOT NULL                COMMENT '实体ID (task_id)',
  `entity_name`   varchar(128) DEFAULT NULL            COMMENT '实体名称 (任务名称快照)',
  `op_type`       varchar(16)  NOT NULL                COMMENT '操作类型 (CREATE/UPDATE/DELETE)',
  `field_name`    varchar(64)  NOT NULL                COMMENT '字段名 (CREATE/DELETE 整体变更时为 *)',
  `old_value`     text         DEFAULT NULL            COMMENT '旧值',
  `new_value`     text         DEFAULT NULL            COMMENT '新值',
  `operator_id`   bigint(20)   DEFAULT NULL            COMMENT '操作人ID (sys_user.user_id)',
  `operator_name` varchar(64)  DEFAULT NULL            COMMENT '操作人 (快照, 任务改名后历史依然读得懂)',
  `ip`            varchar(64)  DEFAULT NULL            COMMENT '客户端IP (兼容 nginx X-Forwarded-For)',
  `user_agent`    varchar(255) DEFAULT NULL            COMMENT '客户端 UA',
  `create_time`   datetime(3)  NOT NULL                COMMENT '创建时间 (毫秒精度)',
  PRIMARY KEY (`id`),
  KEY `idx_entity` (`entity_type`, `entity_id`, `create_time`) COMMENT '按实体查变更',
  KEY `idx_operator` (`operator_id`, `create_time`)         COMMENT '按人查变更',
  KEY `idx_revision` (`revision_id`)                        COMMENT '按请求分组查',
  KEY `idx_create_time` (`create_time`)                     COMMENT '按时间范围查'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志表 (字段级变更追踪, 合规审计)';

-- 校验
SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_audit_log';