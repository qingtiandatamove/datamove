-- ============================================================
-- DataMove 增量升级脚本
-- 日期: 2026-09-28
-- 内容: 新增超级管理员账号 superadmin, 原 admin 降级为普通操作员
-- 幂等: 账号按 user_name 判重(已有则沿用, 不重复插入), 角色关联先删后插, 可重复执行
-- 执行: mysql -uroot -p --default-character-set=utf8mb4 datamove < upgrade_20260928_superadmin.sql
--       注意: 必须带 --default-character-set=utf8mb4, 否则中文昵称会变乱码
-- ============================================================
SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. 超级管理员账号 superadmin / admin123
--    user_name 是登录唯一依据, 同名账号重复存在会让登录报 "账号或密码错误",
--    所以这里先判重: 已存在就沿用原记录(保留原密码), 不存在才插入
-- ------------------------------------------------------------
INSERT INTO `sys_user`
  (`user_id`, `user_name`, `nick_name`, `user_type`, `password`, `status`, `del_flag`, `create_time`)
SELECT 2, 'superadmin', '超级管理员', '00',
       '$2a$10$hSo6QrcAyTx3q0r0mKhvxeG/WooO7dWK3G4E9k/SsNdjeBRv0zyXS', '0', '0', NOW()
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `sys_user` u WHERE u.`user_name` = 'superadmin');

UPDATE `sys_user`
   SET `nick_name` = '超级管理员', `status` = '0', `del_flag` = '0'
 WHERE `user_name` = 'superadmin';

-- ------------------------------------------------------------
-- 2. 授权超级管理员角色 (role_id=1, role_key=admin => 全权限 *:*:*)
--    用变量取实际 user_id: 页面上手工建过的 superadmin 可能是 100 而不是 2
-- ------------------------------------------------------------
SET @su = (SELECT `user_id` FROM `sys_user` WHERE `user_name` = 'superadmin' ORDER BY `user_id` LIMIT 1);
DELETE FROM `sys_user_role` WHERE `user_id` = @su;
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`) VALUES (@su, 1);

-- ------------------------------------------------------------
-- 3. admin 降级为普通操作员 (role_id=2, role_key=operator)
--    降级后 admin 看不到也调不动: 用户管理 / 角色管理 / 审计日志 / 登录日志 / 授权管理
--    保留: 数据源、同步任务、同步日志、数据中心、SQL 工作台、任务大盘
-- ------------------------------------------------------------
DELETE FROM `sys_user_role` WHERE `user_id` = 1 AND `role_id` = 1;
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`) VALUES (1, 2);

UPDATE `sys_user`
   SET `nick_name` = '普通操作员'
 WHERE `user_id` = 1 AND `user_name` = 'admin';

-- ------------------------------------------------------------
-- 校验用 (可选)
--   SELECT u.user_id, u.user_name, u.nick_name, r.role_id, r.role_name, r.role_key
--     FROM sys_user u
--     LEFT JOIN sys_user_role ur ON ur.user_id = u.user_id
--     LEFT JOIN sys_role r ON r.role_id = ur.role_id
--    WHERE u.user_name IN ('superadmin','admin');
--   -- 期望: superadmin -> 超级管理员(admin) ; admin -> 普通操作员(operator)
--
-- 提示: 已在页面上改过 superadmin 密码的话, 本脚本不会覆盖密码;
--       需要统一成 admin123 可执行:
--       UPDATE sys_user SET password='$2a$10$hSo6QrcAyTx3q0r0mKhvxeG/WooO7dWK3G4E9k/SsNdjeBRv0zyXS'
--        WHERE user_name='superadmin';
-- ------------------------------------------------------------
