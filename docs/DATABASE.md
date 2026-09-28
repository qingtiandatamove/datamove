# 数据库表结构与初始化值

> 本文从 README 拆分而来, 完整索引见 [README](../README.md)

## 数据库表结构

| 表 | 说明 |
|----|------|
| `sync_datasource` | 数据源配置 (密码 AES 加密) |
| `sync_task` | 同步任务主表 (含 `binlog_dml_types` 增量 DML 过滤配置) |
| `sync_task_progress` | **断点进度核心表** |
| `sync_task_log` | 同步日志 (含批次明细) |
| `sync_task_run` | **运行历史核心表** (每次启动一条: 结果 / 耗时 / 行数 / 速率 / 异常) |
| `sync_task_verify` | **数据校验运行记录** (每次校验一条: 进度 / 缺失·不一致·多余 统计 / 修复结果) |
| `sync_task_diff` | 数据校验差异明细 (差异类型 / 主键 / 字段差异 / 修复状态), 「一键同步差异」的依据 |
| `sync_audit_log` | **审计日志核心表** (字段级变更: 谁/什么时候/改了哪个任务的哪个字段 old→new, 企业合规审计) |
| `sync_canal_position` | Canal 增量监听位点 |
| `sys_login_log` | 登录日志 (账号/时间/IP/归属地/浏览器/系统/成功失败, 三种登录方式) |
| `sys_user / sys_role / sys_user_role / sys_menu / sys_role_menu` | RuoYi 框架权限 |


## 数据库初始化值


第一次登录后请立即:
1. 修改默认密码
2. 添加需要同步的源库 / 目标库
3. 创建第一个同步任务

