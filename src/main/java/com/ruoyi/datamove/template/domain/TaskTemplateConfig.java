package com.ruoyi.datamove.template.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 模板预置的任务参数
 *
 * <p>字段与 SyncTask 一一对应; needXxx 表示「套用时需要用户补充」,
 * 因为缺了它任务跑不起来(比如 ID 模式必须有主键字段名)。
 */
@Data
public class TaskTemplateConfig implements Serializable {

    /** FULL / INCR / DDL */
    private String taskType;
    /** ID / TIME (INCR 任务不用) */
    private String syncMode;
    /** 批次大小 */
    private Integer batchSize;
    /** 分片数(并行度) */
    private Integer shardCount;
    /** 是否覆盖写入(1=先删后写) */
    private Integer overwriteFlag;
    /** 忽略字段, 逗号分隔 */
    private String ignoreFields;
    /** MANUAL / CRON / EVENT */
    private String triggerType;
    /** CRON 表达式(6位 Spring 格式), triggerType=CRON 时生效 */
    private String cronExpr;
    /** Canal 地址(增量模板) */
    private String canalHost;
    private Integer canalPort;
    private String canalDestination;
    /** 订阅的 DML 类型, 逗号分隔 */
    private String binlogDmlTypes;

    /** 是否需要用户填主键字段 */
    private boolean needIdField;
    /** 是否需要用户填时间字段 */
    private boolean needTimeField;
    /** 是否需要用户确认 CRON 表达式 */
    private boolean needCron;
    /** 是否需要 Canal 连接信息 */
    private boolean needCanal;
}
