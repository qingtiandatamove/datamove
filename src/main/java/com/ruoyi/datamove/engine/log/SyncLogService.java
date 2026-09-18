package com.ruoyi.datamove.engine.log;

import com.ruoyi.datamove.engine.SyncContext;
import com.ruoyi.datamove.task.domain.SyncTaskLog;
import com.ruoyi.datamove.task.mapper.SyncTaskLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 同步日志服务 - 异步落库,不阻塞主同步流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncLogService {

    @Autowired
    private SyncTaskLogMapper logMapper;

    /** 异常信息 / 内容摘要最大保存长度 */
    private static final int MAX_TEXT_LEN = 4000;

    /**
     * 写一条批次日志(不带数据内容)
     */
    @Async("syncExecutor")
    public void writeLog(SyncContext ctx, int batchNo, String startMarker, String endMarker,
                         int batchRows, long totalRows, long costMs,
                         String status, String errorMsg) {
        writeLog(ctx, batchNo, startMarker, endMarker, batchRows, totalRows, costMs, status, errorMsg, null);
    }

    /**
     * 写一条批次日志, 携带本批次同步的数据内容摘要
     */
    @Async("syncExecutor")
    public void writeLog(SyncContext ctx, int batchNo, String startMarker, String endMarker,
                         int batchRows, long totalRows, long costMs,
                         String status, String errorMsg, String content) {
        try {
            SyncTaskLog l = new SyncTaskLog();
            l.setTaskId(ctx.getTask().getId());
            l.setTaskName(ctx.getTask().getTaskName());
            l.setTableName(ctx.getTask().getTableName());
            l.setSyncMode(ctx.getTask().getSyncMode());
            l.setBatchNo(batchNo);
            l.setBatchStartId(startMarker);
            l.setBatchEndId(endMarker);
            l.setBatchRows(batchRows);
            l.setTotalRows(totalRows);
            l.setCostMs(costMs);
            l.setStatus(status);
            l.setErrorMsg(truncate(errorMsg, 2000));
            l.setContent(truncate(content, MAX_TEXT_LEN));
            l.setCreateTime(new Date());
            logMapper.insert(l);
        } catch (Exception e) {
            log.error("write sync log error", e);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...(已截断)";
    }
}
