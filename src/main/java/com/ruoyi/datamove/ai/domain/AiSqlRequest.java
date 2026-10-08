package com.ruoyi.datamove.ai.domain;

import lombok.Data;

import java.io.Serializable;

/** 自然语言生成 SQL 入参 */
@Data
public class AiSqlRequest implements Serializable {

    /** 数据源ID */
    private Long dsId;

    /** 自然语言描述, 如「查最近 7 天下单金额前十的用户」 */
    private String question;

    /** 可选: 指定查哪张表(工作台里已选中表时带上, 能少喂很多表结构) */
    private String table;
}
