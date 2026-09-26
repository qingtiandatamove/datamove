package com.ruoyi.datamove.template.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 迁移任务模板 (内置, 不落库)
 *
 * <p>把常见迁移场景的"经验参数"固化下来: 分片数、批次大小、覆盖策略、调度方式、
 * 忽略字段等。用户套用模板时只需要填「源库 / 目标库 / 表名」, 其余按模板预置。
 */
@Data
public class TaskTemplate implements Serializable {

    /** 模板编码, 唯一 */
    private String code;
    /** 模板名称 */
    private String name;
    /** 一句话说明 */
    private String desc;
    /** 适用场景 */
    private String scenario;
    /** 前端图标 (el-icon-*) */
    private String icon;
    /** 排序 */
    private Integer sort;
    /** 标签, 卡片上展示 */
    private List<String> tags;
    /** 预置的任务参数 */
    private TaskTemplateConfig config;
    /** 使用提示 */
    private List<String> tips;
    /** 限制说明: 模板依赖但当前版本尚未实现的能力, 必须写清楚, 不能让人误以为能用 */
    private String limitation;
}
