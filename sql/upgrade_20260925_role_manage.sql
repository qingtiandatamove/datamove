-- =====================================================================
-- 升级脚本: 角色管理页面及其按钮权限
-- 日期: 2026-09-25
--
-- 背景:
--   之前只有「用户管理」(给人分配角色), 角色本身的菜单权限只能直接改 sys_role_menu。
--   这里补上「系统管理 -> 角色管理」页面, 可以在界面上勾选角色能看到哪些菜单/按钮。
--
-- 权限标识:
--   system:role:list   查看角色列表
--   system:role:add    新增角色
--   system:role:edit   修改角色 / 启停
--   system:role:remove 删除角色
--   system:role:grant  给角色勾选菜单权限
--
-- 幂等: 菜单按 menu_id 判重(INSERT IGNORE), 授权按 role_id+menu_id 判重
-- =====================================================================

-- ---------- 1) 角色管理菜单(挂在「系统管理」目录下, 排在授权管理之后) ----------
INSERT IGNORE INTO `sys_menu`
  (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`)
VALUES
  (108, '角色管理', 105, 3, '/system/role', 'system/role', '1', 'C', '0', '0', 'system:role:list', 'el-icon-s-custom');

-- ---------- 2) 按钮权限 ----------
INSERT INTO `sys_menu`
  (`menu_name`, `parent_id`, `order_num`, `path`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`)
SELECT t.* FROM (
  SELECT '角色新增' menu_name, 108 parent_id, 1 order_num, '' path, '1' is_frame, 'F' menu_type, '0' visible, '0' status, 'system:role:add'    perms, '#' icon
  UNION ALL SELECT '角色修改', 108, 2, '', '1', 'F', '0', '0', 'system:role:edit',   '#'
  UNION ALL SELECT '角色删除', 108, 3, '', '1', 'F', '0', '0', 'system:role:remove', '#'
  UNION ALL SELECT '角色授权', 108, 4, '', '1', 'F', '0', '0', 'system:role:grant',  '#'
) t
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` m WHERE m.perms = t.perms);

-- ---------- 3) 授予超级管理员 ----------
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, menu_id FROM `sys_menu`
WHERE perms IN ('system:role:list', 'system:role:add', 'system:role:edit', 'system:role:remove', 'system:role:grant')
   OR menu_id = 108;
