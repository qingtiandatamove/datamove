package com.ruoyi.datamove.alert.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 告警发送记录 sync_alert_record
 *
 * <p>一条业务告警可能拆成多条记录(钉钉一条 + 邮件一条), 这样某个通道失败可以单独重试,
 * 不会因为"邮件没配好"就把钉钉的成功/失败状态一起盖掉。
 */
@Data
@TableName("sync_alert_record")
public class SyncAlertRecord implements Serializable {

    /** 状态: 待发送 */
    public static final String STATUS_PENDING = "0";
    /** 状态: 发送成功 */
    public static final String STATUS_SUCCESS = "1";
    /** 状态: 发送失败 */
    public static final String STATUS_FAILED = "2";
    /** 状态: 未发送 —— 没配通道, 或配了但通道未启用(如配了邮箱但 sync.mail.enabled=false) */
    public static final String STATUS_SKIPPED = "3";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String taskName;
    /** TASK / DDL / CANAL / VERIFY / TEST */
    private String alertType;
    /** DINGTALK / MAIL */
    private String channel;
    private String subject;
    private String content;
    private String target;
    private String status;
    private Integer retryCount;
    private String errorMsg;
    private Date createTime;
    private Date sendTime;
}
