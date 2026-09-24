-- =====================================================
-- 轻量 MySQL 数据同步工具 - 数据库初始化脚本
-- 对应需求文档 4.1~4.4 四张核心业务表
-- 同时保留 RuoYi-Vue 框架所需的用户/角色/权限基础表
--
-- 【全新环境】只需执行本脚本, 不必再执行 sql/upgrade_*.sql:
--     mysql -uroot -p < sql/datamove.sql
--   本脚本已包含下列历史升级的全部结构变更:
--     upgrade_20260921_alert_email    sync_task.alert_email
--     upgrade_20260921_field_mapping  sync_task_field_mapping 表
--     upgrade_20260921_sql_favorite   sync_sql_favorite 表
--     upgrade_20260922_shard_count    sync_task.shard_count
--     upgrade_20260922_shard_no       sync_task_log.shard_no
--     upgrade_20260922_task_run       sync_task_run 表
--     upgrade_20260922_data_verify    sync_task.ignore_fields 列 + 数据校验两张表
--     upgrade_20260923_audit_log      sync_audit_log 表
--     upgrade_20260923_binlog_filter  sync_task.binlog_dml_types 列
--
-- 【已有环境】请勿执行本脚本 —— 其中含 DROP TABLE 重建, 会清空业务数据;
--   请按日期顺序执行 sql/upgrade_*.sql (那些脚本是幂等的)
-- =====================================================

CREATE DATABASE IF NOT EXISTS `datamove` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `datamove`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============== RuoYi 基础表 ==============

DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `user_id`     bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `user_name`   varchar(30)  NOT NULL COMMENT '用户账号',
  `nick_name`   varchar(30)  NOT NULL DEFAULT '' COMMENT '用户昵称',
  `user_type`   varchar(2)   NOT NULL DEFAULT '00' COMMENT '用户类型（00系统用户 01注册用户）',
  `email`       varchar(50)  DEFAULT '' COMMENT '用户邮箱',
  `phonenumber` varchar(11)  DEFAULT '' COMMENT '手机号码',
  `sex`         char(1)      DEFAULT '0' COMMENT '用户性别（0男 1女 2未知）',
  `avatar`      varchar(255) DEFAULT '' COMMENT '头像地址',
  `password`    varchar(100) NOT NULL DEFAULT '' COMMENT '密码',
  `status`      char(1)      NOT NULL DEFAULT '0' COMMENT '帐号状态（0正常 1停用）',
  `del_flag`    char(1)      NOT NULL DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `login_ip`    varchar(128) DEFAULT '' COMMENT '最后登录IP',
  `login_date`  datetime     DEFAULT NULL COMMENT '最后登录时间',
  `create_by`   varchar(64)  DEFAULT '' COMMENT '创建者',
  `create_time` datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`   varchar(64)  DEFAULT '' COMMENT '更新者',
  `update_time` datetime     DEFAULT NULL COMMENT '更新时间',
  `remark`      varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COMMENT='用户信息表';

DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
  `role_id`     bigint(20)  NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `role_name`   varchar(30) NOT NULL COMMENT '角色名称',
  `role_key`    varchar(100) NOT NULL COMMENT '角色权限字符串',
  `role_sort`   int(4)      NOT NULL DEFAULT 0 COMMENT '显示顺序',
  `data_scope`  char(1)     DEFAULT '1' COMMENT '数据范围（1：全部数据权限 2：自定数据权限）',
  `status`      char(1)     NOT NULL DEFAULT '0' COMMENT '角色状态（0正常 1停用）',
  `del_flag`    char(1)     NOT NULL DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `create_by`   varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime    DEFAULT NULL COMMENT '创建时间',
  `update_by`   varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime    DEFAULT NULL COMMENT '更新时间',
  `remark`      varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COMMENT='角色信息表';

DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `role_id` bigint(20) NOT NULL COMMENT '角色ID',
  PRIMARY KEY (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户和角色关联表';

DROP TABLE IF EXISTS `sys_menu`;
CREATE TABLE `sys_menu` (
  `menu_id`    bigint(20)  NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `menu_name`  varchar(50) NOT NULL COMMENT '菜单名称',
  `parent_id`  bigint(20)  DEFAULT 0 COMMENT '父菜单ID',
  `order_num`  int(4)      DEFAULT 0 COMMENT '显示顺序',
  `path`       varchar(200) DEFAULT '' COMMENT '路由地址',
  `component`  varchar(255) DEFAULT NULL COMMENT '组件路径',
  `is_frame`   char(1)     DEFAULT '1' COMMENT '是否为外链（0是 1否）',
  `menu_type`  char(1)     DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
  `visible`    char(1)     DEFAULT '0' COMMENT '菜单状态（0显示 1隐藏）',
  `status`     char(1)     DEFAULT '0' COMMENT '菜单状态（0正常 1停用）',
  `perms`      varchar(100) DEFAULT NULL COMMENT '权限标识',
  `icon`       varchar(100) DEFAULT '#' COMMENT '菜单图标',
  `create_by`  varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime   DEFAULT NULL COMMENT '创建时间',
  `update_by`  varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime   DEFAULT NULL COMMENT '更新时间',
  `remark`     varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`menu_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2000 DEFAULT CHARSET=utf8mb4 COMMENT='菜单权限表';

