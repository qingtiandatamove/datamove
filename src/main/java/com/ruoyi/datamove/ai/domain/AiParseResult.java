package com.ruoyi.datamove.ai.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 自然语言解析结果: 一份可预览的任务草稿 + 解释 + 风险提示
 */
@Data
public class AiParseResult implements Serializable {

    /** 本次解析草稿 */
    private AiTaskDraft draft;

    /** 解析引擎: AI=大模型 RULE=本地规则兜底 */
    private String engine;

    /** AI 引擎下的模型名, RULE 时为 null */
    private String model;

    /** AI 服务商展示名(如 DeepSeek / 火山方舟), RULE 时为 null */
    private String provider;

    /** 一句话总结 AI 理解到的需求 */
    private String summary;

    /** 逐条解释: 为什么这么配 (用户要能看懂并复核) */
    private List<String> explanations = new ArrayList<>();

    /** 风险提示 */
    private List<Risk> risks = new ArrayList<>();

    /** 缺失的必填项 (为空 = 可以直接创建) */
    private List<String> missing = new ArrayList<>();

    /** 降级说明: AI 调用失败时告诉用户为什么走了规则解析 */
    private String fallbackNote;

    @Data
    public static class Risk implements Serializable {
        /** WARN=需要注意 INFO=提示 */
        private String level;
        private String title;
        private String detail;

        public Risk() {
        }

        public Risk(String level, String title, String detail) {
            this.level = level;
            this.title = title;
            this.detail = detail;
        }
    }
}
