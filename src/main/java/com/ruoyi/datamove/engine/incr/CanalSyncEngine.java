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
import com.ruoyi.datamove.engine.metrics.TaskMetrics;
import com.ruoyi.datamove.engine.metrics.TaskMetricsRegistry;
import com.ruoyi.datamove.task.domain.SyncCanalPosition;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskFieldMapping;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;
import com.ruoyi.datamove.task.mapper.SyncCanalPositionMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskFieldMappingMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskProgressMapper;
import com.ruoyi.datamove.task.service.TaskRunService;
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
 *  - 字段映射支持: 把 rowdata 里的源列名按 sync_task_field_mapping 重命名后再写入目标
 *    (未配对的列保持原名, 等同"老逻辑", 兼容老任务)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CanalSyncEngine {

    private final SyncTaskMapper         taskMapper;
    private final SyncTaskProgressMapper progressMapper;
    private final SyncDatasourceMapper   datasourceMapper;
    private final SyncCanalPositionMapper positionMapper;
    private final SyncTaskFieldMappingMapper fieldMappingMapper;
    private final SyncLogService         logService;
    private final TaskMetricsRegistry    metricsRegistry;
    private final TaskRunService         runService;

    /** 任务 -> worker executor */
    private static final Map<Long, CanalWorker> WORKERS = new ConcurrentHashMap<>();

    /** 单条日志记录的数据内容上限(字符), 超出后不再拼接明细 */
    private static final int MAX_CONTENT_CHARS = 3500;

    /** 构造增量批次日志上下文 (含字段映射) */
    private SyncContext buildCtx(SyncTask task, SyncDatasource src, SyncDatasource tgt) {
        SyncContext ctx = SyncContext.builder()
                .task(task).source(src).target(tgt)
                .batchNo(new java.util.concurrent.atomic.AtomicLong(1))
                .pauseFlag(new java.util.concurrent.atomic.AtomicBoolean(false))
                .stopFlag(new java.util.concurrent.atomic.AtomicBoolean(false))
                .build();
        loadFieldMappings(ctx, task);
        return ctx;
    }

    /** 启动时一次性加载映射, 与 FullSyncEngine 同样的解析逻辑 */
    private void loadFieldMappings(SyncContext ctx, SyncTask task) {
        List<SyncTaskFieldMapping> mappings = fieldMappingMapper.selectList(
                new QueryWrapper<SyncTaskFieldMapping>()
                        .eq("task_id", task.getId())
                        .orderByAsc("sort_no", "id"));
        ctx.setFieldMappings(mappings);
        if (mappings == null || mappings.isEmpty()) return;
        Map<String, String> src2tgt = new LinkedHashMap<>();
        List<String> srcList = new ArrayList<>();
        List<String> tgtList = new ArrayList<>();
        for (SyncTaskFieldMapping m : mappings) {
            if (m == null || m.getSourceField() == null || m.getTargetField() == null) continue;
            src2tgt.put(m.getSourceField(), m.getTargetField());
            srcList.add(m.getSourceField());
            tgtList.add(m.getTargetField());
        }
        ctx.setSrcFieldToTarget(src2tgt);
        ctx.setOrderedSourceFields(srcList);
        ctx.setOrderedTargetFields(tgtList);
        log.info("[IncrSync] task[{}] 字段映射加载完成, 共 {} 条配对", task.getTaskName(), src2tgt.size());
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

        // 实时指标: 增量任务无固定总量(不展示 ETA),
        // 瓶颈看「写入目标库耗时 vs 等待 binlog 事件耗时」
        SyncTaskProgress base = progressMapper.selectOne(
                new QueryWrapper<SyncTaskProgress>().eq("task_id", taskId));
        TaskMetrics metrics = metricsRegistry.ensure(taskId, 1000);
        metrics.setCompareMode(TaskMetrics.MODE_STREAM);
        metrics.activate(-1L, base == null || base.getSuccessRows() == null ? 0L : base.getSuccessRows());

        // 运行历史: 记一条 RUNNING 记录(增量任务长期运行), worker 结束时回填结果
        Long runId = runService.begin(task, src, tgt);
        CanalWorker worker = new CanalWorker(task, connector, src, tgt, runId);
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
        // 运行历史: 立即收口(worker 内也会收口, 幂等)
        runService.finishRunning(taskId, SyncType.STATUS_STOP, null);
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
        /** 本次运行历史ID (可能为 null: 写历史失败) */
        private final Long runId;
        private volatile boolean running = true;
        /** 已应用批次数, 供运行历史回填 */
        private int batchNo = 0;

        public CanalWorker(SyncTask task, CanalConnector connector,
                           SyncDatasource src, SyncDatasource tgt, Long runId) {
            this.task = task;
            this.connector = connector;
            this.src = src;
            this.tgt = tgt;
            this.runId = runId;
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
         * 服务端订阅过滤表达式: 只订阅「源库.任务表」
         *  - canal filter 是逗号分隔的正则, 库名/表名里的正则元字符要先转义
         *  - 源库或表名缺失时退化为 ".*\\..*" (老行为), 客户端仍有兜底过滤
         */
        private String buildSubscribeFilter() {
            String db = src.getDbName();
            String table = task.getTableName();
            if (db == null || db.trim().isEmpty() || table == null || table.trim().isEmpty()) {
                return ".*\\..*";
            }
            return escapeRegex(db.trim()) + "\\." + escapeRegex(table.trim());
        }

        /** 正则元字符转义 (canal filter 表达式按正则解析) */
        private String escapeRegex(String s) {
            return s.replaceAll("([^\\w])", "\\\\$1");
        }

        /**
         * DML 类型过滤: 解析任务配置 "INSERT,UPDATE,DELETE" 的子集
         *  - 空/null/非法值 → 返回 null, 表示不过滤 (三种全部同步, 老任务零感知)
         *  - 非法 token 直接忽略, 全部非法时同样退化为 null
         */
        private Set<CanalEntry.EventType> parseDmlFilter(String config) {
            if (config == null || config.trim().isEmpty()) return null;
            Set<CanalEntry.EventType> allowed = EnumSet.noneOf(CanalEntry.EventType.class);
            for (String t : config.toUpperCase().split(",")) {
                String token = t.trim();
                if ("INSERT".equals(token)) allowed.add(CanalEntry.EventType.INSERT);
                else if ("UPDATE".equals(token)) allowed.add(CanalEntry.EventType.UPDATE);
                else if ("DELETE".equals(token)) allowed.add(CanalEntry.EventType.DELETE);
            }
            return allowed.isEmpty() ? null : allowed;
        }

        /**
         * 解析「忽略字段」配置 (CSV, 列名按 remapColumns 之后的目标列名匹配)
         *
         * 适用场景: 源表有些列 (create_time, update_time, is_deleted 等) 不需要落到目标,
         *           或者源/目标列名相同但业务上希望只读不写
         *
         * @return 列名集合 (永远保留 key 列, 否则 UPDATE/DELETE 找不到目标行);
         *         空 = 不过滤 (与 DML 过滤 null 语义一致)
         */
        private Set<String> parseIgnoreFields(String csv) {
            if (csv == null || csv.trim().isEmpty()) return null;
            Set<String> s = new HashSet<>();
            for (String t : csv.split(",")) {
                t = t.trim();
                if (!t.isEmpty()) s.add(t);
            }
            return s.isEmpty() ? null : s;
        }

        /**
         * 按 ignoreFields 移除「非 key 列」。key 列永远保留 —— 否则 UPDATE/DELETE
         * 无法用 key 定位目标行, 出现「删多/插多/错位」。
         */
        private List<CanalEntry.Column> filterIgnored(List<CanalEntry.Column> cols, Set<String> ignored) {
            if (ignored == null || ignored.isEmpty()) return cols;
            if (cols == null || cols.isEmpty()) return cols;
            List<CanalEntry.Column> keep = new ArrayList<>(cols.size());
            for (CanalEntry.Column c : cols) {
                if (c.getIsKey() || !ignored.contains(c.getName())) keep.add(c);
            }
            return keep;
        }

        /**
         * 记录一行变更内容,供界面"同步内容"展示
         *  - INSERT/DELETE: 列出全部字段 (按映射后的目标名)
         *  - UPDATE: 先给主键,再只列出真正发生变化的字段
         */
        private void appendContent(StringBuilder sb, String op, List<CanalEntry.Column> after,
                                   List<CanalEntry.Column> before, SyncContext ctx) {
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

        /**
         * 字段映射: 把 canal rowdata 里的源列名替换为目标列名
         * - 未配对的列保持原名 (等同"老逻辑"), 避免与目标表无关的字段出错
         * - 主键列也要按映射改 (否则 DELETE 找不到目标行)
         */
        private List<CanalEntry.Column> remapColumns(SyncContext ctx, List<CanalEntry.Column> cols) {
            if (!ctx.isMappingEnabled() || cols == null || cols.isEmpty()) return cols;
            Map<String, String> map = ctx.getSrcFieldToTarget();
            List<CanalEntry.Column> out = new ArrayList<>(cols.size());
            for (CanalEntry.Column c : cols) {
                String newName = map.getOrDefault(c.getName(), c.getName());
                CanalEntry.Column.Builder b = CanalEntry.Column.newBuilder()
                        .setName(newName)
                        .setIsKey(c.getIsKey())
                        .setIsNull(c.getIsNull())
                        .setMysqlType(c.getMysqlType())
                        .setIndex(c.getIndex());
                if (!c.getIsNull()) b.setValue(c.getValue());
                if (c.hasUpdated()) b.setUpdated(c.getUpdated());
                out.add(b.build());
            }
            return out;
        }

        @Override
        public void run() {
            String tableName = task.getTableName();
            String dest      = task.getCanalDestination();
            try {
                connector.connect();
                // 订阅过滤: 服务端只订阅「源库.任务表」, 无关库/表的事件根本不进客户端, 减少网络与解析开销
                // 源库缺失时退化为全量订阅 .*\\..* (老行为), 客户端仍有一层库/表兜底过滤
                connector.subscribe(buildSubscribeFilter());
                connector.rollback();

                // 回放到上次 position
                SyncCanalPosition pos = positionMapper.selectOne(
                        new QueryWrapper<SyncCanalPosition>().eq("task_id", task.getId()));
                if (pos != null && pos.getJournalName() != null) {
                    // Canal server 端已持久化 offset,客户端无需 rollback
                    log.info("[IncrSync] resume from {}", pos.getJournalName());
                }

                SyncContext ctx = buildCtx(task, src, tgt);
                ctx.setRunId(runId);
                log.info("[IncrSync] task[{}] mappingEnabled={}", task.getTaskName(), ctx.isMappingEnabled());
                // DML 类型过滤 (INSERT/UPDATE/DELETE 子集, 空 = 全部)
                Set<CanalEntry.EventType> dmlAllowed = parseDmlFilter(task.getBinlogDmlTypes());
                log.info("[IncrSync] task[{}] dmlFilter={}", task.getTaskName(),
                        dmlAllowed == null ? "ALL" : dmlAllowed);
                // 「忽略字段」过滤: 非 key 列不在 INSERT/UPDATE SQL 中写出 (key 列永远保留)
                Set<String> ignoredFields = parseIgnoreFields(task.getIgnoreFields());
                log.info("[IncrSync] task[{}] ignoreFields={}", task.getTaskName(),
                        ignoredFields == null ? "[]" : ignoredFields);
                TaskMetrics metrics = metricsRegistry.get(task.getId());
                batchNo = 0;
                while (running) {
                    // 等待并拉取 binlog 批次的耗时
                    long getStartMs = System.currentTimeMillis();
                    Message message = connector.getWithoutAck(1000);
                    long getMs = System.currentTimeMillis() - getStartMs;
                    long batchId = message.getId();
                    int size = message.getEntries().size();
                    if (size == 0 || batchId == -1) continue;
                    batchNo++;

                    if (metrics != null) {
                        metrics.beginBatch(batchNo, size);
                        metrics.readRows(size);
                    }

                    long batchStartMs = System.currentTimeMillis();
                    CanalEntry.Entry firstEntry = message.getEntries().get(0);
                    CanalEntry.Entry lastEntry  = message.getEntries().get(size - 1);
                    String startMarker = mark(firstEntry);
                    String endMarker   = mark(lastEntry);

                    int inserts = 0, updates = 0, deletes = 0, errs = 0, filtered = 0;
                    String firstErr = null;
                    StringBuilder contentSb = new StringBuilder();
                    long applyStartMs = System.currentTimeMillis();
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
                            // DML 类型过滤: 任务只勾了部分类型时, 其余事件直接丢弃, 不污染下游
                            if (!dmlAllowed.contains(type)) {
                                filtered++;
                                continue;
                            }

                            for (CanalEntry.RowData rd : rowChange.getRowDatasList()) {
                                try {
                                    if (type == CanalEntry.EventType.INSERT) {
                                        applyInsert(tgt, table, ctx, rd.getAfterColumnsList());
                                        inserts++;
                                        appendContent(contentSb, "INSERT",
                                                remapColumns(ctx, rd.getAfterColumnsList()), null, ctx);
                                    } else if (type == CanalEntry.EventType.UPDATE) {
                                        // before 列用于精确定位旧行(主键/唯一键变更时必须), 不能只传 after
                                        applyUpdate(tgt, table, ctx,
                                                rd.getAfterColumnsList(), rd.getBeforeColumnsList());
                                        updates++;
                                        appendContent(contentSb, "UPDATE",
                                                remapColumns(ctx, rd.getAfterColumnsList()),
                                                remapColumns(ctx, rd.getBeforeColumnsList()), ctx);
                                    } else if (type == CanalEntry.EventType.DELETE) {
                                        applyDelete(tgt, table, ctx, rd.getBeforeColumnsList());
                                        deletes++;
                                        appendContent(contentSb, "DELETE",
                                                remapColumns(ctx, rd.getBeforeColumnsList()), null, ctx);
                                    }
                                } catch (Exception e) {
                                    errs++;
                                    if (firstErr == null) firstErr = e.getMessage();
                                    log.error("[IncrSync] apply error table={}", table, e);
                                }
                            }
                        }

                        // 本批应用到目标库的耗时, 用于大盘瓶颈判断
                        if (metrics != null) {
                            metrics.finishBatch(inserts + updates + deletes, getMs,
                                    System.currentTimeMillis() - applyStartMs,
                                    System.currentTimeMillis() - batchStartMs);
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
                                // 运行历史心跳(最多 5s 一次落库)
                                runService.heartbeat(ctx.getRunId(), progress, batchNo);
                            }
                            log.info("[IncrSync] task[{}] inserts={}, updates={}, deletes={}, errs={}, filtered={}",
                                    task.getTaskName(), inserts, updates, deletes, errs, filtered);
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
                long totalRows = p == null || p.getTotalRows() == null ? 0L : p.getTotalRows();
                if (p != null) {
                    p.setStatus(SyncType.STATUS_FAILED);
                    p.setEndTime(new Date());
                    p.setUpdateTime(new Date());
                    progressMapper.updateById(p);
                }
                task.setStatus(SyncType.STATUS_FAILED);
                taskMapper.updateById(task);

                // 写一条 batchNo=0 的失败日志, "日志"弹窗里能看到具体异常
                String errMsg = e.getMessage();
                runService.finish(runId, SyncType.STATUS_FAILED, p, batchNo,
                        errMsg == null ? e.getClass().getName() : errMsg);
                SyncContext ctx = buildCtx(task, src, tgt);
                logService.writeLog(ctx, 0, null, null, 0, totalRows, 0L,
                        SyncType.LOG_FAILED,
                        errMsg == null ? e.getClass().getName() : errMsg);

                WORKERS.remove(task.getId());
                JdbcUtils.closeQuietly();
            } finally {
                TaskMetrics m = metricsRegistry.get(task.getId());
                if (m != null) m.deactivate();
                // 运行历史: 停止/异常后仍为 RUNNING 的记录兜底收口(finish 幂等)
                runService.finish(runId, SyncType.STATUS_STOP,
                        progressMapper.selectOne(new QueryWrapper<SyncTaskProgress>().eq("task_id", task.getId())),
                        batchNo, null);
                try { connector.disconnect(); } catch (Exception ignored) {}
            }
        }

        /* ============ DML 应用 (支持映射重写 column name) ============ */

        /** 绑定单个参数: Canal 对 NULL 列返回空串, 必须显式 setNull */
        private void setColumn(PreparedStatement ps, int idx, CanalEntry.Column col) throws Exception {
            if (col.getIsNull()) {
                ps.setNull(idx, java.sql.Types.NULL);
            } else {
                ps.setObject(idx, col.getValue());
            }
        }

        private void applyInsert(SyncDatasource ds, String table, SyncContext ctx,
                                 List<CanalEntry.Column> rawCols) throws Exception {
            // 按字段映射重写列名 (不破坏数据, 仅改列名)
            List<CanalEntry.Column> cols = remapColumns(ctx, rawCols);
            // 按 ignoreFields 过滤 (key 列不删)
            cols = filterIgnored(cols, ignoredFields);

            StringBuilder sql = new StringBuilder("INSERT INTO `").append(table).append("` (");
            for (int i = 0; i < cols.size(); i++) sql.append("`").append(cols.get(i).getName()).append("`").append(i < cols.size() - 1 ? "," : "");
            sql.append(") VALUES (");
            for (int i = 0; i < cols.size(); i++) sql.append("?").append(i < cols.size() - 1 ? "," : "");
            sql.append(") ON DUPLICATE KEY UPDATE ");
            for (int i = 0; i < cols.size(); i++)
                sql.append("`").append(cols.get(i).getName()).append("`=VALUES(`").append(cols.get(i).getName()).append("`)")
                   .append(i < cols.size() - 1 ? "," : "");

            try (Connection c = JdbcUtils.getConnection(ds);
                 PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < cols.size(); i++) {
                    setColumn(ps, i + 1, cols.get(i));
                }
                ps.executeUpdate();
            }
        }

        /**
         * UPDATE: 用 before 的 key 列**精确定位**目标行后更新。
         *
         * <p>修复前直接复用 {@code applyInsert}, 只在"key 值未变"时才碰巧正确:
         * <ul>
         *   <li>源库改了主键/唯一键值 (UPDATE t SET id=5 WHERE id=3): ON DUPLICATE KEY UPDATE
         *       找不到 id=5 便走 INSERT, 旧行 id=3 残留成僵尸行</li>
         *   <li>目标表没有主键/唯一键: ON DUPLICATE KEY UPDATE 完全失效, 退化成裸 INSERT,
         *       每条 UPDATE 都插一行新数据, 重跑即翻倍</li>
         * </ul>
         *
         * <p>改为按 before key 定位后, 三条分支各司其职:
         * <ul>
         *   <li>命中 1 行 → 正常更新 (key 值变更也能落库: SET k=新值 WHERE k=旧值)</li>
         *   <li>命中 0 行 → 目标行缺失 (同步从中间开始 / 被手工删掉), 退化为 upsert 补齐</li>
         *   <li>命中 &gt;1 行 → WHERE 不唯一, 目标表 key 结构有问题, 记 WARN 便于排查</li>
         * </ul>
         */
        private void applyUpdate(SyncDatasource ds, String table, SyncContext ctx,
                                 List<CanalEntry.Column> rawAfter,
                                 List<CanalEntry.Column> rawBefore) throws Exception {
            List<CanalEntry.Column> after = remapColumns(ctx, rawAfter);
            if (after == null || after.isEmpty()) return;
            // 按 ignoreFields 过滤 (key 列不删; beforeKeys 已是 key 列, 单独来自 rawBefore, 不需过滤)
            after = filterIgnored(after, ignoredFields);

            List<CanalEntry.Column> beforeKeys = CanalRowKeys.keyColumns(remapColumns(ctx, rawBefore));
            if (beforeKeys.isEmpty()) {
                // before 里没有 key 列 (源表无主键 / binlog 未带 before 列): 无法定位旧行,
                // 只能退化为 upsert —— 与修复前行为一致, 但要留下痕迹
                log.warn("[IncrSync] UPDATE event has no key column, fallback to upsert, table={}", table);
                applyInsert(ds, table, ctx, rawAfter);
                return;
            }

            StringBuilder sql = new StringBuilder("UPDATE `").append(table).append("` SET ");
            for (int i = 0; i < after.size(); i++) {
                sql.append("`").append(after.get(i).getName()).append("`=?")
                   .append(i < after.size() - 1 ? "," : "");
            }
            sql.append(" WHERE ").append(CanalRowKeys.buildWhere(beforeKeys));

            int affected;
            try (Connection c = JdbcUtils.getConnection(ds);
                 PreparedStatement ps = c.prepareStatement(sql.toString())) {
                int idx = 1;
                for (CanalEntry.Column col : after) setColumn(ps, idx++, col);
                for (CanalEntry.Column k : beforeKeys) ps.setObject(idx++, k.getValue());
                affected = ps.executeUpdate();
            }

            if (affected == 0) {
                // 目标库没有这一行(同步从中间开始 / 行被手工删掉): 补插, 避免数据缺失
                log.warn("[IncrSync] UPDATE matched 0 row, target row missing, fallback to upsert, table={}, key=[{}]",
                        table, CanalRowKeys.names(beforeKeys));
                applyInsert(ds, table, ctx, rawAfter);
            } else if (affected > 1) {
                log.warn("[IncrSync] UPDATE matched {} rows (>1), target key is not unique, table={}, key=[{}]",
                        affected, table, CanalRowKeys.names(beforeKeys));
            }
        }

        /**
         * DELETE: 按 before 的**全部** key 列定位并删除。
         *
         * <p>修复前只取第一个 key 列 ({@code for (...) if (isKey) { pk = c; break; }}),
         * 复合主键表 (order_id, item_id) 会退化成 {@code WHERE order_id = ?},
         * 把该 order_id 下的**所有**明细行一并删掉; 且 executeUpdate() 的返回值被丢弃,
         * 删多了不留任何痕迹。
         */
        private void applyDelete(SyncDatasource ds, String table, SyncContext ctx,
                                 List<CanalEntry.Column> rawCols) throws Exception {
            // 字段映射: PK 列名也要重命名 (源 PK 列名 -> 目标 PK 列名)
            List<CanalEntry.Column> keys = CanalRowKeys.keyColumns(remapColumns(ctx, rawCols));
            if (keys.isEmpty()) {
                log.warn("[IncrSync] DELETE event has no key column, skip, table={}", table);
                return;
            }
            for (CanalEntry.Column k : keys) {
                if (k.getIsNull()) {
                    log.warn("[IncrSync] DELETE key column [{}] is null, skip, table={}", k.getName(), table);
                    return;
                }
            }

            String sql = "DELETE FROM `" + table + "` WHERE " + CanalRowKeys.buildWhere(keys);
            try (Connection c = JdbcUtils.getConnection(ds);
                 PreparedStatement ps = c.prepareStatement(sql)) {
                int idx = 1;
                for (CanalEntry.Column k : keys) ps.setObject(idx++, k.getValue());
                int affected = ps.executeUpdate();
                if (affected > 1) {
                    // 复合主键漏拼 / 目标表 key 不唯一 的明确信号, 必须留痕
                    log.warn("[IncrSync] DELETE matched {} rows (>1), target key is not unique, table={}, key=[{}]",
                            affected, table, CanalRowKeys.names(keys));
                }
            }
        }
    }
}