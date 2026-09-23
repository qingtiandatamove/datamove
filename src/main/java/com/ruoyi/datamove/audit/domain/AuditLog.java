package com.ruoyi.datamove.audit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 审计日志 sync_audit_log
 *
 * 字段级变更追踪: 谁在什么时候改了哪个任务的哪个字段 (old → new)
 *
 * 设计要点:
 *   - 一次请求多个字段变更共享一个 revision_id, 便于按请求分组还原「同一时刻发生了什么」
 *   - entity_name / operator_name 都是快照, 任务改名/操作员改名后历史依然读得懂
 *   - 当前仅记录 sync_task (本类不抽象成多实体, 字段集就是 task 字段集; 后续若需审计数据源再加 entity_type)
 *   - 写入失败不影响业务主流程 (Service 层 try/catch 兜底)
 *
 * 对应文档 4.7
 */
@Data
@TableName("sync_audit_log")
public class AuditLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 请求内分组 (同一请求多个字段变更共享 revision_id) */
    private Long revisionId;

    /** 实体类型 (当前仅 sync_task) */
    private String entityType;

    /** 实体ID (task_id) */
    private Long entityId;

    /** 实体名称 (任务名称快照) */
    private String entityName;

    /** 操作类型 CREATE / UPDATE / DELETE */
    private String opType;

    /** 字段名 (CREATE/DELETE 整体变更时为 *) */
    private String fieldName;

    /** 旧值 (CREATE 时为 NULL) */
    private String oldValue;

    /** 新值 (DELETE 时为 NULL) */
    private String newValue;

    /** 操作人ID (sys_user.user_id) */
    private Long operatorId;

    /** 操作人 (快照) */
    private String operatorName;

    /** 客户端IP (兼容 nginx X-Forwarded-For) */
    private String ip;

    /** 客户端 UA */
    private String userAgent;

    /** 创建时间 (毫秒精度) */
    private Date createTime;
}