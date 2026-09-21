package com.ruoyi.datamove.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 同步任务字段映射 sync_task_field_mapping
 *
 * <p>支持源/目标表字段名不同时的一对一重命名同步。
 * <p>FULL 与 INCR 两种模式都生效; 未配置时按字段名同名同步 (兼容旧任务)。
 *
 * @see SyncTask
 */
@Data
@TableName("sync_task_field_mapping")
public class SyncTaskFieldMapping implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务 ID */
    private Long taskId;

    /** 源表字段名 */
    private String sourceField;

    /** 目标表字段名 */
    private String targetField;

    /** SELECT/INSERT 列表里的顺序, UI 拖拽后可调整 */
    private Integer sortNo;

    private Date createTime;
    private Date updateTime;
}