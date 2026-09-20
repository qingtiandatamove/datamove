# DataMove 轻量 MySQL 数据同步工具

> 基于 **RuoYi-Vue 4.8.1 最新版** 前后端分离框架开发的 MySQL 专属数据同步工具
> 完全对应需求文档: `轻量MySQL数据同步工具｜MVP精细化产品需求文档(最终开发版)`

---
![输入图片说明](%E4%BC%81%E4%B8%9A%E5%BE%AE%E4%BF%A1%E6%88%AA%E5%9B%BE_6c625e08-f800-4e66-8bde-e04e82bbfb69.png)
## 一、产品定位

- 面向中小企业 / 外包团队 / 个人 Java 运维的可视化、零代码 MySQL 数据同步工具
- 替代 DataX / Canal 复杂命令行配置
- 支持 **断点续传 + 钉钉告警 + 幂等同步 + License 授权**
- **私有化 Jar 包 + 月订阅**售卖模式

## 二、技术栈

| 层级 | 技术 |
|------|------|
| 后端 | SpringBoot 2.7.18 + MyBatis-Plus 3.5.5 + Spring Security + JWT |
| 前端 | Vue 2.7 + Element UI 2.13 (RuoYi-Vue) |
| 持久层 | MyBatis-Plus 3.5 |
| 同步 | 原生 JDBC 分批 + Canal Client 1.1.7 增量 |
| 加密 | AES 对称加密 (BC) |
| 调度 | Spring `@Scheduled` + ThreadPool |
| 告警 | 钉钉 Webhook |
| 授权 | 自定义云端 License |
| 数据库 | MySQL 8.0 |

## 三、目录结构

```
datamove/
├── pom.xml                       # 后端依赖
├── sql/datamove.sql              # 数据库初始化脚本
├── src/main/java/com/ruoyi/datamove/
│   ├── DatamoveApplication.java  # SpringBoot 启动类
│   ├── auth/                     # 登录 & 用户认证
│   ├── datasource/               # 数据源管理 (需求 3.1)
│   ├── task/                     # 同步任务管理 (需求 3.2)
│   ├── engine/                   # 同步核心引擎
│   │   ├── full/                 #   - 全量同步 (ID+Time 双模式+断点续传+幂等)
│   │   ├── incr/                 #   - Canal 增量同步
│   │   └── log/                  #   - 异步日志
│   ├── log/                      # 日志 Controller(导出 CSV)
│   ├── license/                  # License 授权
│   └── common/                   # 全局异常处理
├── src/main/resources/
│   ├── application.yml
│   └── application-dev.yml
└── ruoyi-ui/                     # 前端(Vue 2 + Element UI)
    ├── vue.config.js
    └── src/
        ├── api/                  # 接口封装
        ├── views/sync/           # 数据源/任务/日志/授权
        └── views/system/         # 用户管理
```

## 四、核心模块说明

### 4.1 数据源管理 - 对应文档 3.1
- 增 / 删 / 改 / 查
- **测试连接**:实时校验数据库连通性 + 账号权限
- 数据库密码 AES 加密,前端密文 (前 2 位 + **** + 后 2 位) 回显
- 删除前检查是否被任务引用

### 4.2 同步任务管理 - 对应文档 3.2
- 支持两种任务类型: **全量 (FULL) / 增量 (INCR-Binlog)**
- 全量同步两种模式:
  - **按主键 ID** (`WHERE id > #{lastId} ORDER BY id ASC LIMIT #{batchSize}`)
  - **按时间字段** (`WHERE time > #{lastTime} OR (time = #{lastTime} AND id > #{lastIdInBatch}) ORDER BY time ASC, id ASC LIMIT ...`)
- **断点续传**:整批 `INSERT ... ON DUPLICATE KEY UPDATE` 成功后才更新断点
- **幂等防重**:任务重跑不产生脏数据
- 任务操作:启动 / 暂停 / 继续 / 终止 / 查看日志
- **单实例保护**:同一任务只能有一个 worker 在跑

### 4.3 同步日志 - 对应文档 3.3
- 任务总进度 / 单批次明细日志 / 异常错误日志
- 支持按任务 ID、状态、表名筛选
- 支持 CSV 导出

### 4.4 钉钉告警 - 对应文档 3.4
- 全量任务运行失败 / 中断终止
- 增量 Binlog 断开 / 监听异常
- 数据源连接超时 / 失败
- 行数不一致

### 4.5 License 授权 - 对应文档 3.5
- **MAC 地址 + LicenseKey** 一机一绑
- 仅启动时联网校验一次,运行时可断网
- 过期后再次启动项目禁止启动
- 管理员可手动修改过期时间(续费)

### 4.6 用户权限 - 对应文档 3.6
- **超级管理员 (admin)**:所有权限
- **普通操作员 (operator)**:仅查看任务、启停任务、查看日志
- 超级管理员账号内置,启动时强制首次修改密码

## 五、数据库表结构 - 对应文档 4

| 表 | 说明 |
|----|------|
| `sync_datasource` | 数据源配置 (密码 AES 加密) |
| `sync_task` | 同步任务主表 |
| `sync_task_progress` | **断点进度核心表** |
| `sync_task_log` | 同步日志 (含批次明细) |
| `sync_license` | License 授权记录 |
| `sync_canal_position` | Canal 增量监听位点 |
| `sys_user / sys_role / sys_user_role / sys_menu / sys_role_menu` | RuoYi 框架权限 |

## 六、快速启动

### 1. 准备环境
- JDK 1.8+
- Maven 3.6+
- MySQL 5.7 / 8.x
- (可选)Canal Server 用于增量同步

### 2. 初始化数据库
```bash
mysql -uroot -p < sql/datamove.sql
```

### 3. 启动后端
```bash
mvn clean spring-boot:run
# 或打包: mvn package -DskipTests
```

启动成功后访问:
- 后端 API: `http://localhost:8080/`
- Swagger 文档: `http://localhost:8080/swagger-ui/index.html`

### 4. 启动前端
```bash
cd ruoyi-ui
npm install
npm run dev
```
打开 `http://localhost:80`,使用 `admin / admin123` 登录

### 5. 默认账号

| 账号 | 密码 | 角色 |
|------|------|------|
| admin | admin123 | 超级管理员 |

## 七、数据库初始化值


第一次登录后请立即:
1. 修改默认密码
2. 进入"授权管理"确认 License 状态
3. 添加需要同步的源库 / 目标库
4. 创建第一个同步任务

## 八、增量同步前置准备 (Canal)

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

## 九、二期预留(本次未实现)

- DDL 表结构同步
- 多线程极速同步
- 数据脱敏
- 数据差异对账校验
- SaaS 多租户网页版
- PostgreSQL / Oracle 支持

---


