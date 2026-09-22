package com.ruoyi.datamove.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.task.domain.SyncTaskRun;
import com.ruoyi.datamove.task.mapper.SyncTaskRunMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
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

/**
 * 任务运行历史 (sync_task_run)
 *
 * 大盘「运行历史」用: 每次启动任务一条记录, 记录 结果/耗时/行数/速率/异常,
 * 支持 分页筛选 / 概览统计 / 按天趋势 / CSV 导出 / 按条件清理 (与同步日志口径一致)。
 */
@Slf4j
@Api(tags = "任务运行历史")
@RestController
@RequestMapping("/sync/task/run")
public class SyncTaskRunController {

    @Autowired
    private SyncTaskRunMapper runMapper;

    @ApiOperation("分页查询运行历史")
    @GetMapping("/page")
    public R<PageResult<SyncTaskRun>> page(@RequestParam(defaultValue = "1") int pageNum,
                                           @RequestParam(defaultValue = "20") int pageSize,
                                           @RequestParam(required = false) Long taskId,
                                           @RequestParam(required = false) String taskName,
                                           @RequestParam(required = false) String tableName,
                                           @RequestParam(required = false) String taskType,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String beginTime,
                                           @RequestParam(required = false) String endTime) {
        QueryWrapper<SyncTaskRun> w = buildWrapper(taskId, taskName, tableName, taskType, status,
                keyword, beginTime, endTime);
        w.orderByDesc("id");
        Page<SyncTaskRun> page = new Page<>(pageNum, pageSize);
        Page<SyncTaskRun> result = runMapper.selectPage(page, w);
        return R.ok(PageResult.of(result.getRecords(), result.getTotal()));
    }

    @ApiOperation("按当前筛选条件统计运行历史")
    @GetMapping("/summary")
    public R<Map<String, Object>> summary(@RequestParam(required = false) Long taskId,
                                          @RequestParam(required = false) String taskName,
                                          @RequestParam(required = false) String tableName,
                                          @RequestParam(required = false) String taskType,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String beginTime,
                                          @RequestParam(required = false) String endTime) {
        QueryWrapper<SyncTaskRun> w = buildWrapper(taskId, taskName, tableName, taskType, status,
                keyword, beginTime, endTime);
        w.select(
                "COUNT(*) AS total",
                "IFNULL(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completed_count",
                "IFNULL(SUM(CASE WHEN status = 'FAILED'    THEN 1 ELSE 0 END), 0) AS failed_count",
                "IFNULL(SUM(CASE WHEN status = 'RUNNING'   THEN 1 ELSE 0 END), 0) AS running_count",
                "IFNULL(SUM(CASE WHEN status = 'PAUSE'     THEN 1 ELSE 0 END), 0) AS paused_count",
                "IFNULL(SUM(CASE WHEN status = 'STOP'      THEN 1 ELSE 0 END), 0) AS stopped_count",
                "IFNULL(SUM(success_rows), 0) AS rows_sum",
                "IFNULL(SUM(failed_rows), 0) AS failed_rows_sum",
                "IFNULL(ROUND(AVG(cost_seconds)), 0) AS avg_cost_seconds",
                "IFNULL(MAX(cost_seconds), 0) AS max_cost_seconds",
                "IFNULL(ROUND(SUM(success_rows) / NULLIF(SUM(cost_seconds), 0)), 0) AS avg_rows_per_sec",
                "IFNULL(SUM(CASE WHEN error_msg IS NOT NULL AND error_msg <> '' THEN 1 ELSE 0 END), 0) AS error_count",
                "MAX(start_time) AS last_time"
        );
        List<Map<String, Object>> list = runMapper.selectMaps(w);
        Map<String, Object> src = list.isEmpty() ? null : list.get(0);
        Map<String, Object> out = new HashMap<>(14);
        out.put("total", val(src, "total"));
        out.put("completedCount", val(src, "completed_count"));
        out.put("failedCount", val(src, "failed_count"));
        out.put("runningCount", val(src, "running_count"));
        out.put("pausedCount", val(src, "paused_count"));
        out.put("stoppedCount", val(src, "stopped_count"));
        out.put("rowsSum", val(src, "rows_sum"));
        out.put("failedRowsSum", val(src, "failed_rows_sum"));
        out.put("avgCostSeconds", val(src, "avg_cost_seconds"));
        out.put("maxCostSeconds", val(src, "max_cost_seconds"));
        out.put("avgRowsPerSec", val(src, "avg_rows_per_sec"));
        out.put("errorCount", val(src, "error_count"));
        out.put("lastTime", src == null ? null : src.get("last_time"));
        return R.ok(out);
    }

