package com.ruoyi.datamove.license.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * License 授权
 * 对应文档 3.5
 */
@Data
@TableName("sync_license")
public class SyncLicense implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String licenseKey;
    private String macAddress;
    private Date expireTime;
    private Integer maxParallel;
    private String status;
    private Date lastCheckTime;
    private Date createTime;
}
