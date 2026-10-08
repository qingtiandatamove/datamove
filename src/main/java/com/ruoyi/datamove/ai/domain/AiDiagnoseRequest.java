package com.ruoyi.datamove.ai.domain;

import lombok.Data;

import java.io.Serializable;

/** 失败任务诊断入参 */
@Data
public class AiDiagnoseRequest implements Serializable {

    private Long taskId;
}
