# DataMove 5 分钟快速开始

> 目标读者: 想 5 分钟内看到这个工具**长什么样、能跑通一个最简同步**的独立开发者 / 中小团队。
>
> 如果是第一次接触, 直接按下面四步走; 想了解功能取舍请看下面的「对比同类工具」和「什么时候别用」。

## 1. 准备两个 MySQL

```bash
# 用 docker 起两个 8.0 实例, 一分钟
docker run -d --name datamove-src  -p 3307:3306  -e MYSQL_ROOT_PASSWORD=root mysql:8.0
docker run -d --name datamove-dest -p 3308:3306  -e MYSQL_ROOT_PASSWORD=root mysql:8.0
```

分别在两边建库建表:
```sql
-- 源库
CREATE DATABASE src_db;
USE src_db;
CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64), price DECIMAL(10,2));
INSERT INTO orders VALUES (1,'A',10),(2,'C',30),(3,'B',20);

-- 目标库
CREATE DATABASE dest_db;
```

## 2. 初始化元数据库 & 启动后端

```bash
# 元数据库 (DataMove 自己的表放这里)
docker run -d --name datamove-meta -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.0
mysql -h127.0.0.1 -P3306 -uroot -proot -e "CREATE DATABASE datamove;"
mysql -h127.0.0.1 -P3306 -uroot -proot datamove < sql/datamove.sql
# 增量场景再跑对应的升级脚本; 全量只需要这一条

# 启动后端 (端口 8080, 默认账户 admin / admin123)
./mvnw spring-boot:run
```

看到 `Started DatamoveApplication` 即可访问 http://localhost:8080 。

## 3. 启动前端

```bash
cd ruoyi-ui
npm install          # 第一次需安装依赖
npm run dev          # 开发模式, 默认 http://localhost:80
```

浏览器打开, 用 admin / admin123 登录。

## 4. 配一个最简同步

1. **数据源** → 新增:
   - `src` → `jdbc:mysql://127.0.0.1:3307/src_db?user=root&password=root`
   - `dest` → `jdbc:mysql://127.0.0.1:3308/dest_db?user=root&password=root`
2. **任务** → 新建 → 选源/目标库、源表 `orders`、目标表 `orders`、模式 FULL+ID、点「保存并启动」
3. **任务大盘** → 实时看到批次速率、ETA、瓶颈库
4. **运行历史** → 任务跑完后看概览卡 + 趋势图 + 明细

> 第一次启动会自动给你注册一个本地试用授权 (30 天), 详见 [LICENSING.md](LICENSING.md)。
> 增量同步还需要 `docker/canal/docker-compose.yml` 起 Canal, 见 [README.md](README.md) 第 3.4 节。

---

## 与同类工具的对比

| 维度 | **DataMove** | DataX | Canal | Flink CDC | SeaTunnel | CloudCanal |
|---|---|---|---|---|---|---|
| 部署形态 | 单 Spring Boot + Vue | 单 JVM + JSON 配置 | 单独服务 (订阅 binlog) | Flink 集群 + Kafka | 分布式集群 | 商业 SaaS |
| **可视化大盘** | ✅ 实时大盘 + 历史统计 + 异常告警 | ❌ 无 | ❌ 无 | ❌ 无 | ❌ 无 | ✅ (商业版) |
| 全量同步 | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| 增量同步 (CDC) | ✅ Canal 接入 | ❌ | ✅ (需自研) | ✅ | ✅ | ✅ |
| 异构源 | MySQL → MySQL/PostgreSQL | 多源 | MySQL binlog → 自处理 | 多源 | 多源 | 多源 |
| 分片并行 | ✅ MIN/MAX 主键均分 | ❌ 需 channel 多开 | — | ✅ | ✅ | ✅ |
| 字段映射 UI | ✅ | ❌ 写 JSON | — | ❌ | ❌ | ✅ |
| 断点续传 | ✅ 跨重启 | ❌ 需自管 | ✅ 位点 | ✅ | ✅ | ✅ |
| 告警通道 | 钉钉 / 邮件 | ❌ | ❌ | ❌ | ❌ | ✅ |
| 上手成本 | **5 分钟** | 半小时 | 1 小时+ | 半天 | 半天+ | 注册即用 |
| 适用数据量 | 10w ~ 千万行 / 单任务 | 千万级 / 分布式 | — | 百万 ~ 亿级 | 亿级 | 视版本 |
| 是否开源 | ✅ Apache-2.0 (本仓库) | ✅ Apache-2.0 | ✅ Apache-2.0 | ✅ Apache-2.0 | ✅ Apache-2.0 | ❌ 商业 |

### 选 DataMove 的理由通常是
- **"我想要 5 分钟看到一个能监控的同步在跑"** —— 这是最大的护城河
- 团队没有大数据基础设施 (Flink / Kafka)
- 个人开发者 / 中小企业 / 外包项目
- 需要可视化的字段映射与历史回溯

### 别选 DataMove 的场景
- **TB / 亿级以上**: 单机引擎扛不住, 用 Flink CDC / SeaTunnel
- **MySQL 以外的源库作为源端**: 目前源库仅支持 MySQL (Canal 限制); 目标库侧支持常见 JDBC
- **需要严格一致性的分布式事务**: 这是 ETL 工具, 不替代业务层的事务方案
- **不想装 MySQL/Redis**: DataMove 把自己的元数据放在 MySQL, 把 token 缓存在 Redis —— 没这两样跑不起来
- **只想跑一次性脚本**: DataX 更快

---

## 常见问题

**Q: 启动时报 `License 校验失败: MAC地址不匹配`**
A: 你换了机器, 旧的 license 行还绑着老 MAC。进 `sync_license` 表, 把 `mac_address` 改成新机器的 MAC, 或删掉这条让系统自动重绑。

**Q: 启动时报 `License 已过期`**
A: 详见 [LICENSING.md](LICENSING.md) 第 2 节: 开源版本地试用策略。

**Q: 看不到任务大盘实时数据**
A: 任务跑起来才会写指标, 且大盘要选「运行中」的任务。引擎的指标在 JVM 内存里, 不会落库。

**Q: 增量同步启动后没事件**
A: 99% 是 Canal 的 `instance.filter.regex` 与任务实际源库/表不匹配, 见 [README.md](README.md) 第 3.4 节。

---

## 下一步

- **不熟悉系统页面, 想知道每一步点什么** → [GUIDE.md](GUIDE.md) 新手引导
- 看完整功能说明 → [README.md](README.md)
- 了解授权模式与开源边界 → [LICENSING.md](LICENSING.md)
- 自己改代码: 跑 `./mvnw test` 看现有单测骨架