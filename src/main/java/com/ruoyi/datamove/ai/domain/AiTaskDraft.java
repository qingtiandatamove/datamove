package com.ruoyi.datamove.ai.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * AI 解析出来的「任务草稿」
 *
 * <p>刻意与 sync_task 字段 1:1, 前端预览后原样回传给后端即可创建任务, 中间不做二次转换。
 * 字段允许为 null: null = AI 没提到这项, 创建时用默认值兜底(不覆盖用户已有配置, 修改场景尤其重要)。
 *
 * <p>模型经常多带 summary / 多余字段, 这里一律忽略, 只挑认识的字段。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiTaskDraft implements Serializable {

    private String taskName;

    /* ---------- 数据源: 优先用 id, 没 id 时按名称匹配 ---------- */
    private Long sourceDatasourceId;
    private String sourceDatasourceName;
    private Long targetDatasourceId;
    private String targetDatasourceName;

    private String tableName;

    /** FULL=全量 INCR=增量 */
    private String taskType;
    /** ID=主键游标 TIME=时间游标 BINLOG=binlog(增量) */
    private String syncMode;

    private String idField;
    private String timeField;

    private Integer batchSize;
    private Integer shardCount;
    /** 1=清空目标表后写入(覆盖) */
    private Integer overwriteFlag;
    /** 数据校验忽略字段(逗号分隔) —— 「脱敏」目前用它实现 */
    private String ignoreFields;
    /** 源表过滤条件(不带 WHERE 关键字的 SQL 片段), 如 status=1 */
    private String whereCondition;
    /** 写入限速(行/秒), null 或 <=0 = 不限速 */
    private Integer rateLimit;

    /* ---------- 调度 ---------- */
    private String triggerType;
    private String cronExpr;

    /* ---------- 增量(Canal) ---------- */
    private String canalHost;
    private Integer canalPort;
    private String canalDestination;
    private String binlogDmlTypes;

    /** 用户要求脱敏的字段(仅作展示/提示, 真正生效是落到 ignoreFields) */
    private List<String> maskFields;

    /** 是否自动生成同名字段映射 */
    private Boolean autoMapping;

    private String remark;
}
