package com.ruoyi.datamove.task.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ruoyi.datamove.engine.consts.SyncType;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;
import com.ruoyi.datamove.task.mapper.SyncTaskMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskProgressMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 启动收口: 把上次进程遗留的「运行中」状态收口成「已停止」。
 *
 * 为什么需要:
 *   sync_task.status 只在「启动/暂停/停止/失败」这几个动作点写一次, 运行期间并不会被周期更新
 *   (真正的心跳写在 sync_task_run.update_time, 最多 5s 一次), 所以进程被 kill / 崩溃 / 重启后,
 *   这条 status 会永久停留在 RUNNING —— 它自己不会自愈。
 * 后果:
 *   前端任务列表 startPolling() 的判断条件是「当前页里有 status === 'RUNNING' 的行」, 于是一条
 *   僵尸 RUNNING 会让列表页每 3 秒打一次 /sync/task/page, 永远停不下来。
 *
 * 为什么在启动时收口是安全的:
 *   本进程刚起来, 内存里的 FullSyncEngine / CanalSyncEngine 都是空的, 不可能有任何任务在跑,
 *   因此此时所有 RUNNING 都必然是上一次进程的遗留。(本应用的任务引擎是进程内单实例, 单机部署)
 *
 * 收口范围与「点停止」保持一致(taskMapper 状态 + 断点进度 + 运行历史), 但**不动断点本身**:
 *   sync_task.status      RUNNING -> STOP
 *   sync_task_progress    状态改为 STOP 并补 endTime, 但 lastSyncMaxId / lastSyncTime 原样保留
 *   sync_task_run         悬挂的 RUNNING 记录 -> STOP, 带上中断原因
 * 所以收口后重新「启动」依旧从断点续传, 不会丢数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleRunningTaskCleaner implements ApplicationRunner {

    private final SyncTaskMapper         taskMapper;
    private final SyncTaskProgressMapper progressMapper;
    private final TaskRunService         runService;

    /** 收口原因: 写进运行历史, 便于以后排查「这次运行为什么是停止的」 */
    private static final String REASON = "应用重启, 上次运行随进程退出而中断, 已自动收口为已停止";

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<SyncTask> stale = taskMapper.selectList(new QueryWrapper<SyncTask>()
                    .eq("del_flag", "0")
                    .eq("status", SyncType.STATUS_RUNNING));
            if (stale.isEmpty()) return;

            Date now = new Date();
            for (SyncTask t : stale) {
                SyncTask u = new SyncTask();
                u.setId(t.getId());
                u.setStatus(SyncType.STATUS_STOP);
                u.setUpdateTime(now);
                taskMapper.updateById(u);

                SyncTaskProgress p = progressMapper.selectOne(
                        new QueryWrapper<SyncTaskProgress>().eq("task_id", t.getId()));
                if (p != null && SyncType.STATUS_RUNNING.equals(p.getStatus())) {
                    p.setStatus(SyncType.STATUS_STOP);
                    p.setEndTime(now);
                    p.setUpdateTime(now);
                    progressMapper.updateById(p);
                }

                runService.finishRunning(t.getId(), SyncType.STATUS_STOP, REASON);
            }

            log.warn("[启动收口] 发现 {} 个上次进程遗留的「运行中」任务, 已收口为已停止: {}",
                    stale.size(),
                    stale.stream().map(t -> t.getId() + ":" + t.getTaskName()).collect(Collectors.joining(", ")));
        } catch (Exception e) {
            // 收口失败不能影响应用启动, 顶多前端继续多轮询一会儿
            log.error("[启动收口] 处理遗留「运行中」任务失败", e);
        }
    }
}
