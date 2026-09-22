-- ============================================================
-- DataMove 增量升级脚本
-- 日期: 2026-09-22
-- 内容: 同步日志支持分片
--   sync_task_log 新增 shard_no 列: 分片号(1 起, NULL=非分片任务)
--   同步日志列表/CSV 导出可按分片区分; 任务大盘展示各分片实时状态
-- 幂等: 可重复执行, 列已存在时自动跳过
-- 执行: mysql -uroot -p datamove < upgrade_20260922_shard_no.sql
-- ============================================================

SET NAMES utf8mb4;

-- 分片号 (MySQL 8 无 IF NOT EXISTS 加列语法, 用存储过程幂等处理)
DROP PROCEDURE IF EXISTS add_shard_no;
DELIMITER $$
CREATE PROCEDURE add_shard_no()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task_log' AND COLUMN_NAME = 'shard_no'
  ) THEN
    ALTER TABLE `sync_task_log`
      ADD COLUMN `shard_no` int(11) DEFAULT NULL
      COMMENT '分片号(1起,NULL=非分片任务)' AFTER `batch_no`;
  END IF;
END$$
DELIMITER ;

CALL add_shard_no();
DROP PROCEDURE IF EXISTS add_shard_no;

-- 校验
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task_log' AND COLUMN_NAME = 'shard_no';
