package com.ruoyi.datamove.task.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ruoyi.datamove.engine.consts.SyncType;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.mapper.SyncTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 任务调度器: 支持 Cron 定时 / 手动 / 事件触发 三选一调度方式。
 *
 * - MANUAL: 什么都不注册, 只能由用户在界面上点「启动」(默认, 兼容存量任务)
 * - CRON:   按任务上的 cron_expr (Spring 6 位表达式) 周期性自动调用 start()
 * - EVENT:  不在本类注册, 由外部系统调 HTTP 回调 POST /sync/task/event/{token} 触发启动
 *
 * 触发语义:
 *   到点的动作与用户点「启动」完全等价 (走 taskService.start), 因此:
 *   - 任务正在 RUNNING / PAUSE 时本次到点直接跳过 (不报错, 打 info 日志)
 *   - FULL 任务按 cron 反复全量/断点续传, DDL 任务可周期性重放表结构
 *   - 手动「停止」只停当次运行, 不注销 cron 注册; 改配置/删除才注销或重注册
 *
 * 单机进程内实现 (与同步引擎一致), 重启后 {@link #reloadAll} 重新装载。
 */
@Slf4j
@Component
public class TaskTriggerScheduler {

    private final SyncTaskMapper taskMapper;

    /** @Lazy 打破循环依赖: SyncTaskServiceImpl 在增删改后要回调本类 register/unregister */
    private final ISyncTaskService taskService;

    private final ThreadPoolTaskScheduler scheduler;

    /** taskId -> 已注册的 cron 调度句柄 */
    private final Map<Long, ScheduledFuture<?>> cronTasks = new ConcurrentHashMap<>();

    public TaskTriggerScheduler(SyncTaskMapper taskMapper,
                                @Lazy ISyncTaskService taskService,
                                ThreadPoolTaskScheduler triggerScheduler) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.scheduler = triggerScheduler;
    }

    /* ==================== 对外注册接口 (任务增/改/删时调用) ==================== */

    /**
     * 注册/刷新一个任务的调度: 按 DB 里最新的 triggerType + cronExpr 生效。
     * 调度方式不是 CRON 时等价于注销 (MANUAL/EVENT 不占 cron 槽位)。
     */
    public synchronized void register(SyncTask task) {
        if (task == null || task.getId() == null) return;
        cancel(task.getId());

        if (!SyncType.TRIGGER_CRON.equals(task.getTriggerType())) return;
        String expr = task.getCronExpr();
        if (expr == null || expr.trim().isEmpty()) {
            log.warn("[trigger] 任务 #{}({}) 调度方式为 CRON 但未填表达式, 不注册", task.getId(), task.getTaskName());
            return;
        }
        try {
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> fireCron(task.getId()), new CronTrigger(expr.trim()));
            cronTasks.put(task.getId(), future);
            log.info("[trigger] 任务 #{}({}) 已注册定时调度: {}", task.getId(), task.getTaskName(), expr.trim());
        } catch (IllegalArgumentException e) {
            // 表达式非法: 不注册也不抛 (服务层保存时已校验过, 这里兜底防崩溃)
            log.error("[trigger] 任务 #{}({}) CRON 表达式非法 [{}], 未注册: {}",
                    task.getId(), task.getTaskName(), expr, e.getMessage());
        }
    }

    /** 注销一个任务的调度 (删除任务 / 调度方式改走 MANUAL/EVENT 时调用) */
    public synchronized void unregister(Long taskId) {
        if (taskId == null) return;
        if (cancel(taskId)) {
            log.info("[trigger] 任务 #{} 已注销定时调度", taskId);
        }
    }

    /** 进程重启后全量重装载: 收口 + 按当前 DB 配置重新注册所有 CRON 任务 */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void reloadAll() {
        for (Long id : cronTasks.keySet()) {
            cancel(id);
        }
        try {
            List<SyncTask> tasks = taskMapper.selectList(new QueryWrapper<SyncTask>()
                    .eq("del_flag", "0")
                    .eq("trigger_type", SyncType.TRIGGER_CRON));
            for (SyncTask t : tasks) {
                register(t);
            }
            log.info("[trigger] 启动装载完成: {} 个 CRON 任务", tasks.size());
        } catch (Exception e) {
            // 装载失败不能影响应用启动; 已注册的部分不受影响
            log.error("[trigger] 启动装载 CRON 任务失败", e);
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        for (Long id : cronTasks.keySet()) {
            cancel(id);
        }
    }

    /** 当前已注册的 cron 任务数 (监控/排查用) */
    public int registeredCount() {
        return cronTasks.size();
    }

    /**
     * cron 表达式校验 (Spring 6 位: 秒 分 时 日 月 周)。
     * 服务层保存任务时调用, 非法表达式直接拒绝入库。
     */
    public static boolean isValidCron(String expr) {
        if (expr == null || expr.trim().isEmpty()) return false;
        try {
            CronExpression.parse(expr.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /* ==================== 内部 ==================== */

    /** cron 到点: 与用户点「启动」等价, 运行中/暂停则跳过本次 */
    private void fireCron(Long taskId) {
        try {
            SyncTask db = taskMapper.selectById(taskId);
            if (db == null || "1".equals(db.getDelFlag())) {
                // 任务被删但注册没来得及清 (如直接操作 DB): 顺手注销
                unregister(taskId);
                return;
            }
            if (!SyncType.TRIGGER_CRON.equals(db.getTriggerType())) {
                unregister(taskId);
                return;
            }
            if (SyncType.STATUS_RUNNING.equals(db.getStatus()) || SyncType.STATUS_PAUSE.equals(db.getStatus())) {
                log.info("[trigger] 任务 #{}({}) 正在运行({}), 本次到点跳过", taskId, db.getTaskName(), db.getStatus());
                return;
            }
            log.info("[trigger] 任务 #{}({}) 定时到点, 自动启动", taskId, db.getTaskName());
            taskService.start(taskId);
        } catch (Exception e) {
            // 单次触发失败不影响下一轮调度; 失败原因任务日志/运行历史里已有记录
            log.error("[trigger] 任务 #{} 定时启动失败: {}", taskId, e.getMessage(), e);
        }
    }

    private boolean cancel(Long taskId) {
        ScheduledFuture<?> future = cronTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
            return true;
        }
        return false;
    }
}
