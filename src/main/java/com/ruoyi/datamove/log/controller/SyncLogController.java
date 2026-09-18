package com.ruoyi.datamove.log.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.task.domain.SyncTaskLog;
import com.ruoyi.datamove.task.mapper.SyncTaskLogMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Api(tags = "同步日志")
@RestController
@RequestMapping("/sync/log")
public class SyncLogController {

    @Autowired
    private SyncTaskLogMapper logMapper;

    @ApiOperation("分页查询日志")
    @GetMapping("/page")
    public R<PageResult<SyncTaskLog>> page(@RequestParam(defaultValue = "1") int pageNum,
                                            @RequestParam(defaultValue = "10") int pageSize,
                                            @RequestParam(required = false) Long taskId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String tableName) {
        Page<SyncTaskLog> page = new Page<>(pageNum, pageSize);
        QueryWrapper<SyncTaskLog> wrapper = new QueryWrapper<>();
        if (taskId != null) wrapper.eq("task_id", taskId);
        if (status != null && !status.isEmpty()) wrapper.eq("status", status);
        if (tableName != null && !tableName.isEmpty()) wrapper.like("table_name", tableName);
        wrapper.orderByDesc("id");
        Page<SyncTaskLog> result = logMapper.selectPage(page, wrapper);
        return R.ok(PageResult.of(result.getRecords(), result.getTotal()));
    }

    @ApiOperation("导出日志(CSV)")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) Long taskId,
                       @RequestParam(required = false) String status,
                       HttpServletResponse response) throws Exception {
        QueryWrapper<SyncTaskLog> wrapper = new QueryWrapper<>();
        if (taskId != null) wrapper.eq("task_id", taskId);
        if (status != null && !status.isEmpty()) wrapper.eq("status", status);
        wrapper.orderByAsc("id");
        List<SyncTaskLog> list = logMapper.selectList(wrapper);

        response.setContentType("text/csv;charset=utf-8");
        response.setHeader("Content-Disposition", "attachment;filename=" +
                URLEncoder.encode("sync_log.csv", "UTF-8"));

        try (OutputStream os = response.getOutputStream()) {
            StringBuilder sb = new StringBuilder("ID,任务ID,任务名称,表名,同步模式,批次号,起始,结束,行数,累计行数,耗时ms,状态,异常信息,同步内容,创建时间\n");
            for (SyncTaskLog l : list) {
                sb.append(l.getId()).append(',')
                        .append(l.getTaskId()).append(',')
                        .append(safe(l.getTaskName())).append(',')
                        .append(safe(l.getTableName())).append(',')
                        .append(safe(l.getSyncMode())).append(',')
                        .append(l.getBatchNo()).append(',')
                        .append(safe(l.getBatchStartId())).append(',')
                        .append(safe(l.getBatchEndId())).append(',')
                        .append(l.getBatchRows()).append(',')
                        .append(l.getTotalRows()).append(',')
                        .append(l.getCostMs()).append(',')
                        .append(safe(l.getStatus())).append(',')
                        .append(safe(l.getErrorMsg() == null ? "" : l.getErrorMsg().replaceAll("[\\r\\n]", " "))).append(',')
                        .append(safe(l.getContent() == null ? "" : l.getContent().replaceAll("[\\r\\n]", " "))).append(',')
                        .append(l.getCreateTime())
                        .append('\n');
            }
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private String safe(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
