-- =====================================================================
-- 升级脚本: 用户自助注册 + 数据归属隔离 (owner_id)
-- 日期: 2026-09-28
--
-- 背景:
--   之前系统只有一个 admin 账号, 所有数据源 / 任务 / 日志都是"公共"的,
--   多人共用一套账号, 谁改了什么、谁能看什么完全分不清。
--   这里引入「归属用户」: 注册用户只能看到自己创建的数据, 互相不可见。
--
-- 设计 (三处配合, 缺一不可):
--   1. 写入: MetaHandlerConfig 从登录上下文取当前用户, 自动填 owner_id
--   2. 查询: MyBatis-Plus 多租户拦截器自动追加 owner_id = #{当前用户}
--          超管(admin 角色 / user_id=1)不加条件, 仍看全量
--   3. 存量: 历史数据全部回填给 admin(user_id=1), 老数据不丢也不外泄
--
-- 隔离范围: sync_* 业务表 (不含 sync_license —— 那是平台级授权, 不属于个人数据)
--
-- 注意: 本脚本重复执行时 ALTER TABLE 会报 duplicate column, 属正常现象, 忽略即可
-- =====================================================================

-- ---------- 1. 业务表增加归属字段 ----------
ALTER TABLE `sync_datasource`          ADD COLUMN `owner_id` bigint(20) DEFAULT NULL COMMENT '归属用户ID(数据隔离)', ADD KEY `idx_owner` (`owner_id`);
ALTER TABLE `sync_task`                ADD COLUMN `owner_id` bigint(20) DEFAULT NULL COMMENT '归属用户ID(数据隔离)', ADD KEY `idx_owner` (`owner_id`);
ALTER TABLE `sync_task_field_mapping`  ADD COLUMN `owner_id` bigint(20) DEFAULT NULL COMMENT '归属用户ID(数据隔离)', ADD KEY `idx_owner` (`owner_id`);
ALTER TABLE `sync_task_progress`       ADD COLUMN `owner_id` bigint(20) DEFAULT NULL COMMENT '归属用户ID(数据隔离)', ADD KEY `idx_owner` (`owner_id`);
ALTER TABLE `sync_task_log`            ADD COLUMN `owner_id` bigint(20) DEFAULT NULL COMMENT '归属用户ID(数据隔离)', ADD KEY `idx_owner` (`owner_id`);
ALTER TABLE `sync_task_run`            ADD COLUMN `owner_id` bigint(20) DEFAULT NULL COMMENT '归属用户ID(数据隔离)', ADD KEY `idx_owner` (`owner_id`);
ALTER TABLE `sync_task_verify`         ADD COLUMN `owner_id` bigint(20) DEFAULT NULL COMMENT '归属用户ID(数据隔离)', ADD KEY `idx_owner` (`owner_id`);
ALTER TABLE `sync_task_diff`           ADD COLUMN `owner_id` bigint(20) DEFAULT NULL COMMENT '归属用户ID(数据隔离)', ADD KEY `idx_owner` (`owner_id`);
ALTER TABLE `sync_sql_log`             ADD COLUMN `owner_id` bigint(20) DEFAULT NULL COMMENT '归属用户ID(数据隔离)', ADD KEY `idx_owner` (`owner_id`);
ALTER TABLE `sync_sql_favorite`        ADD COLUMN `owner_id` bigint(20) DEFAULT NULL COMMENT '归属用户ID(数据隔离)', ADD KEY `idx_owner` (`owner_id`);
ALTER TABLE `sync_alert_record`        ADD COLUMN `owner_id` bigint(20) DEFAULT NULL COMMENT '归属用户ID(数据隔离)', ADD KEY `idx_owner` (`owner_id`);
ALTER TABLE `sync_audit_log`           ADD COLUMN `owner_id` bigint(20) DEFAULT NULL COMMENT '归属用户ID(数据隔离)', ADD KEY `idx_owner` (`owner_id`);
ALTER TABLE `sync_canal_position`      ADD COLUMN `owner_id` bigint(20) DEFAULT NULL COMMENT '归属用户ID(数据隔离)', ADD KEY `idx_owner` (`owner_id`);

-- ---------- 2. 存量数据回填给 admin (user_id=1) ----------
-- 历史数据都是 admin 建的, 回填后 admin 照常可见, 新注册用户看不到
UPDATE `sync_datasource`         SET `owner_id` = 1 WHERE `owner_id` IS NULL;
UPDATE `sync_task`               SET `owner_id` = 1 WHERE `owner_id` IS NULL;
UPDATE `sync_task_field_mapping` SET `owner_id` = 1 WHERE `owner_id` IS NULL;
UPDATE `sync_task_progress`      SET `owner_id` = 1 WHERE `owner_id` IS NULL;
UPDATE `sync_task_log`           SET `owner_id` = 1 WHERE `owner_id` IS NULL;
UPDATE `sync_task_run`           SET `owner_id` = 1 WHERE `owner_id` IS NULL;
UPDATE `sync_task_verify`        SET `owner_id` = 1 WHERE `owner_id` IS NULL;
UPDATE `sync_task_diff`          SET `owner_id` = 1 WHERE `owner_id` IS NULL;
UPDATE `sync_sql_log`            SET `owner_id` = 1 WHERE `owner_id` IS NULL;
UPDATE `sync_sql_favorite`       SET `owner_id` = 1 WHERE `owner_id` IS NULL;
UPDATE `sync_alert_record`       SET `owner_id` = 1 WHERE `owner_id` IS NULL;
UPDATE `sync_audit_log`          SET `owner_id` = 1 WHERE `owner_id` IS NULL;
UPDATE `sync_canal_position`     SET `owner_id` = 1 WHERE `owner_id` IS NULL;

-- ---------- 3. 注册用户默认角色: 普通用户 (role_key=common) ----------
-- 现有角色:
--   role_id=1 admin     超级管理员  —— 看全部数据(隔离对它不生效)
--   role_id=2 operator  普通操作员  —— 早期只读角色, 不用于注册
--   role_id=3 common    普通用户    —— 本次新增, 注册用户自动获得
INSERT IGNORE INTO `sys_role` (`role_id`, `role_name`, `role_key`, `role_sort`, `status`, `create_time`)
VALUES (3, '普通用户', 'common', 3, '0', NOW());

-- 菜单 + 按钮权限: 数据同步全部业务功能 (可建/改/删自己的数据源与任务),
-- 但不给「系统管理」下的东西: 排除「系统管理」(105) 目录、
-- 「授权管理」(sync:license:*)、「审计日志」(sync:audit:*)、「登录日志」(sync:loginlog:*)
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 3, m.`menu_id`
FROM `sys_menu` m
WHERE m.`status` = '0'
  AND m.`menu_id` <> 105
  AND (
        (m.`perms` LIKE 'sync:%'
            AND m.`perms` NOT LIKE 'sync:license:%'
            AND m.`perms` NOT LIKE 'sync:audit:%'
            AND m.`perms` NOT LIKE 'sync:loginlog:%')
     OR ((m.`perms` IS NULL OR m.`perms` = '') AND m.`menu_type` IN ('M', 'C'))
  );
