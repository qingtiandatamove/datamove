# DataMove 授权与开源边界

> 这份文档目的不是堆条款, 是把 **"哪些代码开源、哪些是商业付费、开源版能用到什么程度"** 讲清楚。
>
> 如果只想知道 **"我能不能在生产里免费用"** → 直接看第 2 节。

## 1. 协议

仓库根目录的 [`LICENSE`](LICENSE) 是 **Apache License 2.0**。

这意味着:
- ✅ 个人、公司**免费使用、修改、分发、商用**
- ✅ 可以闭源二次开发后再发布
- ⚠️ 必须保留版权声明 / 变更说明 / NOTICE 文件
- ⚠️ 不提供任何担保 (AS IS)
- ⚠️ 商标 (DataMove 名称 / logo) 不在协议授权范围内, 见第 4 节

基于 Apache-2.0 而非 MIT 选择理由: 显式授予专利使用权, 对贡献者和下游使用者都更友好。

## 2. 开源版与商业版的边界

| 功能 | 开源版 (本仓库) | 商业版 (另行联系) |
|---|---|---|
| 全部同步能力 (全量 / 增量 / DDL / 分片并行 / 字段映射) | ✅ | ✅ |
| 任务大盘 + 运行历史 + 趋势图 | ✅ | ✅ |
| 钉钉 / 邮件告警 | ✅ | ✅ |
| Canal 接入 | ✅ | ✅ |
| MySQL / Redis 元数据 | ✅ | ✅ |
| 数据加密 / 脱敏 | ❌ | ✅ |
| 多租户 / 角色细粒度权限 | ❌ | ✅ |
| 跨实例统一监控 (集群版) | ❌ | ✅ |
| 商业技术支持 (SLA) | ❌ | ✅ |
| 商标 / 品牌对外使用 | ❌ | 需授权 |

> **核心同步能力全开源, 商业版卖的是「企业级特性 + 服务」, 不是把核心功能藏起来。**
> 这是 GitLab CE/EE、Elastic OSS / X-Pack 等成熟双轨模式的做法。

## 3. 当前 LicenseService 行为说明

代码里存在 `com.ruoyi.datamove.license.LicenseService`, **默认情况下它不存在**。

设计上 `LicenseService` 与 `LicenseController` 都加了
`@ConditionalOnProperty(name = "sync.license.enabled", havingValue = "true")`。
当 `sync.license.enabled=false` (本仓库默认) 时:

- Spring **根本不实例化** LicenseService —— 没有 `@EventListener` 注册, 启动期完全无代码路径
- `/sync/license` 路由不被注册, 前端授权管理页面会进入「模块未启用」占位
- **没有** MAC 校验, **没有** `System.exit(1)`, **没有** 联网

这意味着开源 fork 直接 `./mvnw spring-boot:run` 就跑得起来, 零授权摩擦。

### 3.1 启用商业授权 (opt-in)

需要启用时改 `application.yml`:

```yaml
sync:
  license-enabled: true                # ← 改这里
  license-server: https://your-license-server/check
  license-key: ${SYNC_LICENSE_KEY:REAL_KEY}
```

启用后启动期自动做的事:

1. 查本地 `sync_license` 表里 `license_key = ${sync.license-key}` 的记录
2. 没有则**自动注册一条 30 天试用** (`max_parallel = 5`), 并绑定当前 MAC
3. MAC 不匹配 → `System.exit(1)`
4. `expire_time` 已过 → `System.exit(1)`
5. 联网向 `${sync.license-server}` 做一次心跳 (失败不阻塞启动)

### 3.2 长期内网生产的三种合法方式

1. **保持 enabled=false** (默认): 完全不引入授权检查, 适合 OSS / 内网部署
2. **enabled=true + 手动续期** (商业授权 `sync_license` 行):
   ```sql
   UPDATE sync_license
   SET expire_time = DATE_ADD(NOW(), INTERVAL 30 DAY)
   WHERE license_key = 'YOUR_KEY';
   ```
3. **彻底删 LicenseService.java**: 整文件删除即可, 无其他依赖
   (Mapper 与 `sync_license` 表是单独关注点, 删除时一并清理)

## 4. 商标

"DataMove" 名称、相关 logo 与域名归项目作者所有。
Apache-2.0 不授予商标使用权。
如果你 fork 后改了核心并对外发布, 请用一个新名字 (例如 "MyDataSync"), 这是对双方都清楚的做法。

## 5. 贡献者协议

向本仓库提交 PR 即视为:
- 同意以 Apache-2.0 授权你的贡献
- 同意在仓库根目录的 `NOTICE` / `AUTHORS` 中被列出
- 你保留对你原创部分的所有权

不需要签 CLA —— 太小不值得。

## 6. 第三方依赖

本项目基于 RuoYi-Vue 4.8.1 (MIT) 与大量 Apache-2.0 / MIT 依赖 (Hutool、MyBatis-Plus 等)。
完整清单见 [`pom.xml`](pom.xml)。
基于 RuoYi-Vue 的事实须在 README 顶部保留声明与上游链接。

---

有问题或商业合作 → 在 GitHub Issues 里开 discussion 标签的工单, 或直接联系维护者。