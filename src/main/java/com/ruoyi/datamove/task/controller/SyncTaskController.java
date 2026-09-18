package com.ruoyi.datamove.task.controller;

import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.task.domain.SyncTask;
import com.ruoyi.datamove.task.domain.SyncTaskLog;
import com.ruoyi.datamove.task.domain.SyncTaskProgress;
import com.ruoyi.datamove.task.service.ISyncTaskService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

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

    @ApiOperation("清空日志")
    @DeleteMapping("/{id}/logs")
    public R<Void> clear(@PathVariable Long id) {
        taskService.clearLog(id);
        return R.ok();
    }
}