DROP TABLE IF EXISTS `sys_role_menu`;
CREATE TABLE `sys_role_menu` (
  `role_id` bigint(20) NOT NULL COMMENT '角色ID',
  `menu_id` bigint(20) NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`role_id`, `menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色和菜单关联表';

-- ============== 业务核心 4 张表 (对应文档4.1~4.4) ==============

-- 4.1 数据源表
DROP TABLE IF EXISTS `sync_datasource`;
CREATE TABLE `sync_datasource` (
  `id`              bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `datasource_name` varchar(100) NOT NULL COMMENT '数据源名称',
  `host`            varchar(100) NOT NULL COMMENT '数据库IP',
  `port`            int(11)      NOT NULL DEFAULT 3306 COMMENT '端口号',
  `db_name`         varchar(100) NOT NULL COMMENT '数据库名称',
  `username`        varchar(100) NOT NULL COMMENT '登录账号',
  `password`        varchar(255) NOT NULL COMMENT '登录密码(AES加密)',
  `remark`          varchar(500) DEFAULT NULL COMMENT '备注',
  `del_flag`        char(1)      NOT NULL DEFAULT '0' COMMENT '删除标志（0存在 1删除）',
  `create_by`       varchar(64)  DEFAULT '' COMMENT '创建者',
  `create_time`     datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`       varchar(64)  DEFAULT '' COMMENT '更新者',
  `update_time`     datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_datasource_name` (`datasource_name`, `del_flag`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='数据源配置表';

-- 4.2 同步任务表
DROP TABLE IF EXISTS `sync_task`;
CREATE TABLE `sync_task` (
  `id`              bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `task_name`       varchar(100)  NOT NULL COMMENT '任务名称',
  `task_type`       varchar(20)   NOT NULL COMMENT '任务类型(FULL=全量 INCR=增量)',
  `sync_mode`       varchar(20)   NOT NULL COMMENT '同步模式(ID=按主键 TIME=按时间 BINLOG=Binlog)',
  `source_id`       bigint(20)    NOT NULL COMMENT '源数据源ID',
  `target_id`       bigint(20)    NOT NULL COMMENT '目标数据源ID',
  `table_name`      varchar(100)  NOT NULL COMMENT '同步表名',
  `id_field`        varchar(100)  DEFAULT 'id' COMMENT 'ID字段名(ID模式)',
  `time_field`      varchar(100)  DEFAULT 'update_time' COMMENT '时间字段名(TIME模式)',
  `start_id`        bigint(20)    DEFAULT 0 COMMENT '起始ID(ID模式)',
  `start_time`      datetime      DEFAULT NULL COMMENT '起始时间(TIME模式)',
  `batch_size`      int(11)       NOT NULL DEFAULT 1000 COMMENT '批次大小',
  `shard_count`     int(11)       NOT NULL DEFAULT 1 COMMENT '并行分片数(1=串行, >1=按主键区间分片并行, 仅FULL+ID模式)',
  `ignore_fields`   varchar(500)  DEFAULT NULL COMMENT '数据校验忽略字段(逗号分隔, 比对时不比较这些列)',
  `overwrite_flag`  tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否覆盖数据(1=启动时清空目标表再全量同步,仅FULL任务)',
  `dingtalk_webhook` varchar(500) DEFAULT NULL COMMENT '钉钉告警Webhook',
  `alert_email`     varchar(500)  DEFAULT NULL COMMENT '告警邮箱,多个用英文逗号分隔',
  `status`          varchar(20)   NOT NULL DEFAULT 'STOP' COMMENT '任务状态(STOP/RUNNING/PAUSE/COMPLETED/FAILED)',
  `canal_host`      varchar(100)  DEFAULT NULL COMMENT 'Canal服务器地址(INCR模式)',
  `canal_port`      int(11)       DEFAULT 11111 COMMENT 'Canal端口(INCR模式)',
  `canal_destination` varchar(100) DEFAULT NULL COMMENT 'Canal destination',
  `binlog_dml_types` varchar(50)  DEFAULT NULL COMMENT 'binlog DML类型过滤(逗号分隔 INSERT/UPDATE/DELETE 子集, 空=全部同步)',
  `remark`          varchar(500)  DEFAULT NULL COMMENT '备注',
  `del_flag`        char(1)       NOT NULL DEFAULT '0' COMMENT '删除标志',
  `create_by`       varchar(64)   DEFAULT '' COMMENT '创建者',
  `create_time`     datetime      DEFAULT NULL COMMENT '创建时间',
  `update_by`       varchar(64)   DEFAULT '' COMMENT '更新者',
  `update_time`     datetime      DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_name` (`task_name`, `del_flag`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='同步任务表';

-- 4.2.1 任务字段映射表 (源字段 -> 目标字段 一对一重命名, FULL/INCR 通用)
-- 用 IF NOT EXISTS 而非 DROP: 老环境若已执行过 upgrade_20260921_field_mapping.sql,
-- 重复执行本脚本时不会清掉用户已配置好的映射关系
CREATE TABLE IF NOT EXISTS `sync_task_field_mapping` (
  `id`           bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id`      bigint(20)   NOT NULL COMMENT '任务ID (sync_task.id)',
  `source_field` varchar(100) NOT NULL COMMENT '源表字段名',
  `target_field` varchar(100) NOT NULL COMMENT '目标表字段名',
  `sort_no`      int(11)      NOT NULL DEFAULT 0 COMMENT '顺序 (SELECT/INSERT 列表顺序)',
  `create_time`  datetime     DEFAULT NULL,
  `update_time`  datetime     DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_src_f` (`task_id`, `source_field`),
  KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务字段映射表 - 源字段 -> 目标字段 一对一重命名';

-- 4.3 任务断点进度表 (核心表,支撑断点续传)
DROP TABLE IF EXISTS `sync_task_progress`;
CREATE TABLE `sync_task_progress` (
  `id`             bigint(20)  NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id`        bigint(20)  NOT NULL COMMENT '任务ID',
  `last_sync_max_id` bigint(20) DEFAULT 0 COMMENT '已同步最大主键ID',
  `last_sync_time` datetime    DEFAULT NULL COMMENT '已同步最大时间',
  `last_sync_max_id_time` datetime DEFAULT NULL COMMENT 'ID模式下最大ID对应的时间戳,用于时间排序兜底',
  `total_rows`     bigint(20)  NOT NULL DEFAULT 0 COMMENT '累计已同步行数',
  `success_rows`   bigint(20)  NOT NULL DEFAULT 0 COMMENT '成功行数',
  `failed_rows`    bigint(20)  NOT NULL DEFAULT 0 COMMENT '失败行数',
  `cost_seconds`   bigint(20)  NOT NULL DEFAULT 0 COMMENT '累计耗时(秒)',
  `status`         varchar(20) NOT NULL DEFAULT 'STOP' COMMENT '任务状态',
  `start_time`     datetime    DEFAULT NULL COMMENT '本次开始时间',
  `end_time`       datetime    DEFAULT NULL COMMENT '本次结束时间',
  `create_time`    datetime    DEFAULT NULL COMMENT '创建时间',
  `update_time`    datetime    DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务断点进度表';

-- 4.4 同步日志表
DROP TABLE IF EXISTS `sync_task_log`;
CREATE TABLE `sync_task_log` (
  `id`             bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `task_id`        bigint(20)   NOT NULL COMMENT '任务ID',
  `task_name`      varchar(100) DEFAULT NULL COMMENT '任务名称',
  `table_name`     varchar(100) DEFAULT NULL COMMENT '表名',
  `sync_mode`      varchar(20)  DEFAULT NULL COMMENT '同步模式',
  `batch_no`       int(11)      DEFAULT 0 COMMENT '批次号',
  `shard_no`       int(11)      DEFAULT NULL COMMENT '分片号(1起,NULL=非分片任务)',
  `batch_start_id` varchar(100) DEFAULT NULL COMMENT '当前批次起始ID/时间',
  `batch_end_id`   varchar(100) DEFAULT NULL COMMENT '当前批次结束ID/时间',
  `batch_rows`     int(11)      DEFAULT 0 COMMENT '本批次行数',
  `total_rows`     bigint(20)   DEFAULT 0 COMMENT '累计行数',
  `cost_ms`        bigint(20)   DEFAULT 0 COMMENT '本批耗时(毫秒)',
  `status`         varchar(20)  NOT NULL DEFAULT 'SUCCESS' COMMENT '状态(SUCCESS/FAILED/RUNNING)',
  `error_msg`      text         COMMENT '异常信息',
  `content`        text         COMMENT '同步内容摘要(本批次变更数据)',
  `create_time`    datetime     DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步日志表';

-- 运行历史表 (每次启动任务写一条, 结束/失败/停止时回填结果, 大盘「运行历史」用)
DROP TABLE IF EXISTS `sync_task_run`;
CREATE TABLE `sync_task_run` (
  `id`                bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '运行ID',
  `task_id`           bigint(20)    NOT NULL COMMENT '任务ID',
  `task_name`         varchar(100)  DEFAULT NULL COMMENT '任务名称(快照)',
  `task_type`         varchar(20)   DEFAULT NULL COMMENT '任务类型(FULL/INCR/DDL)',
  `sync_mode`         varchar(20)   DEFAULT NULL COMMENT '同步模式(ID/TIME/BINLOG/DDL)',
  `table_name`        varchar(100)  DEFAULT NULL COMMENT '表名',
  `source_name`       varchar(100)  DEFAULT NULL COMMENT '源数据源名称',
  `target_name`       varchar(100)  DEFAULT NULL COMMENT '目标数据源名称',
  `shard_count`       int(11)       DEFAULT 1    COMMENT '并行分片数',
  `status`            varchar(20)   NOT NULL DEFAULT 'RUNNING' COMMENT '状态(RUNNING/COMPLETED/FAILED/PAUSE/STOP)',
  `start_time`        datetime      DEFAULT NULL COMMENT '开始时间',
  `end_time`          datetime      DEFAULT NULL COMMENT '结束时间',
  `cost_seconds`      bigint(20)    DEFAULT 0    COMMENT '本次运行耗时(秒)',
  `total_rows`        bigint(20)    DEFAULT 0    COMMENT '本次同步行数(成功+失败)',
  `success_rows`      bigint(20)    DEFAULT 0    COMMENT '本次成功行数',
  `failed_rows`       bigint(20)    DEFAULT 0    COMMENT '本次失败行数',
  `batch_count`       int(11)       DEFAULT 0    COMMENT '批次数',
  `avg_rows_per_sec`  decimal(16,1) DEFAULT 0.0  COMMENT '平均速率(行/秒)',
  `error_msg`         text          COMMENT '异常信息',
  `create_time`       datetime      DEFAULT NULL,
  `update_time`       datetime      DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_start_time` (`start_time`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务运行历史表';

-- 4.4.1 数据校验运行记录表 (同步完成后「校验数据」一次写一条, 记录差异统计与修复结果)
DROP TABLE IF EXISTS `sync_task_verify`;
CREATE TABLE `sync_task_verify` (
  `id`                bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '校验ID',
  `task_id`           bigint(20)    NOT NULL COMMENT '任务ID (sync_task.id)',
  `task_name`         varchar(100)  DEFAULT NULL COMMENT '任务名称(快照)',
  `table_name`        varchar(100)  DEFAULT NULL COMMENT '表名',
  `source_name`       varchar(100)  DEFAULT NULL COMMENT '源数据源名称(快照)',
  `target_name`       varchar(100)  DEFAULT NULL COMMENT '目标数据源名称(快照)',
  `id_field`          varchar(100)  DEFAULT NULL COMMENT '比对主键列(目标列名)',
  `compare_fields`    text          COMMENT '参与比对的列(目标列名, 逗号分隔)',
  `ignore_fields`     varchar(500)  DEFAULT NULL COMMENT '本次忽略比对的列(目标列名, 逗号分隔)',
  `status`            varchar(20)   NOT NULL DEFAULT 'RUNNING' COMMENT '状态(RUNNING/COMPLETED/FAILED/STOP)',
  `checked_rows`      bigint(20)    NOT NULL DEFAULT 0 COMMENT '已比对行数(源+目标累计推进)',
  `source_rows`       bigint(20)    NOT NULL DEFAULT 0 COMMENT '源表扫描行数',
  `target_rows`       bigint(20)    NOT NULL DEFAULT 0 COMMENT '目标表扫描行数',
  `missing_rows`      bigint(20)    NOT NULL DEFAULT 0 COMMENT '缺失行数(源有目标无)',
  `mismatch_rows`     bigint(20)    NOT NULL DEFAULT 0 COMMENT '不一致行数(主键相同, 字段值不同)',
  `extra_rows`        bigint(20)    NOT NULL DEFAULT 0 COMMENT '多余行数(目标有源无, 仅统计不修复)',
  `diff_rows`         bigint(20)    NOT NULL DEFAULT 0 COMMENT '待修复差异行数(缺失+不一致)',
  `saved_diffs`       int(11)       NOT NULL DEFAULT 0 COMMENT '已落库差异明细条数',
  `truncated`         tinyint(1)    NOT NULL DEFAULT 0 COMMENT '差异明细是否被截断(1=实际差异超过落库上限, 只统计不落明细)',
  `repair_status`     varchar(20)   DEFAULT NULL COMMENT '修复状态(NULL=未修复 RUNNING/COMPLETED/FAILED/STOP)',
  `repair_total`      bigint(20)    NOT NULL DEFAULT 0 COMMENT '待修复行数',
  `repaired_rows`     bigint(20)    NOT NULL DEFAULT 0 COMMENT '已修复行数',
  `repair_failed_rows` bigint(20)   NOT NULL DEFAULT 0 COMMENT '修复失败行数',
  `last_key`          varchar(500)  DEFAULT NULL COMMENT '校验游标(已比对到的最大主键)',
  `cost_ms`           bigint(20)    NOT NULL DEFAULT 0 COMMENT '校验耗时(毫秒)',
  `repair_cost_ms`    bigint(20)    NOT NULL DEFAULT 0 COMMENT '修复耗时(毫秒)',
  `error_msg`         text          COMMENT '异常信息',
  `start_time`        datetime      DEFAULT NULL COMMENT '校验开始时间',
  `end_time`          datetime      DEFAULT NULL COMMENT '校验结束时间',
  `repair_start_time` datetime      DEFAULT NULL COMMENT '修复开始时间',
  `repair_end_time`   datetime      DEFAULT NULL COMMENT '修复结束时间',
  `create_time`       datetime      DEFAULT NULL,
  `update_time`       datetime      DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据校验运行记录表';

-- 4.4.2 数据校验差异明细表 (「一键同步缺失数据」的依据; 修复后回填 repair_status)
DROP TABLE IF EXISTS `sync_task_diff`;
CREATE TABLE `sync_task_diff` (
  `id`            bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '差异ID',
  `verify_id`     bigint(20)    NOT NULL COMMENT '校验ID (sync_task_verify.id)',
  `task_id`       bigint(20)    NOT NULL COMMENT '任务ID',
  `diff_type`     varchar(20)   NOT NULL COMMENT '差异类型(MISSING=源有目标无 MISMATCH=字段不一致 EXTRA=目标有源无)',
  `pk_value`      varchar(500)  DEFAULT NULL COMMENT '主键值(复合主键用 | 连接)',
  `diff_fields`   text          COMMENT '值不一致的列(MISMATCH: 逗号分隔)',
  `source_row`    text          COMMENT '源行快照(JSON)',
  `target_row`    text          COMMENT '目标行快照(JSON)',
  `repair_status` varchar(20)   NOT NULL DEFAULT 'PENDING' COMMENT '修复状态(PENDING/REPAIRED/FAILED/SKIPPED)',
  `repair_error`  varchar(1000) DEFAULT NULL COMMENT '修复失败原因',
  `repair_time`   datetime      DEFAULT NULL COMMENT '修复时间',
  `create_time`   datetime      DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_verify_id` (`verify_id`),
  KEY `idx_verify_type` (`verify_id`, `diff_type`),
  KEY `idx_verify_repair` (`verify_id`, `repair_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据校验差异明细表';

-- 4.5 SQL 执行日志表
DROP TABLE IF EXISTS `sync_sql_log`;
CREATE TABLE `sync_sql_log` (
  `id`            bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `ds_id`         bigint(20)   DEFAULT NULL COMMENT '数据源ID',
  `ds_name`       varchar(100) DEFAULT NULL COMMENT '数据源名称',
  `db_name`       varchar(100) DEFAULT NULL COMMENT '数据库名',
  `source_type`   varchar(20)  DEFAULT 'SQL_CONSOLE' COMMENT '来源(SQL_CONSOLE/DATA_BROWSE)',
  `sql_text`      text         COMMENT '执行的SQL(超长截断)',
  `stmt_count`    int(11)      DEFAULT 0 COMMENT '语句数',
  `result_rows`   bigint(20)   DEFAULT 0 COMMENT '结果集返回行数',
  `affected_rows` bigint(20)   DEFAULT 0 COMMENT '增删改影响行数',
  `cost_ms`       bigint(20)   DEFAULT 0 COMMENT '耗时(毫秒)',
  `status`        varchar(20)  NOT NULL DEFAULT 'SUCCESS' COMMENT '状态(SUCCESS/FAILED)',
  `error_msg`     text         COMMENT '错误信息',
  `oper_name`     varchar(64)  DEFAULT NULL COMMENT '操作人',
  `oper_ip`       varchar(64)  DEFAULT NULL COMMENT '操作IP',
  `client_info`   varchar(255) DEFAULT NULL COMMENT '客户端信息',
  `create_time`   datetime     DEFAULT NULL COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_ds_id` (`ds_id`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL执行日志表';

-- 4.6 SQL 工作台收藏夹
-- 用 IF NOT EXISTS: 老环境若已执行过 upgrade_20260921_sql_favorite.sql, 不会清掉已有收藏
CREATE TABLE IF NOT EXISTS `sync_sql_favorite` (
  `id`          bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '收藏ID',
  `user_id`     bigint(20)   DEFAULT NULL COMMENT '创建者ID',
  `user_name`   varchar(64)  DEFAULT NULL COMMENT '创建者账号',
  `ds_id`       bigint(20)   DEFAULT NULL COMMENT '数据源ID, NULL=通用',
  `ds_name`     varchar(100) DEFAULT NULL COMMENT '数据源名称',
  `title`       varchar(64)  NOT NULL COMMENT '收藏标题',
  `sql_text`    text         NOT NULL COMMENT 'SQL 内容',
  `tags`        varchar(255) DEFAULT NULL COMMENT '逗号分隔标签',
  `use_count`   int(11)      NOT NULL DEFAULT 0 COMMENT '使用次数',
  `shared`      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否团队共享: 0=私有 1=共享',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_name` (`user_name`),
  KEY `idx_ds_id` (`ds_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL 工作台收藏夹';

-- ============== 授权License表 ==============
DROP TABLE IF EXISTS `sync_license`;
CREATE TABLE `sync_license` (
  `id`           bigint(20)  NOT NULL AUTO_INCREMENT COMMENT '主键',
  `license_key`  varchar(100) NOT NULL COMMENT 'License Key',
  `mac_address`  varchar(100) DEFAULT NULL COMMENT '绑定的MAC地址',
  `expire_time`  datetime    DEFAULT NULL COMMENT '到期时间',
  `max_parallel` int(11)     DEFAULT 5 COMMENT '最大并行任务数',
  `status`       char(1)     NOT NULL DEFAULT '0' COMMENT '状态',
  `last_check_time` datetime DEFAULT NULL COMMENT '最后校验时间',
  `create_time`  datetime    DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_license_key` (`license_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='License授权表';

-- ============== 初始化种子数据 ==============

-- 初始账号 admin / admin123
-- RuoYi 默认使用 BCrypt 加密,以下为 BCrypt 加密后的 admin123
INSERT INTO `sys_user` (`user_id`, `user_name`, `nick_name`, `user_type`, `password`, `status`, `create_time`)
VALUES (1, 'admin', '超级管理员', '00', '$2a$10$7JB720NubH3eXX4.5Zc.fu8F.Nq3f3I9G6wT5p2v8eVaEOzWBH4L.', '0', NOW());

INSERT INTO `sys_role` (`role_id`, `role_name`, `role_key`, `role_sort`, `status`, `create_time`)
VALUES
(1, '超级管理员', 'admin', 1, '0', NOW()),
(2, '普通操作员', 'operator', 2, '0', NOW());

INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES (1, 1);

-- 菜单 (数据同步工具专用)
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`)
VALUES
(100, '数据同步', 0, 1, 'sync', NULL, '1', 'M', '0', '0', '', 'guide'),
(101, '数据源管理', 100, 1, 'datasource', 'sync/datasource/index', '1', 'C', '0', '0', 'sync:datasource:list', 'dict'),
(102, '同步任务', 100, 2, 'task', 'sync/task/index', '1', 'C', '0', '0', 'sync:task:list', 'build'),
(103, '同步日志', 100, 3, 'log', 'sync/log/index', '1', 'C', '0', '0', 'sync:log:list', 'log'),
(106, '审计日志', 100, 5, 'audit', 'sync/audit/index', '1', 'C', '0', '0', 'sync:audit:list', 'log'),
(105, '系统管理', 0, 6, 'system', NULL, '1', 'M', '0', '0', '', 'tree'),
(107, '用户管理', 105, 1, 'user', 'system/user/index', '1', 'C', '0', '0', 'system:user:list', 'user'),
(104, '授权管理', 105, 2, 'license', 'sync/license/index', '1', 'C', '0', '0', 'sync:license:list', 'valid-code');

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1, 100), (1, 101), (1, 102), (1, 103), (1, 104), (1, 105), (1, 106), (1, 107),
(2, 100), (2, 101), (2, 102), (2, 103);

-- 同步任务表的菜单按钮权限 (管理员独占)
INSERT INTO `sys_menu` (`menu_name`, `parent_id`, `order_num`, `path`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`)
VALUES
('数据源新增', 101, 1, '', '1', 'F', '0', '0', 'sync:datasource:add', '#'),
('数据源修改', 101, 2, '', '1', 'F', '0', '0', 'sync:datasource:edit', '#'),
('数据源删除', 101, 3, '', '1', 'F', '0', '0', 'sync:datasource:remove', '#'),
('数据源测试连接', 101, 4, '', '1', 'F', '0', '0', 'sync:datasource:test', '#'),
('任务新增', 102, 1, '', '1', 'F', '0', '0', 'sync:task:add', '#'),
('任务修改', 102, 2, '', '1', 'F', '0', '0', 'sync:task:edit', '#'),
('任务删除', 102, 3, '', '1', 'F', '0', '0', 'sync:task:remove', '#'),
('任务启动', 102, 4, '', '1', 'F', '0', '0', 'sync:task:start', '#'),
('任务暂停', 102, 5, '', '1', 'F', '0', '0', 'sync:task:pause', '#'),
('任务继续', 102, 6, '', '1', 'F', '0', '0', 'sync:task:resume', '#'),
('任务终止', 102, 7, '', '1', 'F', '0', '0', 'sync:task:stop', '#'),
('任务查看日志', 102, 8, '', '1', 'F', '0', '0', 'sync:task:log', '#'),
('日志导出', 103, 1, '', '1', 'F', '0', '0', 'sync:log:export', '#'),
('日志清空', 103, 2, '', '1', 'F', '0', '0', 'sync:log:clear', '#'),
('License 查询', 104, 1, '', '1', 'F', '0', '0', 'sync:license:query', '#'),
('License 配置', 104, 2, '', '1', 'F', '0', '0', 'sync:license:config', '#');

-- 管理员拥有所有菜单权限
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, menu_id FROM sys_menu WHERE menu_id NOT IN (SELECT menu_id FROM sys_role_menu WHERE role_id = 1);
-- 操作员仅有查询权限
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 2, menu_id FROM sys_menu WHERE perms IN (
  'sync:datasource:list','sync:task:list','sync:log:list','sync:task:start','sync:task:pause','sync:task:resume','sync:task:stop','sync:task:log'
) AND menu_id NOT IN (SELECT menu_id FROM sys_role_menu WHERE role_id = 2);

-- 默认License示例数据 (本机MAC可在启动日志查看,第一次启动会自动注册)
INSERT INTO `sync_license` (`license_key`, `mac_address`, `expire_time`, `max_parallel`, `status`, `create_time`)
VALUES ('DEMO-LICENSE-KEY-2026', NULL, DATE_ADD(NOW(), INTERVAL 30 DAY), 5, '0', NOW());

-- Canal Offset 持久化表
DROP TABLE IF EXISTS `sync_canal_position`;
CREATE TABLE `sync_canal_position` (
  `id`          bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id`     bigint(20)   NOT NULL COMMENT '任务ID',
  `destination` varchar(100) NOT NULL COMMENT 'Canal destination',
  `journal_name` varchar(100) DEFAULT NULL COMMENT 'Binlog文件名',
  `position`    bigint(20)   DEFAULT 0 COMMENT 'Binlog位置',
  `timestamp`   bigint(20)   DEFAULT 0 COMMENT '时间戳',
  `create_time` datetime     DEFAULT NULL,
  `update_time` datetime     DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Canal监听断点位';

-- 4.7 审计日志表 (字段级变更追踪, 企业合规审计必备)
-- 每次「新增/修改/删除」任务写一批行: 同一请求多个字段共享 revision_id
DROP TABLE IF EXISTS `sync_audit_log`;
CREATE TABLE `sync_audit_log` (
  `id`            bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `revision_id`   bigint(20)   NOT NULL                COMMENT '请求内分组 (同一请求多个字段变更共享 revision_id)',
  `entity_type`   varchar(32)  NOT NULL DEFAULT 'sync_task' COMMENT '实体类型 (当前仅 sync_task)',
  `entity_id`     bigint(20)   NOT NULL                COMMENT '实体ID (task_id)',
  `entity_name`   varchar(128) DEFAULT NULL            COMMENT '实体名称 (任务名称快照)',
  `op_type`       varchar(16)  NOT NULL                COMMENT '操作类型 (CREATE/UPDATE/DELETE)',
  `field_name`    varchar(64)  NOT NULL                COMMENT '字段名 (CREATE/DELETE 整体变更时为 *)',
  `old_value`     text         DEFAULT NULL            COMMENT '旧值',
  `new_value`     text         DEFAULT NULL            COMMENT '新值',
  `operator_id`   bigint(20)   DEFAULT NULL            COMMENT '操作人ID (sys_user.user_id)',
  `operator_name` varchar(64)  DEFAULT NULL            COMMENT '操作人 (快照, 任务改名后历史依然读得懂)',
  `ip`            varchar(64)  DEFAULT NULL            COMMENT '客户端IP (兼容 nginx X-Forwarded-For)',
  `user_agent`    varchar(255) DEFAULT NULL            COMMENT '客户端 UA',
  `create_time`   datetime(3)  NOT NULL                COMMENT '创建时间 (毫秒精度)',
  PRIMARY KEY (`id`),
  KEY `idx_entity` (`entity_type`, `entity_id`, `create_time`) COMMENT '按实体查变更',
  KEY `idx_operator` (`operator_id`, `create_time`)         COMMENT '按人查变更',
  KEY `idx_revision` (`revision_id`)                        COMMENT '按请求分组查',
  KEY `idx_create_time` (`create_time`)                     COMMENT '按时间范围查'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志表 (字段级变更追踪, 合规审计)';

SET FOREIGN_KEY_CHECKS = 1;
