package com.ruoyi.datamove.task.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 任务大盘条目: 任务 + 断点进度 + 运行期实时指标
 *
 * 对应文档「任务监控 / 大盘」, 供前端展示 行/秒、ETA、当前批次、瓶颈库
 */
@Data
public class TaskDashboardVO implements Serializable {

    /* ---------- 任务基本信息 ---------- */
    private Long   taskId;
    private String taskName;
    private String taskType;
    private String syncMode;
    private String tableName;
    private String status;
    private Boolean running;

    /** 源/目标数据源名称, 用于展示瓶颈所在 */
    private String sourceName;
    private String targetName;

    private Integer batchSize;

    /** 配置的并行分片数, null/1 = 单线程 */
    private Integer shardCount;

    /* ---------- 进度 ---------- */
    /** 本次运行已同步行数(续传场景已扣除启动前的历史累计) */
    private Long syncRows;
    /** 累计同步行数(含历史) */
    private Long totalRows;
    private Long failedRows;
    /** 本次运行需同步总行数估算, -1 = 未知 */
    private Long totalEstimate;
    /** 预估剩余行数 */
    private Long remainRows;
    /** 本次运行进度百分比 0-100, -1 = 未知 */
    private Integer progress;

    private Date   startTime;
    private Date   updateTime;
    private Long   costSeconds;

    /* ---------- 实时指标 ---------- */
    /** 实时速率(行/秒, 10s 滑动窗口) */
    private Double rowsPerSec;
    /** 本次运行平均速率(行/秒) */
    private Double avgRowsPerSec;
    /** 预计剩余秒数, -1 = 未知 */
    private Long   etaSeconds;
    /** 预计剩余时间的可读文本 */
    private String etaText;

    /** 当前批次号与已处理行数 */
    private Integer currentBatch;
    private Integer currentBatchRows;
    /** 最近一批总耗时 */
    private Long    lastBatchCostMs;

    /** 窗口内平均: 源库读取耗时 / 目标库写入耗时 */
    private Double readMs;
    private Double writeMs;
    /** 瓶颈: SOURCE / TARGET / BALANCED / UNKNOWN */
    private String bottleneck;
    /** 瓶颈的可读文本, 未知时为 null */
    private String bottleneckText;

    /* ---------- 历史运行 (sync_task_run) ---------- */
    /** 历史累计运行次数 */
    private Integer runCount;
    /** 最近一次运行: 状态 / 开始时间 / 结束时间 / 耗时 / 成功行数 / 失败行数 */
    private String lastRunStatus;
    private Date   lastRunTime;
    private Date   lastRunEndTime;
    private Long   lastRunCostSeconds;
    private Long   lastRunRows;
    private Long   lastRunFailedRows;

    /* ---------- 分片实时监控 (仅分片任务) ---------- */
    /** 各分片实时状态, 按分片号升序; null/空 = 非分片任务 */
    private List<ShardVO> shards;

    /**
     * 单个分片的实时状态
     */
    @Data
    public static class ShardVO implements Serializable {
        /** 分片号 (1 起) */
        private Integer shardNo;
        /** 分片负责的主键区间 [rangeLo, rangeHi] */
        private Long rangeLo;
        private Long rangeHi;
        /** 本分片已同步行数 */
        private Long rows;
        /** 本分片游标当前位置(最近一批的最大 id) */
        private Long currentId;
        /** 本分片实时速率(行/秒, 10s 窗口) */
        private Double rowsPerSec;
        /** 本分片已完成批次数 */
        private Integer batches;
        /** RUNNING / DONE / FAILED */
        private String state;
        /** 失败原因(仅 FAILED 时) */
        private String error;
    }
}
