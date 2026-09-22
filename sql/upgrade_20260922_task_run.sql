-- ============================================================
-- DataMove 增量升级脚本
-- 日期: 2026-09-22
-- 内容: 任务运行历史表 sync_task_run
--   每次启动任务写一条记录(状态 RUNNING), 任务 完成/失败/停止/暂停 时回填
--   结束时间、耗时、成功/失败行数、批次数、平均速率; 大盘「运行历史」按此表查询,
--   支持 分页筛选 / 按天趋势 / CSV 导出 / 按条件清理
-- 幂等: 可重复执行 (CREATE TABLE IF NOT EXISTS)
-- 执行: mysql -uroot -p datamove < upgrade_20260922_task_run.sql
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `sync_task_run` (
  `id`                bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '运行ID',
  `task_id`           bigint(20)    NOT NULL COMMENT '任务ID',
  `task_name`         varchar(100)  DEFAULT NULL COMMENT '任务名称(快照)',
  `task_type`         varchar(20)   DEFAULT NULL COMMENT '任务类型(FULL/INCR/DDL)',
  `sync_mode`         varchar(20)   DEFAULT NULL COMMENT '同步模式(ID/TIME/BINLOG/DDL)',
  `table_name`        varchar(100)  DEFAULT NULL COMMENT '表名',
  `source_name`       varchar(100)  DEFAULT NULL COMMENT '源数据源名称',
  `target_name`       varchar(100)  DEFAULT NULL COMMENT '目标数据源名称',
  `shard_count`       int(11)       DEFAULT 1    COMMENT '并行分片数',
  `status`            varchar(20)   NOT NULL DEFAULT 'RUNNING' COMMENT '状态(RUNNING/COMPLETED/FAILED/PAUSE/STOP)',
  `start_time`        datetime      DEFAULT NULL COMMENT '开始时间',
  `end_time`          datetime      DEFAULT NULL COMMENT '结束时间',
  `cost_seconds`      bigint(20)    DEFAULT 0    COMMENT '本次运行耗时(秒)',
  `total_rows`        bigint(20)    DEFAULT 0    COMMENT '本次同步行数(成功+失败)',
  `success_rows`      bigint(20)    DEFAULT 0    COMMENT '本次成功行数',
  `failed_rows`       bigint(20)    DEFAULT 0    COMMENT '本次失败行数',
  `batch_count`       int(11)       DEFAULT 0    COMMENT '批次数',
  `avg_rows_per_sec`  decimal(16,1) DEFAULT 0.0  COMMENT '平均速率(行/秒)',
  `error_msg`         text          COMMENT '异常信息',
  `create_time`       datetime      DEFAULT NULL,
  `update_time`       datetime      DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_start_time` (`start_time`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务运行历史表';

-- 校验
SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task_run';
