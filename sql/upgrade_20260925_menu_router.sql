-- =====================================================================
-- 升级脚本: 菜单补齐 + 路由路径修正 (为「按权限动态渲染菜单」做准备)
-- 日期: 2026-09-25
--
-- 背景:
--   sys_menu 里只有早期 6 个页面(数据源/任务/日志/审计/用户/授权),
--   后来新增的首页 / 数据中心 / SQL工作台 / SQL执行日志 / 任务大盘 都没进来,
--   而且老菜单的 path 存的是相对路径('datasource'), 前端路由却是 '/sync/datasource'。
--   要做动态菜单, 必须先把菜单数据和真实前端路由对齐。
--
-- 约定 (后端 SysPermissionService.menusOfUser 依赖这两条):
--   1. path 存完整前端路由, 如 '/sync/task'; 目录(M)的 path 不用, 置空
--   2. perms 为空 = 登录即可见(首页 / 目录); perms 非空 = 需要被角色授权才可见
--
-- 幂等: 菜单按 menu_id 主键判重(INSERT IGNORE), 授权按 role_id+menu_id 判重
-- =====================================================================

-- ---------- 1) 老菜单的 path 改成完整前端路由 ----------
UPDATE `sys_menu` SET `path` = '/sync/datasource' WHERE `menu_id` = 101;
UPDATE `sys_menu` SET `path` = '/sync/task'        WHERE `menu_id` = 102;
UPDATE `sys_menu` SET `path` = '/sync/log'         WHERE `menu_id` = 103 AND `order_num` = 3;
UPDATE `sys_menu` SET `path` = '/sync/audit'       WHERE `menu_id` = 106;
UPDATE `sys_menu` SET `path` = '/system/user'      WHERE `menu_id` = 107;
-- 授权管理在侧栏归到「系统管理」目录下, 但前端路由一直在 /sync 下, 以路由为准
UPDATE `sys_menu` SET `path` = '/sync/license'     WHERE `menu_id` = 104;
-- 目录不参与路由跳转, path 清空
UPDATE `sys_menu` SET `path` = '' WHERE `menu_id` IN (100, 105);

-- ---------- 2) 补齐新增页面 ----------
-- 首页: perms 留空 -> 所有登录用户可见
INSERT IGNORE INTO `sys_menu`
  (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`)
VALUES
  (300, '首页',         0,   0, '/index',    'dashboard/index',  '1', 'C', '0', '0', '',                    'el-icon-house'),
-- 数据工作台目录 + 三个子页面
  (301, '数据工作台',   0,   1, '',          NULL,               '1', 'M', '0', '0', '',                    'el-icon-edit-outline'),
  (302, '数据中心',     301, 1, '/browse',   'sync/browse',      '1', 'C', '0', '0', 'sync:browse:list',   'el-icon-search'),
  (303, 'SQL 工作台',   301, 2, '/sql',      'sync/sql',         '1', 'C', '0', '0', 'sync:sql:list',      'el-icon-monitor'),
  (304, 'SQL 执行日志', 301, 3, '/sql-log',  'sync/sqlLog',      '1', 'C', '0', '0', 'sync:sql:log:list',  'el-icon-tickets'),
-- 任务大盘挂在「数据同步」目录下, 排在同步日志之前
  (305, '任务大盘',     100, 3, '/sync/dashboard', 'sync/dashboard', '1', 'C', '0', '0', 'sync:dashboard:list', 'el-icon-odometer');

-- 侧栏顺序: 首页(0) > 数据工作台(1) > 数据同步(2) > 审计日志(5) > 系统管理(6)
UPDATE `sys_menu` SET `order_num` = 2 WHERE `menu_id` = 100;
UPDATE `sys_menu` SET `order_num` = 4 WHERE `menu_id` = 103;  -- 同步日志排到任务大盘之后
UPDATE `sys_menu` SET `order_num` = 5 WHERE `menu_id` = 106;
UPDATE `sys_menu` SET `order_num` = 6 WHERE `menu_id` = 105;

-- ---------- 3) 授权 ----------
-- 超级管理员(role_id=1): 全部菜单 (admin 角色本身就有 *:*:*, 这里显式补齐 role_menu,
-- 保证哪天去掉 admin 短路逻辑后菜单也不丢)
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, menu_id FROM `sys_menu` WHERE `menu_type` IN ('M', 'C');

-- 普通操作员(role_id=2): 首页 + 数据工作台 + 数据集成中心, 不含审计日志与系统管理
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 2, menu_id FROM `sys_menu`
WHERE `menu_id` IN (300, 301, 302, 303, 304, 100, 101, 102, 103, 305);
