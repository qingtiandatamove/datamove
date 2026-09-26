package com.ruoyi.datamove.ai.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 修改任务结果: 只展示「改了哪些字段 old → new」, 确认后由 apply 落库
 */
@Data
public class AiModifyResult implements Serializable {

    private Long taskId;
    private String taskName;

    /** 解析引擎: AI / RULE */
    private String engine;

    /** AI 对指令的理解 */
    private String summary;

    /** 修改后的完整草稿: 前端确认后原样回传, 避免二次调用结果不一致 */
    private AiTaskDraft draft;

    /** 字段级变更清单 (未变化的字段不出现) */
    private List<Change> changes = new ArrayList<>();

    /** 风险提示 */
    private List<AiParseResult.Risk> risks = new ArrayList<>();

    /** 未识别/无法处理的诉求 */
    private List<String> unhandled = new ArrayList<>();

    @Data
    public static class Change implements Serializable {
        /** 字段英文名 (与 sync_task 列名对应) */
        private String field;
        /** 字段中文名 */
        private String label;
        private String oldValue;
        private String newValue;

        public Change() {
        }

        public Change(String field, String label, String oldValue, String newValue) {
            this.field = field;
            this.label = label;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }
}
