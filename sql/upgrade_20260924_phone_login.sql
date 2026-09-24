-- ============================================================
-- DataMove 增量升级脚本
-- 日期: 2026-09-24
-- 内容: 短信验证码登录支持 — sys_user.phonenumber 加索引
--       (phonenumber 字段在 datamove.sql 已存在, 这里只补索引)
-- 幂等: 可重复执行, 已存在该索引时自动跳过
-- 执行: mysql -uroot -p datamove < upgrade_20260924_phone_login.sql
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. 给 sys_user.phonenumber 加索引
--    短信登录时 AuthServiceImpl 按 phonenumber 查用户, 没索引全表;
--    用普通索引即可, 不加 UNIQUE 以兼容历史脏数据 (多个用户空手机号共存)
-- ------------------------------------------------------------
DROP PROCEDURE IF EXISTS `datamove_idx_phone`;
DELIMITER $$
CREATE PROCEDURE `datamove_idx_phone`()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'sys_user'
          AND INDEX_NAME   = 'idx_phonenumber'
    ) THEN
        ALTER TABLE `sys_user`
            ADD INDEX `idx_phonenumber` (`phonenumber`);
    END IF;
END$$
DELIMITER ;

CALL `datamove_idx_phone`();
DROP PROCEDURE IF EXISTS `datamove_idx_phone`;

-- ------------------------------------------------------------
-- 2. (可选) 给 admin 用户绑手机号, 方便本地联调短信登录
--    生产环境按真实情况改; 不想执行就把这一段 SQL 注释掉
-- ------------------------------------------------------------
-- UPDATE `sys_user`
-- SET `phonenumber` = '13800138000'
-- WHERE `user_name` = 'admin'
--   AND (`phonenumber` IS NULL OR `phonenumber` = '');

-- 校验
SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME   = 'sys_user'
  AND INDEX_NAME   = 'idx_phonenumber';

SELECT user_id, user_name, nick_name, phonenumber, status, del_flag
FROM sys_user
ORDER BY user_id;