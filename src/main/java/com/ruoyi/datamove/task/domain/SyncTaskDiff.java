package com.ruoyi.datamove.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 数据校验差异明细 sync_task_diff
 *
 * 一条 = 一行差异。三类:
 *  - MISSING  源有目标无 → 修复动作 INSERT
 *  - MISMATCH 主键相同但字段值不同 → 修复动作 UPDATE(只更新 diff_fields 里的列)
 *  - EXTRA    目标有源无 → 不参与修复, 只展示(删目标数据的破坏性动作不放进「一键同步」)
 */
@Data
@TableName("sync_task_diff")
public class SyncTaskDiff implements Serializable {

    /** 差异类型: 源有目标无 */
    public static final String TYPE_MISSING  = "MISSING";
    /** 差异类型: 主键相同, 字段值不同 */
    public static final String TYPE_MISMATCH = "MISMATCH";
    /** 差异类型: 目标有源无 */
    public static final String TYPE_EXTRA    = "EXTRA";

    /** 修复状态: 待处理 */
    public static final String REPAIR_PENDING  = "PENDING";
    /** 修复状态: 已修复 */
    public static final String REPAIR_DONE     = "REPAIRED";
    /** 修复状态: 修复失败 */
    public static final String REPAIR_FAILED   = "FAILED";
    /** 修复状态: 跳过(如 EXTRA 不修复 / 明细被截断) */
    public static final String REPAIR_SKIPPED  = "SKIPPED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long verifyId;
    private Long taskId;

    /** MISSING / MISMATCH / EXTRA */
    private String diffType;

    /** 主键值(复合主键用 | 连接) */
    private String pkValue;

    /** 值不一致的列(MISMATCH 时才有, 逗号分隔) */
    private String diffFields;

    /** 源行快照(JSON) */
    private String sourceRow;
    /** 目标行快照(JSON) */
    private String targetRow;

    /** PENDING / REPAIRED / FAILED / SKIPPED */
    private String repairStatus;
    private String repairError;
    private Date repairTime;

    private Date createTime;
}
