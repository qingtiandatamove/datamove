package com.ruoyi.datamove.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 同步任务 sync_task
 *
 * 对应文档 4.2
 */
@Data
@TableName("sync_task")
public class SyncTask extends BaseEntity implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @NotBlank(message = "任务名称不能为空")
    private String taskName;

    /** FULL=全量 INCR=增量(Binlog) */
    @NotBlank(message = "任务类型不能为空")
    private String taskType;

    /** ID=按主键 TIME=按时间 BINLOG */
    @NotBlank(message = "同步模式不能为空")
    private String syncMode;

    @NotNull
    private Long sourceId;

    @NotNull
    private Long targetId;

    @NotBlank
    private String tableName;

    private String idField;
    private String timeField;
    private Long startId;
    private java.util.Date startTime;

    @NotNull
    private Integer batchSize;

    /** 并行分片数: 1=单线程串行(默认); >1=FULL+ID 模式按主键区间分片并行, 大表提速 */
    private Integer shardCount;

    /**
     * 数据校验忽略字段: 逗号分隔(目标列名), 校验时不比较这些列。
     * 目标库由 DB 自动维护的列(update_time / ON UPDATE CURRENT_TIMESTAMP)天然与源库不同,
     * 不配忽略会刷出满屏假差异。
     */
    private String ignoreFields;

    /** 是否覆盖数据: 1=启动时先清空目标表再全量同步 (仅 FULL 任务生效) */
    private Integer overwriteFlag;

    private String dingtalkWebhook;

    /** 告警邮箱, 多个用英文逗号分隔; 为空则不发送邮件告警 */
    private String alertEmail;

    /** STOP/RUNNING/PAUSE/COMPLETED/FAILED */
    private String status;

    // 增量专属
    private String canalHost;
    private Integer canalPort;
    private String canalDestination;

    /** binlog DML 类型过滤: 逗号分隔 INSERT/UPDATE/DELETE 子集; 为空 = 全部同步 */
    private String binlogDmlTypes;

    private String delFlag;
}
