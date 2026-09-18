package com.ruoyi.datamove.log.controller;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.log.domain.SyncSqlLog;
import com.ruoyi.datamove.log.mapper.SyncSqlLogMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SQL 执行日志: 数据工作台(SQL 工作台 / 数据中心)对数据源的操作留痕
 */
@Api(tags = "SQL 执行日志")
@RestController
@RequestMapping("/sync/sql/log")
public class SyncSqlLogController {

    /** 导出最多条数, 防止一次拉爆内存 */
    private static final int EXPORT_MAX = 10000;

    @Autowired
    private SyncSqlLogMapper sqlLogMapper;

    @ApiOperation("分页查询 SQL 执行日志")
    @GetMapping("/page")
    public R<PageResult<SyncSqlLog>> page(@RequestParam(defaultValue = "1") int pageNum,
                                          @RequestParam(defaultValue = "20") int pageSize,
                                          @RequestParam(required = false) Long dsId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String sourceType,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String beginTime,
                                          @RequestParam(required = false) String endTime) {
        Page<SyncSqlLog> page = new Page<>(pageNum, pageSize);
        Page<SyncSqlLog> result = sqlLogMapper.selectPage(page,
                buildWrapper(dsId, status, sourceType, keyword, beginTime, endTime).orderByDesc("id"));
        return R.ok(PageResult.of(result.getRecords(), result.getTotal()));
    }

    @ApiOperation("导出 SQL 执行日志(CSV)")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) Long dsId,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String sourceType,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String beginTime,
                       @RequestParam(required = false) String endTime,
                       HttpServletResponse response) throws Exception {
        List<SyncSqlLog> list = sqlLogMapper.selectList(
                buildWrapper(dsId, status, sourceType, keyword, beginTime, endTime)
                        .orderByDesc("id").last("limit " + EXPORT_MAX));

        response.setContentType("text/csv;charset=utf-8");
        response.setHeader("Content-Disposition", "attachment;filename=" +
                URLEncoder.encode("sql_log.csv", "UTF-8"));

        try (OutputStream os = response.getOutputStream()) {
            StringBuilder sb = new StringBuilder();
            sb.append("ID,操作时间,操作人,IP,数据源,数据库,来源,状态,语句数,结果行数,影响行数,耗时ms,SQL,错误信息\n");
            for (SyncSqlLog e : list) {
                sb.append(e.getId()).append(',')
                        .append(safe(e.getCreateTime() == null ? "" : DateUtil.formatDateTime(e.getCreateTime()))).append(',')
                        .append(safe(e.getOperName())).append(',')
                        .append(safe(e.getOperIp())).append(',')
                        .append(safe(e.getDsName())).append(',')
                        .append(safe(e.getDbName())).append(',')
                        .append(safe(sourceLabel(e.getSourceType()))).append(',')
                        .append(safe(e.getStatus())).append(',')
                        .append(e.getStmtCount() == null ? 0 : e.getStmtCount()).append(',')
                        .append(e.getResultRows() == null ? 0 : e.getResultRows()).append(',')
                        .append(e.getAffectedRows() == null ? 0 : e.getAffectedRows()).append(',')
                        .append(e.getCostMs() == null ? 0 : e.getCostMs()).append(',')
                        .append(safe(e.getSqlText())).append(',')
                        .append(safe(e.getErrorMsg()))
                        .append('\n');
            }
            // BOM 头: 保证 Excel 打开 UTF-8 中文不乱码
            os.write("\ufeff".getBytes(StandardCharsets.UTF_8));
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    /** 查询条件组装 */
    private QueryWrapper<SyncSqlLog> buildWrapper(Long dsId, String status, String sourceType,
                                                  String keyword, String beginTime, String endTime) {
        QueryWrapper<SyncSqlLog> wrapper = new QueryWrapper<>();
        if (dsId != null) wrapper.eq("ds_id", dsId);
        if (StrUtil.isNotBlank(status)) wrapper.eq("status", status);
        if (StrUtil.isNotBlank(sourceType)) wrapper.eq("source_type", sourceType);
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(q -> q.like("sql_text", keyword)
                    .or().like("oper_name", keyword)
                    .or().like("error_msg", keyword));
        }
        if (StrUtil.isNotBlank(beginTime)) wrapper.ge("create_time", beginTime);
        if (StrUtil.isNotBlank(endTime)) wrapper.le("create_time", endTime);
        return wrapper;
    }

    private String sourceLabel(String sourceType) {
        if ("DATA_BROWSE".equals(sourceType)) return "数据中心";
        if ("SQL_CONSOLE".equals(sourceType)) return "SQL 工作台";
        return sourceType == null ? "" : sourceType;
    }

    /** CSV 转义: 含逗号/引号/换行时加引号包裹, 内部引号翻倍, 换行压成空格 */
    private String safe(String s) {
        if (s == null) return "";
        String v = s.replaceAll("[\\r\\n]+", " ");
        if (v.contains(",") || v.contains("\"")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
