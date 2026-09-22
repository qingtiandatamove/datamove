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
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
                                            @RequestParam(required = false) String taskName,
                                            @RequestParam(required = false) String tableName,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Integer shardNo,
                                            @RequestParam(required = false) Integer batchNo,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Boolean hasError,
                                            @RequestParam(required = false) String beginTime,
                                            @RequestParam(required = false) String endTime) {
        QueryWrapper<SyncTaskLog> wrapper = buildWrapper(taskId, taskName, tableName, status,
                shardNo, batchNo, keyword, hasError, beginTime, endTime);
        wrapper.orderByDesc("id");
        Page<SyncTaskLog> page = new Page<>(pageNum, pageSize);
        Page<SyncTaskLog> result = logMapper.selectPage(page, wrapper);
        return R.ok(PageResult.of(result.getRecords(), result.getTotal()));
    }

    @ApiOperation("按当前筛选条件统计日志(条数/行数/耗时/异常数)")
    @GetMapping("/summary")
    public R<Map<String, Object>> summary(@RequestParam(required = false) Long taskId,
                                          @RequestParam(required = false) String taskName,
                                          @RequestParam(required = false) String tableName,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) Integer shardNo,
                                          @RequestParam(required = false) Integer batchNo,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Boolean hasError,
                                          @RequestParam(required = false) String beginTime,
                                          @RequestParam(required = false) String endTime) {
        QueryWrapper<SyncTaskLog> w = buildWrapper(taskId, taskName, tableName, status,
                shardNo, batchNo, keyword, hasError, beginTime, endTime);
        w.select(
                "COUNT(*) AS total",
                "IFNULL(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_count",
                "IFNULL(SUM(CASE WHEN status = 'FAILED'  THEN 1 ELSE 0 END), 0) AS failed_count",
                "IFNULL(SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END), 0) AS running_count",
                // 区间内实际同步行数(各批次之和)
                "IFNULL(SUM(batch_rows), 0) AS rows_sum",
                "IFNULL(ROUND(AVG(cost_ms)), 0) AS avg_cost_ms",
                "IFNULL(MAX(cost_ms), 0) AS max_cost_ms",
                "IFNULL(SUM(CASE WHEN error_msg IS NOT NULL AND error_msg <> '' THEN 1 ELSE 0 END), 0) AS error_count",
                "MAX(create_time) AS last_time"
        );
        List<Map<String, Object>> list = logMapper.selectMaps(w);
        Map<String, Object> src = list.isEmpty() ? null : list.get(0);
        Map<String, Object> out = new HashMap<>(10);
        out.put("total", val(src, "total"));
        out.put("successCount", val(src, "success_count"));
        out.put("failedCount", val(src, "failed_count"));
        out.put("runningCount", val(src, "running_count"));
        out.put("rowsSum", val(src, "rows_sum"));
        out.put("avgCostMs", val(src, "avg_cost_ms"));
        out.put("maxCostMs", val(src, "max_cost_ms"));
        out.put("errorCount", val(src, "error_count"));
        out.put("lastTime", src == null ? null : src.get("last_time"));
        return R.ok(out);
    }

    @ApiOperation("导出日志(CSV)")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) Long taskId,
                       @RequestParam(required = false) String taskName,
                       @RequestParam(required = false) String tableName,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) Integer shardNo,
                       @RequestParam(required = false) Integer batchNo,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Boolean hasError,
                       @RequestParam(required = false) String beginTime,
                       @RequestParam(required = false) String endTime,
                       HttpServletResponse response) throws Exception {
        QueryWrapper<SyncTaskLog> wrapper = buildWrapper(taskId, taskName, tableName, status,
                shardNo, batchNo, keyword, hasError, beginTime, endTime);
        wrapper.orderByAsc("id");
        List<SyncTaskLog> list = logMapper.selectList(wrapper);

        response.setContentType("text/csv;charset=utf-8");
        response.setHeader("Content-Disposition", "attachment;filename=" +
                URLEncoder.encode("sync_log.csv", "UTF-8"));

        try (OutputStream os = response.getOutputStream()) {
            StringBuilder sb = new StringBuilder("ID,任务ID,任务名称,表名,同步模式,批次号,分片号,起始,结束,行数,累计行数,耗时ms,状态,异常信息,同步内容,创建时间\n");
            for (SyncTaskLog l : list) {
                sb.append(l.getId()).append(',')
                        .append(l.getTaskId()).append(',')
                        .append(safe(l.getTaskName())).append(',')
                        .append(safe(l.getTableName())).append(',')
                        .append(safe(l.getSyncMode())).append(',')
                        .append(l.getBatchNo()).append(',')
                        .append(l.getShardNo() == null ? "" : "S" + l.getShardNo()).append(',')
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

    /**
     * 统一构造筛选条件 (分页 / 统计 / 导出 三处共用, 保证口径一致)
     *
     * @param keyword   全文关键字, 模糊匹配 任务名/表名/位点/同步内容/异常信息/同步模式
     * @param hasError  仅看带异常信息的日志
     * @param beginTime 起始时间, 支持 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss
     * @param endTime   结束时间(仅日期时补齐到当天 23:59:59)
     */
    private QueryWrapper<SyncTaskLog> buildWrapper(Long taskId, String taskName, String tableName,
                                                   String status, Integer shardNo, Integer batchNo,
                                                   String keyword, Boolean hasError,
                                                   String beginTime, String endTime) {
        QueryWrapper<SyncTaskLog> w = new QueryWrapper<>();
        if (taskId != null) w.eq("task_id", taskId);
        if (notBlank(taskName)) w.like("task_name", taskName.trim());
        if (notBlank(tableName)) w.like("table_name", tableName.trim());
        if (notBlank(status)) w.eq("status", status.trim());
        if (shardNo != null) w.eq("shard_no", shardNo);
        if (batchNo != null) w.eq("batch_no", batchNo);
        if (Boolean.TRUE.equals(hasError)) {
            w.isNotNull("error_msg");
            w.ne("error_msg", "");
        }
        if (notBlank(keyword)) {
            String kw = keyword.trim();
            // 用 and(...) 包一层, 避免 or 条件把前面的 eq/like 冲掉
            w.and(q -> q.like("task_name", kw)
                    .or().like("table_name", kw)
                    .or().like("batch_start_id", kw)
                    .or().like("batch_end_id", kw)
                    .or().like("content", kw)
                    .or().like("error_msg", kw)
                    .or().like("sync_mode", kw));
        }
        Date begin = parseTime(beginTime, false);
        Date end = parseTime(endTime, true);
        if (begin != null) w.ge("create_time", begin);
        if (end != null) w.le("create_time", end);
        return w;
    }

    /** 解析时间参数; endOfDay=true 且只给到日期时, 补齐到 23:59:59 */
    private Date parseTime(String text, boolean endOfDay) {
        if (!notBlank(text)) return null;
        String s = text.trim().replace('T', ' ');
        try {
            if (s.length() <= 10) {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .parse(s + (endOfDay ? " 23:59:59" : " 00:00:00"));
            }
            if (s.length() == 16) {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s + ":00");
            }
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (Exception e) {
            // 时间格式非法时忽略该条件, 不阻断查询
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private Object val(Map<String, Object> row, String key) {
        if (row == null) return 0;
        Object v = row.get(key);
        return v == null ? 0 : v;
    }

    private String safe(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * 按天聚合同步行数 + 成功/失败次数, 用于首页趋势图
     * 入参: days 取值范围 1-90, 默认 7
     * 返回: 把没有日志的日期也补 0, 方便前端画连续的折线/柱状
     */
    @ApiOperation("按天聚合同步日志(趋势图)")
    @GetMapping("/trend")
    public R<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > 90) days = 7;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // 把 MySQL 的 GROUP BY 结果聚成 map, key 为 yyyy-MM-dd
        QueryWrapper<SyncTaskLog> w = new QueryWrapper<>();
        w.select(
                "DATE_FORMAT(create_time, '%Y-%m-%d') AS d",
                // 'rows' 是 MySQL 8.0 保留字 (窗口函数 ROWS BETWEEN ...), 必须用反引号
                "IFNULL(SUM(batch_rows), 0) AS `rows`",
                "SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count",
                "SUM(CASE WHEN status = 'FAILED'  THEN 1 ELSE 0 END) AS failed_count"
        );
        w.ge("create_time", LocalDate.now().minusDays(days - 1).atStartOfDay());
        w.groupBy("d");
        w.orderByAsc("d");
        Map<String, Map<String, Object>> grouped = new TreeMap<>();
        for (Map<String, Object> row : logMapper.selectMaps(w)) {
            String d = String.valueOf(row.get("d"));
            grouped.put(d, row);
        }

        // 补齐空白日期, 让前端曲线连续
        List<Map<String, Object>> out = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            String d = LocalDate.now().minusDays(i).format(fmt);
            Map<String, Object> row = grouped.get(d);
            Map<String, Object> item = new HashMap<>(4);
            item.put("date", d);
            item.put("rows",          row == null ? 0 : row.get("rows"));
            item.put("successCount",  row == null ? 0 : row.get("success_count"));
            item.put("failedCount",   row == null ? 0 : row.get("failed_count"));
            out.add(item);
        }
        return R.ok(out);
    }
}