    @ApiOperation("按天聚合运行历史(趋势图)")
    @GetMapping("/trend")
    public R<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "14") int days,
                                              @RequestParam(required = false) Long taskId) {
        if (days < 1 || days > 90) days = 14;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        QueryWrapper<SyncTaskRun> w = new QueryWrapper<>();
        if (taskId != null) w.eq("task_id", taskId);
        w.select(
                "DATE_FORMAT(start_time, '%Y-%m-%d') AS d",
                "COUNT(*) AS run_count",
                "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed_count",
                "SUM(CASE WHEN status = 'FAILED'    THEN 1 ELSE 0 END) AS failed_count",
                "IFNULL(SUM(success_rows), 0) AS `rows`",
                "IFNULL(SUM(cost_seconds), 0) AS cost_seconds"
        );
        w.ge("start_time", LocalDate.now().minusDays(days - 1).atStartOfDay());
        w.groupBy("d");
        w.orderByAsc("d");

        Map<String, Map<String, Object>> grouped = new TreeMap<>();
        for (Map<String, Object> row : runMapper.selectMaps(w)) {
            grouped.put(String.valueOf(row.get("d")), row);
        }

        // 补齐空白日期, 让趋势连续
        List<Map<String, Object>> out = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            String d = LocalDate.now().minusDays(i).format(fmt);
            Map<String, Object> row = grouped.get(d);
            Map<String, Object> item = new HashMap<>(6);
            item.put("date", d);
            item.put("runCount",       row == null ? 0 : row.get("run_count"));
            item.put("completedCount", row == null ? 0 : row.get("completed_count"));
            item.put("failedCount",    row == null ? 0 : row.get("failed_count"));
            item.put("rows",           row == null ? 0 : row.get("rows"));
            item.put("costSeconds",    row == null ? 0 : row.get("cost_seconds"));
            out.add(item);
        }
        return R.ok(out);
    }

    @ApiOperation("导出运行历史(CSV)")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) Long taskId,
                       @RequestParam(required = false) String taskName,
                       @RequestParam(required = false) String tableName,
                       @RequestParam(required = false) String taskType,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String beginTime,
                       @RequestParam(required = false) String endTime,
                       HttpServletResponse response) throws Exception {
        QueryWrapper<SyncTaskRun> w = buildWrapper(taskId, taskName, tableName, taskType, status,
                keyword, beginTime, endTime);
        w.orderByAsc("id");
        List<SyncTaskRun> list = runMapper.selectList(w);

        response.setContentType("text/csv;charset=utf-8");
        response.setHeader("Content-Disposition", "attachment;filename=" +
                URLEncoder.encode("sync_task_run.csv", "UTF-8"));

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try (OutputStream os = response.getOutputStream()) {
            StringBuilder sb = new StringBuilder(
                    "运行ID,任务ID,任务名称,任务类型,同步模式,表名,源库,目标库,分片数,状态,开始时间,结束时间,耗时秒,成功行数,失败行数,总行数,批次数,平均行每秒,异常信息\n");
            for (SyncTaskRun r : list) {
                sb.append(r.getId()).append(',')
                        .append(r.getTaskId()).append(',')
                        .append(safe(r.getTaskName())).append(',')
                        .append(safe(r.getTaskType())).append(',')
                        .append(safe(r.getSyncMode())).append(',')
                        .append(safe(r.getTableName())).append(',')
                        .append(safe(r.getSourceName())).append(',')
                        .append(safe(r.getTargetName())).append(',')
                        .append(r.getShardCount() == null ? 1 : r.getShardCount()).append(',')
                        .append(safe(r.getStatus())).append(',')
                        .append(r.getStartTime() == null ? "" : fmt.format(r.getStartTime())).append(',')
                        .append(r.getEndTime() == null ? "" : fmt.format(r.getEndTime())).append(',')
                        .append(r.getCostSeconds() == null ? 0 : r.getCostSeconds()).append(',')
                        .append(r.getSuccessRows() == null ? 0 : r.getSuccessRows()).append(',')
                        .append(r.getFailedRows() == null ? 0 : r.getFailedRows()).append(',')
                        .append(r.getTotalRows() == null ? 0 : r.getTotalRows()).append(',')
                        .append(r.getBatchCount() == null ? 0 : r.getBatchCount()).append(',')
                        .append(r.getAvgRowsPerSec() == null ? 0 : r.getAvgRowsPerSec()).append(',')
                        .append(safe(r.getErrorMsg() == null ? "" : r.getErrorMsg().replaceAll("[\\r\\n]", " ")))
                        .append('\n');
            }
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    /**
     * 按筛选条件清理运行历史
     *
     * 安全约束: 至少给一个条件(任务/状态/关键字/时间/保留天数), 否则必须 force=true,
     * 避免误点清空全部历史记录。
     *
     * @param beforeDays 保留最近 N 天, 只清理更早的历史; 传了就忽略 endTime
     */
    @ApiOperation("清理运行历史(按筛选条件, 无条件需 force=true)")
    @DeleteMapping("/clear")
    public R<Integer> clear(@RequestParam(required = false) Long taskId,
                            @RequestParam(required = false) String taskName,
                            @RequestParam(required = false) String tableName,
                            @RequestParam(required = false) String taskType,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String beginTime,
                            @RequestParam(required = false) String endTime,
                            @RequestParam(required = false) Integer beforeDays,
                            @RequestParam(required = false, defaultValue = "false") boolean force) {
        boolean keepRecent = beforeDays != null && beforeDays > 0;
        QueryWrapper<SyncTaskRun> w = buildWrapper(taskId, taskName, tableName, taskType, status,
                keyword, beginTime, keepRecent ? null : endTime);
        if (keepRecent) {
            w.lt("start_time", LocalDate.now().minusDays(beforeDays - 1).atStartOfDay());
        }
        boolean hasCondition = taskId != null || notBlank(taskName) || notBlank(tableName) || notBlank(taskType)
                || notBlank(status) || notBlank(keyword) || notBlank(beginTime) || notBlank(endTime) || keepRecent;
        if (!hasCondition && !force) {
            throw new RuntimeException("未指定任何清理条件, 已取消; 如需清空全部运行历史请勾选「清空全部」");
        }
        // 运行中的记录不清理, 避免正在跑的任务历史被删掉
        w.ne("status", "RUNNING");
        int deleted = runMapper.delete(w);
        log.info("[clearRun] 条件清理运行历史 deleted={} taskId={} status={} beforeDays={} force={}",
                deleted, taskId, status, beforeDays, force);
        return R.ok(deleted, "已清理 " + deleted + " 条运行历史");
    }

    /**
     * 统一构造筛选条件 (分页 / 统计 / 导出 / 清理 共用, 保证口径一致)
     */
    private QueryWrapper<SyncTaskRun> buildWrapper(Long taskId, String taskName, String tableName,
                                                   String taskType, String status, String keyword,
                                                   String beginTime, String endTime) {
        QueryWrapper<SyncTaskRun> w = new QueryWrapper<>();
        if (taskId != null) w.eq("task_id", taskId);
        if (notBlank(taskName)) w.like("task_name", taskName.trim());
        if (notBlank(tableName)) w.like("table_name", tableName.trim());
        if (notBlank(taskType)) w.eq("task_type", taskType.trim());
        if (notBlank(status)) w.eq("status", status.trim());
        if (notBlank(keyword)) {
            String kw = keyword.trim();
            // and(...) 包一层, 避免 or 条件把前面的 eq/like 冲掉
            w.and(q -> q.like("task_name", kw)
                    .or().like("table_name", kw)
                    .or().like("source_name", kw)
                    .or().like("target_name", kw)
                    .or().like("sync_mode", kw)
                    .or().like("error_msg", kw));
        }
        Date begin = parseTime(beginTime, false);
        Date end = parseTime(endTime, true);
        if (begin != null) w.ge("start_time", begin);
        if (end != null) w.le("start_time", end);
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
}
