-- =====================================================================
-- 升级脚本: 「模板市场」移到「同步任务」上面
-- 日期: 2026-09-26
--
-- 数据同步目录下的新顺序:
--   数据源管理(1) > 模板市场(2) > 同步任务(3) > 任务大盘(4) >
--   同步日志(5)   > 审计日志(6) > 告警中心(7)
-- 理由: 模板是"创建任务的入口", 排在任务列表前面更顺手 —— 先选模板建任务, 再进任务列表看
-- =====================================================================

UPDATE `sys_menu` SET `order_num` = 1 WHERE `menu_id` = 101; -- 数据源管理
UPDATE `sys_menu` SET `order_num` = 2 WHERE `menu_id` = 110; -- 模板市场
UPDATE `sys_menu` SET `order_num` = 3 WHERE `menu_id` = 102; -- 同步任务
UPDATE `sys_menu` SET `order_num` = 4 WHERE `menu_id` = 305; -- 任务大盘
UPDATE `sys_menu` SET `order_num` = 5 WHERE `menu_id` = 103; -- 同步日志
UPDATE `sys_menu` SET `order_num` = 6 WHERE `menu_id` = 106; -- 审计日志
UPDATE `sys_menu` SET `order_num` = 7 WHERE `menu_id` = 109; -- 告警中心
