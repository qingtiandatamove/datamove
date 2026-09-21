package com.ruoyi.datamove.browse.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * SQL 工作台收藏 sync_sql_favorite
 */
@Data
@TableName("sync_sql_favorite")
public class SyncSqlFavorite implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 创建者ID */
    private Long userId;
    /** 创建者账号 */
    private String userName;
    /** 数据源ID (NULL = 通用) */
    private Long dsId;
    /** 数据源名称 */
    private String dsName;
    /** 收藏标题 */
    private String title;
    /** SQL 内容 */
    private String sqlText;
    /** 逗号分隔标签 */
    private String tags;
    /** 使用次数 */
    private Integer useCount;
    /** 是否团队共享: 0=私有 1=共享 */
    private Integer shared;
    /** 创建时间 */
    private Date createTime;
    /** 更新时间 */
    private Date updateTime;
}