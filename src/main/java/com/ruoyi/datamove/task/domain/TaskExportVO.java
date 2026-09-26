package com.ruoyi.datamove.task.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 任务导入导出 DTO (配置迁移)
 *
 * JSON 结构描述整条任务: 业务配置 + 数据源引用(按名称, 跨环境可重映射) + 字段映射。
 * 刻意不导出的内容:
 *   - id / status / startId / startTime / delFlag: 环境相关的运行态, 导入时重置
 *   - 数据源密码: 敏感信息, 跨环境也不通用 (目标环境按名称匹配自己的数据源)
 *   - 日志/进度/运行历史: 体积大且无迁移意义
 */
@Data
public class TaskExportVO implements Serializable {

    /** 导出格式版本: 以后结构变了靠它做兼容判断 */
    private Integer exportVersion = 1;

    /** 导出时间 */
    private Date exportedAt;

    /* ---------- 任务业务配置 (与 sync_task 业务字段 1:1) ---------- */
    private String taskName;
    private String taskType;
    private String syncMode;
    private String tableName;
    private String idField;
    private String timeField;
    private Integer batchSize;
    private Integer shardCount;
    private String ignoreFields;
    private Integer overwriteFlag;
    /** 源表过滤条件(SQL WHERE 片段) */
    private String whereCondition;
    /** 写入限速(行/秒) */
    private Integer rateLimit;
    private String dingtalkWebhook;
    private String alertEmail;
    private String remark;

    /* 增量(INCR)专属 */
    private String canalHost;
    private Integer canalPort;
    private String canalDestination;
    private String binlogDmlTypes;

    /* 调度方式: 导出 triggerType/cronExpr 便于跨环境迁移; eventToken 是密钥, 不随文件导出, 导入时重新生成 */
    private String triggerType;
    private String cronExpr;

    /* ---------- 数据源引用: 跨环境按名称重映射, 不导出 Long 型 ID ---------- */

    /** 源数据源名称 (目标环境必须存在同名数据源, 否则导入报错) */
    private String sourceDatasourceName;
    /** 目标数据源名称 */
    private String targetDatasourceName;

    /** 源数据源参考信息 (host/port/dbName, 便于人工核对两环境的数据源是否指向同一库) */
    private DatasourceRef sourceDatasourceRef;
    private DatasourceRef targetDatasourceRef;

    /* ---------- 字段映射 ---------- */
    private List<FieldMappingItem> fieldMappings;

    /** 数据源参考信息: 不含密码/账号, 仅用于人工核对 */
    @Data
    public static class DatasourceRef implements Serializable {
        private String host;
        private Integer port;
        private String dbName;
    }

    /** 字段映射条目 */
    @Data
    public static class FieldMappingItem implements Serializable {
        private String sourceField;
        private String targetField;
        private Integer sortNo;
    }
}
