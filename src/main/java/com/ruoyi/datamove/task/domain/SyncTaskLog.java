package com.ruoyi.datamove.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 同步日志 sync_task_log
 * 对应文档 4.4
 */
@Data
@TableName("sync_task_log")
public class SyncTaskLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private String taskName;
    private String tableName;
    private String syncMode;

    private Integer batchNo;
    private String batchStartId;
    private String batchEndId;
    private Integer batchRows;
    private Long totalRows;
    private Long costMs;

    private String status;     // SUCCESS/FAILED/RUNNING
    private String errorMsg;

    /** 本批次同步的数据内容摘要 */
    private String content;

    private Date createTime;
}
