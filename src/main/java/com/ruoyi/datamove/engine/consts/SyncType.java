package com.ruoyi.datamove.engine.consts;

/**
 * 同步引擎常量
 */
public final class SyncType {

    private SyncType() {}

    /** 任务类型 */
    public static final String TASK_FULL = "FULL";
    public static final String TASK_INCR = "INCR";
    public static final String TASK_DDL  = "DDL";       // 同步表结构

    /** 同步模式 */
    public static final String MODE_ID     = "ID";       // 按主键
    public static final String MODE_TIME   = "TIME";     // 按时间
    public static final String MODE_BINLOG = "BINLOG";   // Canal
    public static final String MODE_DDL    = "DDL";      // 仅同步 DDL, 单次操作

    /** 任务状态 */
    public static final String STATUS_STOP      = "STOP";
    public static final String STATUS_RUNNING   = "RUNNING";
    public static final String STATUS_PAUSE     = "PAUSE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED    = "FAILED";

    /** 日志状态 */
    public static final String LOG_SUCCESS = "SUCCESS";
    public static final String LOG_FAILED  = "FAILED";
    public static final String LOG_RUNNING = "RUNNING";

    /** 调度方式 (三选一) */
    public static final String TRIGGER_CRON   = "CRON";    // 定时调度 (cron 表达式)
    public static final String TRIGGER_MANUAL = "MANUAL";  // 手动启动 (默认)
    public static final String TRIGGER_EVENT  = "EVENT";   // 事件触发 (HTTP 回调)
}
