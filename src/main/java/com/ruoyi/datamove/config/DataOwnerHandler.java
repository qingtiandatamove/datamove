package com.ruoyi.datamove.config;

import com.ruoyi.datamove.auth.OwnerContext;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 数据隔离处理器: 给业务表的所有 SELECT / UPDATE / DELETE 自动追加 owner_id = 当前用户
 *
 * <p>为什么不逐个改查询条件? 项目里查询散落在 Controller + Service + 引擎几十处,
 * 手写条件一定会漏 —— 漏一处就是越权。交给 MyBatis-Plus 的租户拦截器统一改写,
 * 规则只在这一处, 新增表只要在 {@link #ISOLATED_TABLES} 里加一行。
 *
 * <p>三条边界:
 * <ol>
 *   <li>超管 (admin) 返回 null → 不追加条件 → 看全量, 便于排查问题</li>
 *   <li>非白名单表 (sys_* / sync_license) 一律忽略 —— 用户表、角色表、License 是平台级的</li>
 *   <li>未登录 (后台线程没传上下文) 返回 null → 不追加; 依赖调用方用 OwnerContext.wrap 传归属</li>
 * </ol>
 */
public class DataOwnerHandler implements TenantLineHandler {

    /** 参与隔离的业务表; 不在表里的都是平台级数据, 不做隔离 */
    private static final Set<String> ISOLATED_TABLES = new HashSet<>(Arrays.asList(
            "sync_datasource",
            "sync_task",
            "sync_task_field_mapping",
            "sync_task_progress",
            "sync_task_log",
            "sync_task_run",
            "sync_task_verify",
            "sync_task_diff",
            "sync_sql_log",
            // sync_sql_favorite 刻意不在此列: 收藏夹自带「本人私有 OR 全员共享」语义,
            // 再加 owner_id 会把别人主动共享的收藏也过滤掉, 反而破坏功能。
            // 它本来就有 user_name 过滤, 私有部分依旧是隔离的。
            "sync_alert_record",
            "sync_audit_log",
            "sync_canal_position"
    ));

    /**
     * 只在 {@link #ignoreTable} 返回 false 时才被调用, 所以这里一定能拿到归属用户。
     *
     * <p>注意: 千万不要返回 null 来表达"不隔离" —— MP 会把它拼成 {@code owner_id = NULL},
     * 结果是一条都查不到。要不要隔离必须在 ignoreTable() 里决定。
     */
    @Override
    public Expression getTenantId() {
        return new LongValue(OwnerContext.currentUserId());
    }

    @Override
    public String getTenantIdColumn() {
        return "owner_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        // 1. 白名单之外的表 (sys_* / sync_license / 收藏夹) 一律不隔离
        if (!ISOLATED_TABLES.contains(tableName.toLowerCase())) return true;
        // 2. 超管看全量, 便于排查别人的任务
        if (OwnerContext.isAdmin()) return true;
        // 3. 没有归属上下文时不加条件: 启动清理、CRON 装载等系统内部流程需要看全量,
        //    否则它们会一条都查不到 (注意不能靠 getTenantId 返回 null 来表达, 见上面说明)
        return OwnerContext.currentUserId() == null;
    }
}
