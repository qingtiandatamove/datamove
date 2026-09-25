package com.ruoyi.datamove.task.controller;

import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskLog;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;
import com.ruoyi.datamove.task.domain.TaskDashboardVO;
import com.ruoyi.datamove.task.domain.TaskExportVO;
import com.ruoyi.datamove.task.service.ISyncTaskService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@Api(tags = "同步任务管理")
@RestController
@RequestMapping("/sync/task")
public class SyncTaskController {

    @Autowired
    private ISyncTaskService taskService;

    @ApiOperation("分页查询")
    @GetMapping("/page")
    public R<PageResult<SyncTask>> page(@RequestParam(defaultValue = "1") int pageNum,
                                        @RequestParam(defaultValue = "10") int pageSize,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String taskType,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false, defaultValue = "id") String orderByColumn,
                                        @RequestParam(required = false, defaultValue = "asc") String isAsc) {
        return R.ok(taskService.page(keyword, taskType, status, orderByColumn, isAsc, pageNum, pageSize));
    }

    @ApiOperation("任务大盘(行/秒、ETA、当前批次、瓶颈库)")
    @GetMapping("/dashboard")
    public R<List<TaskDashboardVO>> dashboard() {
        return R.ok(taskService.dashboard());
    }

    @ApiOperation("详情")
    @GetMapping("/{id}")
    public R<SyncTask> detail(@PathVariable Long id) {
        return R.ok(taskService.detail(id));
    }

    @ApiOperation("新增")
    @PostMapping
    public R<Long> add(@RequestBody @Valid SyncTask t) {
        return R.ok(taskService.add(t));
    }

    @ApiOperation("修改")
    @PutMapping
    public R<Void> update(@RequestBody @Valid SyncTask t) {
        taskService.update(t);
        return R.ok();
    }

    @ApiOperation("删除")
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        taskService.remove(id);
        return R.ok();
    }

    /**
     * 克隆任务: 复制源任务的全部业务配置 (含同步表名), 重置状态/起始位点, 返回新任务 ID。
     * 新任务 status=STOP, 必须先在编辑页修改表名再启动, 否则会与源任务写入同一张表。
     */
    @ApiOperation("克隆任务 (复制配置, 重置运行态)")
    @PostMapping("/{id}/clone")
    public R<Long> cloneTask(@PathVariable Long id) {
        return R.ok(taskService.clone(id));
    }

    /* ============ 配置迁移 (导入导出) ============ */

    /** 导出任务配置: 前端拿到 JSON 后保存为 .json 文件, 拿到目标环境导入 */
    @ApiOperation("导出任务配置 (JSON, 跨环境迁移)")
    @GetMapping("/{id}/export")
    public R<TaskExportVO> exportTask(@PathVariable Long id) {
        return R.ok(taskService.exportTask(id));
    }

    /**
     * 导入任务配置: 数据源按「同名」匹配当前环境重映射 ID (不存在则报错),
     * 任务名冲突自动加 .import 后缀, 状态重置 STOP、断点清空, 字段映射一并导入。
     */
    @ApiOperation("导入任务配置 (JSON, 跨环境迁移)")
    @PostMapping("/import")
    public R<Long> importTask(@RequestBody TaskExportVO vo) {
        return R.ok(taskService.importTask(vo));
    }

    /* 同步操作 */

    @ApiOperation("启动")
    @PostMapping("/{id}/start")
    public R<Void> start(@PathVariable Long id) {
        taskService.start(id);
        return R.ok();
    }

    @ApiOperation("暂停")
    @PostMapping("/{id}/pause")
    public R<Void> pause(@PathVariable Long id) {
        taskService.pause(id);
        return R.ok();
    }

    @ApiOperation("继续(断点续传)")
    @PostMapping("/{id}/resume")
    public R<Void> resume(@PathVariable Long id) {
        taskService.resume(id);
        return R.ok();
    }

    @ApiOperation("终止")
    @PostMapping("/{id}/stop")
    public R<Void> stop(@PathVariable Long id) {
        taskService.stop(id);
        return R.ok();
    }

    @ApiOperation("重置进度(清断点,仅FULL任务,非RUNNING)")
    @PostMapping("/{id}/reset")
    public R<Void> reset(@PathVariable Long id) {
        taskService.reset(id);
        return R.ok();
    }

    @ApiOperation("查看进度")
    @GetMapping("/{id}/progress")
    public R<SyncTaskProgress> progress(@PathVariable Long id) {
        return R.ok(taskService.progress(id));
    }

    @ApiOperation("查看日志")
    @GetMapping("/{id}/logs")
    public R<PageResult<SyncTaskLog>> logs(@PathVariable Long id,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "1") int pageNum,
                                           @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(taskService.logs(id, status, pageNum, pageSize));
    }

    @ApiOperation("清理日志(不传 beforeDays 清理全部, 传 N 只清理 N 天前的)")
    @DeleteMapping("/{id}/logs")
    public R<Integer> clear(@PathVariable Long id,
                            @RequestParam(required = false) Integer beforeDays) {
        int deleted = taskService.clearLog(id, beforeDays);
        return R.ok(deleted, "已清理 " + deleted + " 条日志");
    }
}
