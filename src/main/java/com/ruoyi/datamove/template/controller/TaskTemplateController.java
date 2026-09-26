package com.ruoyi.datamove.template.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.template.TaskTemplateService;
import com.ruoyi.datamove.template.domain.TaskTemplate;
import com.ruoyi.datamove.template.domain.TemplateApplyRequest;
import com.ruoyi.datamove.template.domain.TemplateApplyResult;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Api(tags = "迁移任务模板市场")
@RestController
@RequestMapping("/sync/template")
@RequiredArgsConstructor
public class TaskTemplateController {

    private final TaskTemplateService templateService;

    @ApiOperation("模板列表")
    @GetMapping("/list")
    public R<List<TaskTemplate>> list() {
        return R.ok(templateService.list());
    }

    @ApiOperation("模板详情 (含预置参数与使用提示)")
    @GetMapping("/{code}")
    public R<TaskTemplate> detail(@PathVariable String code) {
        return R.ok(templateService.detail(code));
    }

    @ApiOperation("套用模板创建任务 (返回新任务ID)")
    @PostMapping("/{code}/apply")
    public R<TemplateApplyResult> apply(@PathVariable String code,
                                        @RequestBody(required = false) TemplateApplyRequest req) {
        return R.ok(templateService.apply(code, req));
    }
}
