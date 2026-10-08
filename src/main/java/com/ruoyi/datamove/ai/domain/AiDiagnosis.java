package com.ruoyi.datamove.ai.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 失败任务诊断结果
 *
 * <p>只读分析: 不碰任务配置、不改库, 只给出「原因 + 建议」。要不要改由用户自己点。
 */
@Data
public class AiDiagnosis {

    private Long taskId;
    private String taskName;

    /** AI / RULE */
    private String engine;
    private String provider;
    private String model;
    private String fallbackNote;

    /** 问题分类: 连接权限 / 表结构 / 数据冲突 / 类型不兼容 / 资源超时 / 配置问题 / 其它 */
    private String category;

    /** 一句话结论 */
    private String summary;

    /** 详细原因分析 */
    private String cause;

    /** 喂给模型的证据(任务配置摘要 + 最近失败日志), 让用户知道结论是怎么来的 */
    private List<String> evidence = new ArrayList<>();

    /** 修复建议: 每条带一个可执行的动作 */
    private List<Suggestion> suggestions = new ArrayList<>();

    @Data
    public static class Suggestion {
        private String title;
        private String detail;
        /** 具体怎么改: 配置改哪个字段、或给出一段可直接执行的 SQL */
        private String action;

        public Suggestion() {
        }

        public Suggestion(String title, String detail, String action) {
            this.title = title;
            this.detail = detail;
            this.action = action;
        }
    }
}
