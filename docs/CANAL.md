# 增量同步前置准备 (Canal)

> 本文从 README 拆分而来, 完整索引见 [README](../README.md)


仅全量可跳过。启用增量前:
```sql
-- 1. 源库开启 binlog ROW 模式
SET GLOBAL binlog_format = 'ROW';


-- 3. Canal Server 部署参考官方文档
--    下载地址: https://github.com/alibaba/canal/releases
```

在 Canal 配置文件中指向源库,然后在 DataMove 创建增量任务时填写:
- Canal Host
- Canal Port (默认 11111)
- Canal Destination (canal 配置文件中 instance 名)

