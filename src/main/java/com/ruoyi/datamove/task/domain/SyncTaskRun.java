package com.ruoyi.datamove.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 任务运行历史 sync_task_run
 *
 * 每次「启动」任务写一条记录 (status=RUNNING), 运行过程中按心跳回填行数,
 * 结束时回填 状态/结束时间/耗时/成功率, 供任务大盘「运行历史」查看:
 *  - 分页筛选(任务/状态/类型/时间范围/关键字)
 *  - 概览统计(运行次数/成功/失败/同步行数/平均速率)
 *  - 按天趋势、CSV 导出、按条件清理
 */
@Data
@TableName("sync_task_run")
public class SyncTaskRun implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    /** 任务名称快照(任务改名/删除后历史仍可读) */
    private String taskName;
    /** FULL / INCR / DDL */
    private String taskType;
    /** ID / TIME / BINLOG / DDL */
    private String syncMode;
    private String tableName;
    private String sourceName;
    private String targetName;
    /** 并行分片数 */
    private Integer shardCount;

    /** RUNNING / COMPLETED / FAILED / PAUSE / STOP */
    private String status;

    private Date startTime;
    private Date endTime;
    /** 本次运行耗时(秒) */
    private Long costSeconds;
    /** 本次同步行数(成功 + 失败) */
    private Long totalRows;
    private Long successRows;
    private Long failedRows;
    /** 批次数 */
    private Integer batchCount;
    /** 平均速率(行/秒) */
    private Double avgRowsPerSec;

    private String errorMsg;

    private Date createTime;
    private Date updateTime;
}
