package com.ruoyi.datamove.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 数据校验运行记录 sync_task_verify
 *
 * 同步任务跑完后点「校验数据」即产生一条, 流程分两段:
 *  1) 校验段: 按主键双游标归并比对源库与目标库, 统计 缺失/不一致/多余 三类差异
 *  2) 修复段: 「一键同步缺失数据」按差异明细回放 INSERT / UPDATE, 结果回填 repair_* 字段
 *
 * 校验与修复都不改动 sync_task.status —— 它是同步任务的状态, 校验是旁路只读动作,
 * 两者若共用状态位会出现「任务已完成但显示运行中」这类错乱。
 */
@Data
@TableName("sync_task_verify")
public class SyncTaskVerify implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    /** 任务名称快照(任务改名/删除后历史仍可读) */
    private String taskName;
    private String tableName;
    /** 源/目标数据源名称快照 */
    private String sourceName;
    private String targetName;

    /** 比对主键列(目标列名) */
    private String idField;
    /** 参与比对的列(目标列名, 逗号分隔) */
    private String compareFields;
    /** 本次忽略比对的列(目标列名, 逗号分隔) */
    private String ignoreFields;

    /** RUNNING / COMPLETED / FAILED / STOP */
    private String status;

    /** 已比对行数(源+目标两个游标累计推进数) */
    private Long checkedRows;
    /** 源表扫描行数 */
    private Long sourceRows;
    /** 目标表扫描行数 */
    private Long targetRows;
    /** 缺失行数(源有目标无) */
    private Long missingRows;
    /** 不一致行数(主键相同, 字段值不同) */
    private Long mismatchRows;
    /** 多余行数(目标有源无, 仅统计不修复) */
    private Long extraRows;
    /** 待修复差异行数 = 缺失 + 不一致 */
    private Long diffRows;

    /** 已落库的差异明细条数 */
    private Integer savedDiffs;
    /** 差异明细是否被截断: 1 = 实际差异超过落库上限, 后续只计数不落明细 */
    private Integer truncated;

    /** 修复状态: null = 未修复; RUNNING / COMPLETED / FAILED / STOP */
    private String repairStatus;
    private Long repairTotal;
    private Long repairedRows;
    private Long repairFailedRows;

    /** 校验游标(已比对到的最大主键), 仅用于展示与排查 */
    private String lastKey;

    private Long costMs;
    private Long repairCostMs;
    private String errorMsg;

    private Date startTime;
    private Date endTime;
    private Date repairStartTime;
    private Date repairEndTime;

    private Date createTime;
    private Date updateTime;
}
