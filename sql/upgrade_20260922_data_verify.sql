-- ============================================================
-- DataMove 增量升级脚本
-- 日期: 2026-09-22
-- 内容: 数据差异对账 + 一键修复
--   1) sync_task 新增 ignore_fields 列: 校验时忽略比对的列(逗号分隔)
--      用途: update_time / 自动维护的时间戳等天然不一致的列, 不配忽略会刷出满屏假差异
--   2) sync_task_verify 表: 每次「数据校验」一条运行记录(进度/统计/修复结果)
--   3) sync_task_diff   表: 差异明细(缺失/不一致/多余), 是「一键同步缺失数据」的依据
-- 幂等: 可重复执行 (加列走存储过程判存在, 建表走 IF NOT EXISTS)
-- 执行: mysql -uroot -p datamove < upgrade_20260922_data_verify.sql
-- ============================================================

SET NAMES utf8mb4;

-- 数据校验忽略字段 (MySQL 8 无 IF NOT EXISTS 加列语法, 用存储过程幂等处理)
DROP PROCEDURE IF EXISTS add_ignore_fields;
DELIMITER $$
CREATE PROCEDURE add_ignore_fields()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'ignore_fields'
  ) THEN
    ALTER TABLE `sync_task`
      ADD COLUMN `ignore_fields` varchar(500) DEFAULT NULL
      COMMENT '数据校验忽略字段(逗号分隔, 比对时不比较这些列)' AFTER `shard_count`;
  END IF;
END$$
DELIMITER ;

CALL add_ignore_fields();
DROP PROCEDURE IF EXISTS add_ignore_fields;

-- 数据校验运行记录表
CREATE TABLE IF NOT EXISTS `sync_task_verify` (
  `id`                bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '校验ID',
  `task_id`           bigint(20)    NOT NULL COMMENT '任务ID (sync_task.id)',
  `task_name`         varchar(100)  DEFAULT NULL COMMENT '任务名称(快照)',
  `table_name`        varchar(100)  DEFAULT NULL COMMENT '表名',
  `source_name`       varchar(100)  DEFAULT NULL COMMENT '源数据源名称(快照)',
  `target_name`       varchar(100)  DEFAULT NULL COMMENT '目标数据源名称(快照)',
  `id_field`          varchar(100)  DEFAULT NULL COMMENT '比对主键列(目标列名)',
  `compare_fields`    text          COMMENT '参与比对的列(目标列名, 逗号分隔)',
  `ignore_fields`     varchar(500)  DEFAULT NULL COMMENT '本次忽略比对的列(目标列名, 逗号分隔)',
  `status`            varchar(20)   NOT NULL DEFAULT 'RUNNING' COMMENT '状态(RUNNING/COMPLETED/FAILED/STOP)',
  `checked_rows`      bigint(20)    NOT NULL DEFAULT 0 COMMENT '已比对行数(源+目标累计推进)',
  `source_rows`       bigint(20)    NOT NULL DEFAULT 0 COMMENT '源表扫描行数',
  `target_rows`       bigint(20)    NOT NULL DEFAULT 0 COMMENT '目标表扫描行数',
  `missing_rows`      bigint(20)    NOT NULL DEFAULT 0 COMMENT '缺失行数(源有目标无)',
  `mismatch_rows`     bigint(20)    NOT NULL DEFAULT 0 COMMENT '不一致行数(主键相同, 字段值不同)',
  `extra_rows`        bigint(20)    NOT NULL DEFAULT 0 COMMENT '多余行数(目标有源无, 仅统计不修复)',
  `diff_rows`         bigint(20)    NOT NULL DEFAULT 0 COMMENT '待修复差异行数(缺失+不一致)',
  `saved_diffs`       int(11)       NOT NULL DEFAULT 0 COMMENT '已落库差异明细条数',
  `truncated`         tinyint(1)    NOT NULL DEFAULT 0 COMMENT '差异明细是否被截断(1=实际差异超过落库上限, 只统计不落明细)',
  `repair_status`     varchar(20)   DEFAULT NULL COMMENT '修复状态(NULL=未修复 RUNNING/COMPLETED/FAILED/STOP)',
  `repair_total`      bigint(20)    NOT NULL DEFAULT 0 COMMENT '待修复行数',
  `repaired_rows`     bigint(20)    NOT NULL DEFAULT 0 COMMENT '已修复行数',
  `repair_failed_rows` bigint(20)   NOT NULL DEFAULT 0 COMMENT '修复失败行数',
  `last_key`          varchar(500)  DEFAULT NULL COMMENT '校验游标(已比对到的最大主键)',
  `cost_ms`           bigint(20)    NOT NULL DEFAULT 0 COMMENT '校验耗时(毫秒)',
  `repair_cost_ms`    bigint(20)    NOT NULL DEFAULT 0 COMMENT '修复耗时(毫秒)',
  `error_msg`         text          COMMENT '异常信息',
  `start_time`        datetime      DEFAULT NULL COMMENT '校验开始时间',
  `end_time`          datetime      DEFAULT NULL COMMENT '校验结束时间',
  `repair_start_time` datetime      DEFAULT NULL COMMENT '修复开始时间',
  `repair_end_time`   datetime      DEFAULT NULL COMMENT '修复结束时间',
  `create_time`       datetime      DEFAULT NULL,
  `update_time`       datetime      DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据校验运行记录表';

-- 差异明细表
CREATE TABLE IF NOT EXISTS `sync_task_diff` (
  `id`            bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '差异ID',
  `verify_id`     bigint(20)    NOT NULL COMMENT '校验ID (sync_task_verify.id)',
  `task_id`       bigint(20)    NOT NULL COMMENT '任务ID',
  `diff_type`     varchar(20)   NOT NULL COMMENT '差异类型(MISSING=源有目标无 MISMATCH=字段不一致 EXTRA=目标有源无)',
  `pk_value`      varchar(500)  DEFAULT NULL COMMENT '主键值(复合主键用 | 连接)',
  `diff_fields`   text          COMMENT '值不一致的列(MISMATCH: 逗号分隔)',
  `source_row`    text          COMMENT '源行快照(JSON)',
  `target_row`    text          COMMENT '目标行快照(JSON)',
  `repair_status` varchar(20)   NOT NULL DEFAULT 'PENDING' COMMENT '修复状态(PENDING/REPAIRED/FAILED/SKIPPED)',
  `repair_error`  varchar(1000) DEFAULT NULL COMMENT '修复失败原因',
  `repair_time`   datetime      DEFAULT NULL COMMENT '修复时间',
  `create_time`   datetime      DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_verify_id` (`verify_id`),
  KEY `idx_verify_type` (`verify_id`, `diff_type`),
  KEY `idx_verify_repair` (`verify_id`, `repair_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据校验差异明细表';

-- 校验
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'ignore_fields';

SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('sync_task_verify', 'sync_task_diff');
