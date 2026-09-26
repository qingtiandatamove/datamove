package com.ruoyi.datamove.ai.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 自然语言输入
 */
@Data
public class AiTextRequest implements Serializable {

    /** 用户用自然语言描述的需求 / 修改指令 */
    private String text;
}
