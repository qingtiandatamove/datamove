package com.ruoyi.datamove.template;

import com.ruoyi.datamove.auth.service.IAuthService;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.service.ISyncDatasourceService;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskFieldMapping;
import com.ruoyi.datamove.task.service.ISyncTaskFieldMappingService;
import com.ruoyi.datamove.task.service.ISyncTaskService;
import com.ruoyi.datamove.template.domain.TaskTemplate;
import com.ruoyi.datamove.template.domain.TemplateApplyRequest;
import com.ruoyi.datamove.template.domain.TemplateApplyResult;
import com.ruoyi.datamove.util.JdbcUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模板套用: 按模板预置参数创建一个「普通任务」
 *
 * <p>关键设计: 套用出来的就是一个普通 SyncTask, 不做任何特殊标记。
 * 这样任务页、监控、日志、导入导出、权限全都不用改 —— 模板只是"帮你把表单填好"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskTemplateService {

    /** 表名只允许 字母数字下划线, 允许 db.table 形式 */
    private static final String TABLE_PATTERN = "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)?";

    private final TaskTemplateRegistry registry;
    private final ISyncTaskService taskService;
    private final ISyncDatasourceService datasourceService;
    private final ISyncTaskFieldMappingService mappingService;
    private final IAuthService authService;

    public List<TaskTemplate> list() {
        return registry.list();
    }

    public TaskTemplate detail(String code) {
        TaskTemplate t = registry.get(code);
        if (t == null) throw new RuntimeException("模板不存在: " + code);
        return t;
    }

    /**
     * 套用模板创建任务
     */
    public TemplateApplyResult apply(String code, TemplateApplyRequest req) {
        TaskTemplate tpl = registry.get(code);
        if (tpl == null) throw new RuntimeException("模板不存在: " + code);
        if (req == null) throw new RuntimeException("缺少套用参数");

        SyncDatasource source = datasourceService.getById(req.getSourceId());
        SyncDatasource target = datasourceService.getById(req.getTargetId());
        if (source == null) throw new RuntimeException("源数据源不存在");
        if (target == null) throw new RuntimeException("目标数据源不存在");
        if (source.getId().equals(target.getId())) throw new RuntimeException("源库与目标库不能是同一个");

        String table = req.getTableName() == null ? "" : req.getTableName().trim();
        if (!table.matches(TABLE_PATTERN)) throw new RuntimeException("表名不合法: " + req.getTableName());

        TaskTemplateConfigView cfg = new TaskTemplateConfigView(tpl);
        if (cfg.needIdField && !StringUtils.hasText(req.getIdField())) {
            throw new RuntimeException("该模板按主键分片, 必须填写主键字段名");
        }
        if (cfg.needTimeField && !StringUtils.hasText(req.getTimeField())) {
            throw new RuntimeException("该模板按时间增量, 必须填写时间字段名(如 update_time)");
        }

        SyncTask t = buildTask(tpl, cfg, req, table, source, target);
        Long taskId = taskService.add(t);

        TemplateApplyResult res = new TemplateApplyResult();
        res.setTaskId(taskId);
        res.setTaskName(t.getTaskName());
        res.setMappingCount(0);

        if (Boolean.TRUE.equals(req.getAutoMapping()) && !"DDL".equalsIgnoreCase(cfg.taskType)) {
            try {
                res.setMappingCount(autoMapping(taskId, source, target, table, t.getIgnoreFields()));
            } catch (Exception e) {
                // 映射生成失败不影响任务本身: 任务已创建, 用户可以在字段映射页手动配
                log.warn("[Template] auto mapping failed, taskId={}", taskId, e);
                res.setMappingNote("自动生成字段映射失败: " + e.getMessage() + ", 可到任务的字段映射里手动配置");
            }
        }
        return res;
    }

    private SyncTask buildTask(TaskTemplate tpl, TaskTemplateConfigView cfg, TemplateApplyRequest req,
                               String table, SyncDatasource source, SyncDatasource target) {
        SyncTask t = new SyncTask();
        String taskName = StringUtils.hasText(req.getTaskName())
                ? req.getTaskName().trim()
                : tpl.getName() + "-" + table;
        t.setTaskName(taskName);
        t.setTaskType(cfg.taskType);
        t.setSyncMode(cfg.syncMode);
        t.setSourceId(source.getId());
        t.setTargetId(target.getId());
        t.setTableName(table);

        t.setIdField(cfg.needIdField ? req.getIdField().trim() : null);
        t.setTimeField(cfg.needTimeField ? req.getTimeField().trim() : null);
        t.setStartId(0L);

        t.setBatchSize(cfg.batchSize);
        t.setShardCount(cfg.shardCount);
        t.setOverwriteFlag(cfg.overwriteFlag);
        t.setIgnoreFields(StringUtils.hasText(req.getIgnoreFields()) ? req.getIgnoreFields().trim() : cfg.ignoreFields);

        // CRON: 用户没改就用模板默认; 非 CRON 模板不写 cron
        t.setTriggerType(cfg.triggerType);
        t.setCronExpr("CRON".equalsIgnoreCase(cfg.triggerType)
                ? (StringUtils.hasText(req.getCronExpr()) ? req.getCronExpr().trim() : cfg.cronExpr)
                : null);

        if ("INCR".equalsIgnoreCase(cfg.taskType)) {
            // sync_mode 列非空: 增量不走 ID/TIME 游标, 统一用 BINLOG 占位(与任务页新建增量任务一致)
            t.setSyncMode(cfg.syncMode == null || cfg.syncMode.isEmpty() ? "BINLOG" : cfg.syncMode);
            t.setCanalHost(StringUtils.hasText(req.getCanalHost()) ? req.getCanalHost().trim() : cfg.canalHost);
            t.setCanalPort(req.getCanalPort() != null ? req.getCanalPort() : cfg.canalPort);
            t.setCanalDestination(StringUtils.hasText(req.getCanalDestination())
                    ? req.getCanalDestination().trim() : cfg.canalDestination);
            t.setBinlogDmlTypes(cfg.binlogDmlTypes);
        }

        t.setAlertEmail(req.getAlertEmail());
        t.setDingtalkWebhook(req.getDingtalkWebhook());
        t.setRemark("由模板「" + tpl.getName() + "」创建");

        try {
            if (authService.currentUser() != null) t.setCreateBy(authService.currentUser().getUserName());
        } catch (Exception ignore) { /* 取不到操作人就留空 */ }
        return t;
    }

    /**
     * 同名列自动配对: 源表列 ∩ 目标表列 - 忽略字段, 按源表列顺序生成映射
     */
    private int autoMapping(Long taskId, SyncDatasource source, SyncDatasource target,
                            String table, String ignoreFields) {
        Set<String> ignored = new HashSet<>();
        if (StringUtils.hasText(ignoreFields)) {
            Arrays.stream(ignoreFields.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .forEach(s -> ignored.add(s.toLowerCase()));
        }

        List<Map<String, String>> srcCols = JdbcUtils.listColumns(source, table);
        Set<String> tgtCols = new HashSet<>();
        for (Map<String, String> c : JdbcUtils.listColumns(target, table)) {
            String n = c.get("columnName");
            if (n != null) tgtCols.add(n.toLowerCase());
        }

        List<SyncTaskFieldMapping> mappings = new ArrayList<>();
        int sort = 0;
        for (Map<String, String> c : srcCols) {
            String name = c.get("columnName");
            if (name == null) continue;
            if (ignored.contains(name.toLowerCase())) continue;
            if (!tgtCols.contains(name.toLowerCase())) continue;
            SyncTaskFieldMapping m = new SyncTaskFieldMapping();
            m.setTaskId(taskId);
            m.setSourceField(name);
            m.setTargetField(name);
            m.setSortNo(sort++);
            mappings.add(m);
        }
        if (mappings.isEmpty()) return 0;
        mappingService.replace(taskId, mappings);
        return mappings.size();
    }

    /** 把模板配置摊平, 省得到处判空 */
    private static class TaskTemplateConfigView {
        private final String taskType;
        private final String syncMode;
        private final Integer batchSize;
        private final Integer shardCount;
        private final Integer overwriteFlag;
        private final String ignoreFields;
        private final String triggerType;
        private final String cronExpr;
        private final String canalHost;
        private final Integer canalPort;
        private final String canalDestination;
        private final String binlogDmlTypes;
        private final boolean needIdField;
        private final boolean needTimeField;

        TaskTemplateConfigView(TaskTemplate tpl) {
            this.taskType = tpl.getConfig().getTaskType();
            this.syncMode = tpl.getConfig().getSyncMode();
            this.batchSize = tpl.getConfig().getBatchSize();
            this.shardCount = tpl.getConfig().getShardCount();
            this.overwriteFlag = tpl.getConfig().getOverwriteFlag();
            this.ignoreFields = tpl.getConfig().getIgnoreFields();
            this.triggerType = tpl.getConfig().getTriggerType();
            this.cronExpr = tpl.getConfig().getCronExpr();
            this.canalHost = tpl.getConfig().getCanalHost();
            this.canalPort = tpl.getConfig().getCanalPort();
            this.canalDestination = tpl.getConfig().getCanalDestination();
            this.binlogDmlTypes = tpl.getConfig().getBinlogDmlTypes();
            this.needIdField = tpl.getConfig().isNeedIdField();
            this.needTimeField = tpl.getConfig().isNeedTimeField();
        }
    }
}
