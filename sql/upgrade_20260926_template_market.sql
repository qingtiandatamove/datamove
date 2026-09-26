-- =====================================================================
-- 升级脚本: 迁移任务模板市场 (菜单与按钮权限)
-- 日期: 2026-09-26
--
-- 说明:
--   模板本身是内置的(代码中的 TaskTemplateRegistry), 不需要建表;
--   这里只加菜单与权限, 让页面能被授权访问。
--
-- 权限标识:
--   sync:template:list  查看模板市场
--   sync:template:apply 套用模板(会创建任务)
-- =====================================================================

INSERT IGNORE INTO `sys_menu`
  (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`)
VALUES
  (110, '模板市场', 100, 7, '/sync/template', 'sync/template', '1', 'C', '0', '0', 'sync:template:list', 'el-icon-magic-stick');

INSERT INTO `sys_menu`
  (`menu_name`, `parent_id`, `order_num`, `path`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`)
SELECT t.* FROM (
  SELECT '模板套用' menu_name, 110 parent_id, 1 order_num, '' path, '1' is_frame, 'F' menu_type, '0' visible, '0' status, 'sync:template:apply' perms, '#' icon
) t
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` m WHERE m.perms = t.perms);

-- 授予超级管理员
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, menu_id FROM `sys_menu`
WHERE perms IN ('sync:template:list', 'sync:template:apply') OR menu_id = 110;
