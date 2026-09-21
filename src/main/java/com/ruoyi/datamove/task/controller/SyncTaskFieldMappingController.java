package com.ruoyi.datamove.task.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.task.domain.SyncTaskFieldMapping;
import com.ruoyi.datamove.task.service.ISyncTaskFieldMappingService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Api(tags = "同步任务-字段映射")
@RestController
@RequestMapping("/sync/task/fieldMapping")
public class SyncTaskFieldMappingController {

    @Autowired
    private ISyncTaskFieldMappingService mappingService;

    @ApiOperation("列出该任务的全部字段映射")
    @GetMapping("/list/{taskId}")
    public R<List<SyncTaskFieldMapping>> list(@PathVariable Long taskId) {
        return R.ok(mappingService.listByTaskId(taskId));
    }

    /**
     * 全量替换式保存: 前端把当前 UI 上的所有配对 (包括删除的) 一次性提交过来;
     * 后端先清空再批量插入 (事务), 避免漏删/脏数据
     */
    @ApiOperation("保存字段映射 (替换式)")
    @PostMapping("/save/{taskId}")
    public R<Void> save(@PathVariable Long taskId,
                        @RequestBody List<SyncTaskFieldMapping> mappings) {
        mappingService.replace(taskId, mappings);
        return R.ok();
    }

    @ApiOperation("清空字段映射 (回到同名兼容模式)")
    @DeleteMapping("/{taskId}")
    public R<Void> clear(@PathVariable Long taskId) {
        mappingService.clear(taskId);
        return R.ok();
    }
}