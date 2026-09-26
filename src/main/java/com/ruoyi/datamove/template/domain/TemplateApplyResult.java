package com.ruoyi.datamove.template.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 套用模板的结果
 */
@Data
public class TemplateApplyResult implements Serializable {

    /** 新建的任务ID */
    private Long taskId;
    private String taskName;
    /** 自动生成的字段映射条数 */
    private Integer mappingCount;
    /** 自动生成映射时的提示(比如目标表不存在、列读不到) */
    private String mappingNote;
}
