package com.ruoyi.datamove.log.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * SQL 执行日志 sync_sql_log
 * 记录数据工作台(SQL 工作台 / 数据中心)对数据源的每一次操作
 */
@Data
@TableName("sync_sql_log")
public class SyncSqlLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源ID */
    private Long dsId;
    /** 数据源名称 */
    private String dsName;
    /** 数据库名 */
    private String dbName;
    /** 来源: SQL_CONSOLE(SQL工作台) / DATA_BROWSE(数据中心) */
    private String sourceType;
    /** 执行的SQL(超长截断) */
    private String sqlText;
    /** 语句数 */
    private Integer stmtCount;
    /** 结果集返回行数 */
    private Long resultRows;
    /** 增删改影响行数 */
    private Long affectedRows;
    /** 耗时(毫秒) */
    private Long costMs;
    /** 状态: SUCCESS/FAILED */
    private String status;
    /** 错误信息 */
    private String errorMsg;
    /** 操作人 */
    private String operName;
    /** 操作IP */
    private String operIp;
    /** 客户端信息 */
    private String clientInfo;
    /** 操作时间 */
    private Date createTime;
}
