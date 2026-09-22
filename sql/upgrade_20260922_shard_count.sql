-- ============================================================
-- DataMove 增量升级脚本
-- 日期: 2026-09-22
-- 内容: 大数据分片并行同步
--   sync_task 新增 shard_count 列: 并行分片数
--   1 = 单线程串行 (默认, 原行为); >1 = FULL+ID 模式按主键区间分片并行
-- 幂等: 可重复执行, 列已存在时自动跳过
-- 执行: mysql -uroot -p datamove < upgrade_20260922_shard_count.sql
-- ============================================================

SET NAMES utf8mb4;

-- 并行分片数 (MySQL 8 无 IF NOT EXISTS 加列语法, 用存储过程幂等处理)
DROP PROCEDURE IF EXISTS add_shard_count;
DELIMITER $$
CREATE PROCEDURE add_shard_count()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'shard_count'
  ) THEN
    ALTER TABLE `sync_task`
      ADD COLUMN `shard_count` int(11) NOT NULL DEFAULT 1
      COMMENT '并行分片数(1=串行, >1=按主键区间分片并行, 仅FULL+ID模式)' AFTER `batch_size`;
  END IF;
END$$
DELIMITER ;

CALL add_shard_count();
DROP PROCEDURE IF EXISTS add_shard_count;

-- 校验
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'shard_count';
