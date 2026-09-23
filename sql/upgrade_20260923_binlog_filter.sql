-- ============================================================
-- DataMove 增量升级脚本
-- 日期: 2026-09-23
-- 内容: binlog 事件过滤 (按 库/表/DML 类型过滤增量事件, 避免无关事件污染下游)
--   sync_task 新增 binlog_dml_types 列:
--     - 逗号分隔的 INSERT/UPDATE/DELETE 子集, 如 "INSERT,UPDATE" 表示只同步新增与更新
--     - NULL / 空串 = 不过滤 (三种 DML 全部同步, 老任务零感知)
--   配套逻辑:
--     - 库/表过滤: Canal 服务端订阅表达式从 .*\\..* 收紧为「源库.任务表」,
--       无关库/表的事件不再进客户端, 减少网络与解析开销 (源库缺失时自动退化为老行为)
--     - DML 过滤: 客户端按本列配置丢弃未勾选类型的事件, 批次日志可见 filtered 计数
-- 幂等: 可重复执行 (列存在时跳过)
-- 执行: mysql -uroot -p datamove < upgrade_20260923_binlog_filter.sql
-- ============================================================

SET NAMES utf8mb4;

-- binlog DML 类型过滤 (逗号分隔 INSERT/UPDATE/DELETE 子集, 空=全部)
ALTER TABLE `sync_task`
    ADD COLUMN `binlog_dml_types` varchar(50) DEFAULT NULL COMMENT 'binlog DML类型过滤(逗号分隔 INSERT/UPDATE/DELETE 子集, 空=全部同步)' AFTER `canal_destination`;

-- 校验
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'binlog_dml_types';
