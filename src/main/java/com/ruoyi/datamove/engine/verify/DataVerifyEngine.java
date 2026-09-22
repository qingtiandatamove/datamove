package com.ruoyi.datamove.engine.verify;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.mapper.SyncDatasourceMapper;
import com.ruoyi.datamove.engine.SyncContext;
import com.ruoyi.datamove.engine.consts.SyncType;
import com.ruoyi.datamove.engine.log.SyncLogService;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskDiff;
import com.ruoyi.datamove.task.domain.SyncTaskFieldMapping;
import com.ruoyi.datamove.task.domain.SyncTaskVerify;
import com.ruoyi.datamove.task.mapper.SyncTaskDiffMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskFieldMappingMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskVerifyMapper;
import com.ruoyi.datamove.util.AlertUtils;
import com.ruoyi.datamove.util.JdbcUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据差异对账 + 一键修复引擎
 *
 * <p>定位: 同步任务跑完之后的一次「旁路体检」。它不参与同步, 也不改 sync_task.status,
 * 触发方式是用户在任务列表上点一下, 所以不需要暂停/续跑那套状态机。
 *
 * <p>对账算法 —— 源/目标各开一个流式游标, 按主键升序双指针归并:
 * <pre>
 *   源主键 < 目标主键  → 该行源有目标无  = MISSING  (源多出来的, 补 INSERT)
 *   源主键 > 目标主键  → 该行目标有源无  = EXTRA    (目标脏数据, 只报告不删)
 *   两边主键相等       → 比字段值, 不同 = MISMATCH (只更新不一致的列)
 * </pre>
 * 相比「两边分别 count(*) 比总数」, 归并能定位到具体哪一行、哪个字段, 这才有可能"一键修复";
 * 相比「分段 checksum」, 它不需要额外建索引或全表排序, 且天然支持断点式进度上报。
 *
 * <p>两个游标都用 {@code setFetchSize(Integer.MIN_VALUE)} 走 MySQL 流式读取, 内存里
 * 始终只有一行 —— 否则一张千万行表会把两边的结果集全拉进堆里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataVerifyEngine {

    private final SyncTaskMapper              taskMapper;
    private final SyncDatasourceMapper        datasourceMapper;
    private final SyncTaskFieldMappingMapper  fieldMappingMapper;
    private final SyncTaskVerifyMapper        verifyMapper;
    private final SyncTaskDiffMapper          diffMapper;
    private final SyncLogService              logService;
    private final ObjectMapper                objectMapper;

    /** 同一任务同时只允许一个校验在跑 (key = taskId) */
    private static final Set<Long> RUNNING_TASKS = ConcurrentHashMap.newKeySet();
    /** 校验中止标记 (key = verifyId) */
    private static final Map<Long, AtomicBoolean> STOP_FLAGS = new ConcurrentHashMap<>();
    /** 修复中止标记 (key = verifyId) */
    private static final Map<Long, AtomicBoolean> REPAIR_STOP_FLAGS = new ConcurrentHashMap<>();

    /** 差异明细落库上限: 差异可能上百万条, 全落库既慢又撑爆表; 统计数字始终是全量准确的 */
    private static final int  MAX_SAVED_DIFFS = 2000;
    /** 差异明细批量落库条数 */
    private static final int  DIFF_FLUSH_SIZE = 200;
    /** 比对进度落库间隔(行) */
    private static final long PROGRESS_EVERY_ROWS = 5000L;
    /** 校验日志落库间隔(行) */
    private static final long LOG_EVERY_ROWS = 50000L;
    /** 每批修复条数 */
    private static final int  REPAIR_BATCH = 200;

    /* ==================== 对外接口 ==================== */

    /**
     * 启动一次数据校验: 同步解析比对计划(快, 出错立刻抛给调用方), 异步跑比对
     *
     * @return 校验ID, 前端据此轮询进度
     */
    public Long start(Long taskId) {
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null) throw new RuntimeException("任务不存在: " + taskId);
        if (SyncType.TASK_DDL.equalsIgnoreCase(task.getTaskType())) {
            throw new RuntimeException("同步表结构(DDL)任务没有数据可校验");
        }
        SyncDatasource src = datasourceMapper.selectById(task.getSourceId());
        SyncDatasource tgt = datasourceMapper.selectById(task.getTargetId());
        if (src == null || tgt == null) throw new RuntimeException("任务关联的数据源不存在");

        // 比对计划同步构建: 表不存在 / 列对不上这类问题要让用户点下去就报错, 而不是异步静默失败
        VerifyPlan plan = buildPlan(task, tgt);
        if (plan.srcCols.isEmpty()) throw new RuntimeException("没有可比对的列, 请检查目标表是否存在: " + task.getTableName());

        if (!RUNNING_TASKS.add(taskId)) throw new RuntimeException("该任务已有校验正在进行中");

        SyncTaskVerify v = new SyncTaskVerify();
        v.setTaskId(taskId);
        v.setTaskName(task.getTaskName());
        v.setTableName(task.getTableName());
        v.setSourceName(src.getDatasourceName());
        v.setTargetName(tgt.getDatasourceName());
        v.setIdField(plan.tgtIdCol);
        v.setCompareFields(RowDiffUtils.join(plan.tgtCols));
        v.setIgnoreFields(task.getIgnoreFields());
        v.setStatus(SyncType.STATUS_RUNNING);
        v.setCheckedRows(0L);
        v.setSourceRows(0L);
        v.setTargetRows(0L);
        v.setMissingRows(0L);
        v.setMismatchRows(0L);
        v.setExtraRows(0L);
        v.setDiffRows(0L);
        v.setSavedDiffs(0);
        v.setTruncated(0);
        v.setRepairTotal(0L);
        v.setRepairedRows(0L);
        v.setRepairFailedRows(0L);
        v.setCostMs(0L);
        v.setRepairCostMs(0L);
        v.setStartTime(new Date());
        v.setCreateTime(new Date());
        v.setUpdateTime(new Date());
        verifyMapper.insert(v);

        log.info("[Verify] task[{}] 启动数据校验 verifyId={}, 表={}, 列数={}, 主键={}",
                task.getTaskName(), v.getId(), task.getTableName(), plan.srcCols.size(), plan.tgtIdCol);

        final Long verifyId = v.getId();
        final AtomicBoolean stopFlag = new AtomicBoolean(false);
        STOP_FLAGS.put(verifyId, stopFlag);

        Thread t = new Thread(() -> doVerify(verifyId, task, src, tgt, plan, stopFlag), "datamove-verify-" + taskId);
        t.setDaemon(true);
        t.start();
        return verifyId;
    }

    /** 中止校验: 只置标记, 线程自己收口(避免连接被强行关闭留下一堆半截状态) */
    public void stop(Long verifyId) {
        AtomicBoolean f = STOP_FLAGS.get(verifyId);
        if (f == null) throw new RuntimeException("校验不在运行中");
        f.set(true);
    }

    /** 最近一次校验记录(含运行中的), 任务列表刷新时用来显示入口按钮状态 */
    public SyncTaskVerify latest(Long taskId) {
        return verifyMapper.selectOne(new QueryWrapper<SyncTaskVerify>()
                .eq("task_id", taskId)
                .orderByDesc("id")
                .last("LIMIT 1"));
    }

    /**
     * 一键同步差异: 把该次校验发现的所有 PENDING 差异(缺失 + 不一致)在目标库重放一遍。
     * 多余行(EXTRA)不在此列 —— 以源库为准的删除是破坏性动作, 不能藏在一个"一键修复"里。
     */
    public void repair(Long verifyId) {
        SyncTaskVerify v = verifyMapper.selectById(verifyId);
        if (v == null) throw new RuntimeException("校验记录不存在: " + verifyId);
        if (SyncType.STATUS_RUNNING.equals(v.getStatus())) {
            throw new RuntimeException("校验还在进行中, 请等它跑完再修复");
        }
        if (SyncType.STATUS_RUNNING.equals(v.getRepairStatus())) {
            throw new RuntimeException("该次校验的修复正在进行中");
        }
        long pending = countPending(verifyId);
        if (pending == 0) throw new RuntimeException("没有需要修复的差异");

        SyncTask task = taskMapper.selectById(v.getTaskId());
        if (task == null) throw new RuntimeException("任务不存在, 无法修复");
        SyncDatasource src = datasourceMapper.selectById(task.getSourceId());
        SyncDatasource tgt = datasourceMapper.selectById(task.getTargetId());
        if (src == null || tgt == null) throw new RuntimeException("任务关联的数据源不存在");

        SyncTaskVerify up = new SyncTaskVerify();
        up.setId(verifyId);
        up.setRepairStatus(SyncType.STATUS_RUNNING);
        up.setRepairTotal(pending);
        up.setRepairStartTime(new Date());
        up.setRepairEndTime(null);
        up.setUpdateTime(new Date());
        verifyMapper.updateById(up);

        if (!REPAIR_STOP_FLAGS.containsKey(verifyId)) {
            REPAIR_STOP_FLAGS.put(verifyId, new AtomicBoolean(false));
        }
        final AtomicBoolean stopFlag = REPAIR_STOP_FLAGS.get(verifyId);
        stopFlag.set(false);

        log.info("[Verify] verifyId={} 启动一键修复, 待修复 {} 行", verifyId, pending);
        Thread t = new Thread(() -> doRepair(v.getTaskId(), verifyId, src, tgt, task, stopFlag),
                "datamove-repair-" + verifyId);
        t.setDaemon(true);
        t.start();
    }

    /** 中止修复 */
    public void stopRepair(Long verifyId) {
        AtomicBoolean f = REPAIR_STOP_FLAGS.get(verifyId);
        if (f == null) throw new RuntimeException("修复不在运行中");
        f.set(true);
    }

    private long countPending(Long verifyId) {
        Long c = diffMapper.selectCount(new QueryWrapper<SyncTaskDiff>()
                .eq("verify_id", verifyId)
                .eq("repair_status", SyncTaskDiff.REPAIR_PENDING)
                .in("diff_type", Arrays.asList(SyncTaskDiff.TYPE_MISSING, SyncTaskDiff.TYPE_MISMATCH)));
        return c == null ? 0L : c;
    }

    /* ==================== 校验主流程 ==================== */

    private void doVerify(Long verifyId, SyncTask task, SyncDatasource srcDs, SyncDatasource tgtDs,
                          VerifyPlan plan, AtomicBoolean stopFlag) {
        long startMs = System.currentTimeMillis();

        long sourceRows = 0, targetRows = 0, missing = 0, mismatch = 0, extra = 0;
        long lastProgressRows = 0, lastLogRows = 0;
        int saved = 0;
        boolean truncated = false;
        List<SyncTaskDiff> buffer = new ArrayList<>();

        String srcSql = buildSelectSql(task.getTableName(), plan.srcIdCol, plan.srcCols);
        String tgtSql = buildSelectSql(task.getTableName(), plan.tgtIdCol, plan.tgtCols);
        SyncContext logCtx = SyncContext.builder().task(task).build();

        try (Connection srcConn = JdbcUtils.newConnection(srcDs);
             Connection tgtConn = JdbcUtils.newConnection(tgtDs);
             RowCursor sc = new RowCursor(srcConn, srcSql, plan.srcCols, plan.idIdx);
             RowCursor tc = new RowCursor(tgtConn, tgtSql, plan.tgtCols, plan.idIdx)) {

            boolean sHas = sc.advance();
            boolean tHas = tc.advance();

            while ((sHas || tHas) && !stopFlag.get()) {
                SyncTaskDiff d = null;

                if (!sHas) {
                    // 源已扫完, 目标剩下的全是多余行
                    targetRows++;
                    extra++;
                    d = newDiff(verifyId, task.getId(), SyncTaskDiff.TYPE_EXTRA, tc, plan, null);
                    tHas = tc.advance();
                } else if (!tHas) {
                    // 目标已扫完, 源剩下的全是缺失行
                    sourceRows++;
                    missing++;
                    d = newDiff(verifyId, task.getId(), SyncTaskDiff.TYPE_MISSING, sc, plan, null);
                    sHas = sc.advance();
                } else {
                    int cmp = RowDiffUtils.compareKey(sc.key(), tc.key());
                    if (cmp < 0) {
                        sourceRows++;
                        missing++;
                        d = newDiff(verifyId, task.getId(), SyncTaskDiff.TYPE_MISSING, sc, plan, null);
                        sHas = sc.advance();
                    } else if (cmp > 0) {
                        targetRows++;
                        extra++;
                        d = newDiff(verifyId, task.getId(), SyncTaskDiff.TYPE_EXTRA, tc, plan, null);
                        tHas = tc.advance();
                    } else {
                        sourceRows++;
                        targetRows++;
                        List<String> diffs = RowDiffUtils.diffColumns(
                                sc.row(), tc.row(), plan.compareIdx, plan.tgtCols);
                        if (!diffs.isEmpty()) {
                            mismatch++;
                            d = newDiff(verifyId, task.getId(), SyncTaskDiff.TYPE_MISMATCH, sc, plan,
                                    RowDiffUtils.join(diffs));
                        }
                        sHas = sc.advance();
                        tHas = tc.advance();
                    }
                }

                if (d != null) {
                    if (saved < MAX_SAVED_DIFFS) {
                        buffer.add(d);
                        saved++;
                        if (buffer.size() >= DIFF_FLUSH_SIZE) {
                            flushDiffs(buffer);
                        }
                    } else {
                        truncated = true;
                    }
                }

                long done = sourceRows + targetRows;
                if (done - lastProgressRows >= PROGRESS_EVERY_ROWS) {
                    lastProgressRows = done;
                    updateVerifyProgress(verifyId, sourceRows, targetRows, missing, mismatch, extra,
                            saved, truncated, sHas ? sc.key() : null);
                }
                if (done - lastLogRows >= LOG_EVERY_ROWS) {
                    lastLogRows = done;
                    logService.writeLog(logCtx, (int) (done / LOG_EVERY_ROWS),
                            String.valueOf(sHas ? sc.key() : "-"), "-", 0, done,
                            System.currentTimeMillis() - startMs, SyncType.LOG_RUNNING, null,
                            String.format("已比对 %d 行, 缺失 %d, 不一致 %d, 多余 %d", done, missing, mismatch, extra));
                }
            }

            flushDiffs(buffer);

            boolean stopped = stopFlag.get();
            long costMs = System.currentTimeMillis() - startMs;

            SyncTaskVerify up = new SyncTaskVerify();
            up.setId(verifyId);
            up.setStatus(stopped ? SyncType.STATUS_STOP : SyncType.STATUS_COMPLETED);
            up.setSourceRows(sourceRows);
            up.setTargetRows(targetRows);
            up.setCheckedRows(sourceRows + targetRows);
            up.setMissingRows(missing);
            up.setMismatchRows(mismatch);
            up.setExtraRows(extra);
            up.setDiffRows(missing + mismatch);
            up.setSavedDiffs(saved);
            up.setTruncated(truncated ? 1 : 0);
            up.setLastKey(sc.key() == null ? null : RowDiffUtils.normalize(sc.key()));
            up.setCostMs(costMs);
            up.setEndTime(new Date());
            up.setUpdateTime(new Date());
            verifyMapper.updateById(up);

            String summary = String.format("比对完成: 源 %d 行, 目标 %d 行, 缺失 %d, 不一致 %d, 多余 %d%s",
                    sourceRows, targetRows, missing, mismatch, extra,
                    truncated ? " (差异明细超过 " + MAX_SAVED_DIFFS + " 条, 仅保留前 " + MAX_SAVED_DIFFS + " 条)" : "");
            logService.writeLog(logCtx, 0, "VERIFY", "-", 0, sourceRows + targetRows, costMs,
                    stopped ? SyncType.LOG_FAILED : SyncType.LOG_SUCCESS, null, summary);
            log.info("[Verify] verifyId={} {} 耗时 {}ms", verifyId, summary, costMs);

            // 有差异才告警: 用户既然点了校验, 就是想第一时间知道数据对不上
            if (!stopped && (missing + mismatch) > 0) {
                try {
                    AlertUtils.alert(task, "[DataMove 数据校验] 任务[" + task.getTaskName() + "] 发现数据差异",
                            summary + "\n可在任务列表点「查看差异」并一键同步缺失数据。");
                } catch (Exception e) {
                    log.warn("[Verify] 差异告警发送失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[Verify] verifyId={} 校验失败", verifyId, e);
            flushDiffs(buffer);
            markFailed(verifyId, e);
            try {
                logService.writeLog(SyncContext.builder().task(task).build(), 0, "VERIFY", "-", 0,
                        sourceRows + targetRows, System.currentTimeMillis() - startMs,
                        SyncType.LOG_FAILED, e.getMessage(), null);
            } catch (Exception ignored) {
            }
            try {
                AlertUtils.alert(task, "[DataMove 数据校验] 任务[" + task.getTaskName() + "] 校验失败",
                        e.getMessage());
            } catch (Exception ignored) {
            }
        } finally {
            RUNNING_TASKS.remove(task.getId());
            STOP_FLAGS.remove(verifyId);
        }
    }

    private void markFailed(Long verifyId, Exception e) {
        try {
            SyncTaskVerify up = new SyncTaskVerify();
            up.setId(verifyId);
            up.setStatus(SyncType.STATUS_FAILED);
            up.setErrorMsg(truncate(e.getMessage(), 2000));
            up.setEndTime(new Date());
            up.setUpdateTime(new Date());
            verifyMapper.updateById(up);
        } catch (Exception ex) {
            log.error("[Verify] 回写失败状态出错 verifyId={}", verifyId, ex);
        }
    }

    private void updateVerifyProgress(Long verifyId, long sourceRows, long targetRows,
                                      long missing, long mismatch, long extra,
                                      int saved, boolean truncated, Object cursorKey) {
        try {
            SyncTaskVerify up = new SyncTaskVerify();
            up.setId(verifyId);
            up.setSourceRows(sourceRows);
            up.setTargetRows(targetRows);
            up.setCheckedRows(sourceRows + targetRows);
            up.setMissingRows(missing);
            up.setMismatchRows(mismatch);
            up.setExtraRows(extra);
            up.setDiffRows(missing + mismatch);
            up.setSavedDiffs(saved);
            up.setTruncated(truncated ? 1 : 0);
            up.setLastKey(cursorKey == null ? null : RowDiffUtils.normalize(cursorKey));
            up.setUpdateTime(new Date());
            verifyMapper.updateById(up);
        } catch (Exception e) {
            log.warn("[Verify] 更新校验进度失败 verifyId={}: {}", verifyId, e.getMessage());
        }
    }

    /** 组装一条差异: 快照取「源行」的语义 —— 修复时按它回放, 所以 MISSING/MISMATCH 都存源行 */
    private SyncTaskDiff newDiff(Long verifyId, Long taskId, String type,
                                 RowCursor cursor, VerifyPlan plan, String diffFields) {
        SyncTaskDiff d = new SyncTaskDiff();
        d.setVerifyId(verifyId);
        d.setTaskId(taskId);
        d.setDiffType(type);
        d.setPkValue(RowDiffUtils.normalize(cursor.key()));
        d.setDiffFields(diffFields);
        d.setRepairStatus(SyncTaskDiff.TYPE_EXTRA.equals(type)
                ? SyncTaskDiff.REPAIR_SKIPPED : SyncTaskDiff.REPAIR_PENDING);
        d.setCreateTime(new Date());
        if (SyncTaskDiff.TYPE_EXTRA.equals(type)) {
            // 多余行的"样貌"在目标侧, 存目标行便于用户判断该不该手工清理
            d.setTargetRow(toJson(plan.tgtCols, cursor.row()));
        } else {
            d.setSourceRow(toJson(plan.tgtCols, cursor.row()));
        }
        return d;
    }

    private void flushDiffs(List<SyncTaskDiff> buffer) {
        if (buffer.isEmpty()) return;
        for (SyncTaskDiff d : buffer) {
            try {
                diffMapper.insert(d);
            } catch (Exception e) {
                log.warn("[Verify] 差异明细落库失败 pk={}: {}", d.getPkValue(), e.getMessage());
            }
        }
        buffer.clear();
    }

    /* ==================== 修复主流程 ==================== */

    private void doRepair(Long taskId, Long verifyId, SyncDatasource srcDs, SyncDatasource tgtDs,
                          SyncTask task, AtomicBoolean stopFlag) {
        long startMs = System.currentTimeMillis();
        long repaired = 0, failed = 0;
        SyncContext logCtx = SyncContext.builder().task(task).build();

        try {
            VerifyPlan plan = buildPlan(task, tgtDs);
            // 修复必须回到源库取"当前值"再写目标: 差异明细里的快照是归一化后的字符串,
            // 用它回写会把 BLOB 写坏、把类型信息丢掉; 顺带也能拿到源库最新的值。
            Map<String, String> tgtToSrc = new LinkedHashMap<>();
            for (int i = 0; i < plan.tgtCols.size(); i++) tgtToSrc.put(plan.tgtCols.get(i), plan.srcCols.get(i));

            String insertSql = buildInsertSql(task.getTableName(), plan.tgtCols);
            String pkTgtCol = plan.tgtIdCol;

            long lastId = 0L;
            while (!stopFlag.get()) {
                List<SyncTaskDiff> batch = diffMapper.selectList(new QueryWrapper<SyncTaskDiff>()
                        .eq("verify_id", verifyId)
                        .eq("repair_status", SyncTaskDiff.REPAIR_PENDING)
                        .in("diff_type", Arrays.asList(SyncTaskDiff.TYPE_MISSING, SyncTaskDiff.TYPE_MISMATCH))
                        .gt("id", lastId)
                        .orderByAsc("id")
                        .last("LIMIT " + REPAIR_BATCH));
                if (batch.isEmpty()) break;
                lastId = batch.get(batch.size() - 1).getId();

                // 一次把这一批涉及的行从源库捞回来 (IN 查询), 避免逐行 round-trip
                Map<String, Object[]> srcRows = loadSourceRows(srcDs, task.getTableName(),
                        plan, batch);

                for (SyncTaskDiff d : batch) {
                    if (stopFlag.get()) break;
                    Object[] srcRow = srcRows.get(keyOf(d.getPkValue()));
                    if (srcRow == null) {
                        // 源行已不存在(校验之后被删了) —— 标失败而不是硬塞一条脏数据进目标
                        markDiff(d.getId(), SyncTaskDiff.REPAIR_FAILED, "源库已不存在该行, 跳过");
                        failed++;
                        continue;
                    }
                    try {
                        if (SyncTaskDiff.TYPE_MISSING.equals(d.getDiffType())) {
                            insertRow(tgtDs, task.getTableName(), plan, insertSql, srcRow);
                        } else {
                            updateRow(tgtDs, task.getTableName(), plan, tgtToSrc, pkTgtCol, d, srcRow);
                        }
                        markDiff(d.getId(), SyncTaskDiff.REPAIR_DONE, null);
                        repaired++;
                    } catch (Exception e) {
                        markDiff(d.getId(), SyncTaskDiff.REPAIR_FAILED, truncate(e.getMessage(), 900));
                        failed++;
                        log.warn("[Verify] verifyId={} 修复失败 pk={}: {}", verifyId, d.getPkValue(), e.getMessage());
                    }
                }

                SyncTaskVerify up = new SyncTaskVerify();
                up.setId(verifyId);
                up.setRepairedRows(repaired);
                up.setRepairFailedRows(failed);
                up.setUpdateTime(new Date());
                verifyMapper.updateById(up);
            }

            boolean stopped = stopFlag.get();
            long costMs = System.currentTimeMillis() - startMs;
            SyncTaskVerify up = new SyncTaskVerify();
            up.setId(verifyId);
            up.setRepairStatus(stopped ? SyncType.STATUS_STOP : SyncType.STATUS_COMPLETED);
            up.setRepairedRows(repaired);
            up.setRepairFailedRows(failed);
            up.setRepairCostMs(costMs);
            up.setRepairEndTime(new Date());
            up.setUpdateTime(new Date());
            verifyMapper.updateById(up);

            String summary = String.format("差异修复%s: 成功 %d 行, 失败 %d 行", stopped ? "中止" : "完成", repaired, failed);
            logService.writeLog(logCtx, 0, "REPAIR", "-", (int) repaired, repaired + failed,
                    costMs, failed > 0 ? SyncType.LOG_FAILED : SyncType.LOG_SUCCESS, null, summary);
            log.info("[Verify] verifyId={} {} 耗时 {}ms", verifyId, summary, costMs);
        } catch (Exception e) {
            log.error("[Verify] verifyId={} 修复失败", verifyId, e);
            SyncTaskVerify up = new SyncTaskVerify();
            up.setId(verifyId);
            up.setRepairStatus(SyncType.STATUS_FAILED);
            up.setRepairedRows(repaired);
            up.setRepairFailedRows(failed);
            up.setErrorMsg(truncate("修复失败: " + e.getMessage(), 2000));
            up.setRepairEndTime(new Date());
            up.setUpdateTime(new Date());
            verifyMapper.updateById(up);
            try {
                AlertUtils.alert(task, "[DataMove 数据修复] 任务[" + task.getTaskName() + "] 修复失败", e.getMessage());
            } catch (Exception ignored) {
            }
        } finally {
            REPAIR_STOP_FLAGS.remove(verifyId);
        }
    }

    /**
     * 按这批差异的主键, 从源库一次性取回当前行数据 (单条 SELECT ... WHERE pk IN (...))
     */
    private Map<String, Object[]> loadSourceRows(SyncDatasource srcDs, String table,
                                                 VerifyPlan plan, List<SyncTaskDiff> batch) throws Exception {
        Map<String, Object[]> result = new LinkedHashMap<>();
        List<String> pks = new ArrayList<>(batch.size());
        for (SyncTaskDiff d : batch) {
            if (d.getPkValue() != null) pks.add(d.getPkValue());
        }
        if (pks.isEmpty()) return result;

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(joinQuoted(plan.srcCols));
        sql.append(" FROM `").append(table).append("` WHERE `").append(plan.srcIdCol).append("` IN (");
        for (int i = 0; i < pks.size(); i++) sql.append(i > 0 ? ",?" : "?");
        sql.append(")");

        try (Connection c = JdbcUtils.newConnection(srcDs);
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < pks.size(); i++) ps.setObject(i + 1, pks.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Object[] row = new Object[plan.srcCols.size()];
                    for (int i = 0; i < plan.srcCols.size(); i++) row[i] = rs.getObject(plan.srcCols.get(i));
                    result.put(keyOf(RowDiffUtils.normalize(row[plan.idIdx])), row);
                }
            }
        }
        return result;
    }

    private void insertRow(SyncDatasource tgtDs, String table, VerifyPlan plan,
                           String insertSql, Object[] srcRow) throws Exception {
        try (Connection c = JdbcUtils.newConnection(tgtDs);
             PreparedStatement ps = c.prepareStatement(insertSql)) {
            for (int i = 0; i < plan.tgtCols.size(); i++) ps.setObject(i + 1, srcRow[i]);
            ps.executeUpdate();
        }
    }

    /** MISMATCH: 只更新值不一致的那几列, 不整行回写 —— 避免覆盖目标库其他列上可能存在的并发写入 */
    private void updateRow(SyncDatasource tgtDs, String table, VerifyPlan plan,
                           Map<String, String> tgtToSrc, String pkTgtCol,
                           SyncTaskDiff d, Object[] srcRow) throws Exception {
        List<String> setCols = new ArrayList<>();
        if (d.getDiffFields() != null && !d.getDiffFields().isEmpty()) {
            for (String f : d.getDiffFields().split(",")) {
                String col = f.trim();
                if (!col.isEmpty() && plan.tgtCols.contains(col) && !col.equals(pkTgtCol)) setCols.add(col);
            }
        }
        if (setCols.isEmpty()) return;

        StringBuilder sql = new StringBuilder("UPDATE `").append(table).append("` SET ");
        for (int i = 0; i < setCols.size(); i++) {
            sql.append(i > 0 ? "," : "").append("`").append(setCols.get(i)).append("`=?");
        }
        sql.append(" WHERE `").append(pkTgtCol).append("`=?");

        try (Connection c = JdbcUtils.newConnection(tgtDs);
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            for (String col : setCols) {
                String srcCol = tgtToSrc.get(col);
                int si = plan.srcCols.indexOf(srcCol);
                ps.setObject(idx++, si >= 0 ? srcRow[si] : null);
            }
            ps.setObject(idx, srcRow[plan.idIdx]);
            ps.executeUpdate();
        }
    }

    private void markDiff(Long diffId, String status, String error) {
        try {
            SyncTaskDiff up = new SyncTaskDiff();
            up.setId(diffId);
            up.setRepairStatus(status);
            up.setRepairError(error);
            up.setRepairTime(new Date());
            diffMapper.updateById(up);
        } catch (Exception e) {
            log.warn("[Verify] 回写差异修复状态失败 id={}: {}", diffId, e.getMessage());
        }
    }

    /* ==================== 比对计划 ==================== */

    /**
     * 构建「源列 ↔ 目标列」的有序配对, 与同步引擎的口径保持一致:
     * 配了字段映射就按映射走, 没配就按同名走(列集取目标表的实际列)。
     */
    private VerifyPlan buildPlan(SyncTask task, SyncDatasource tgt) {
        String table = task.getTableName();
        LinkedHashMap<String, String> pairs = new LinkedHashMap<>();

        List<SyncTaskFieldMapping> mappings = fieldMappingMapper.selectList(
                new QueryWrapper<SyncTaskFieldMapping>()
                        .eq("task_id", task.getId())
                        .orderByAsc("sort_no", "id"));
        if (mappings != null && !mappings.isEmpty()) {
            for (SyncTaskFieldMapping m : mappings) {
                if (m.getSourceField() == null || m.getTargetField() == null) continue;
                pairs.put(m.getSourceField(), m.getTargetField());
            }
        } else {
            List<Map<String, String>> cols = JdbcUtils.listColumns(tgt, table);
            for (Map<String, String> c : cols) {
                String name = c.get("columnName");
                if (name != null) pairs.put(name, name);
            }
        }

        VerifyPlan plan = new VerifyPlan();
        String tgtId = (task.getIdField() == null || task.getIdField().isEmpty()) ? "id" : task.getIdField();
        String srcId = null;
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            if (tgtId.equals(e.getValue())) { srcId = e.getKey(); break; }
        }
        // 主键可能没配进映射(部分字段映射场景), 但比对必须靠它归并, 补上
        if (srcId == null) srcId = tgtId;
        if (!pairs.containsKey(srcId)) pairs.put(srcId, tgtId);

        plan.srcCols = new ArrayList<>(pairs.keySet());
        plan.tgtCols = new ArrayList<>(pairs.values());
        plan.srcIdCol = srcId;
        plan.tgtIdCol = tgtId;
        plan.idIdx = plan.srcCols.indexOf(srcId);
        if (plan.idIdx < 0) plan.idIdx = 0;

        // 忽略列: 允许用户按源列名或目标列名填写, 统一归一到目标列名
        Set<String> ignored = new LinkedHashSet<>();
        for (String name : parseCsv(task.getIgnoreFields())) {
            if (pairs.containsValue(name)) ignored.add(name);
            else if (pairs.containsKey(name)) ignored.add(pairs.get(name));
        }
        plan.ignored = ignored;

        // 参与值比较的列: 排除主键(归并阶段已确认相等)与被忽略的列
        plan.compareIdx = new ArrayList<>();
        for (int i = 0; i < plan.tgtCols.size(); i++) {
            if (i == plan.idIdx) continue;
            if (ignored.contains(plan.tgtCols.get(i))) continue;
            plan.compareIdx.add(i);
        }
        return plan;
    }

    /* ==================== 工具 ==================== */

    private String buildSelectSql(String table, String idCol, List<String> cols) {
        return "SELECT " + joinQuoted(cols)
                + " FROM `" + table + "`"
                + " ORDER BY `" + idCol + "` ASC";
    }

    private static String joinQuoted(List<String> cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("`").append(cols.get(i)).append("`");
        }
        return sb.toString();
    }

    private String buildInsertSql(String table, List<String> cols) {
        StringBuilder sb = new StringBuilder("INSERT INTO `").append(table).append("` (");
        for (int i = 0; i < cols.size(); i++) sb.append("`").append(cols.get(i)).append("`").append(i < cols.size() - 1 ? "," : "");
        sb.append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) sb.append("?").append(i < cols.size() - 1 ? "," : "");
        // 幂等: 修复期间目标行被别人插进去了也不会重复插入(原地更新)
        sb.append(") ON DUPLICATE KEY UPDATE ");
        for (int i = 0; i < cols.size(); i++) {
            sb.append("`").append(cols.get(i)).append("`=VALUES(`").append(cols.get(i)).append("`)")
              .append(i < cols.size() - 1 ? "," : "");
        }
        return sb.toString();
    }

    private String toJson(List<String> cols, Object[] vals) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < cols.size() && i < vals.length; i++) {
            m.put(cols.get(i), RowDiffUtils.normalize(vals[i]));
        }
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return String.valueOf(m);
        }
    }

    private static Set<String> parseCsv(String csv) {
        Set<String> set = new LinkedHashSet<>();
        if (csv == null || csv.trim().isEmpty()) return set;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }

    /** 主键值的规范化文本形式, 用于 Map 索引(MISSING 快照与 IN 查询结果的键必须同源) */
    private static String keyOf(String normalizedPk) {
        return normalizedPk == null ? "" : normalizedPk;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...(已截断)";
    }

    /* ==================== 内部类 ==================== */

    /** 一次校验的比对计划(构建后只读) */
    private static class VerifyPlan {
        List<String> srcCols;
        List<String> tgtCols;
        String srcIdCol;
        String tgtIdCol;
        int idIdx;
        List<Integer> compareIdx;
        Set<String> ignored;
    }

    /**
     * 流式行游标: 只持有"当前一行", 内存占用与表大小无关。
     *
     * <p>{@code setFetchSize(Integer.MIN_VALUE)} 是 MySQL 驱动的流式读取开关 —— 它让驱动
     * 逐行从 socket 拉取而不是把整个结果集读进内存。代价是连接在读完前被独占, 所以这里
     * 一律用 {@link JdbcUtils#newConnection} 取独立连接, 不复用同步任务的长连接缓存。
     */
    private static class RowCursor implements AutoCloseable {
        private final PreparedStatement ps;
        private final ResultSet rs;
        private final List<String> cols;
        private final int keyIdx;
        private Object[] row;
        private Object key;

        RowCursor(Connection conn, String sql, List<String> cols, int keyIdx) throws SQLException {
            this.cols = cols;
            this.keyIdx = keyIdx;
            this.ps = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            this.ps.setFetchSize(Integer.MIN_VALUE);
            this.rs = ps.executeQuery();
        }

        /** 推进到下一行, 返回是否还有数据 */
        boolean advance() throws SQLException {
            if (!rs.next()) {
                row = null;
                key = null;
                return false;
            }
            Object[] r = new Object[cols.size()];
            for (int i = 0; i < cols.size(); i++) r[i] = rs.getObject(cols.get(i));
            row = r;
            key = (keyIdx >= 0 && keyIdx < r.length) ? r[keyIdx] : null;
            return true;
        }

        Object key() { return key; }

        Object[] row() { return row; }

        @Override
        public void close() {
            // 流式结果集未读完就关闭: 驱动内部会尝试消费剩余数据, 失败也不影响本次校验的结论
            JdbcUtils.closeQuietly(rs, ps);
        }
    }
}
