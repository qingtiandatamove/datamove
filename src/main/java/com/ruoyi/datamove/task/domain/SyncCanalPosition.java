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
 * Canal 监听位点
 */
@Data
@TableName("sync_canal_position")
public class SyncCanalPosition implements Serializable {

    /** 归属用户ID: 数据隔离用, 落库时由 MetaHandlerConfig 自动填充 */
    @TableField(value = "owner_id", fill = FieldFill.INSERT)
    private Long ownerId;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private String destination;
    private String journalName;
    private Long position;
    private Long timestamp;
    private Date createTime;
    private Date updateTime;
}
