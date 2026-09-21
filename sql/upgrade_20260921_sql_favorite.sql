-- ============================================================
-- 升级脚本: SQL 工作台收藏夹
-- 日期: 2026-09-21
-- 说明: 新增 sync_sql_favorite 表, 存储 SQL 工作台用户收藏的 SQL
-- ============================================================

DROP TABLE IF EXISTS `sync_sql_favorite`;
CREATE TABLE `sync_sql_favorite` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '收藏ID',
  `user_id`     BIGINT       DEFAULT NULL COMMENT '创建者ID',
  `user_name`   VARCHAR(64)  DEFAULT NULL COMMENT '创建者账号',
  `ds_id`       BIGINT       DEFAULT NULL COMMENT '数据源ID, NULL=通用',
  `ds_name`     VARCHAR(100) DEFAULT NULL COMMENT '数据源名称',
  `title`       VARCHAR(64)  NOT NULL COMMENT '收藏标题',
  `sql_text`    TEXT         NOT NULL COMMENT 'SQL 内容',
  `tags`        VARCHAR(255) DEFAULT NULL COMMENT '逗号分隔标签',
  `use_count`   INT          NOT NULL DEFAULT 0 COMMENT '使用次数',
  `shared`      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否团队共享: 0=私有 1=共享',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_name` (`user_name`),
  KEY `idx_ds_id` (`ds_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL 工作台收藏夹';