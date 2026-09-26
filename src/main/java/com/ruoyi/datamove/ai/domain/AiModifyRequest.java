package com.ruoyi.datamove.ai.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * AI 修改任务请求: 指定任务 + 一句自然语言指令
 */
@Data
public class AiModifyRequest implements Serializable {

    /** 目标任务 ID */
    private Long taskId;

    /** 修改指令, 如「改成只同步近 3 个月数据」「限速调到 500 条每秒」 */
    private String text;
}
