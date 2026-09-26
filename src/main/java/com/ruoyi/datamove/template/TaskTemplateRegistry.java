package com.ruoyi.datamove.template;

import com.ruoyi.datamove.template.domain.TaskTemplate;
import com.ruoyi.datamove.template.domain.TaskTemplateConfig;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置模板库
 *
 * <p>模板是「经验参数」而不是黑盒: 套用后任务仍然是一个普通任务,
 * 可以在任务页继续改任何参数 —— 模板只是帮用户少填十几个字段、少踩几个坑。
 *
 * <p>新增模板只需要在这里加一个方法并在构造函数里注册, 不需要改表、不需要改前端。
 */
@Component
public class TaskTemplateRegistry {

    private final Map<String, TaskTemplate> templates = new LinkedHashMap<>();

    public TaskTemplateRegistry() {
        register(fullMigration());
        register(fullPlusIncr());
        register(bizToTest());
        register(bizToDw());
        register(bigTable());
        register(sensitiveMask());
    }

    public List<TaskTemplate> list() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(templates.values()));
    }

    public TaskTemplate get(String code) {
        return templates.get(code);
    }

    private void register(TaskTemplate t) {
        templates.put(t.getCode(), t);
    }

    /* ==================== 模板定义 ==================== */

    /** MySQL → MySQL 全量迁移: 最常用, 一次搬完历史数据 */
    private TaskTemplate fullMigration() {
        TaskTemplateConfig cfg = new TaskTemplateConfig();
        cfg.setTaskType("FULL");
        cfg.setSyncMode("ID");
        cfg.setBatchSize(2000);
        cfg.setShardCount(1);
        cfg.setOverwriteFlag(0);
        cfg.setTriggerType("MANUAL");
        cfg.setNeedIdField(true);

        TaskTemplate t = new TaskTemplate();
        t.setCode("mysql-full");
        t.setName("MySQL → MySQL 全量迁移");
        t.setDesc("一次性把整张表搬到目标库, 支持断点续传");
        t.setScenario("迁移历史数据 / 首次建库 / 整表重建");
        t.setIcon("el-icon-coin");
        t.setSort(1);
        t.setTags(Arrays.asList("全量", "ID 分片", "断点续传"));
        t.setConfig(cfg);
        t.setTips(Arrays.asList(
                "主键建议用数值自增列, 字符串主键也能跑但分片效率低",
                "中断后再次启动会从断点继续, 不会重复插入(幂等写入)",
                "目标表已存在时不重建表, 只写数据; 需要重建请先用「表结构同步」模板"));
        return t;
    }

    /** 全量 + 增量: 先全量搬历史, 再用本模板接 binlog, 做到不停机迁移 */
    private TaskTemplate fullPlusIncr() {
        TaskTemplateConfig cfg = new TaskTemplateConfig();
        cfg.setTaskType("INCR");
        // 增量任务不走 ID/TIME 游标, 但 sync_mode 列非空, 用 BINLOG 占位(与任务页新建增量任务一致)
        cfg.setSyncMode("BINLOG");
        cfg.setBatchSize(1000);
        cfg.setShardCount(1);
        cfg.setOverwriteFlag(0);
        cfg.setTriggerType("MANUAL");
        cfg.setCanalHost("127.0.0.1");
        cfg.setCanalPort(11111);
        cfg.setCanalDestination("example");
        cfg.setBinlogDmlTypes("INSERT,UPDATE,DELETE");
        cfg.setNeedCanal(true);

        TaskTemplate t = new TaskTemplate();
        t.setCode("mysql-full-incr");
        t.setName("全量 + 增量(不停机迁移)");
        t.setDesc("先用全量模板搬历史数据, 再用本模板订阅 binlog 追增量");
        t.setScenario("要求业务不停机的迁移 / 双写过渡期");
        t.setIcon("el-icon-refresh");
        t.setSort(2);
        t.setTags(Arrays.asList("增量", "Canal", "binlog"));
        t.setConfig(cfg);
        t.setTips(Arrays.asList(
                "正确顺序: 先跑全量迁移 → 记下完成时间 → 再启动本增量任务",
                "Canal 的 instance.filter.regex 必须包含任务的源库.源表, 否则收不到事件",
                "位点会持久化到 sync_canal_position, 重启后从上次位点继续"));
        t.setLimitation("需要先自行启动 Canal 服务(docker/canal/docker-compose.yml), 本模板只负责配置订阅参数");
        return t;
    }

    /** 业务库 → 测试库: 允许覆盖, 小批次避免压垮生产库 */
    private TaskTemplate bizToTest() {
        TaskTemplateConfig cfg = new TaskTemplateConfig();
        cfg.setTaskType("FULL");
        cfg.setSyncMode("ID");
        cfg.setBatchSize(500);
        cfg.setShardCount(1);
        cfg.setOverwriteFlag(1);
        cfg.setTriggerType("MANUAL");
        cfg.setNeedIdField(true);

        TaskTemplate t = new TaskTemplate();
        t.setCode("biz-to-test");
        t.setName("业务库 → 测试库");
        t.setDesc("小批次慢速拉取 + 覆盖写入, 目标表有数据也直接覆盖");
        t.setScenario("给测试环境灌数据 / 定期重置测试库");
        t.setIcon("el-icon-s-platform");
        t.setSort(3);
        t.setTags(Arrays.asList("覆盖写入", "限流", "测试环境"));
        t.setConfig(cfg);
        t.setTips(Arrays.asList(
                "批次刻意调到 500: 同步是从生产库读, 批次太大会拖慢业务查询",
                "overwrite=覆盖写入, 目标表同主键数据会被替换, 不是追加",
                "只灌部分数据的话, 在任务里配 start_id 或加过滤条件"));
        return t;
    }

    /** 业务库 → 数仓: 按更新时间增量 + 每天定时 */
    private TaskTemplate bizToDw() {
        TaskTemplateConfig cfg = new TaskTemplateConfig();
        cfg.setTaskType("FULL");
        cfg.setSyncMode("TIME");
        cfg.setBatchSize(5000);
        cfg.setShardCount(1);
        cfg.setOverwriteFlag(1);
        cfg.setTriggerType("CRON");
        cfg.setCronExpr("0 0 2 * * ?");
        cfg.setNeedTimeField(true);
        cfg.setNeedCron(true);

        TaskTemplate t = new TaskTemplate();
        t.setCode("biz-to-dw");
        t.setName("业务库 → 数仓(每日增量)");
        t.setDesc("按更新时间做增量, 每天凌晨 2 点自动跑一次");
        t.setScenario("数仓/报表库的 T+1 数据同步");
        t.setIcon("el-icon-data-board");
        t.setSort(4);
        t.setTags(Arrays.asList("定时", "TIME 游标", "T+1"));
        t.setConfig(cfg);
        t.setTips(Arrays.asList(
                "时间字段一般填 update_time, 必须有索引, 否则每批都是全表扫描",
                "CRON 是 Spring 6 位格式(秒 分 时 日 月 周), 默认 0 0 2 * * ? 即每天 02:00",
                "首跑会从头同步, 之后每次只同步上次时间点之后的变更"));
        t.setLimitation("源表的删除操作不会同步(TIME 游标只能看到 UPDATE/INSERT), 需要捕获删除请用增量模板");
        return t;
    }

    /** 大表分批迁移: 提高并行度和批次 */
    private TaskTemplate bigTable() {
        TaskTemplateConfig cfg = new TaskTemplateConfig();
        cfg.setTaskType("FULL");
        cfg.setSyncMode("ID");
        cfg.setBatchSize(5000);
        cfg.setShardCount(8);
        cfg.setOverwriteFlag(0);
        cfg.setTriggerType("MANUAL");
        cfg.setNeedIdField(true);

        TaskTemplate t = new TaskTemplate();
        t.setCode("big-table");
        t.setName("大表分批迁移");
        t.setDesc("8 分片并行 + 每批 5000 行, 千万级单表也能在可接受时间内跑完");
        t.setScenario("千万行以上大表 / 需要缩短迁移窗口");
        t.setIcon("el-icon-s-grid");
        t.setSort(5);
        t.setTags(Arrays.asList("大表", "并行", "MIN/MAX 分片"));
        t.setConfig(cfg);
        t.setTips(Arrays.asList(
                "分片按主键 MIN/MAX 均分, 主键必须数值型且分布均匀, 否则分片会倾斜",
                "先确认目标库能扛住 8 路并发写入, 扛不住就把分片数调小",
                "同步期间源库会有持续读取压力, 建议放在业务低峰"));
        return t;
    }

    /** 敏感字段: 当前以「忽略字段」实现, 真正的脱敏引擎尚未实现 */
    private TaskTemplate sensitiveMask() {
        TaskTemplateConfig cfg = new TaskTemplateConfig();
        cfg.setTaskType("FULL");
        cfg.setSyncMode("ID");
        cfg.setBatchSize(2000);
        cfg.setShardCount(1);
        cfg.setOverwriteFlag(0);
        cfg.setTriggerType("MANUAL");
        cfg.setIgnoreFields("password,id_card,phone,mobile,email,bank_card,address");
        cfg.setNeedIdField(true);

        TaskTemplate t = new TaskTemplate();
        t.setCode("sensitive-mask");
        t.setName("敏感字段脱敏迁移");
        t.setDesc("迁移时排除常见敏感字段, 目标库对应列留空");
        t.setScenario("生产数据外发 / 给外包或测试环境供数");
        t.setIcon("el-icon-lock");
        t.setSort(6);
        t.setTags(Arrays.asList("敏感字段", "字段排除", "合规"));
        t.setConfig(cfg);
        t.setTips(Arrays.asList(
                "ignore_fields 里的字段会被排除, 目标表该列写入 NULL 或默认值",
                "目标表对应列必须允许为 NULL, 否则整批插入会失败",
                "套用后请按自己业务核对字段清单, 别直接照抄默认列表"));
        t.setLimitation("当前版本只支持「整字段排除」。掩码/哈希/加密等表达式脱敏尚未实现, 需要保留字段但打码的场景暂不支持");
        return t;
    }
}
