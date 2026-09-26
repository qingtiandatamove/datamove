-- =====================================================================
-- 升级脚本: AI 任务配置助手 (菜单与按钮权限)
-- 日期: 2026-09-26
--
-- 说明:
--   助手本身不需要建表。是否调用大模型由配置决定 (sync.ai.api-key),
--   没配 Key 时自动降级为本地规则解析 —— 菜单与权限照常可用。
--
-- 权限标识:
--   sync:ai:parse  解析自然语言(生成配置草稿)
--   sync:ai:apply  确认草稿并创建 / 修改任务
--
-- 执行方式 (务必带 utf8mb4, 否则中文菜单名会存成乱码):
--   mysql --default-character-set=utf8mb4 -uroot -p datamove < sql/upgrade_20260926_ai_assistant.sql
-- =====================================================================

INSERT IGNORE INTO `sys_menu`
  (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`)
VALUES
  (111, 'AI 配置助手', 100, 1, '/sync/ai', 'sync/ai', '1', 'C', '0', '0', 'sync:ai:parse', 'el-icon-edit-outline');

INSERT INTO `sys_menu`
  (`menu_name`, `parent_id`, `order_num`, `path`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`)
SELECT t.* FROM (
  SELECT 'AI 生成任务' menu_name, 111 parent_id, 1 order_num, '' path, '1' is_frame, 'F' menu_type, '0' visible, '0' status, 'sync:ai:apply' perms, '#' icon
) t
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` m WHERE m.perms = t.perms);

-- 排在「数据源管理」前面: 它是建任务的入口
UPDATE `sys_menu` SET `order_num` = 2 WHERE `menu_id` = 101; -- 数据源管理
UPDATE `sys_menu` SET `order_num` = 3 WHERE `menu_id` = 110; -- 模板市场
UPDATE `sys_menu` SET `order_num` = 4 WHERE `menu_id` = 102; -- 同步任务
UPDATE `sys_menu` SET `order_num` = 5 WHERE `menu_id` = 305; -- 任务大盘
UPDATE `sys_menu` SET `order_num` = 6 WHERE `menu_id` = 103; -- 同步日志
UPDATE `sys_menu` SET `order_num` = 7 WHERE `menu_id` = 106; -- 审计日志
UPDATE `sys_menu` SET `order_num` = 8 WHERE `menu_id` = 109; -- 告警中心

-- 授予超级管理员
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, menu_id FROM `sys_menu`
WHERE perms IN ('sync:ai:parse', 'sync:ai:apply') OR menu_id = 111;
