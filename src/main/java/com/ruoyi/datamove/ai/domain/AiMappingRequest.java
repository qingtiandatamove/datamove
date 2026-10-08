package com.ruoyi.datamove.ai.domain;

import lombok.Data;

import java.io.Serializable;

/** 字段映射推荐入参: 两边的数据源 + 表名 */
@Data
public class AiMappingRequest implements Serializable {

    private Long sourceId;
    private String sourceTable;
    private Long targetId;
    private String targetTable;
}
