package com.ruoyi.datamove.ai.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 确认应用 AI 修改: taskId + 预览时返回的草稿, 保证"所见即所改"
 */
@Data
public class AiModifyApplyRequest implements Serializable {

    private Long taskId;

    private AiTaskDraft draft;
}
