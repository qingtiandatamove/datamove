package com.ruoyi.datamove.template.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 套用模板的请求参数
 *
 * <p>只让用户填「跟自己环境有关」的东西: 源库、目标库、表名、以及模板要求的
 * 主键/时间字段。其余参数全部来自模板预置, 套用后仍可在任务页任意修改。
 */
@Data
public class TemplateApplyRequest implements Serializable {

    private Long sourceId;
    private Long targetId;
    private String tableName;
    /** 任务名, 不填则自动生成 */
    private String taskName;

    /** ID 模式必填: 主键字段名 */
    private String idField;
    /** TIME 模式必填: 时间字段名 */
    private String timeField;
    /** CRON 模板必填: 6 位 Spring 表达式 */
    private String cronExpr;
    /** 增量模板必填 */
    private String canalHost;
    private Integer canalPort;
    private String canalDestination;

    /** 覆盖模板默认的忽略字段(留空则用模板默认) */
    private String ignoreFields;
    private String alertEmail;
    private String dingtalkWebhook;
    /** 是否按同名列自动生成字段映射 */
    private Boolean autoMapping;
}
