package com.ruoyi.datamove.engine.incr;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ruoyi.datamove.util.AlertUtils;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.mapper.SyncDatasourceMapper;
import com.ruoyi.datamove.engine.SyncContext;
import com.ruoyi.datamove.engine.consts.SyncType;
import com.ruoyi.datamove.engine.log.SyncLogService;
import com.ruoyi.datamove.task.domain.SyncCanalPosition;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;
import com.ruoyi.datamove.task.mapper.SyncCanalPositionMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskProgressMapper;
import com.ruoyi.datamove.util.JdbcUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.*;

/**
 * Canal 增量同步引擎
 *
 * 对应文档 3.2.4 增量 Binlog 同步任务
 *  - 监听 MySQL Binlog (ROW 模式)
 *  - 把 INSERT/UPDATE/DELETE 实时应用到目标库
 *  - Canal 自带 Offset 断点,持久化到 sync_canal_position
 *  - 异常自动钉钉告警,支持启停
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CanalSyncEngine {

    private final SyncTaskMapper         taskMapper;
    private final SyncTaskProgressMapper progressMapper;
    private final SyncDatasourceMapper   datasourceMapper;
    private final SyncCanalPositionMapper positionMapper;
    private final SyncLogService         logService;

    /** 任务 -> worker executor */
    private static final Map<Long, CanalWorker> WORKERS = new ConcurrentHashMap<>();

    /** 单条日志记录的数据内容上限(字符), 超出后不再拼接明细 */
    private static final int MAX_CONTENT_CHARS = 3500;

    /** 构造增量批次日志上下文 */
    private SyncContext buildCtx(SyncTask task, SyncDatasource src, SyncDatasource tgt) {
        return SyncContext.builder()
                .task(task).source(src).target(tgt)
                .batchNo(new java.util.concurrent.atomic.AtomicLong(1))
                .pauseFlag(new java.util.concurrent.atomic.AtomicBoolean(false))
                .stopFlag(new java.util.concurrent.atomic.AtomicBoolean(false))
                .build();
    }

    /** 启动增量同步 */
    public synchronized void start(Long taskId) {
        if (WORKERS.containsKey(taskId)) {
            throw new RuntimeException("增量任务已在运行中");
        }
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null) throw new RuntimeException("任务不存在");
        if (task.getCanalHost() == null || task.getCanalDestination() == null) {
            throw new RuntimeException("Canal服务器配置不完整");
        }

        SyncDatasource src = datasourceMapper.selectById(task.getSourceId());
        SyncDatasource tgt = datasourceMapper.selectById(task.getTargetId());
        if (src == null || tgt == null) throw new RuntimeException("任务关联的数据源不存在");

        // Canal connector: 单连接模式,无集群/无账号密码的版本
        CanalConnector connector = CanalConnectors.newSingleConnector(
                new InetSocketAddress(task.getCanalHost(), task.getCanalPort()),
                task.getCanalDestination(),
                "", "");

        CanalWorker worker = new CanalWorker(task, connector, src, tgt);
        WORKERS.put(taskId, worker);
        executor.submit(worker);

        // 状态: RUNNING
        SyncTaskProgress p = progressMapper.selectOne(
                new QueryWrapper<SyncTaskProgress>().eq("task_id", taskId));
        if (p == null) {
            p = new SyncTaskProgress();
            p.setTaskId(taskId);
            p.setStatus(SyncType.STATUS_RUNNING);
            p.setStartTime(new Date());
            p.setCreateTime(new Date());
            p.setUpdateTime(new Date());
            progressMapper.insert(p);
        } else {
            p.setStatus(SyncType.STATUS_RUNNING);
            p.setStartTime(new Date());
            p.setEndTime(null);
            p.setUpdateTime(new Date());
            progressMapper.updateById(p);
        }
        task.setStatus(SyncType.STATUS_RUNNING);
        taskMapper.updateById(task);

        log.info("[IncrSync] task[{}] started, canal={}:{}, dest={}",
                task.getTaskName(), task.getCanalHost(), task.getCanalPort(),
                task.getCanalDestination());
    }

    /** 停止增量 */
    public synchronized void stop(Long taskId) {
        CanalWorker w = WORKERS.remove(taskId);
        if (w != null) w.shutdown();
        SyncTask task = taskMapper.selectById(taskId);
        if (task != null) {
            task.setStatus(SyncType.STATUS_STOP);
            taskMapper.updateById(task);
        }
        SyncTaskProgress p = progressMapper.selectOne(
                new QueryWrapper<SyncTaskProgress>().eq("task_id", taskId));
        if (p != null) {
            p.setStatus(SyncType.STATUS_STOP);
            p.setEndTime(new Date());
            p.setUpdateTime(new Date());
            progressMapper.updateById(p);
        }
    }

    /** 异步执行器 */
    private static final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    /** Canal worker */
    public class CanalWorker implements Runnable {

        private final SyncTask task;
        private final CanalConnector connector;
        private final SyncDatasource src;
        private final SyncDatasource tgt;
        private volatile boolean running = true;

        public CanalWorker(SyncTask task, CanalConnector connector,
                           SyncDatasource src, SyncDatasource tgt) {
            this.task = task;
            this.connector = connector;
            this.src = src;
            this.tgt = tgt;
        }

        public void shutdown() {
            running = false;
            try { connector.disconnect(); } catch (Exception ignored) {}
        }

        /** binlog 位点标记 file:offset */
        private String mark(CanalEntry.Entry e) {
            return e.getHeader().getLogfileName() + ":" + e.getHeader().getLogfileOffset();
        }

        /**
         * 记录一行变更内容,供界面"同步内容"展示
         *  - INSERT/DELETE: 列出全部字段
         *  - UPDATE: 先给主键,再只列出真正发生变化的字段
         */
        private void appendContent(StringBuilder sb, String op, List<CanalEntry.Column> after,
                                   List<CanalEntry.Column> before) {
            if (sb.length() >= MAX_CONTENT_CHARS) return;
            sb.append(op);
            if ("UPDATE".equals(op)) {
                for (CanalEntry.Column c : after) {
                    if (c.getIsKey()) {
                        sb.append(' ').append(c.getName()).append('=').append(val(c));
                        break;
                    }
                }
                sb.append(" | ");
                boolean first = true;
                for (CanalEntry.Column c : after) {
                    String oldV = columnValue(before, c.getName(), val(c));
                    String newV = val(c);
                    if (!oldV.equals(newV)) {
                        if (!first) sb.append(", ");
                        sb.append(c.getName()).append(": ").append(oldV).append(" -> ").append(newV);
                        first = false;
                    }
                }
                if (first) sb.append("(字段无变化)");
            } else {
                sb.append(' ');
                boolean first = true;
                for (CanalEntry.Column c : after) {
                    if (!first) sb.append(", ");
                    sb.append(c.getName()).append('=').append(val(c));
                    first = false;
                }
            }
            sb.append('\n');
        }

        private String val(CanalEntry.Column c) {
            return c.getIsNull() ? "NULL" : c.getValue();
        }

        private String columnValue(List<CanalEntry.Column> cols, String name, String def) {
            if (cols != null) {
                for (CanalEntry.Column c : cols) {
                    if (name.equals(c.getName())) return val(c);
                }
            }
            return def;
        }

        @Override
        public void run() {
            String tableName = task.getTableName();
            String dest      = task.getCanalDestination();
            try {
                connector.connect();
                // 订阅目标表的所有 DML
                connector.subscribe(".*\\..*");
                connector.rollback();

                // 回放到上次 position
                SyncCanalPosition pos = positionMapper.selectOne(
                        new QueryWrapper<SyncCanalPosition>().eq("task_id", task.getId()));
                if (pos != null && pos.getJournalName() != null) {
                    // Canal server 端已持久化 offset,客户端无需 rollback
                    log.info("[IncrSync] resume from {}", pos.getJournalName());
                }

                SyncContext ctx = buildCtx(task, src, tgt);
                int batchNo = 0;
                while (running) {
                    Message message = connector.getWithoutAck(1000);
                    long batchId = message.getId();
                    int size = message.getEntries().size();
                    if (size == 0 || batchId == -1) continue;
                    batchNo++;

                    long batchStartMs = System.currentTimeMillis();
                    CanalEntry.Entry firstEntry = message.getEntries().get(0);
                    CanalEntry.Entry lastEntry  = message.getEntries().get(size - 1);
                    String startMarker = mark(firstEntry);
                    String endMarker   = mark(lastEntry);

                    int inserts = 0, updates = 0, deletes = 0, errs = 0;
                    String firstErr = null;
                    StringBuilder contentSb = new StringBuilder();
                    try {
                        for (CanalEntry.Entry entry : message.getEntries()) {
                            if (entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONBEGIN ||
                                entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONEND) continue;

                            CanalEntry.RowChange rowChange;
                            try {
                                rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
                            } catch (Exception e) {
                                log.warn("parse RowChange error, skip", e);
                                continue;
                            }
                            CanalEntry.EventType type = rowChange.getEventType();
                            String schema = entry.getHeader().getSchemaName();
                            String table  = entry.getHeader().getTableName();
                            // 只处理源库的变更:目标库与源库在同一实例且表名相同时,
                            // 自身写入的 binlog 会被 canal 再次捕获,导致重复处理、统计翻倍
                            if (src.getDbName() != null && !src.getDbName().isEmpty()
                                    && schema != null && !schema.isEmpty()
                                    && !schema.equalsIgnoreCase(src.getDbName())) continue;
                            if (!table.equalsIgnoreCase(tableName)) continue;

                            for (CanalEntry.RowData rd : rowChange.getRowDatasList()) {
                                try {
                                    if (type == CanalEntry.EventType.INSERT) {
                                        applyInsert(tgt, table, rd.getAfterColumnsList());
                                        inserts++;
                                        appendContent(contentSb, "INSERT", rd.getAfterColumnsList(), null);
                                    } else if (type == CanalEntry.EventType.UPDATE) {
                                        applyUpdate(tgt, table, rd.getAfterColumnsList());
                                        updates++;
                                        appendContent(contentSb, "UPDATE", rd.getAfterColumnsList(), rd.getBeforeColumnsList());
                                    } else if (type == CanalEntry.EventType.DELETE) {
                                        applyDelete(tgt, table, rd.getBeforeColumnsList());
                                        deletes++;
                                        appendContent(contentSb, "DELETE", rd.getBeforeColumnsList(), null);
                                    }
                                } catch (Exception e) {
                                    errs++;
                                    if (firstErr == null) firstErr = e.getMessage();
                                    log.error("[IncrSync] apply error table={}", table, e);
                                }
                            }
                        }

                        // ack - 标记 batch 已处理
                        connector.ack(batchId);

                        // 持久化 position
                        if (size > 0) {
                            SyncCanalPosition p = positionMapper.selectOne(
                                    new QueryWrapper<SyncCanalPosition>().eq("task_id", task.getId()));
                            if (p == null) {
                                p = new SyncCanalPosition();
                                p.setTaskId(task.getId());
                                p.setDestination(dest);
                                p.setCreateTime(new Date());
                            }
                            p.setJournalName(lastEntry.getHeader().getLogfileName());
                            p.setPosition(lastEntry.getHeader().getLogfileOffset());
                            p.setTimestamp(lastEntry.getHeader().getExecuteTime());
                            p.setUpdateTime(new Date());
                            if (p.getId() == null) positionMapper.insert(p);
                            else positionMapper.updateById(p);
                        }

                        // 更新进度统计(失败也要落库,否则同步出错时界面无任何提示)
                        if (inserts + updates + deletes + errs > 0) {
                            SyncTaskProgress progress = progressMapper.selectOne(
                                    new QueryWrapper<SyncTaskProgress>().eq("task_id", task.getId()));
                            if (progress != null) {
                                progress.setSuccessRows((progress.getSuccessRows() == null ? 0L : progress.getSuccessRows()) + inserts + updates + deletes);
                                progress.setFailedRows((progress.getFailedRows() == null ? 0L : progress.getFailedRows()) + errs);
                                progress.setTotalRows((progress.getTotalRows() == null ? 0L : progress.getTotalRows()) + inserts + updates + deletes);
                                progress.setUpdateTime(new Date());
                                progressMapper.updateById(progress);
                            }
                            log.info("[IncrSync] task[{}] inserts={}, updates={}, deletes={}, errs={}",
                                    task.getTaskName(), inserts, updates, deletes, errs);
                            // 批次日志异步落库, 界面"同步日志"可见
                            logService.writeLog(ctx, batchNo, startMarker, endMarker,
                                    inserts + updates + deletes,
                                    progress.getTotalRows() == null ? 0L : progress.getTotalRows(),
                                    System.currentTimeMillis() - batchStartMs,
                                    errs > 0 ? SyncType.LOG_FAILED : SyncType.LOG_SUCCESS,
                                    errs > 0 ? "本批次 " + errs + " 条应用失败: " + firstErr : null,
                                    contentSb.length() == 0 ? null : contentSb.toString());
                        }
                    } catch (Exception e) {
                        log.error("[IncrSync] apply batch error", e);
                        // 回滚 - 不 ack,下次重做
                        connector.rollback(batchId);
                        SyncTaskProgress pg = progressMapper.selectOne(
                                new QueryWrapper<SyncTaskProgress>().eq("task_id", task.getId()));
                        logService.writeLog(ctx, batchNo, startMarker, endMarker, 0,
                                pg == null || pg.getTotalRows() == null ? 0L : pg.getTotalRows(),
                                System.currentTimeMillis() - batchStartMs,
                                SyncType.LOG_FAILED, e.getMessage());
                        AlertUtils.alert(task, "增量批次异常",
                                "【DataMove告警】增量任务[" + task.getTaskName() + "]异常:\n" + e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("[IncrSync] canal worker fatal", e);
                AlertUtils.alert(task, "增量连接异常",
                        "【DataMove告警】增量任务[" + task.getTaskName() + "]连接异常:\n" + e.getMessage());

                SyncTaskProgress p = progressMapper.selectOne(
                        new QueryWrapper<SyncTaskProgress>().eq("task_id", task.getId()));
                if (p != null) {
                    p.setStatus(SyncType.STATUS_FAILED);
                    p.setEndTime(new Date());
                    p.setUpdateTime(new Date());
                    progressMapper.updateById(p);
                }
                task.setStatus(SyncType.STATUS_FAILED);
                taskMapper.updateById(task);

                WORKERS.remove(task.getId());
                JdbcUtils.closeQuietly();
            } finally {
                try { connector.disconnect(); } catch (Exception ignored) {}
            }
        }

        /* ============ DML 应用 ============ */

        private void applyInsert(SyncDatasource ds, String table, List<CanalEntry.Column> cols) throws Exception {
            StringBuilder sql = new StringBuilder("INSERT INTO `").append(table).append("` (");
            List<CanalEntry.Column> list = cols;
            for (int i = 0; i < list.size(); i++) sql.append("`").append(list.get(i).getName()).append("`").append(i < list.size() - 1 ? "," : "");
            sql.append(") VALUES (");
            for (int i = 0; i < list.size(); i++) sql.append("?").append(i < list.size() - 1 ? "," : "");
            sql.append(") ON DUPLICATE KEY UPDATE ");
            for (int i = 0; i < list.size(); i++)
                sql.append("`").append(list.get(i).getName()).append("`=VALUES(`").append(list.get(i).getName()).append("`)")
                   .append(i < list.size() - 1 ? "," : "");

            try (Connection c = JdbcUtils.getConnection(ds);
                 PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < list.size(); i++) {
                    CanalEntry.Column col = list.get(i);
                    // Canal 对 NULL 列返回的是空串,必须显式 setNull,
                    // 否则 int/decimal/datetime 等列会报 "Incorrect integer value: ''"
                    if (col.getIsNull()) {
                        ps.setNull(i + 1, java.sql.Types.NULL);
                    } else {
                        ps.setObject(i + 1, col.getValue());
                    }
                }
                ps.executeUpdate();
            }
        }

        private void applyUpdate(SyncDatasource ds, String table, List<CanalEntry.Column> cols) throws Exception {
            // 文档未强制要求幂等,这里为简化复用 INSERT,注意 Update 用 REPLACE 更合理
            applyInsert(ds, table, cols);
        }

        private void applyDelete(SyncDatasource ds, String table, List<CanalEntry.Column> cols) throws Exception {
            // 取主键的简化: 取第一个 key=PRI 的列
            CanalEntry.Column pk = null;
            for (CanalEntry.Column c : cols) if (c.getIsKey()) { pk = c; break; }
            if (pk == null) {
                log.warn("[IncrSync] no pk column found, skip delete");
                return;
            }
            String sql = "DELETE FROM `" + table + "` WHERE `" + pk.getName() + "` = ?";
            try (Connection c = JdbcUtils.getConnection(ds);
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, pk.getValue());
                ps.executeUpdate();
            }
        }
    }
}
