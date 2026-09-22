package com.ruoyi.datamove.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.datamove.task.domain.SyncTaskRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface SyncTaskRunMapper extends BaseMapper<SyncTaskRun> {

    /**
     * 每个任务最近一次运行记录 (大盘「全部任务」展示最近运行用)
     */
    @Select("SELECT r.* FROM sync_task_run r " +
            "INNER JOIN (SELECT task_id, MAX(id) AS mid FROM sync_task_run GROUP BY task_id) m ON r.id = m.mid")
    List<SyncTaskRun> selectLatestPerTask();

    /**
     * 每个任务的运行次数 (taskId -> count)
     */
    @Select("SELECT task_id AS taskId, COUNT(*) AS cnt FROM sync_task_run GROUP BY task_id")
    List<Map<String, Object>> countGroupByTask();
}
