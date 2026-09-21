-- ============================================================
-- DataMove 增量升级脚本
-- 日期: 2026-09-21
-- 内容: 同步任务支持「字段不同」映射同步
--   新增 sync_task_field_mapping 表, 存 task_id -> (源字段, 目标字段) 配对
--   FULL 任务与 INCR 增量都支持; 不配置映射时按字段名同名同步 (向后兼容)
-- 幂等: 可重复执行, 已存在该表时自动跳过
-- 执行: mysql -uroot -p datamove < upgrade_20260921_field_mapping.sql
-- ============================================================

SET NAMES utf8mb4;

-- 同步任务-字段映射表
CREATE TABLE IF NOT EXISTS `sync_task_field_mapping` (
  `id`           bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id`      bigint(20)   NOT NULL COMMENT '任务ID (sync_task.id)',
  `source_field` varchar(100) NOT NULL COMMENT '源表字段名',
  `target_field` varchar(100) NOT NULL COMMENT '目标表字段名',
  `sort_no`      int(11)      NOT NULL DEFAULT 0 COMMENT '顺序 (SELECT/INSERT 列表顺序)',
  `create_time`  datetime     DEFAULT NULL,
  `update_time`  datetime     DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_src_f` (`task_id`, `source_field`),
  KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务字段映射表 - 源字段 -> 目标字段 一对一重命名';

-- 校验
SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task_field_mapping';