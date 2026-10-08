-- ============================================================
-- DataMove 增量升级脚本
-- 日期: 2026-10-08
-- 内容: AI 能力扩展 —— 字段映射推荐 / 失败任务诊断 / 自然语言生成 SQL
-- 幂等: 权限按 perms 判重, 授权用 INSERT IGNORE, 可重复执行
-- 执行: mysql -uroot -p --default-character-set=utf8mb4 datamove < upgrade_20261008_ai_features.sql
--       注意: 必须带 --default-character-set=utf8mb4, 否则中文菜单名会变乱码
-- ============================================================
SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. 按钮级权限 (挂在 111 AI 配置助手 下面)
--    这三个都是「只读建议类」能力: 不建任务、不改库,
--    所以权限单独拆开, 只给到超管(1)和注册用户(3), 操作员(2)维持原样
-- ------------------------------------------------------------
INSERT INTO `sys_menu`
  (`menu_name`,`parent_id`,`order_num`,`path`,`is_frame`,`menu_type`,`visible`,`status`,`perms`,`icon`)
SELECT t.* FROM (
  SELECT 'AI 字段映射推荐' menu_name, 111 parent_id, 1 order_num, '' path, '1' is_frame, 'F' menu_type, '0' visible, '0' status, 'sync:ai:mapping'  perms, '#' icon
  UNION ALL SELECT 'AI 失败任务诊断', 111, 2, '', '1', 'F', '0', '0', 'sync:ai:diagnose', '#'
  UNION ALL SELECT 'AI 生成SQL',     111, 3, '', '1', 'F', '0', '0', 'sync:ai:sql',      '#'
) t
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` m WHERE m.perms = t.perms);

-- ------------------------------------------------------------
-- 2. 授权: 超级管理员(1) + 注册用户(3)
-- ------------------------------------------------------------
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`role_id`, m.`menu_id`
FROM `sys_role` r
JOIN `sys_menu` m ON m.`perms` IN ('sync:ai:mapping','sync:ai:diagnose','sync:ai:sql')
WHERE r.`role_id` IN (1, 3);

-- ------------------------------------------------------------
-- 校验用 (可选)
--   SELECT r.role_id, r.role_key, m.perms
--     FROM sys_role_menu rm
--     JOIN sys_role r ON r.role_id = rm.role_id
--     JOIN sys_menu m ON m.menu_id = rm.menu_id
--    WHERE m.perms LIKE 'sync:ai:%' ORDER BY r.role_id, m.perms;
--   -- 期望: role 1 与 role 3 各有 sync:ai:parse / mapping / diagnose / sql
-- ------------------------------------------------------------
