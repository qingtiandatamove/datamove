package com.ruoyi.datamove.task.controller;

import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.task.domain.SyncTaskDiff;
import com.ruoyi.datamove.task.domain.SyncTaskVerify;
import com.ruoyi.datamove.task.service.DataVerifyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据校验(差异对账 + 一键修复)
 *
 * 独立于 /sync/task 之外: 校验是旁路动作, 不占用同步任务的状态位与暂停/续跑语义。
 */
@Api(tags = "数据校验")
@RestController
@RequestMapping("/sync/verify")
public class SyncTaskVerifyController {

    @Autowired
    private DataVerifyService verifyService;

    @ApiOperation("启动数据校验(返回校验ID, 前端据此轮询进度)")
    @PostMapping("/start/{taskId}")
    public R<Long> start(@PathVariable Long taskId) {
        return R.ok(verifyService.start(taskId));
    }

    @ApiOperation("校验详情/进度")
    @GetMapping("/{verifyId}")
    public R<SyncTaskVerify> detail(@PathVariable Long verifyId) {
        return R.ok(verifyService.detail(verifyId));
    }

    @ApiOperation("任务最近一次校验(无则 data 为 null)")
    @GetMapping("/latest/{taskId}")
    public R<SyncTaskVerify> latest(@PathVariable Long taskId) {
        return R.ok(verifyService.latest(taskId));
    }

    @ApiOperation("差异汇总(按类型/修复状态计数)")
    @GetMapping("/{verifyId}/summary")
    public R<Map<String, Object>> summary(@PathVariable Long verifyId) {
        return R.ok(verifyService.diffSummary(verifyId));
    }

    @ApiOperation("差异明细分页")
    @GetMapping("/{verifyId}/diffs")
    public R<PageResult<SyncTaskDiff>> diffs(@PathVariable Long verifyId,
                                             @RequestParam(required = false) String diffType,
                                             @RequestParam(required = false) String repairStatus,
                                             @RequestParam(defaultValue = "1") int pageNum,
                                             @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(verifyService.diffPage(verifyId, diffType, repairStatus, pageNum, pageSize));
    }

    @ApiOperation("差异明细(不分页, 用于前端导出)")
    @GetMapping("/{verifyId}/diffList")
    public R<List<SyncTaskDiff>> diffList(@PathVariable Long verifyId,
                                          @RequestParam(required = false) String diffType) {
        return R.ok(verifyService.diffList(verifyId, diffType));
    }

    @ApiOperation("一键同步差异(补缺失 + 修不一致)")
    @PostMapping("/{verifyId}/repair")
    public R<Void> repair(@PathVariable Long verifyId) {
        verifyService.repair(verifyId);
        return R.ok();
    }

    @ApiOperation("中止校验")
    @PostMapping("/{verifyId}/stop")
    public R<Void> stop(@PathVariable Long verifyId) {
        verifyService.stop(verifyId);
        return R.ok();
    }

    @ApiOperation("中止修复")
    @PostMapping("/{verifyId}/repair/stop")
    public R<Void> stopRepair(@PathVariable Long verifyId) {
        verifyService.stopRepair(verifyId);
        return R.ok();
    }
}
