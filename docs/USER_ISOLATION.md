# 多用户注册与数据隔离

> 本文从 README 拆分而来, 完整索引见 [README](../README.md)

## 1. 它能做什么

- **自助注册**：登录页点「立即注册」（或直接访问 `#/register`），填账号 / 密码即可开号，**注册成功直接登录**，不用再输一次密码。
- **数据互相看不见**：A 用户看不到 B 用户的数据源、同步任务、同步日志、运行历史、校验记录、告警、审计；只能操作自己创建的数据。
- **超管例外**：`admin`（以及任何带 admin 角色的账号）看全量，方便排查问题。
- **可关闭**：`sync.register.enabled=false` 时 `/auth/register` 直接拒绝，退回单人使用模式。

## 2. 注册流程（后端）

`POST /auth/register`（已在 SecurityConfig 放行，免 JWT）

| 字段 | 必填 | 规则 |
|---|---|---|
| username | 是 | 4-20 位，字母开头，只能含字母 / 数字 / 下划线 |
| password | 是 | 5-20 位，BCrypt 加密存储 |
| nickName | 否 | 不填则与账号相同 |
| email | 否 | 填了要校验格式 + 查重（否则验证码登录会串号） |
| phonenumber | 否 | 同上，11 位手机号 |

服务端处理：

1. 校验开关 / 格式 / 账号重复
2. 落库 `sys_user`（`status=0`、`user_type=00`、`del_flag=0`）
3. **分配默认角色** `common`（普通用户），决定他能看见哪些菜单 —— 没有角色 = 没有菜单 = 登录后一片空白
4. 直接返回 `token`（与登录返回结构一致）

相关代码：`AuthServiceImpl#register`、`AuthController#register`。

## 3. 数据隔离怎么实现

一句话：**写入时记归属，查询时自动过滤**。

### 3.1 写入：`owner_id` 自动填充

13 张业务表统一加了 `owner_id`（归属用户ID）。落库时由 `MetaHandlerConfig`（MyBatis-Plus 的 `MetaObjectHandler`）从登录上下文取当前用户自动填充，业务代码不用管。

顺带修掉一个老问题：`create_by` / `update_by` 以前**写死成 `"admin"`**，现在改成真实用户名 —— 审计日志里终于能看出是谁干的。

### 3.2 查询：MyBatis-Plus 多租户拦截器

`DataOwnerHandler` + `TenantLineInnerInterceptor` 给白名单表的 **SELECT / UPDATE / DELETE** 自动追加 `owner_id = 当前用户`。

为什么不逐个改查询条件？查询散落在 Controller + Service + 引擎几十处，手写一定会漏，漏一处就是越权。交给拦截器统一改写，规则只在一处，加表只要在 `ISOLATED_TABLES` 里加一行。

注意拦截器**必须排在分页插件之前**：先改写成带归属条件的 SQL，再由分页插件包 `LIMIT`；反过来的话 count 出来的是未过滤总数，页码会错乱。

### 3.3 后台线程：归属要显式传递

同步引擎 / Canal 监听 / 数据校验 / 异步日志 / 定时调度 都跑在自己 new 的线程里，`SecurityContext` 是空的。不处理的话日志会落成「无人认领」，用户看不到自己任务的运行记录。

处理方式：`OwnerContext.wrap(ownerId, runnable)` 把归属带进线程，退出时清理（线程池会复用线程，不清理会把归属"借"给下一个任务）。

已接入的位置：

| 位置 | 说明 |
|---|---|
| `FullSyncEngine` 主循环 + 分片线程 | 按 `task.getOwnerId()` |
| `CanalSyncEngine` worker | 增量同步长期运行 |
| `DataVerifyEngine` 校验 / 一键修复 | 校验记录与差异明细 |
| `SyncLogService` 异步日志 | `@Async` 线程池，显式 `setOwnerId` |
| `TaskTriggerScheduler` CRON 触发 | 定时到点没有登录态 |
| `SyncTaskServiceImpl#triggerByEvent` | 事件回调免 JWT |
| `AlertCenterService` 告警记录 | 告警常由后台线程发出 |

兜底：取不到归属时写 `0`（`OwnerContext.UNKNOWN_OWNER`）—— 只有超管看得到，**宁可看不见，也不能串到别人名下**。

## 4. 隔离范围

| 表 | 是否隔离 | 说明 |
|---|---|---|
| sync_datasource / sync_task | ✅ | 核心资产，按创建人隔离 |
| sync_task_log / sync_task_run / sync_task_progress / sync_task_field_mapping | ✅ | 随任务归属 |
| sync_task_verify / sync_task_diff | ✅ | 校验与差异明细 |
| sync_sql_log | ✅ | SQL 执行日志 |
| sync_alert_record / sync_audit_log / sync_canal_position | ✅ | 告警、审计、增量位点 |
| **sync_sql_favorite** | ❌ | **刻意不隔离**：收藏夹自带「本人私有 OR 全员共享」语义，再加 `owner_id` 会把别人主动共享的收藏过滤掉 |
| sync_license | ❌ | 平台级授权，不属于个人数据 |
| sys_* (用户/角色/菜单) | ❌ | 框架表，由 RBAC 管 |

## 5. 存量数据怎么办

升级脚本 `sql/upgrade_20260928_user_register.sql` 做三件事：

1. 13 张业务表加 `owner_id` + 索引
2. **历史数据全部回填给 `admin`（user_id=1）**：老数据不丢，也不外泄给新注册用户
3. 新增「普通用户」角色（`role_key=common`，role_id=3）并授予业务模块菜单权限（不含系统管理 / 授权管理 / 审计日志）

老库按日期顺序执行 `sql/upgrade_*.sql` 即可；全新环境直接跑 `sql/datamove.sql`，它已包含 `owner_id` 字段。

**不执行脚本直接启动会报 `Unknown column 'owner_id'`** —— 字段是硬依赖。

## 6. 边界与注意事项

- **超管看全量**：`admin` 角色（或 user_id=1）不加隔离条件。想让某个账号看全部数据，给它 admin 角色即可。
- **默认角色可配**：`sync.register.role-key`（默认 `common`）。改之前确认 `sys_role` 里存在对应 `role_key`，否则新用户看不到菜单（后端会打 warn 日志）。
- **数据源是隔离的**：用户只能引用自己的数据源建任务，看不到别人的库连接。
- **事件触发 / CRON 触发**的归属取自任务本身，不依赖调用方身份，所以外部系统触发不会让数据串到调用方名下。
- **手写 `@Select` 的复杂 SQL**（如 `SyncTaskRunMapper#selectLatestPerTask` 带 JOIN 子查询）由拦截器按表名改写。升级后建议重点回归「任务大盘 - 运行历史」，如发现漏过滤可用 `@InterceptorIgnore(tenantLine = "true")` 单独豁免并手写条件。
