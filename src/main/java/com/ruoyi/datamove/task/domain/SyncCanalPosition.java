package com.ruoyi.datamove.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * Canal 监听位点
 */
@Data
@TableName("sync_canal_position")
public class SyncCanalPosition implements Serializable {

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
