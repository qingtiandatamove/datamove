package com.ruoyi.datamove.audit.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.LoginUser;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.datamove.audit.domain.AuditLog;
import com.ruoyi.datamove.audit.mapper.AuditLogMapper;
import com.ruoyi.datamove.task.domain.SyncTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计日志服务 (字段级变更追踪)
 *
 * 写入:
 *   - recordTaskCreate(task)                 任务新增时调用
 *   - recordTaskUpdate(before, after)        任务修改时调用 (只写真正变化的字段, 同次请求共享 revision_id)
 *   - recordTaskDelete(task)                 任务删除(软删)时调用
 *   - recordTaskAction(task, action, ...)    任务「启动/暂停/继续/停止」动作时调用 (单行, 不是字段级 diff)
 *
 * 查询:
 *   - page(...)                               分页列表 (供前端审计日志页用)
 *
 * 异常兜底: 审计日志写入失败绝不抛出去, 只 warn 一下 —— 审计日志是合规可选项, 不能让它搞挂主流程
 *
 * 对应文档 4.7
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final int MAX_TEXT_LEN = 4000;

    private static final AtomicLong SEQ = new AtomicLong(0);
    private static final ThreadLocal<SimpleDateFormat> FMT = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

    private final AuditLogMapper auditLogMapper;

    /* ============ 写入 ============ */

    public void recordTaskCreate(SyncTask task) {
        if (task == null) return;
        long rev = nextRevisionId();
        // CREATE: 仅 after=task, before=null. 原代码写成了 (rev, task, null) 把 task 传给了 before,
        // 导致 addIfChanged 里 getter.apply(after=null) 直接 NPE, 新任务压根写不进审计
        List<AuditLog> rows = buildDiffRows(rev, null, task, "CREATE");
        if (rows.isEmpty()) return;
        safeInsertBatch(rows, "CREATE", task.getId());
    }

    public void recordTaskUpdate(SyncTask before, SyncTask after) {
        if (before == null || after == null) return;
        long rev = nextRevisionId();
        List<AuditLog> rows = buildDiffRows(rev, before, after, "UPDATE");
        if (rows.isEmpty()) return;
        safeInsertBatch(rows, "UPDATE", after.getId());
    }

    public void recordTaskDelete(SyncTask task) {
        if (task == null) return;
        long rev = nextRevisionId();
        // DELETE: 仅 before=task (被删时的快照), after=null. 调用方语义: 记录「任务被删那一刻的全部字段快照」
        List<AuditLog> rows = buildDiffRows(rev, task, null, "DELETE");
        if (rows.isEmpty()) return;
        safeInsertBatch(rows, "DELETE", task.getId());
    }

    /**
     * 任务「启动 / 暂停 / 继续 / 停止」动作审计
     *
     * 与 recordTaskCreate/Update/Delete 不同 —— 这里是「任务生命周期」事件, 不是字段级 diff,
     * 因此不走 buildDiffRows, 直接构造一条单行审计记录:
     *   - revision_id 唯一 (一次动作一行)
     *   - op_type      START / PAUSE / RESUME / STOP
     *   - field_name   "status"  —— 复用字段列展示「动作前状态 → 动作后状态」
     *   - old_value    动作前的 status
     *   - new_value    动作意图的新 status (start=RUNNING / pause=PAUSE / resume=RUNNING / stop=STOP)
     *
     * 调用方应保证在引擎方法成功之后再调本方法 —— 如果引擎抛异常, 这次动作就没发生, 不应记审计
     */
    public void recordTaskAction(SyncTask task, String action, String oldStatus, String newStatus) {
        if (task == null) return;
        if (!"START".equals(action) && !"STOP".equals(action)
                && !"PAUSE".equals(action) && !"RESUME".equals(action)) return;
        long rev = nextRevisionId();
        AuditLog row = new AuditLog();
        row.setRevisionId(rev);
        row.setEntityType("sync_task");
        row.setEntityId(task.getId());
        row.setEntityName(task.getTaskName());
        row.setOpType(action);
        row.setFieldName("status");
        row.setOldValue(oldStatus);
        row.setNewValue(newStatus);
        fillOperator(row);
        row.setCreateTime(new Date());
        safeInsertBatch(Collections.singletonList(row), action, task.getId());
    }

    /* ============ 查询 ============ */

    public PageResult<AuditLog> page(String keyword, String opType, String beginTime, String endTime,
                                     String orderByColumn, String isAsc,
                                     int pageNum, int pageSize) {
        Page<AuditLog> page = new Page<>(pageNum, pageSize);
        QueryWrapper<AuditLog> wrapper = new QueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("entity_name", kw)
                    .or().like("field_name", kw)
                    .or().like("operator_name", kw)
                    .or().like("old_value", kw)
                    .or().like("new_value", kw)
                    .or().like("ip", kw));
        }
        if (StrUtil.isNotBlank(opType)) wrapper.eq("op_type", opType);
        if (StrUtil.isNotBlank(beginTime)) wrapper.ge("create_time", beginTime);
        if (StrUtil.isNotBlank(endTime))   wrapper.le("create_time", endTime);

        // 排序白名单: 防止前端传入任意列名拼到 SQL
        String col = (orderByColumn == null || orderByColumn.isEmpty()) ? "id" : orderByColumn;
        if (!"id".equals(col) && !"create_time".equals(col)) col = "id";
        boolean asc = "asc".equalsIgnoreCase(isAsc);
        wrapper.orderBy(true, asc, col);

        Page<AuditLog> result = auditLogMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal());
    }

    /**
     * 查询同一次请求 (revision_id) 下的所有字段变更 —— 详情弹窗用
     */
    public List<AuditLog> listByRevision(long revisionId) {
        return auditLogMapper.selectList(
                new QueryWrapper<AuditLog>().eq("revision_id", revisionId).orderByAsc("id"));
    }

    /* ============ 字段 diff ============ */

    /**
     * 构造一批审计行
     *
     * CREATE/DELETE: before=null 或 after=null, 把整条记录的「当前值」按字段拆开
     * UPDATE:        before/after 都非空, 只保留真正变化的字段
     *
     * 字段列表与 SyncTaskServiceImpl.update(...) 的白名单赋值完全对齐 —— 漏一行 = 漏审计
     */
    private List<AuditLog> buildDiffRows(long revisionId, SyncTask before, SyncTask after, String opType) {
        SyncTask snapshot = after != null ? after : before;
        List<AuditLog> rows = new ArrayList<>();

        addIfChanged(rows, revisionId, snapshot, opType, "taskName",        before, after, SyncTask::getTaskName);
        addIfChanged(rows, revisionId, snapshot, opType, "taskType",        before, after, SyncTask::getTaskType);
        addIfChanged(rows, revisionId, snapshot, opType, "syncMode",        before, after, SyncTask::getSyncMode);
        addIfChanged(rows, revisionId, snapshot, opType, "sourceId",        before, after, SyncTask::getSourceId);
        addIfChanged(rows, revisionId, snapshot, opType, "targetId",        before, after, SyncTask::getTargetId);
        addIfChanged(rows, revisionId, snapshot, opType, "tableName",       before, after, SyncTask::getTableName);
        addIfChanged(rows, revisionId, snapshot, opType, "idField",         before, after, SyncTask::getIdField);
        addIfChanged(rows, revisionId, snapshot, opType, "timeField",       before, after, SyncTask::getTimeField);
        addIfChanged(rows, revisionId, snapshot, opType, "startId",         before, after, SyncTask::getStartId);
        addIfChanged(rows, revisionId, snapshot, opType, "startTime",       before, after, SyncTask::getStartTime);
        addIfChanged(rows, revisionId, snapshot, opType, "batchSize",       before, after, SyncTask::getBatchSize);
        addIfChanged(rows, revisionId, snapshot, opType, "shardCount",      before, after, SyncTask::getShardCount);
        addIfChanged(rows, revisionId, snapshot, opType, "ignoreFields",    before, after, SyncTask::getIgnoreFields);
        addIfChanged(rows, revisionId, snapshot, opType, "overwriteFlag",   before, after, SyncTask::getOverwriteFlag);
        addIfChanged(rows, revisionId, snapshot, opType, "dingtalkWebhook", before, after, SyncTask::getDingtalkWebhook);
        addIfChanged(rows, revisionId, snapshot, opType, "alertEmail",      before, after, SyncTask::getAlertEmail);
        addIfChanged(rows, revisionId, snapshot, opType, "canalHost",       before, after, SyncTask::getCanalHost);
        addIfChanged(rows, revisionId, snapshot, opType, "canalPort",       before, after, SyncTask::getCanalPort);
        addIfChanged(rows, revisionId, snapshot, opType, "canalDestination",before, after, SyncTask::getCanalDestination);
        addIfChanged(rows, revisionId, snapshot, opType, "binlogDmlTypes",  before, after, SyncTask::getBinlogDmlTypes);
        // 调度方式: 记录类型与表达式; eventToken 是触发密钥, 不写入审计日志
        addIfChanged(rows, revisionId, snapshot, opType, "triggerType",     before, after, SyncTask::getTriggerType);
        addIfChanged(rows, revisionId, snapshot, opType, "cronExpr",        before, after, SyncTask::getCronExpr);
        addIfChanged(rows, revisionId, snapshot, opType, "remark",          before, after, SyncTask::getRemark);

        // CREATE 还要单独记下 status (add() 强制写成 STOP); DELETE 不打, 反正删了状态没意义
        if ("CREATE".equals(opType)) {
            addIfChanged(rows, revisionId, snapshot, opType, "status", before, after, SyncTask::getStatus);
        }

        return rows;
    }

    /**
     * 字段值提取: String/Number/Date 各自归一化为字符串, null 保持 null, 便于对账
     */
    private static String valueOf(Object v) {
        if (v == null) return null;
        if (v instanceof Date) return FMT.get().format((Date) v);
        return String.valueOf(v);
    }

    /**
     * 单字段审计行规则:
     *   - UPDATE:  old 与 new 都 null 跳过; old == new 跳过; 其余写一行
     *   - CREATE:  old=null, new=after 当前值; new=null 时跳过 (不写空字段, 减少噪声)
     *   - DELETE:  old=before 当前值; new=null; old=null 时跳过
     */
    private static void addIfChanged(List<AuditLog> rows, long revisionId, SyncTask snapshot, String opType,
                                        String fieldName, SyncTask before, SyncTask after,
                                        java.util.function.Function<SyncTask, Object> getter) {
            // CREATE 传 before=null, DELETE 传 after=null. 不做 null 防护 getter.apply(null) 会 NPE
            String oldVal = before == null ? null : valueOf(getter.apply(before));
            String newVal = after  == null ? null : valueOf(getter.apply(after));

        boolean keep;
        if ("UPDATE".equals(opType)) {
            keep = !(oldVal == null && newVal == null) && !java.util.Objects.equals(oldVal, newVal);
        } else if ("CREATE".equals(opType)) {
            keep = newVal != null;
        } else if ("DELETE".equals(opType)) {
            keep = oldVal != null;
        } else {
            keep = false;
        }
        if (!keep) return;

        AuditLog row = new AuditLog();
        row.setRevisionId(revisionId);
        row.setEntityType("sync_task");
        row.setEntityId(snapshot.getId());
        row.setEntityName(snapshot.getTaskName());
        row.setOpType(opType);
        row.setFieldName(fieldName);
        row.setOldValue(truncate(oldVal));
        row.setNewValue(truncate(newVal));
        fillOperator(row);
        row.setCreateTime(new Date());
        rows.add(row);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > MAX_TEXT_LEN ? s.substring(0, MAX_TEXT_LEN) : s;
    }

    /* ============ 操作人上下文 ============ */

    private static void fillOperator(AuditLog row) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof LoginUser) {
                LoginUser u = (LoginUser) auth.getPrincipal();
                row.setOperatorId(u.getUserId());
                row.setOperatorName(u.getUserName());
            }
        } catch (Exception ignored) {
        }
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                row.setIp(StrUtil.maxLength(clientIp(req), 64));
                row.setUserAgent(StrUtil.maxLength(req.getHeader("User-Agent"), 255));
            }
        } catch (Exception ignored) {
        }
    }

    /** 取真实客户端IP(兼容 nginx 等反向代理) */
    private static String clientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String header : headers) {
            String ip = request.getHeader(header);
            if (StrUtil.isNotBlank(ip) && !"unknown".equalsIgnoreCase(ip)) {
                int idx = ip.indexOf(',');
                return idx > 0 ? ip.substring(0, idx).trim() : ip.trim();
            }
        }
        return request.getRemoteAddr();
    }

    /* ============ revision_id 生成 (单进程够用) ============ */

    private static long nextRevisionId() {
        return System.currentTimeMillis() * 1000L + (SEQ.incrementAndGet() & 0x3FFL);
    }

    /* ============ 写入异常兜底 ============ */

    private void safeInsertBatch(List<AuditLog> rows, String opType, Long taskId) {
        try {
            for (AuditLog r : rows) {
                auditLogMapper.insert(r);
            }
        } catch (Exception e) {
            log.warn("[AUDIT] 写入审计日志失败 opType={} taskId={} rows={} err={}",
                    opType, taskId, rows.size(), e.getMessage());
        }
    }
}