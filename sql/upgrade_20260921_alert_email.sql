-- ============================================================
-- DataMove 增量升级脚本
-- 日期: 2026-09-21
-- 内容: 失败告警支持「邮件」方式, sync_task 表新增 alert_email 字段
-- 幂等: 可重复执行, 已存在该字段时自动跳过
-- 执行: mysql -uroot -p datamove < upgrade_20260921_alert_email.sql
-- ============================================================

-- 客户端字符集未必是 utf8mb4 (容器内 mysql CLI 默认即为 latin1),
-- 不声明会把脚本里的中文注释按 latin1 二次编码写进库, 导致乱码
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `datamove_add_alert_email`;
DELIMITER $$
CREATE PROCEDURE `datamove_add_alert_email`()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'sync_task'
          AND COLUMN_NAME  = 'alert_email'
    ) THEN
        ALTER TABLE `sync_task`
            ADD COLUMN `alert_email` varchar(500) DEFAULT NULL
            COMMENT '告警邮箱,多个用英文逗号分隔' AFTER `dingtalk_webhook`;
    END IF;
END$$
DELIMITER ;

CALL `datamove_add_alert_email`();
DROP PROCEDURE IF EXISTS `datamove_add_alert_email`;

-- 校验
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME   = 'sync_task'
  AND COLUMN_NAME  = 'alert_email';
