-- =====================================================================
-- 升级脚本: 用户管理的按钮级权限 (配合「用户授权」功能)
-- 日期: 2026-09-25
--
-- 说明:
--   sys_menu 里原来只有「用户管理」这个菜单 (menu_id=107, perms=system:user:list),
--   没有增删改/授权的按钮权限 —— 用户授权后拿到的权限集合里就没有这些标识,
--   前端按钮无法按权限显隐。这里补 4 条 F(按钮) 型菜单, 并授予超级管理员角色。
--
--   权限标识:
--     system:user:add    新增用户
--     system:user:edit   编辑用户 / 启停
--     system:user:remove 删除用户
--     system:user:grant  给用户分配角色 (授权)
--
--   注意: 操作员(role_id=2) 刻意不授予, 普通操作员不应能改账号。
--   幂等: 按 perms 判重, 重复执行不会产生重复数据
-- =====================================================================

INSERT INTO `sys_menu`
  (`menu_name`, `parent_id`, `order_num`, `path`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`)
SELECT t.* FROM (
  SELECT '用户新增' menu_name, 107 parent_id, 1 order_num, '' path, '1' is_frame, 'F' menu_type, '0' visible, '0' status, 'system:user:add'    perms, '#' icon
  UNION ALL SELECT '用户修改', 107, 2, '', '1', 'F', '0', '0', 'system:user:edit',   '#'
  UNION ALL SELECT '用户删除', 107, 3, '', '1', 'F', '0', '0', 'system:user:remove', '#'
  UNION ALL SELECT '用户授权', 107, 4, '', '1', 'F', '0', '0', 'system:user:grant',  '#'
) t
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` m WHERE m.perms = t.perms);

-- 授予超级管理员(role_id=1)
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, menu_id FROM `sys_menu`
WHERE perms IN ('system:user:add', 'system:user:edit', 'system:user:remove', 'system:user:grant')
  AND menu_id NOT IN (SELECT menu_id FROM `sys_role_menu` WHERE role_id = 1);
