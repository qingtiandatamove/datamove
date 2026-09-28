-- ============================================================
-- DataMove 增量升级脚本
-- 日期: 2026-09-28
-- 内容: 登录日志 —— 记录每次登录的账号/时间/IP/地点/浏览器/系统/成功失败
-- 幂等: 建表走 IF NOT EXISTS, 菜单按 menu_id / perms 判重, 可重复执行
-- 执行: mysql -uroot -p --default-character-set=utf8mb4 datamove < upgrade_20260928_login_log.sql
--       注意: 必须带 --default-character-set=utf8mb4, 否则中文注释/菜单名会变乱码
-- ============================================================
SET NAMES utf8mb4;

-- 1) 登录日志表
CREATE TABLE IF NOT EXISTS `sys_login_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `user_name` varchar(50) DEFAULT NULL COMMENT '登录账号 (失败时为输入值)',
  `nick_name` varchar(50) DEFAULT NULL COMMENT '用户昵称快照',
  `login_type` varchar(20) DEFAULT NULL COMMENT '登录方式 PASSWORD/SMS/EMAIL',
  `status` char(1) NOT NULL DEFAULT '0' COMMENT '登录状态 0成功 1失败',
  `msg` varchar(255) DEFAULT NULL COMMENT '提示信息 (成功文案 / 失败原因)',
  `ip` varchar(64) DEFAULT NULL COMMENT '客户端IP (兼容 nginx X-Forwarded-For)',
  `login_location` varchar(255) DEFAULT NULL COMMENT '登录地点 (IP 归属地, 解析不出来为空)',
  `browser` varchar(100) DEFAULT NULL COMMENT '浏览器',
  `os` varchar(100) DEFAULT NULL COMMENT '操作系统',
  `user_agent` varchar(500) DEFAULT NULL COMMENT '客户端 UA 原文',
  `login_time` datetime DEFAULT NULL COMMENT '登录时间',
  PRIMARY KEY (`id`),
  KEY `idx_login_time` (`login_time`),
  KEY `idx_login_user` (`user_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志';

-- 2) 菜单: 挂在「系统管理」目录下 (与用户管理/角色管理/授权管理同级)
INSERT IGNORE INTO `sys_menu`
  (`menu_id`,`menu_name`,`parent_id`,`order_num`,`path`,`component`,`is_frame`,`menu_type`,`visible`,`status`,`perms`,`icon`)
VALUES
  (112, '登录日志', 105, 4, '/sync/login-log', 'sync/loginLog', '1', 'C', '0', '0', 'sync:loginlog:list', 'el-icon-document-checked');

-- 3) 按钮级权限 (按 perms 判重)
INSERT INTO `sys_menu`
  (`menu_name`,`parent_id`,`order_num`,`path`,`is_frame`,`menu_type`,`visible`,`status`,`perms`,`icon`)
SELECT t.* FROM (
  SELECT '登录日志删除' menu_name, 112 parent_id, 1 order_num, '' path, '1' is_frame, 'F' menu_type, '0' visible, '0' status, 'sync:loginlog:remove' perms, '#' icon
) t
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` m WHERE m.perms = t.perms);

-- 4) 授权给超级管理员 (role_id=1)
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, menu_id FROM `sys_menu`
WHERE menu_id = 112 OR perms IN ('sync:loginlog:list','sync:loginlog:remove');
