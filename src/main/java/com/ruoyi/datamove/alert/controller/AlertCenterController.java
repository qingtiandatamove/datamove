package com.ruoyi.datamove.alert.controller;

import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.alert.domain.SyncAlertRecord;
import com.ruoyi.datamove.alert.service.AlertCenterService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Api(tags = "告警中心")
@RestController
@RequestMapping("/sync/alert")
@RequiredArgsConstructor
public class AlertCenterController {

    private final AlertCenterService alertService;

    @ApiOperation("告警记录分页")
    @GetMapping("/page")
    public R<PageResult<SyncAlertRecord>> page(@RequestParam(defaultValue = "1") int pageNum,
                                                @RequestParam(defaultValue = "10") int pageSize,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String channel,
                                                @RequestParam(required = false) String alertType,
                                                @RequestParam(required = false) String keyword) {
        return R.ok(alertService.page(pageNum, pageSize, status, channel, alertType, keyword));
    }

    @ApiOperation("告警统计 (总数/成功/失败/待发送/今日)")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(alertService.stats());
    }

    @ApiOperation("重试一条失败告警")
    @PostMapping("/{id}/retry")
    public R<SyncAlertRecord> retry(@PathVariable Long id) {
        return R.ok(alertService.retry(id));
    }

    @ApiOperation("发送测试告警 (验证通道是否配好)")
    @PostMapping("/test")
    public R<SyncAlertRecord> test(@RequestBody Map<String, String> body) {
        return R.ok(alertService.test(
                body.get("channel"),
                body.get("target"),
                body.get("subject"),
                body.get("content")));
    }

    @ApiOperation("删除一条告警记录")
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        alertService.delete(id);
        return R.ok();
    }

    @ApiOperation("清理 N 天前的告警记录")
    @PostMapping("/clear")
    public R<Integer> clear(@RequestParam(defaultValue = "30") int days) {
        return R.ok(alertService.clear(days));
    }
}
