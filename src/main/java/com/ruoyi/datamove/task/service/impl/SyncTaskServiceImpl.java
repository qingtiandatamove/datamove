package com.ruoyi.datamove.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.datamove.audit.service.AuditLogService;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.mapper.SyncDatasourceMapper;
import com.ruoyi.datamove.engine.consts.SyncType;
import com.ruoyi.datamove.engine.full.DdlSyncEngine;
import com.ruoyi.datamove.engine.full.FullSyncEngine;
import com.ruoyi.datamove.engine.incr.CanalSyncEngine;
import com.ruoyi.datamove.engine.metrics.TaskMetrics;
import com.ruoyi.datamove.engine.metrics.TaskMetricsRegistry;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskFieldMapping;
import com.ruoyi.datamove.task.domain.SyncTaskLog;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;
import com.ruoyi.datamove.task.domain.SyncTaskRun;
import com.ruoyi.datamove.task.domain.TaskDashboardVO;
import com.ruoyi.datamove.task.domain.TaskExportVO;
import com.ruoyi.datamove.task.mapper.SyncTaskFieldMappingMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskLogMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskProgressMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskRunMapper;
import com.ruoyi.datamove.task.service.ISyncTaskFieldMappingService;
import com.ruoyi.datamove.task.service.ISyncTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTaskServiceImpl implements ISyncTaskService {

    private final SyncTaskMapper                  taskMapper;
    private final SyncTaskProgressMapper          progressMapper;
    private final SyncTaskLogMapper               logMapper;
    private final SyncTaskRunMapper               runMapper;
    private final SyncDatasourceMapper            datasourceMapper;
    private final FullSyncEngine                  fullSyncEngine;
    private final CanalSyncEngine                 canalSyncEngine;
    private final DdlSyncEngine                   ddlSyncEngine;
    private final TaskMetricsRegistry             metricsRegistry;
    private final AuditLogService                 auditLogService;
    private final ISyncTaskFieldMappingService    fieldMappingService;

    @Override
    public PageResult<SyncTask> page(String keyword, String taskType, String status,
                                    String orderByColumn, String isAsc,
                                    int pageNum, int pageSize) {
        Page<SyncTask> page = new Page<>(pageNum, pageSize);
        QueryWrapper<SyncTask> wrapper = new QueryWrapper<>();
        wrapper.eq("del_flag", "0");
        if (keyword != null && !keyword.isEmpty())
            wrapper.and(w -> w.like("task_name", keyword).or().like("table_name", keyword));
        if (taskType != null && !taskType.isEmpty()) wrapper.eq("task_type", taskType);
        if (status != null && !status.isEmpty()) wrapper.eq("status", status);
        // 排序字段白名单: 仅允许 id / create_time, 防止前端传入任意列名拼到 SQL 里
        String col = (orderByColumn == null || orderByColumn.isEmpty()) ? "id" : orderByColumn;
        if (!"id".equals(col) && !"create_time".equals(col)) col = "id";
        boolean asc = "asc".equalsIgnoreCase(isAsc);
        wrapper.orderBy(true, asc, col);
        Page<SyncTask> result = taskMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal());
    }

    @Override
    public SyncTask detail(Long id) {
        return taskMapper.selectById(id);
    }

    @Override
    @Transactional
    public Long add(SyncTask t) {
        // 任务类型校验
        if (t.getTaskType() == null
                || !(SyncType.TASK_FULL.equalsIgnoreCase(t.getTaskType())
                  || SyncType.TASK_INCR.equalsIgnoreCase(t.getTaskType())
                  || SyncType.TASK_DDL.equalsIgnoreCase(t.getTaskType()))) {
            throw new RuntimeException("不支持的任务类型: " + t.getTaskType());
        }
        // DDL 类型必须填一个 syncMode 占位 (syncMode 字段 NOT NULL)
        if (SyncType.TASK_DDL.equalsIgnoreCase(t.getTaskType())
                && (t.getSyncMode() == null || t.getSyncMode().isEmpty())) {
            t.setSyncMode(SyncType.MODE_DDL);
        }
        // 唯一名校验
        Long exists = taskMapper.selectCount(
                new QueryWrapper<SyncTask>().eq("task_name", t.getTaskName()).eq("del_flag", "0"));
        if (exists > 0) throw new RuntimeException("任务名称已存在: " + t.getTaskName());
        // binlog DML 类型过滤: 归一化为大写逗号串 (非法 token 丢弃, 全非法 = null 不过滤)
        t.setBinlogDmlTypes(normalizeBinlogDmlTypes(t.getBinlogDmlTypes()));
        t.setStatus(SyncType.STATUS_STOP);
        taskMapper.insert(t);
        // 初始化进度
        SyncTaskProgress p = new SyncTaskProgress();
        p.setTaskId(t.getId());
        p.setLastSyncMaxId(t.getStartId() == null ? 0L : t.getStartId());
        p.setLastSyncTime(t.getStartTime());
        p.setTotalRows(0L);
        p.setStatus(SyncType.STATUS_STOP);
        p.setCreateTime(new Date());
        p.setUpdateTime(new Date());
        progressMapper.insert(p);
        // 审计: 字段级记录本次新增的全部字段快照 (谁、新增时间、字段 → 值)
        auditLogService.recordTaskCreate(t);
        return t.getId();
    }

    @Override
    @Transactional
    public void update(SyncTask t) {
        SyncTask db = taskMapper.selectById(t.getId());
        if (db == null) throw new RuntimeException("任务不存在");
        if (SyncType.STATUS_RUNNING.equals(db.getStatus())) {
            throw new RuntimeException("运行中的任务不允许编辑,请先停止任务");
        }
        // 任务名称唯一性预校验 (排除自身): add() 有同样校验, update 没有 — 缺失会让 DB 唯一索引兜底报 SQL 异常,
        // 用户看到的是「Duplicate entry ... for key 'sync_task.uk_task_name'」而不是友好提示。
        if (t.getTaskName() != null && !t.getTaskName().equals(db.getTaskName())) {
            Long same = taskMapper.selectCount(
                    new QueryWrapper<SyncTask>()
                            .eq("task_name", t.getTaskName())
                            .eq("del_flag", "0")
                            .ne("id", t.getId()));
            if (same != null && same > 0) {
                throw new RuntimeException("任务名称已存在: " + t.getTaskName());
            }
        }
        // 审计: 拷贝一份修改前的快照, 待赋值完后与新值对比
        SyncTask snapshot = copy(db);
        db.setTaskName(t.getTaskName());
        db.setTaskType(t.getTaskType());
        db.setSyncMode(t.getSyncMode());
        db.setSourceId(t.getSourceId());
        db.setTargetId(t.getTargetId());
        db.setTableName(t.getTableName());
        db.setIdField(t.getIdField());
        db.setTimeField(t.getTimeField());
        db.setStartId(t.getStartId());
        db.setStartTime(t.getStartTime());
        db.setBatchSize(t.getBatchSize());
        db.setShardCount(t.getShardCount() == null ? 1 : Math.max(1, t.getShardCount()));
        db.setOverwriteFlag(t.getOverwriteFlag() == null ? 0 : t.getOverwriteFlag());
        // 数据校验忽略字段: 这里是逐字段白名单赋值, 漏一行配置就永远存不进去
        db.setIgnoreFields(t.getIgnoreFields());
        db.setDingtalkWebhook(t.getDingtalkWebhook());
        db.setAlertEmail(t.getAlertEmail());
        db.setCanalHost(t.getCanalHost());
        db.setCanalPort(t.getCanalPort());
        db.setCanalDestination(t.getCanalDestination());
        // binlog DML 类型过滤: 归一化为大写逗号串 (非法 token 丢弃, 全非法 = null 不过滤)
        db.setBinlogDmlTypes(normalizeBinlogDmlTypes(t.getBinlogDmlTypes()));
        db.setRemark(t.getRemark());
        // 审计: 记录本次修改, 同一个请求的所有字段变更共享 revision_id
        auditLogService.recordTaskUpdate(snapshot, db);
        taskMapper.updateById(db);
    }

    @Override
    @Transactional
    public void remove(Long id) {
        SyncTask db = taskMapper.selectById(id);
        if (db == null) throw new RuntimeException("任务不存在");
        if (SyncType.STATUS_RUNNING.equals(db.getStatus())) {
            throw new RuntimeException("运行中的任务不允许删除,请先停止任务");
        }
        // 软删唯一索引冲突保护:
        //   uk_task_name 是 (task_name, del_flag),允许「同名一删一存」(历史已删的任务不应占名额)。
        //   但软删路径是把当前行 del_flag '0'→'1',若 DB 里已存在另一行 (同名, del_flag='1') 就会撞唯一索引,
        //   典型场景:「创建 A → 删 A → 重建同名 B → 删 B」第二轮删除就报 Duplicate entry。
        //   解决办法: 删除前先看是否有同名 (del_flag='1') 行,有就把当前行 task_name 加个唯一后缀再软删,
        //   避免 UPDATE 把两行都变成 (同名, del_flag='1')。
        //   审计一致性: 改名只发生在 UPDATE 这一刻,记录审计前先快照原始 task 对象,这样审计日志里的 task_name
        //   还是用户认知中的「真实名字」,带后缀的 DB 行只是临时避让,合规追溯完整。
        SyncTask snapshot = copy(db);
        Long tombstone = taskMapper.selectCount(
                new QueryWrapper<SyncTask>()
                        .eq("task_name", db.getTaskName())
                        .eq("del_flag", "1")
                        .ne("id", id));
        if (tombstone != null && tombstone > 0) {
            db.setTaskName(db.getTaskName() + ".del_" + id + "_" + System.currentTimeMillis());
        }
        db.setDelFlag("1");
        taskMapper.updateById(db);
        // 同步删除日志、断点进度与运行历史
        logMapper.delete(new QueryWrapper<SyncTaskLog>().eq("task_id", id));
        runMapper.delete(new QueryWrapper<SyncTaskRun>().eq("task_id", id));
        progressMapper.delete(new QueryWrapper<SyncTaskProgress>().eq("task_id", id));
        // 清理运行期实时指标
        metricsRegistry.remove(id);
        // 审计: 软删也算「删除」, 记录被删那一刻的字段快照 (合规审计: 任务曾存在过、有过哪些字段配置)
        // 关键: 用原始 snapshot (没改名) 而不是 db, 否则审计日志里的 task_name 会是 .del_xxx 后缀的避让名
        auditLogService.recordTaskDelete(snapshot);
    }

    @Override
    @Transactional
    public Long clone(Long sourceId) {
        SyncTask src = taskMapper.selectById(sourceId);
        if (src == null) throw new RuntimeException("源任务不存在");
        // 仅允许克隆非运行中的任务: 运行中的任务有 in-flight 引擎线程, 克隆一份的状态字段会和实时内存状态打架
        if (SyncType.STATUS_RUNNING.equalsIgnoreCase(src.getStatus())) {
            throw new RuntimeException("运行中的任务不允许克隆, 请先停止任务");
        }

        // 1. 拷贝主表业务字段
        SyncTask copy = new SyncTask();
        // 业务配置 1:1 复制 (syncTaskType, 同步模式, 数据源, 字段, 批次/分片, 覆盖标志, 校验忽略字段,
        // 告警渠道, Canal 配置, binlog DML 过滤) — 这些是用户实际想要省去重复配置的字段
        copy.setTaskType(src.getTaskType());
        copy.setSyncMode(src.getSyncMode());
        copy.setSourceId(src.getSourceId());
        copy.setTargetId(src.getTargetId());
        copy.setIdField(src.getIdField());
        copy.setTimeField(src.getTimeField());
        copy.setBatchSize(src.getBatchSize());
        copy.setShardCount(src.getShardCount());
        copy.setIgnoreFields(src.getIgnoreFields());
        copy.setOverwriteFlag(src.getOverwriteFlag());
        copy.setDingtalkWebhook(src.getDingtalkWebhook());
        copy.setAlertEmail(src.getAlertEmail());
        copy.setCanalHost(src.getCanalHost());
        copy.setCanalPort(src.getCanalPort());
        copy.setCanalDestination(src.getCanalDestination());
        copy.setBinlogDmlTypes(src.getBinlogDmlTypes());

        // 强制重置的字段 (运行态 / 启动位点)
        copy.setId(null);                                          // 主键重置 → 新增
        copy.setTaskName(buildCopyName(src.getTaskName()));         // 任务名去重
        // 同步表名沿用源任务: 符合「复制任务改个表名就能用」语义, 后续用户在编辑页改表名再保存
        // (这里如果清空成 null, DB 上 sync_task.table_name 是 NOT NULL 会直接报错; 若改成可空, 又要跑迁移)
        copy.setTableName(src.getTableName());
        copy.setStartId(null);                                  // 起始 ID 重置: 用 source 表的实际起始
        copy.setStartTime(null);                                   // 起始时间重置
        copy.setStatus(SyncType.STATUS_STOP);                       // 状态强制 STOP
        copy.setDelFlag("0");                                       // 显式设 0: 避免依赖 DB 默认值/逻辑删除配置的隐性行为, 防止被 page() 的 del_flag='0' 过滤掉
        // remark 拼接前缀, 备注里能溯源到源任务; 同时避免「保留备注里的「克隆自」前缀」造成越加越长
        String oldRemark = src.getRemark();
        String mergedRemark = "克隆自任务 #" + src.getId() + (oldRemark == null || oldRemark.isEmpty() ? "" : "\n" + oldRemark);
        copy.setRemark(mergedRemark);

        // 任务名去重 + 唯一索引兜底: buildCopyName 是 check-then-insert, 两个并发克隆同名源任务时
        // 都会读到「名字可用」然后都 INSERT, 第二个 INSERT 触发 SQLIntegrityConstraintViolationException。
        // 这里捕获后用 UUID 短后缀强制区分, 避免返回给用户难看的 500。
        insertWithUniqueName(copy);

        // 2. 同步拷贝字段映射 (不拷的话新任务会丢配置, 用户还得再拖一遍; 映射与表名解耦, 表变了也不影响)
        List<SyncTaskFieldMapping> mappings = fieldMappingService.listByTaskId(src.getId());
        if (mappings != null && !mappings.isEmpty()) {
            // 直接复用 replace(): 先删(空表无副作用)再插, 已经在 @Transactional 里
            // 用 LinkedList 包一层避免被 replace() 内部顺序重排搞乱
            fieldMappingService.replace(copy.getId(), mappings);
        }

        // 3. 审计: 用 CREATE 事件记录, 但在备注里加了「克隆自任务#X」便于溯源
        auditLogService.recordTaskCreate(copy);
        log.info("[clone] 源任务 #{}({}) → 新任务 #{}, 字段映射 {} 条", src.getId(), src.getTaskName(), copy.getId(), mappings == null ? 0 : mappings.size());
        return copy.getId();
    }

    /* ==================== 任务导入导出 (配置迁移) ==================== */

    @Override
    public TaskExportVO exportTask(Long id) {
        SyncTask task = taskMapper.selectById(id);
        if (task == null) throw new RuntimeException("任务不存在: " + id);
        if ("1".equals(task.getDelFlag())) throw new RuntimeException("任务已删除, 不允许导出");

        TaskExportVO vo = new TaskExportVO();
        vo.setExportedAt(new Date());

        // 业务配置 1:1 (导出即快照, 不含运行态)
        vo.setTaskName(task.getTaskName());
        vo.setTaskType(task.getTaskType());
        vo.setSyncMode(task.getSyncMode());
        vo.setTableName(task.getTableName());
        vo.setIdField(task.getIdField());
        vo.setTimeField(task.getTimeField());
        vo.setBatchSize(task.getBatchSize());
        vo.setShardCount(task.getShardCount());
        vo.setIgnoreFields(task.getIgnoreFields());
        vo.setOverwriteFlag(task.getOverwriteFlag());
        vo.setDingtalkWebhook(task.getDingtalkWebhook());
        vo.setAlertEmail(task.getAlertEmail());
        vo.setRemark(task.getRemark());
        vo.setCanalHost(task.getCanalHost());
        vo.setCanalPort(task.getCanalPort());
        vo.setCanalDestination(task.getCanalDestination());
        vo.setBinlogDmlTypes(task.getBinlogDmlTypes());

        // 数据源引用: 按名称导出 (跨环境 ID 不可沿用), 附 host/port/dbName 供人工核对
        vo.setSourceDatasourceRef(toRef(task.getSourceId()));
        vo.setTargetDatasourceRef(toRef(task.getTargetId()));
        SyncDatasource src = datasourceMapper.selectById(task.getSourceId());
        SyncDatasource tgt = datasourceMapper.selectById(task.getTargetId());
        if (src == null) throw new RuntimeException("源数据源不存在 (id=" + task.getSourceId() + "), 请检查任务配置");
        if (tgt == null) throw new RuntimeException("目标数据源不存在 (id=" + task.getTargetId() + "), 请检查任务配置");
        vo.setSourceDatasourceName(src.getDatasourceName());
        vo.setTargetDatasourceName(tgt.getDatasourceName());

        // 字段映射
        List<SyncTaskFieldMapping> mappings = fieldMappingService.listByTaskId(id);
        if (mappings != null && !mappings.isEmpty()) {
            vo.setFieldMappings(mappings.stream().map(m -> {
                TaskExportVO.FieldMappingItem item = new TaskExportVO.FieldMappingItem();
                item.setSourceField(m.getSourceField());
                item.setTargetField(m.getTargetField());
                item.setSortNo(m.getSortNo());
                return item;
            }).collect(java.util.stream.Collectors.toList()));
        }
        log.info("[export] 任务 #{}({}) 导出, 字段映射 {} 条", id, task.getTaskName(), vo.getFieldMappings() == null ? 0 : vo.getFieldMappings().size());
        return vo;
    }

    @Override
    @Transactional
    public Long importTask(TaskExportVO vo) {
        if (vo == null) throw new RuntimeException("导入内容为空");
        // 版本兼容: 目前只有 v1; 未来结构变化时在这里分支处理旧版本
        if (vo.getExportVersion() == null) vo.setExportVersion(1);
        if (vo.getTaskType() == null
                || !(SyncType.TASK_FULL.equalsIgnoreCase(vo.getTaskType())
                  || SyncType.TASK_INCR.equalsIgnoreCase(vo.getTaskType())
                  || SyncType.TASK_DDL.equalsIgnoreCase(vo.getTaskType()))) {
            throw new RuntimeException("导入文件里的任务类型不合法: " + vo.getTaskType());
        }
        if (vo.getTableName() == null || vo.getTableName().isEmpty()) {
            throw new RuntimeException("导入文件缺少同步表名 (tableName)");
        }

        // 数据源按名称重映射: 目标环境必须已存在同名数据源 (数据源含密码, 不适合随任务迁移, 引导用户先建)
        SyncDatasource sourceDs = findDatasourceByName(vo.getSourceDatasourceName());
        SyncDatasource targetDs = findDatasourceByName(vo.getTargetDatasourceName());

        SyncTask t = new SyncTask();
        t.setTaskType(vo.getTaskType());
        t.setSyncMode(vo.getSyncMode() == null || vo.getSyncMode().isEmpty()
                ? SyncType.MODE_DDL : vo.getSyncMode()); // DDL 缺省占位, 与 add() 逻辑一致
        t.setSourceId(sourceDs.getId());
        t.setTargetId(targetDs.getId());
        t.setTableName(vo.getTableName());
        t.setIdField(vo.getIdField());
        t.setTimeField(vo.getTimeField());
        t.setBatchSize(vo.getBatchSize() == null ? 1000 : vo.getBatchSize());
        t.setShardCount(vo.getShardCount() == null ? 1 : vo.getShardCount());
        t.setIgnoreFields(vo.getIgnoreFields());
        t.setOverwriteFlag(vo.getOverwriteFlag() == null ? 0 : vo.getOverwriteFlag());
        t.setDingtalkWebhook(vo.getDingtalkWebhook());
        t.setAlertEmail(vo.getAlertEmail());
        t.setCanalHost(vo.getCanalHost());
        t.setCanalPort(vo.getCanalPort());
        t.setCanalDestination(vo.getCanalDestination());
        t.setBinlogDmlTypes(normalizeBinlogDmlTypes(vo.getBinlogDmlTypes()));
        // 运行态全部重置: 导入的是配置, 不是进度
        t.setStartId(null);
        t.setStartTime(null);
        t.setStatus(SyncType.STATUS_STOP);
        t.setDelFlag("0");
        // 撞名自动加 .import 后缀, 不阻断迁移 (改个名字就能用, 比报错让用户手工改文件友好)
        t.setTaskName(buildImportName(vo.getTaskName()));
        String oldRemark = vo.getRemark();
        t.setRemark("导入任务 (源环境数据源: " + vo.getSourceDatasourceName() + " → " + vo.getTargetDatasourceName() + ")"
                + (oldRemark == null || oldRemark.isEmpty() ? "" : "\n" + oldRemark));
        insertWithUniqueName(t);

        // 进度初始化 (与 add() 一致)
        SyncTaskProgress p = new SyncTaskProgress();
        p.setTaskId(t.getId());
        p.setLastSyncMaxId(0L);
        p.setTotalRows(0L);
        p.setStatus(SyncType.STATUS_STOP);
        p.setCreateTime(new Date());
        p.setUpdateTime(new Date());
        progressMapper.insert(p);

        // 字段映射一并导入
        if (vo.getFieldMappings() != null && !vo.getFieldMappings().isEmpty()) {
            List<SyncTaskFieldMapping> mappings = new ArrayList<>();
            for (TaskExportVO.FieldMappingItem item : vo.getFieldMappings()) {
                if (item == null || item.getSourceField() == null || item.getTargetField() == null) continue;
                SyncTaskFieldMapping m = new SyncTaskFieldMapping();
                m.setSourceField(item.getSourceField());
                m.setTargetField(item.getTargetField());
                m.setSortNo(item.getSortNo() == null ? mappings.size() : item.getSortNo());
                mappings.add(m);
            }
            if (!mappings.isEmpty()) {
                fieldMappingService.replace(t.getId(), mappings);
            }
        }

        auditLogService.recordTaskCreate(t);
        log.info("[import] 任务「{}」导入为新任务 #{}, 数据源 {}→{} 重映射为 id {}→{}, 字段映射 {} 条",
                vo.getTaskName(), t.getId(), vo.getSourceDatasourceName(), vo.getTargetDatasourceName(),
                sourceDs.getId(), targetDs.getId(),
                vo.getFieldMappings() == null ? 0 : vo.getFieldMappings().size());
        return t.getId();
    }

    /** 数据源 id → 参考信息 (不含密码/账号) */
    private TaskExportVO.DatasourceRef toRef(Long datasourceId) {
        TaskExportVO.DatasourceRef ref = new TaskExportVO.DatasourceRef();
        if (datasourceId == null) return ref;
        SyncDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds != null) {
            ref.setHost(ds.getHost());
            ref.setPort(ds.getPort());
            ref.setDbName(ds.getDbName());
        }
        return ref;
    }

    /** 按名称查当前环境的数据源, 查不到给出明确指引 (而不是让后续 NPE) */
    private SyncDatasource findDatasourceByName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new RuntimeException("导入文件缺少数据源名称, 可能不是本系统导出的任务文件");
        }
        List<SyncDatasource> list = datasourceMapper.selectList(
                new QueryWrapper<SyncDatasource>().eq("datasource_name", name).eq("del_flag", "0"));
        if (list == null || list.isEmpty()) {
            throw new RuntimeException("当前环境不存在名为「" + name + "」的数据源, 请先在「数据源管理」创建同名数据源后再导入");
        }
        return list.get(0);
    }

    /** 导入撞名: taskName.import → taskName.import (2) → … (与克隆的 .copy 命名区分开) */
    private String buildImportName(String sourceName) {
        if (sourceName == null || sourceName.trim().isEmpty()) sourceName = "imported-task";
        String base = sourceName + ".import";
        String candidate = base;
        int seq = 2;
        while (true) {
            Long n = taskMapper.selectCount(
                    new QueryWrapper<SyncTask>().eq("task_name", candidate).eq("del_flag", "0"));
            if (n == null || n == 0) return candidate;
            candidate = base + " (" + (seq++) + ")";
        }
    }

    /**
     * 任务名去重: src.copy → src.copy (2) → src.copy (3) …
     * 已删(del_flag='1')的名字视作不存在, 因为已删任务不应占名额
     */
    private String buildCopyName(String sourceName) {
        String base = sourceName + ".copy";
        String candidate = base;
        int seq = 2;
        while (true) {
            Long n = taskMapper.selectCount(
                    new QueryWrapper<SyncTask>().eq("task_name", candidate).eq("del_flag", "0"));
            if (n == null || n == 0) return candidate;
            candidate = base + " (" + (seq++) + ")";
        }
    }

    /**
     * 插入任务, 处理并发克隆时的唯一索引冲突:
     * buildCopyName 的「check-then-insert」在并发场景下会失效 (两个事务都读到名字可用, 然后第二个 INSERT 报错)。
     * 捕获 SQLIntegrityConstraintViolationException 后用 UUID 短后缀强制区分, 重试一次就够 — 并发冲突极端罕见,
     * 重试循环用更多次数反而掩盖了「真的有同名任务存在」的真实情况, 让用户以为系统有问题。
     */
    private void insertWithUniqueName(SyncTask copy) {
        try {
            taskMapper.insert(copy);
        } catch (Exception e) {
            if (isTaskNameUniqueViolation(e)) {
                String fallback = copy.getTaskName() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
                log.warn("[clone] 任务名 {} 撞唯一索引 (并发克隆), 回退为 {}", copy.getTaskName(), fallback);
                copy.setTaskName(fallback);
                taskMapper.insert(copy);
            } else {
                throw e;
            }
        }
    }

    /**
     * 判断异常是否是 sync_task.uk_task_name 唯一索引冲突 (JDBC 层)
     */
    private boolean isTaskNameUniqueViolation(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof SQLIntegrityConstraintViolationException) {
                String msg = cur.getMessage();
                // 数据库驱动返回的 message 形如:
                //   Duplicate entry 'xxx' for key 'sync_task.uk_task_name'
                return msg != null && msg.contains("uk_task_name");
            }
            cur = cur.getCause();
        }
        return false;
    }

    @Override
    public void start(Long id) {
        SyncTask db = taskMapper.selectById(id);
        if (db == null) throw new RuntimeException("任务不存在");
        String oldStatus = db.getStatus();
        if (SyncType.TASK_FULL.equalsIgnoreCase(db.getTaskType())) {
            fullSyncEngine.start(id);
        } else if (SyncType.TASK_INCR.equalsIgnoreCase(db.getTaskType())) {
            canalSyncEngine.start(id);
        } else if (SyncType.TASK_DDL.equalsIgnoreCase(db.getTaskType())) {
            ddlSyncEngine.start(id);
        } else {
            throw new RuntimeException("不支持的任务类型: " + db.getTaskType());
        }
        // 引擎已成功接管 —— 记一条 START 审计 (审计写入兜底 warn, 不会影响主流程)
        auditLogService.recordTaskAction(db, "START", oldStatus, SyncType.STATUS_RUNNING);
    }

    @Override
    public void pause(Long id) {
        SyncTask db = taskMapper.selectById(id);
        if (db == null) throw new RuntimeException("任务不存在");
        if (!SyncType.TASK_FULL.equalsIgnoreCase(db.getTaskType())) {
            throw new RuntimeException("仅全量任务支持暂停");
        }
        String oldStatus = db.getStatus();
        fullSyncEngine.pause(id);
        auditLogService.recordTaskAction(db, "PAUSE", oldStatus, SyncType.STATUS_PAUSE);
    }

    @Override
    public void resume(Long id) {
        SyncTask db = taskMapper.selectById(id);
        if (db == null) throw new RuntimeException("任务不存在");
        if (!SyncType.TASK_FULL.equalsIgnoreCase(db.getTaskType())) {
            throw new RuntimeException("仅全量任务支持继续");
        }
        String oldStatus = db.getStatus();
        fullSyncEngine.resume(id);
        auditLogService.recordTaskAction(db, "RESUME", oldStatus, SyncType.STATUS_RUNNING);
    }

    @Override
    public void stop(Long id) {
        SyncTask db = taskMapper.selectById(id);
        if (db == null) throw new RuntimeException("任务不存在");
        String oldStatus = db.getStatus();
        if (SyncType.TASK_FULL.equalsIgnoreCase(db.getTaskType())) {
            fullSyncEngine.stop(id);
        } else if (SyncType.TASK_INCR.equalsIgnoreCase(db.getTaskType())) {
            canalSyncEngine.stop(id);
        }
        // DDL 同步是单次秒级操作, 不需要 stop —— 也不记审计)
        auditLogService.recordTaskAction(db, "STOP", oldStatus, SyncType.STATUS_STOP);
    }

    @Override
    public void reset(Long id) {
        SyncTask db = taskMapper.selectById(id);
        if (db == null) throw new RuntimeException("任务不存在");
        if (!SyncType.TASK_FULL.equalsIgnoreCase(db.getTaskType())) {
            throw new RuntimeException("仅全量任务支持重置");
        }
        if (SyncType.STATUS_RUNNING.equalsIgnoreCase(db.getStatus())) {
            throw new RuntimeException("运行中的任务不可重置, 请先停止");
        }
        // 1) 清空断点进度 (下次 start 会自动重建空记录, 从头全量)
        progressMapper.delete(new QueryWrapper<SyncTaskProgress>().eq("task_id", id));
        // 2) 任务状态回到 STOP
        db.setStatus(SyncType.STATUS_STOP);
        taskMapper.updateById(db);
        log.info("[reset] taskId={} 已重置同步进度", id);
    }

    @Override
    public SyncTaskProgress progress(Long id) {
        return fullSyncEngine.progress(id);
    }

    @Override
    public PageResult<SyncTaskLog> logs(Long taskId, String status, int pageNum, int pageSize) {
        Page<SyncTaskLog> page = new Page<>(pageNum, pageSize);
        QueryWrapper<SyncTaskLog> wrapper = new QueryWrapper<>();
        wrapper.eq("task_id", taskId);
        if (status != null && !status.isEmpty()) wrapper.eq("status", status);
        wrapper.orderByDesc("id");
        Page<SyncTaskLog> result = logMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal());
    }

    @Override
    public int clearLog(Long taskId, Integer beforeDays) {
        if (taskId == null) throw new RuntimeException("任务 ID 不能为空");
        QueryWrapper<SyncTaskLog> wrapper = new QueryWrapper<>();
        wrapper.eq("task_id", taskId);
        if (beforeDays != null && beforeDays > 0) {
            // 保留最近 N 天: 删除 (N-1) 天前零点之前的日志, 即今天与最近 N-1 天保留
            wrapper.lt("create_time", LocalDate.now().minusDays(beforeDays - 1).atStartOfDay());
        }
        int deleted = logMapper.delete(wrapper);
        log.info("[clearLog] taskId={} beforeDays={} deleted={}", taskId, beforeDays, deleted);
        return deleted;
    }

    @Override
    public int clearAllLog() {
        int deleted = logMapper.delete(new QueryWrapper<>());
        log.warn("[clearLog] 已清空全部任务日志, deleted={}", deleted);
        return deleted;
    }

    /* ============ 任务大盘 ============ */

    @Override
    public List<TaskDashboardVO> dashboard() {
        List<SyncTask> tasks = taskMapper.selectList(
                new QueryWrapper<SyncTask>().eq("del_flag", "0").orderByDesc("id"));
        if (tasks.isEmpty()) return new ArrayList<>();

        Map<Long, SyncTaskProgress> progressMap = new HashMap<>();
        for (SyncTaskProgress p : progressMapper.selectList(null)) {
            if (p.getTaskId() != null) progressMap.put(p.getTaskId(), p);
        }
        Map<Long, SyncDatasource> dsMap = new HashMap<>();
        for (SyncDatasource ds : datasourceMapper.selectList(null)) {
            dsMap.put(ds.getId(), ds);
        }

        long now = System.currentTimeMillis();

        // 历史运行: 每个任务最近一次运行记录 + 累计运行次数 (sync_task_run)
        Map<Long, SyncTaskRun> lastRunMap = new HashMap<>();
        for (SyncTaskRun r : runMapper.selectLatestPerTask()) {
            if (r.getTaskId() != null) lastRunMap.put(r.getTaskId(), r);
        }
        Map<Long, Long> runCountMap = new HashMap<>();
        for (Map<String, Object> row : runMapper.countGroupByTask()) {
            Object tid = row.get("taskId") != null ? row.get("taskId") : row.get("task_id");
            Object cnt = row.get("cnt") != null ? row.get("cnt") : row.get("CNT");
            if (tid instanceof Number) {
                runCountMap.put(((Number) tid).longValue(), cnt instanceof Number ? ((Number) cnt).longValue() : 0L);
            }
        }

        List<TaskDashboardVO> list = new ArrayList<>(tasks.size());
        for (SyncTask task : tasks) {
            TaskDashboardVO vo = toDashboardVO(task, progressMap.get(task.getId()), dsMap, now);
            fillRunHistory(vo, lastRunMap.get(task.getId()), runCountMap.get(task.getId()));
            list.add(vo);
        }
        // 运行中 / 暂停 的排前面, 其余按最近更新倒序
        list.sort((a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return ra - rb;
            if (a.getUpdateTime() == null || b.getUpdateTime() == null) return 0;
            return b.getUpdateTime().compareTo(a.getUpdateTime());
        });
        return list;
    }

    /** 组装单条大盘数据: 任务 + 断点进度 + 运行期实时指标 */
    private TaskDashboardVO toDashboardVO(SyncTask task, SyncTaskProgress p,
                                          Map<Long, SyncDatasource> dsMap, long now) {
        TaskDashboardVO vo = new TaskDashboardVO();
        vo.setTaskId(task.getId());
        vo.setTaskName(task.getTaskName());
        vo.setTaskType(task.getTaskType());
        vo.setSyncMode(task.getSyncMode());
        vo.setTableName(task.getTableName());
        vo.setStatus(task.getStatus());
        vo.setBatchSize(task.getBatchSize());
        vo.setShardCount(task.getShardCount() == null ? 1 : task.getShardCount());

        SyncDatasource src = dsMap.get(task.getSourceId());
        SyncDatasource tgt = dsMap.get(task.getTargetId());
        vo.setSourceName(src == null ? null : src.getDatasourceName());
        vo.setTargetName(tgt == null ? null : tgt.getDatasourceName());

        // 缺省值: 无法估算时不展示进度条与 ETA
        vo.setTotalEstimate(-1L);
        vo.setEtaSeconds(-1L);
        vo.setProgress(-1);

        boolean running = SyncType.STATUS_RUNNING.equals(task.getStatus());
        vo.setRunning(running);

        long successRows = p == null || p.getSuccessRows() == null ? 0L : p.getSuccessRows();
        vo.setTotalRows(p == null || p.getTotalRows() == null ? 0L : p.getTotalRows());
        vo.setFailedRows(p == null || p.getFailedRows() == null ? 0L : p.getFailedRows());
        if (p != null) {
            vo.setStartTime(p.getStartTime());
            vo.setUpdateTime(p.getUpdateTime());
        }

        // 本次运行已同步 = 累计行数 - 启动时的历史累计(续传场景)
        TaskMetrics m = metricsRegistry.get(task.getId());
        long baseline = m == null ? 0L : m.getBaselineRows();
        long syncRows = Math.max(0L, successRows - baseline);
        vo.setSyncRows(syncRows);

        // 运行时长与平均速率
        if (p != null && p.getStartTime() != null) {
            long end = p.getEndTime() == null ? now : p.getEndTime().getTime();
            long costSeconds = Math.max(0L, (end - p.getStartTime().getTime()) / 1000L);
            vo.setCostSeconds(costSeconds);
            if (costSeconds > 0) vo.setAvgRowsPerSec(round1(syncRows * 1.0D / costSeconds));
        }

        if (m == null) {
            // 进程重启或本次未启动过: 无实时指标, 已完成的任务直接按 100% 展示
            if (SyncType.STATUS_COMPLETED.equals(task.getStatus())) vo.setProgress(100);
            return vo;
        }

        long estimate = m.getTotalEstimate();
        vo.setTotalEstimate(estimate);
        if (estimate > 0) {
            vo.setRemainRows(Math.max(0L, estimate - syncRows));
            vo.setProgress((int) Math.min(100L, syncRows * 100L / estimate));
        } else if (SyncType.STATUS_COMPLETED.equals(task.getStatus())) {
            vo.setProgress(100);
        }

        double rate = m.liveRowsPerSec();
        vo.setRowsPerSec(round1(rate));
        vo.setCurrentBatch(m.getCurrentBatch());
        vo.setCurrentBatchRows(m.getCurrentBatchRows());
        vo.setLastBatchCostMs(m.getLastBatchCostMs());
        vo.setReadMs(round1(m.getReadMs()));
        vo.setWriteMs(round1(m.getWriteMs()));
        vo.setBottleneck(m.getBottleneck());
        vo.setBottleneckText(bottleneckText(m.getBottleneck(), task.getTaskType()));

        // ETA: 只在运行中、有实时速率、且已知总量时给出
        if (running && rate > 0 && estimate > 0) {
            long eta = (long) Math.ceil(Math.max(0L, estimate - syncRows) / rate);
            vo.setEtaSeconds(eta);
            vo.setEtaText(formatEta(eta));
        }

        // 分片实时监控: 仅分片任务(本次运行注册过 shard)时输出
        if (m.hasShards()) {
            Collection<TaskMetrics.ShardState> snapshot = m.shardSnapshot();
            List<TaskDashboardVO.ShardVO> shardVOs = new ArrayList<>(snapshot.size());
            for (TaskMetrics.ShardState s : snapshot) {
                TaskDashboardVO.ShardVO sv = new TaskDashboardVO.ShardVO();
                sv.setShardNo(s.getShardNo());
                sv.setRangeLo(s.getRangeLo());
                sv.setRangeHi(s.getRangeHi());
                sv.setRows(s.getRows());
                sv.setCurrentId(s.getCurrentId());
                sv.setRowsPerSec(round1(s.getRowsPerSec()));
                sv.setBatches(s.getBatches());
                sv.setState(s.getState());
                sv.setError(s.getError());
                shardVOs.add(sv);
            }
            vo.setShards(shardVOs);
        }
        return vo;
    }

    /**
     * 大盘补充历史运行信息: 累计运行次数 + 最近一次运行结果 (sync_task_run)
     */
    private void fillRunHistory(TaskDashboardVO vo, SyncTaskRun last, Long runCount) {
        vo.setRunCount(runCount == null ? 0 : runCount.intValue());
        if (last == null) return;
        vo.setLastRunStatus(last.getStatus());
        vo.setLastRunTime(last.getStartTime());
        vo.setLastRunEndTime(last.getEndTime());
        vo.setLastRunCostSeconds(last.getCostSeconds());
        vo.setLastRunRows(last.getSuccessRows());
        vo.setLastRunFailedRows(last.getFailedRows());
    }

    /** 大盘排序权重: 运行中 > 暂停 > 其他 */
    private static int rank(TaskDashboardVO vo) {
        if (SyncType.STATUS_RUNNING.equals(vo.getStatus())) return 0;
        if (SyncType.STATUS_PAUSE.equals(vo.getStatus())) return 1;
        return 2;
    }

    private static Double round1(double v) {
        return Math.round(v * 10D) / 10D;
    }

    /** 瓶颈可读文本, 无法判定时返回 null */
    private static String bottleneckText(String bottleneck, String taskType) {
        if (TaskMetrics.SOURCE.equals(bottleneck)) return "源库";
        if (TaskMetrics.TARGET.equals(bottleneck)) return "目标库";
        if (TaskMetrics.BALANCED.equals(bottleneck)) {
            // 增量任务: 大部分时间在等 binlog 事件, 写入耗时没超过等待时间即无瓶颈
            return SyncType.TASK_INCR.equalsIgnoreCase(taskType) ? "无瓶颈" : "读写均衡";
        }
        return null;
    }

    /** ETA 可读文本: 1h2m3s */
    private static String formatEta(long seconds) {
        if (seconds < 0) return null;
        if (seconds > 99L * 3600L) return ">99h";
        long h = seconds / 3600L;
        long mi = seconds % 3600L / 60L;
        long s = seconds % 60L;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h");
        if (h > 0 || mi > 0) sb.append(mi).append("m");
        return sb.append(s).append("s").toString();
    }

    /**
     * 深拷贝一份任务快照, 用于审计日志对比「修改前」状态
     * 只复制会出现在审计日志白名单里的字段 —— 与 AuditLogService.buildDiffRows 完全对齐
     */
    private static SyncTask copy(SyncTask src) {
        SyncTask c = new SyncTask();
        c.setId(src.getId());
        c.setTaskName(src.getTaskName());
        c.setTaskType(src.getTaskType());
        c.setSyncMode(src.getSyncMode());
        c.setSourceId(src.getSourceId());
        c.setTargetId(src.getTargetId());
        c.setTableName(src.getTableName());
        c.setIdField(src.getIdField());
        c.setTimeField(src.getTimeField());
        c.setStartId(src.getStartId());
        c.setStartTime(src.getStartTime());
        c.setBatchSize(src.getBatchSize());
        c.setShardCount(src.getShardCount());
        c.setIgnoreFields(src.getIgnoreFields());
        c.setOverwriteFlag(src.getOverwriteFlag());
        c.setDingtalkWebhook(src.getDingtalkWebhook());
        c.setAlertEmail(src.getAlertEmail());
        c.setCanalHost(src.getCanalHost());
        c.setCanalPort(src.getCanalPort());
        c.setCanalDestination(src.getCanalDestination());
        c.setBinlogDmlTypes(src.getBinlogDmlTypes());
        c.setRemark(src.getRemark());
        c.setStatus(src.getStatus());
        return c;
    }

    /**
     * binlog DML 类型过滤配置归一化:
     *  - "insert, update" → "INSERT,UPDATE"
     *  - 非法 token 直接丢弃; 全部非法或为空 → null (不过滤, 三种 DML 全同步)
     *  - 三种全勾 (等价于不过滤) 也收敛为 null, 与前端「全勾 = 不过滤」语义一致
     */
    private static String normalizeBinlogDmlTypes(String config) {
        if (config == null || config.trim().isEmpty()) return null;
        java.util.LinkedHashSet<String> keep = new java.util.LinkedHashSet<>();
        for (String t : config.toUpperCase().split(",")) {
            String token = t.trim();
            if ("INSERT".equals(token) || "UPDATE".equals(token) || "DELETE".equals(token)) {
                keep.add(token);
            }
        }
        if (keep.isEmpty() || keep.size() == 3) return null;
        return String.join(",", keep);
    }
}
