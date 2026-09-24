-- 2026-09-24: 授权管理菜单挪到「系统管理」目录下 (与用户管理同级)
-- 原: 顶级菜单「用户管理」 + 数据同步下的「授权管理」
-- 现: 顶级目录「系统管理」 -> 子菜单「用户管理」「授权管理」
-- 幂等: 重复执行不会报错, 也不会产生重复数据
-- ⚠️ 执行时必须指定 utf8mb4 客户端字符集, 否则中文菜单名会被双重编码:
--    mysql --default-character-set=utf8mb4 -uroot -p datamove < upgrade_20260924_menu_license.sql

-- 1) 105 由顶级菜单「用户管理」改为顶级目录「系统管理」
UPDATE `sys_menu`
SET `menu_name` = '系统管理', `order_num` = 6, `path` = 'system', `component` = NULL,
    `menu_type` = 'M', `icon` = 'tree'
WHERE `menu_id` = 105 AND `menu_type` = 'C';

-- 2) 新增「用户管理」子菜单 (沿用原 105 的组件与权限标识)
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`,
  `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`)
SELECT 107, '用户管理', 105, 1, 'user', 'system/user/index', '1', 'C', '0', '0', 'system:user:list', 'user'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 107);

-- 3) 「授权管理」(104) 挂到系统管理目录下
UPDATE `sys_menu` SET `parent_id` = 105, `order_num` = 2
WHERE `menu_id` = 104 AND (`parent_id` <> 105 OR `order_num` <> 2);

-- 4) admin 角色补上新增的 107 菜单 (幂等)
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 107 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 107);
