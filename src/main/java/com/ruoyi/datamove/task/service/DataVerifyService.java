package com.ruoyi.datamove.task.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.datamove.engine.verify.DataVerifyEngine;
import com.ruoyi.datamove.task.domain.SyncTaskDiff;
import com.ruoyi.datamove.task.domain.SyncTaskVerify;
import com.ruoyi.datamove.task.mapper.SyncTaskDiffMapper;
import com.ruoyi.datamove.task.mapper.SyncTaskVerifyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据校验的对外服务: 启动/中止校验, 差异查询, 一键修复
 *
 * <p>真正的比对与修复逻辑在 {@link DataVerifyEngine}, 这里只做参数校验与查询组装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataVerifyService {

    private final DataVerifyEngine      verifyEngine;
    private final SyncTaskVerifyMapper  verifyMapper;
    private final SyncTaskDiffMapper    diffMapper;

    /** 启动数据校验, 返回校验ID */
    public Long start(Long taskId) {
        return verifyEngine.start(taskId);
    }

    /** 中止校验 */
    public void stop(Long verifyId) {
        verifyEngine.stop(verifyId);
    }

    /** 一键同步差异(补缺失 + 修不一致) */
    public void repair(Long verifyId) {
        verifyEngine.repair(verifyId);
    }

    /** 中止修复 */
    public void stopRepair(Long verifyId) {
        verifyEngine.stopRepair(verifyId);
    }

    /** 任务最近一次校验记录, 没有则返回 null */
    public SyncTaskVerify latest(Long taskId) {
        return verifyEngine.latest(taskId);
    }

    public SyncTaskVerify detail(Long verifyId) {
        SyncTaskVerify v = verifyMapper.selectById(verifyId);
        if (v == null) throw new RuntimeException("校验记录不存在: " + verifyId);
        return v;
    }

    /**
     * 差异明细分页。
     * 排序把 MISSING 放最前 —— 用户点开最先想看到的是"目标库缺了哪些行"。
     */
    public PageResult<SyncTaskDiff> diffPage(Long verifyId, String diffType, String repairStatus,
                                             int pageNum, int pageSize) {
        QueryWrapper<SyncTaskDiff> w = new QueryWrapper<SyncTaskDiff>().eq("verify_id", verifyId);
        if (diffType != null && !diffType.trim().isEmpty()) w.eq("diff_type", diffType.trim());
        if (repairStatus != null && !repairStatus.trim().isEmpty()) w.eq("repair_status", repairStatus.trim());
        w.last("ORDER BY FIELD(diff_type,'MISSING','MISMATCH','EXTRA') ASC, id ASC");

        Page<SyncTaskDiff> p = diffMapper.selectPage(new Page<>(pageNum, pageSize), w);
        return PageResult.of(p.getRecords(), p.getTotal());
    }

    /** 差异按类型与修复状态聚合, 用于前端标签计数 */
    public Map<String, Object> diffSummary(Long verifyId) {
        Map<String, Object> m = new LinkedHashMap<>();
        long missing = count(verifyId, SyncTaskDiff.TYPE_MISSING, null);
        long mismatch = count(verifyId, SyncTaskDiff.TYPE_MISMATCH, null);
        long extra = count(verifyId, SyncTaskDiff.TYPE_EXTRA, null);
        m.put(SyncTaskDiff.TYPE_MISSING, missing);
        m.put(SyncTaskDiff.TYPE_MISMATCH, mismatch);
        m.put(SyncTaskDiff.TYPE_EXTRA, extra);
        m.put(SyncTaskDiff.REPAIR_PENDING, count(verifyId, null, SyncTaskDiff.REPAIR_PENDING));
        m.put(SyncTaskDiff.REPAIR_DONE, count(verifyId, null, SyncTaskDiff.REPAIR_DONE));
        m.put(SyncTaskDiff.REPAIR_FAILED, count(verifyId, null, SyncTaskDiff.REPAIR_FAILED));
        // 明细被截断时, 上面这些数字只是"已落库的样本", 真实差异量看 sync_task_verify 的统计字段
        return m;
    }

    private long count(Long verifyId, String diffType, String repairStatus) {
        QueryWrapper<SyncTaskDiff> w = new QueryWrapper<SyncTaskDiff>().eq("verify_id", verifyId);
        if (diffType != null) w.eq("diff_type", diffType);
        if (repairStatus != null) w.eq("repair_status", repairStatus);
        Long c = diffMapper.selectCount(w);
        return c == null ? 0L : c;
    }

    /** 差异明细导出用(不分页) */
    public List<SyncTaskDiff> diffList(Long verifyId, String diffType) {
        QueryWrapper<SyncTaskDiff> w = new QueryWrapper<SyncTaskDiff>().eq("verify_id", verifyId);
        if (diffType != null && !diffType.trim().isEmpty()) w.eq("diff_type", diffType.trim());
        w.last("ORDER BY FIELD(diff_type,'MISSING','MISMATCH','EXTRA') ASC, id ASC");
        return diffMapper.selectList(w);
    }

    /** 供前端「可修复类型」下拉使用 */
    public List<String> repairTypes() {
        return Arrays.asList(SyncTaskDiff.TYPE_MISSING, SyncTaskDiff.TYPE_MISMATCH);
    }
}
