package com.ruoyi.datamove.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;

/**
 * 任务断点进度 sync_task_progress
 *
 * 对应文档 4.3 核心表
 */
@Data
@TableName("sync_task_progress")
public class SyncTaskProgress implements Serializable {

    /** 归属用户ID: 数据隔离用, 落库时由 MetaHandlerConfig 自动填充 */
    @TableField(value = "owner_id", fill = FieldFill.INSERT)
    private Long ownerId;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long lastSyncMaxId;

    private Date lastSyncTime;

    private Date lastSyncMaxIdTime;

    private Long totalRows;
    private Long successRows;
    private Long failedRows;

    private Long costSeconds;

    private String status;

    private Date startTime;
    private Date endTime;

    private Date createTime;
    private Date updateTime;
}
