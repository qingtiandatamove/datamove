-- =====================================================================
-- 升级脚本: 菜单图标换成贴切的 Element UI 图标
-- 日期: 2026-09-25
--
-- 背景:
--   sys_menu.icon 里残留早期 RuoYi 的自定义图标名(guide / dict / build / log / tree / user / valid-code),
--   这些名字在当前前端没有对应图标, 会被 layout 的 iconOf() 统一降级成 el-icon-menu,
--   结果侧栏里好几个菜单长得一模一样。这里全部换成 Element UI 自带的 el-icon-*。
--
-- 选图标的思路(按"这个页面是干什么的"选, 不是随便挑好看的):
--   首页       -> s-home      家的实心版, 侧栏第一项更有存在感
--   数据工作台 -> s-platform  操作台/工作台
--   数据中心   -> s-grid      表格数据
--   SQL 工作台 -> monitor     终端/控制台
--   SQL执行日志-> tickets     一条条执行记录
--   数据同步   -> connection  两个库连起来(目录)
--   数据源管理 -> coin        数据库(Element 里 coin 就是那个圆柱体数据库)
--   同步任务   -> s-order     任务单
--   任务大盘   -> data-board  看板
--   同步日志   -> document    日志文档
--   审计日志   -> view        审计=查看留痕
--   系统管理   -> setting     (目录)
--   用户管理   -> user
--   授权管理   -> s-claim     徽章/证书(License), 与"重置密码"的 key 区分开
--   角色管理   -> s-custom    角色/人群
-- =====================================================================

UPDATE `sys_menu` SET `icon` = 'el-icon-s-home'    WHERE `menu_id` = 300; -- 首页
UPDATE `sys_menu` SET `icon` = 'el-icon-s-platform' WHERE `menu_id` = 301; -- 数据工作台
UPDATE `sys_menu` SET `icon` = 'el-icon-s-grid'    WHERE `menu_id` = 302; -- 数据中心
UPDATE `sys_menu` SET `icon` = 'el-icon-monitor'   WHERE `menu_id` = 303; -- SQL 工作台
UPDATE `sys_menu` SET `icon` = 'el-icon-tickets'   WHERE `menu_id` = 304; -- SQL 执行日志
UPDATE `sys_menu` SET `icon` = 'el-icon-connection' WHERE `menu_id` = 100; -- 数据同步(目录)
UPDATE `sys_menu` SET `icon` = 'el-icon-coin'      WHERE `menu_id` = 101; -- 数据源管理
UPDATE `sys_menu` SET `icon` = 'el-icon-s-order'   WHERE `menu_id` = 102; -- 同步任务
UPDATE `sys_menu` SET `icon` = 'el-icon-data-board' WHERE `menu_id` = 305; -- 任务大盘
UPDATE `sys_menu` SET `icon` = 'el-icon-document'  WHERE `menu_id` = 103; -- 同步日志
UPDATE `sys_menu` SET `icon` = 'el-icon-view'      WHERE `menu_id` = 106; -- 审计日志
UPDATE `sys_menu` SET `icon` = 'el-icon-setting'   WHERE `menu_id` = 105; -- 系统管理(目录)
UPDATE `sys_menu` SET `icon` = 'el-icon-user'      WHERE `menu_id` = 107; -- 用户管理
UPDATE `sys_menu` SET `icon` = 'el-icon-s-claim'   WHERE `menu_id` = 104; -- 授权管理(License)
UPDATE `sys_menu` SET `icon` = 'el-icon-s-custom'  WHERE `menu_id` = 108; -- 角色管理
