package com.ruoyi.datamove.engine.full;

import com.ruoyi.common.utils.DingTalkUtils;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.mapper.SyncDatasourceMapper;
import com.ruoyi.datamove.engine.consts.SyncType;
import com.ruoyi.datamove.engine.log.SyncLogService;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;
import com.ruoyi.datamove.task.mapper.SyncTaskMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskProgressMapper;
import com.ruoyi.datamove.util.JdbcUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * 表结构同步引擎
 *
 * 行为:
 *  - 仅复制表结构 (DDL), 不传数据
 *  - 目标表不存在 → 自动从源表 SHOW CREATE TABLE 拿 DDL 并执行
 *  - 目标表已存在 → 默认跳过 (不破坏现有数据), 任务直接 COMPLETED
 *  - 单次操作, 不支持暂停/继续 (任务会瞬间完成)
 *
 * 对应 SyncType.TASK_DDL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DdlSyncEngine {

    private final SyncTaskMapper taskMapper;
    private final SyncTaskProgressMapper progressMapper;
    private final SyncDatasourceMapper datasourceMapper;
    private final SyncLogService logService;

    /** 单实例保护 */
    private static final Set<Long> RUNNING_TASK = new HashSet<>();

    /**
     * 启动 (DDL 同步是单次操作, 同步进行)
     */
    public synchronized void start(Long taskId) {
        if (RUNNING_TASK.contains(taskId)) {
            throw new RuntimeException("任务已在运行中,请勿重复启动");
        }
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null) throw new RuntimeException("任务不存在");

        SyncDatasource src = datasourceMapper.selectById(task.getSourceId());
        SyncDatasource tgt = datasourceMapper.selectById(task.getTargetId());
        if (src == null || tgt == null) throw new RuntimeException("任务关联的数据源不存在");

        if (!JdbcUtils.testConnection(src)) {
            DingTalkUtils.sendText(task.getDingtalkWebhook(),
                    "【DataMove告警】任务[" + task.getTaskName() + "]启动失败:源库连接失败");
            throw new RuntimeException("无法连接源数据库");
        }
        if (!JdbcUtils.testConnection(tgt)) {
            DingTalkUtils.sendText(task.getDingtalkWebhook(),
                    "【DataMove告警】任务[" + task.getTaskName() + "]启动失败:目标库连接失败");
            throw new RuntimeException("无法连接目标数据库");
        }

        RUNNING_TASK.add(taskId);
        try {
            // 更新任务状态为 RUNNING, 紧接着会被置为 COMPLETED / FAILED
            task.setStatus(SyncType.STATUS_RUNNING);
            taskMapper.updateById(task);

            // 同步执行 (任务本身是秒级)
            doDdlSync(task, src, tgt);

            // 完成后置为 COMPLETED
            task.setStatus(SyncType.STATUS_COMPLETED);
            taskMapper.updateById(task);
            // 进度表也写一条, 让前端有进度可看
            upsertProgress(task);
        } catch (Throwable t) {
            log.error("[DDL] task[{}] sync error", task.getTaskName(), t);
            DingTalkUtils.sendText(task.getDingtalkWebhook(),
                    "【DataMove告警】表结构同步任务[" + task.getTaskName() + "]失败:\n" + t.getMessage());
            task.setStatus(SyncType.STATUS_FAILED);
            taskMapper.updateById(task);
            upsertProgressFailed(task);
            throw new RuntimeException(t.getMessage());
        } finally {
            RUNNING_TASK.remove(taskId);
        }
    }

    private void doDdlSync(SyncTask task, SyncDatasource src, SyncDatasource tgt) {
        String table = task.getTableName();
        if (table == null || table.isEmpty()) {
            throw new RuntimeException("表名不能为空");
        }
        if (!table.matches("[A-Za-z0-9_]+")) {
            throw new RuntimeException("非法表名: " + table);
        }

        // 1. 源表必须存在
        if (!JdbcUtils.isTableExists(src, table)) {
            throw new RuntimeException("源表 " + src.getDbName() + "." + table + " 不存在");
        }

        // 2. 目标表已存在 → 默认跳过, 写日志后 COMPLETED
        if (JdbcUtils.isTableExists(tgt, table)) {
            log.info("[DDL] task[{}] 目标表 {}.{} 已存在, 跳过建表",
                    task.getTaskName(), tgt.getDbName(), table);
            logService.writeLog(
                    buildCtx(task, src, tgt), 1, table, table,
                    0, 0, 0, SyncType.LOG_SUCCESS,
                    "目标表已存在, 跳过建表 (DDL 同步默认不覆盖)");
            return;
        }

        // 3. 拿源表 DDL
        String ddl = JdbcUtils.getShowCreateTable(src, table);
        if (ddl == null || ddl.isEmpty()) {
            throw new RuntimeException("无法获取源表 DDL: " + table);
        }

        // 4. 规范化: 去掉 db.tbl 前缀, 加 IF NOT EXISTS
        String normalized = ddl.replaceFirst(
                "(?i)CREATE\\s+TABLE\\s+(IF NOT EXISTS\\s+)?(`[^`]+`\\.)?`" + table + "`",
                "CREATE TABLE IF NOT EXISTS `" + table + "`");

        // 5. 在目标库执行
        long start = System.currentTimeMillis();
        try (Connection c = JdbcUtils.getConnection(tgt);
             Statement s = c.createStatement()) {
            s.executeUpdate(normalized);
            long cost = System.currentTimeMillis() - start;
            log.info("[DDL] task[{}] 自动建表 {}.{} 成功, cost={}ms",
                    task.getTaskName(), tgt.getDbName(), table, cost);
            logService.writeLog(
                    buildCtx(task, src, tgt), 1, table, table,
                    0, 0, cost, SyncType.LOG_SUCCESS,
                    "DDL 已应用: " + truncate(normalized, 500));
        } catch (Exception e) {
            log.error("[DDL] task[{}] 执行 DDL 失败: {}", task.getTaskName(), e.getMessage());
            logService.writeLog(
                    buildCtx(task, src, tgt), 1, table, table,
                    0, 0, System.currentTimeMillis() - start, SyncType.LOG_FAILED,
                    "DDL 执行失败: " + e.getMessage());
            throw new RuntimeException("执行 DDL 失败: " + e.getMessage());
        }
    }

    private com.ruoyi.datamove.engine.SyncContext buildCtx(SyncTask task, SyncDatasource src, SyncDatasource tgt) {
        return com.ruoyi.datamove.engine.SyncContext.builder()
                .task(task).source(src).target(tgt)
                .batchNo(new java.util.concurrent.atomic.AtomicLong(1))
                .pauseFlag(new java.util.concurrent.atomic.AtomicBoolean(false))
                .stopFlag(new java.util.concurrent.atomic.AtomicBoolean(false))
                .build();
    }

    private void upsertProgress(SyncTask task) {
        SyncTaskProgress p = progressMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SyncTaskProgress>()
                        .eq("task_id", task.getId()));
        if (p == null) {
            p = new SyncTaskProgress();
            p.setTaskId(task.getId());
            p.setCreateTime(new Date());
        }
        p.setStatus(SyncType.STATUS_COMPLETED);
        p.setStartTime(new Date());
        p.setEndTime(new Date());
        p.setTotalRows(0L);
        p.setSuccessRows(0L);
        p.setFailedRows(0L);
        p.setUpdateTime(new Date());
        if (p.getId() == null) progressMapper.insert(p);
        else progressMapper.updateById(p);
    }

    private void upsertProgressFailed(SyncTask task) {
        SyncTaskProgress p = progressMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SyncTaskProgress>()
                        .eq("task_id", task.getId()));
        if (p == null) {
            p = new SyncTaskProgress();
            p.setTaskId(task.getId());
            p.setCreateTime(new Date());
        }
        p.setStatus(SyncType.STATUS_FAILED);
        p.setEndTime(new Date());
        p.setUpdateTime(new Date());
        if (p.getId() == null) progressMapper.insert(p);
        else progressMapper.updateById(p);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}