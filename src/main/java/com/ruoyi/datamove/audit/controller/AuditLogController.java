package com.ruoyi.datamove.audit.controller;

import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.audit.domain.AuditLog;
import com.ruoyi.datamove.audit.service.AuditLogService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Api(tags = "审计日志")
@RestController
@RequestMapping("/sync/audit")
public class AuditLogController {

    @Autowired
    private AuditLogService auditLogService;

    @ApiOperation("审计日志分页(谁在什么时候改了哪个任务的哪个字段, 合规审计必备)")
    @GetMapping("/page")
    public R<PageResult<AuditLog>> page(@RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "20") int pageSize,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String opType,
                                       @RequestParam(required = false) String beginTime,
                                       @RequestParam(required = false) String endTime,
                                       @RequestParam(required = false, defaultValue = "id") String orderByColumn,
                                       @RequestParam(required = false, defaultValue = "desc") String isAsc) {
        return R.ok(auditLogService.page(keyword, opType, beginTime, endTime,
                orderByColumn, isAsc, pageNum, pageSize));
    }

    @ApiOperation("查询同一次请求的所有字段变更 (详情用)")
    @GetMapping("/revision/{revisionId}")
    public R<List<AuditLog>> revision(@PathVariable Long revisionId) {
        return R.ok(auditLogService.listByRevision(revisionId));
    }
}