package com.ruoyi.datamove.ai.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 字段映射推荐结果
 *
 * <p>AI 只负责"猜映射", 落库仍然走 {@code /sync/task/fieldMapping/save/{taskId}} 的替换式保存,
 * 用户必须自己确认 —— 猜错字段比不猜更危险。
 */
@Data
public class AiMappingSuggestion {

    /** AI / RULE */
    private String engine;
    private String provider;
    private String model;
    /** 降级原因: AI 未配置或调用失败时说明用了什么兜底 */
    private String fallbackNote;

    private String sourceTable;
    private String targetTable;
    private String summary;

    /** 推荐出来的配对 (已剔除模型瞎编的字段) */
    private List<Item> mappings = new ArrayList<>();

    /** 源表有但没匹配上目标列的字段 */
    private List<String> unmappedSource = new ArrayList<>();

    /** 目标表有但没被匹配到的字段 (通常是目标库自维护的列, 如 update_time) */
    private List<String> unmappedTarget = new ArrayList<>();

    @Data
    public static class Item {
        private String sourceField;
        private String targetField;
        /** high / mid / low */
        private String confidence;
        private String reason;

        public Item() {
        }

        public Item(String sourceField, String targetField, String confidence, String reason) {
            this.sourceField = sourceField;
            this.targetField = targetField;
            this.confidence = confidence;
            this.reason = reason;
        }
    }
}
