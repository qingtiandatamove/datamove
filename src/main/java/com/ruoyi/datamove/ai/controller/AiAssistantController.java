package com.ruoyi.datamove.ai.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.ai.AiAssistantService;
import com.ruoyi.datamove.ai.domain.AiModifyApplyRequest;
import com.ruoyi.datamove.ai.domain.AiModifyRequest;
import com.ruoyi.datamove.ai.domain.AiModifyResult;
import com.ruoyi.datamove.ai.domain.AiParseResult;
import com.ruoyi.datamove.ai.domain.AiTaskDraft;
import com.ruoyi.datamove.ai.domain.AiTextRequest;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Api(tags = "AI 任务配置助手")
@RestController
@RequestMapping("/sync/ai")
@RequiredArgsConstructor
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;

    @ApiOperation("助手状态: AI 是否可用, 未配置时返回规则解析模式")
    @GetMapping("/status")
    public R<AiParseResult> status() {
        return R.ok(aiAssistantService.status());
    }

    @ApiOperation("自然语言 → 任务配置草稿(含解释与风险提示)")
    @PostMapping("/parse")
    public R<AiParseResult> parse(@RequestBody AiTextRequest req) {
        return R.ok(aiAssistantService.parse(req == null ? null : req.getText()));
    }

    @ApiOperation("确认草稿 → 一键创建任务, 返回任务ID")
    @PostMapping("/apply")
    public R<Long> apply(@RequestBody AiTaskDraft draft) {
        return R.ok(aiAssistantService.apply(draft));
    }

    @ApiOperation("AI 修改任务(预览差异, 不落库)")
    @PostMapping("/modify")
    public R<AiModifyResult> modify(@RequestBody AiModifyRequest req) {
        if (req == null || req.getTaskId() == null) throw new RuntimeException("缺少任务ID");
        return R.ok(aiAssistantService.previewModify(req.getTaskId(), req.getText()));
    }

    @ApiOperation("确认应用 AI 修改")
    @PostMapping("/modify/apply")
    public R<AiModifyResult> applyModify(@RequestBody AiModifyApplyRequest req) {
        if (req == null || req.getTaskId() == null) throw new RuntimeException("缺少任务ID");
        return R.ok(aiAssistantService.applyModify(req.getTaskId(), req.getDraft()));
    }
}
