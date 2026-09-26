-- =====================================================================
-- 升级脚本: 同步任务支持「源表过滤条件」与「写入限速」
-- 日期: 2026-09-26
--
-- 背景:
--   AI 任务配置助手会按自然语言要求产出 where_condition(如 status=1) 与
--   rate_limit(如 1000 行/秒), 老库没有这两列, 需要先补列。
--
-- 影响面:
--   两列都允许为 NULL, 老任务零感知 —— 为空 = 全表同步 / 不限速。
--
-- 执行方式 (务必带 utf8mb4, 否则中文列注释会存成乱码):
--   mysql --default-character-set=utf8mb4 -uroot -p datamove < sql/upgrade_20260926_ai_where_rate.sql
-- =====================================================================

ALTER TABLE `sync_task`
  ADD COLUMN `where_condition` varchar(1000) DEFAULT NULL COMMENT '源表过滤条件(SQL WHERE 片段, 不带 WHERE 关键字, 如 status=1)' AFTER `overwrite_flag`,
  ADD COLUMN `rate_limit`      int(11)       DEFAULT NULL COMMENT '写入限速(行/秒), 为空或<=0 表示不限速' AFTER `where_condition`;
