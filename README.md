# 剧本杀场次预约系统 urban-script-reservation

> **一句话定位**：一个面向高并发场景的**在线抢位预约系统**。核心难点不是用了多少框架，而是**单行热点库存 2000 并发零超卖**，以及订单超时/场次关闭时**自动取消且不重复回滚**。整个项目的取舍都围绕这两个问题展开；在此之上再扩展两条链路：**AI 陪练**（FastAPI + LangGraph 意图路由 + 工具调用）与 **Neo4j 知识图谱推荐**。

***

## 🎯 项目亮点（面试重点）

### 亮点 1 · 高并发抢位零超卖：Redis+Lua vs MySQL 乐观锁

抢位是典型的秒杀场景，同一场次 6 个名额被数千人同时抢。

- **初版踩坑**：直接用 MySQL 乐观锁 `UPDATE ... SET booked=booked+1 WHERE ...`，
  单行热点被 InnoDB 行锁强行串行化，2000 并发下产生约 **24000 次 SQL**，DB CPU 飙高。

- **最终方案**：**Redis+Lua 原子扣减**扛第一层，MySQL 只记最终结果。

  - Lua 脚本内先判断 `booked + playerCnt <= capacity`，再 `HINCRBY`，判断与扣减原子执行，天然杜绝先读后写竞态；

  - Redisson 分布式锁 `reserve:lock:{sessionId}:{userId}` 防同一用户重复下单；

  - Redis 不可用时（仅捕获连接/系统异常）自动降级为 MySQL 乐观锁，并异步 `forceRefreshBooked` 校准回 Redis，保证最终一致。

**实测对比（2000 并发 / 10 名额）**：

| 指标        | MySQL 乐观锁        | Redis+Lua                  |
| --------- | ---------------- | -------------------------- |
| 冲突率       | **86.5%**        | **0.000%**                 |
| QPS       | **≈ 20**（97s 跑完） | **≈ 192**（峰值 669，2.99s 清空） |
| 库存 booked | 10/10（靠字段约束兜底）   | 10/10，与成功数严格一致             |
| 订单层       | 出现 22>10 越量      | 严格 = capacity，零超卖          |

### 亮点 2 · 订单最终一致性：RMQ TTL+DLX 延迟关单

抢到名额 15 分钟不支付要自动作废并**放回名额**；主办方关场要**批量取消所有待支付单**。
两处都怕**重复回滚**和**误取消已支付单**。

- 下单即发延迟消息到 `order.delay.queue`，通过 `x-dead-letter-exchange` + TTL 到期转入 `order.dlx.queue`；

- 消费者**幂等**：先查 `status=0`（待支付）才取消，否则直接跳过；

- 取消时**双写回滚**：Redis 库存（Lua/forceRefresh）+ MySQL `booked` 字段；

- 场次关闭：开场前 2 小时外允许关闭，关闭后删 Redis 缓存、状态置 0，再由 `SessionCloseConsumer` 批量取消待支付单。

### 亮点 3 · 微服务三环鉴权封闭

6 个 Java 服务拆开后，入口、服务内、服务间都要防越权。

1. **入口网关**：Spring Cloud Gateway `JwtAuthFilter` —— 白名单（注册 / 登录 / 内部接口）直接放行；带 `Authorization: Bearer` 的请求验签验过期后，解析出 `userId / role` 注入 `X-User-Id / X-User-Role` 转发下游；无 token 的请求则透传给下游，由 `@RequireRole` 决定放行与否（公开查询接口无需逐个列入白名单）；
2. **服务内**：`@RequireRole` + `@Around` 切面统一角色鉴权（类/方法级），从网关注入的 Header 取角色；
3. **服务间**：`InternalApiKeyFilter` 拦截 `/**/internal/**` 校验 `X-Internal-Api-Key`，Feign 由 `InternalApiKeyInterceptor` 自动带该头；密钥统一放 **Nacos** **`urban-shared-config`** 一处管理。

### 亮点 4 · AI 剧本推荐 + 降级兜底

- agent-gateway 承接前端调用并转发到 python-agent（FastAPI + LangGraph + LLM）；Python 侧不直连业务库、也不绕过鉴权，一律走 agent-gateway 的 `/internal/**` 接口并携带内部 API Key；

- **PythonAgentClient + FallbackFactory**：AI 服务不可用时返回兜底文案，不让 AI 故障拖垮主链路
  （Sentinel 目前在 Windows 环境下禁用 —— 其 `DateFileLogHandler` 存在 NPE，详见 `agent-gateway/application.yml` 注释；Feign 异常仍由 FallbackFactory 兜底，故熔断语义不变）。

***

## 🏗️ 系统架构

```
                        ┌──────────────────────────────────────────┐
                        │            浏览器 / Postman              │
                        └────────────────────┬─────────────────────┘
                                             │ :8081
                        ┌────────────────────▼─────────────────────┐
                        │  reservation-gateway                     │
                        │  JwtAuthFilter 鉴权 · CORS · 路由分发    │
                        └────────────────────┬─────────────────────┘
                                             │ 路由到各业务服务
  ┌──────────┐┌──────────┐┌──────────┐┌──────────┐┌──────────┐
  │  user    ││  shop    ││  order   ││  agent   ││recommend │
  │  :8082   ││  :8083   ││  :8084   ││  :8085   ││  :8086   │
  └──────────┘└──────────┘└────┬─────┘└────┬─────┘└──────────┘
  注册/登录    门店/剧本    下单/库存    AI 网关    Neo4j 推荐
                               └─────┬─────┘
                                     │ Feign（带 Internal API Key）
                        ┌────────────▼─────────────────────────────┐
                        │           python-agent  :8000            │
                        │        FastAPI + LangGraph + LLM         │
                        └──────────────────────────────────────────┘

  公共中间件：Nacos 2.4 · MySQL 8.0 · Redis 7 · RabbitMQ 3.13 · Neo4j · Redisson
```

> 架构图源文件：[interview\_shots/architecture\_renderer.html](interview_shots/architecture_renderer.html)

***

## 📁 模块说明（Maven 多模块 + Python Agent）

| 模块 / 目录               | 端口   | 职责        | 关键能力                                                                        |
| --------------------- | ---- | --------- | --------------------------------------------------------------------------- |
| `reservation-common`  | —    | 公共依赖      | 统一响应 `R`、全局异常、JWT 工具、`@RequireRole` 注解、InternalApiKeyInterceptor、雪花 ID      |
| `reservation-gateway` | 8081 | 主网关       | JWT 鉴权过滤器、CORS 配置、路由分发到各业务服务                                                |
| `user-service`        | 8082 | 用户服务      | 注册/登录/个人资料、BCrypt 密码、JWT 签发、角色切面                                            |
| `shop-service`        | 8083 | 门店/剧本/场次  | 门店 CRUD、剧本 CRUD、场次创建（DM 时段冲突检测）、场次关闭（开场前 2h 限制）                             |
| `order-service`       | 8084 | 订单/库存     | Redis+Lua 扣减、Redisson 分布式锁、TTL+DLX 延迟关单、关场批量取消、双写回滚降级                       |
| `agent-gateway`       | 8085 | Agent 网关  | Feign 调 python-agent、FallbackFactory 降级兜底、Feign 调内部订单/门店接口                     |
| `recommend-service`   | 8086 | 推荐服务      | Neo4j 剧本知识图谱（剧本/标签/作者/用户/玩过关系）、订单与剧本同步入图、相似与热门推荐（Redis 缓存）             |
| `python-agent/`       | 8000 | LLM Agent | FastAPI + LangGraph（意图识别 → 条件路由 → 工具调用/回复生成，三节点）、Redis 会话记忆、httpx 调 Java 内部接口 |
| `init-scripts/`       | —    | 初始化 SQL   | 建库建表脚本（`01-init.sql`）                                                       |
| `interview_shots/`    | —    | 面试素材      | Nacos 服务列表、MQ 控制台、核心代码、架构图等截图                                               |

***

## 🛠️ 技术栈

**后端（Java 17）**

- Spring Boot 3.2.5 + Spring Cloud 2023.0.1 + Spring Cloud Alibaba 2023.0.1.0

- Spring Cloud Gateway + OpenFeign + Nacos（注册/配置中心）

- MyBatis-Plus 3.5.5 + MySQL 8.0

- Redis 7 + Redisson + Lua（原子扣减脚本见 `order-service/src/main/resources/lua/`）

- RabbitMQ 3.13（TTL + 死信队列实现延迟消息）

- Neo4j 5.24（剧本知识图谱，供 recommend-service 做推荐）

- Knife4j（Swagger）文档、JJWT 0.12.x

- Sentinel 1.8.6（**当前在 Windows 环境下禁用**：其 `DateFileLogHandler` 存在 NPE，见 `agent-gateway/application.yml`；Feign 降级由 FallbackFactory 承担，不依赖 Sentinel）

**AI Agent（Python 3.12）**

- FastAPI 0.115 + LangGraph 0.2（进程级单例编译图）+ Pydantic v2

- httpx（同步调用 OpenAI 兼容接口）+ DeepSeek-Chat（默认端点 `https://api.deepseek.com/v1`，可换成任意兼容服务）

- redis-py（会话记忆 List 结构，LRANGE / RPUSH / LTRIM）

- loguru + python-dotenv

***

## 🚀 快速启动（本地复现）

> **前置条件**：已安装 JDK 17、Maven 3.9+、Python 3.12+、Docker Desktop

### 第一步 · 启动中间件（Docker Compose）

```bash
# 启动 MySQL / Redis / Nacos / RabbitMQ
docker-compose up -d

# 等待健康检查全部通过（约 60s，Nacos 启动较慢）
docker ps
```

| 中间件           | 端口           | 账号    | 密码          | 控制台                           |
| ------------- | ------------ | ----- | ----------- | ----------------------------- |
| MySQL 8.0     | 3306         | root  | 123456      | —                             |
| Redis 7       | 6379         | —     | 无密码         | —                             |
| Nacos 2.4.0   | 8848         | nacos | nacos       | <http://localhost:8848/nacos> |
| RabbitMQ 3.13 | 5672 / 15672 | guest | guest       | <http://localhost:15672>      |
| Neo4j 5.24    | 7687 / 7474  | neo4j | urban-neo4j | <http://localhost:7474>       |

> ⚠️ **安全说明**：以上账号密码为**本地演示环境**配置，仅用于快速复现，生产环境请务必修改强密码并通过环境变量/Nacos 配置中心管理。

### 第二步 · 初始化数据库

```bash
# 用客户端执行 init-scripts/01-init.sql（建库 urban_script_reservation + 5 张表）
mysql -h127.0.0.1 -uroot -p123456 < init-scripts/01-init.sql
```

### 第三步 · Nacos 配置（共享配置 + 敏感密钥）

> 各服务通过 `spring.config.import` 以 `optional:nacos:` 方式引入下述 DataId（配置见各服务 `application.yml`）。
> `optional:` 前缀意味着 **Nacos 未启动时不会阻塞启动**，会自动降级使用各服务 yml 中的本地默认值；
> 但**生产环境务必在 Nacos 中配置真实密钥**，并可用环境变量（如 `JWT_SECRET`）覆盖。

1. 打开 <http://localhost:8848/nacos> （nacos/nacos）
2. `配置管理 → 配置列表 → 新建配置`，**dataId=`urban-shared-config`**，group=`DEFAULT_GROUP`，格式 YAML：

   ```yaml
   # JWT 签名密钥 —— 由 JwtAutoConfiguration 启动时注入 JwtUtil，
   # 所有服务与网关必须使用同一个值，否则会出现"登录成功但接口全 401"
   jwt:
     secret: your-jwt-secret-at-least-32-characters-long-please-change
     expire-millis: 7200000

   # 服务间内部 API Key（InternalApiKeyFilter / InternalApiKeyInterceptor 校验 X-Internal-Api-Key）
   urban:
     internal-api-key: test-api-key-please-change-in-prod

   # 支付回调 HMAC 验签密钥 —— 回调接口免鉴权、靠验签自证身份，务必替换
   pay:
     sign-secret: your-pay-sign-secret-please-change-in-prod
   ```
3. 另可建 `urban-shared.yaml` 存放 MySQL/Redis 等公共基础设施配置（可选）。
4. 发布后无需重启：网关已开启 `refresh-enabled: true`，修改 `jwt.secret` 会自动重新注入 JwtUtil。

### 第四步 · Maven 构建 + 启动 6 个 Java 服务

```bash
# 根目录构建（父 POM 聚合 7 个 Java 模块，其中 reservation-common 为公共依赖）
mvn clean install -DskipTests

# 方式 A：用项目提供的启动脚本（Windows PowerShell）
powershell -ExecutionPolicy Bypass -File .\_start_services.ps1

# 方式 B：按顺序手动启动（推荐第一次用，方便看日志）
# 1) order-service        端口 8084
java -jar order-service/target/order-service-1.0.0.jar
# 2) shop-service         端口 8083
java -jar shop-service/target/shop-service-1.0.0.jar
# 3) user-service         端口 8082
java -jar user-service/target/user-service-1.0.0.jar
# 4) reservation-gateway  端口 8081
java -jar reservation-gateway/target/reservation-gateway-1.0.0.jar
# 5) agent-gateway        端口 8085
java -jar agent-gateway/target/agent-gateway-1.0.0.jar
# 6) recommend-service    端口 8086（依赖 Neo4j）
java -jar recommend-service/target/recommend-service-1.0.0.jar
```

### 第五步 · 启动 Python Agent（端口 8000）

```bash
cd python-agent

# 建议虚拟环境
python -m venv .venv
.venv\Scripts\activate          # Windows
# source .venv/bin/activate     # macOS/Linux

pip install -r requirements.txt

python main.py
# 健康检查：curl http://localhost:8000/api/health
```

**环境变量**：`python-agent/config.py` 加载的是**项目根目录**的 `.env`（不是 `python-agent/.env`）。
在项目根目录执行 `cp .env.example .env` 后填写：

| 变量 | 说明 |
| --- | --- |
| `INTERNAL_API_KEY` | **必填**。须与 Java 侧 `urban.internal-api-key`（Nacos `urban-shared-config`）完全一致，否则调用 agent-gateway 的 `/internal/**` 会返回 403，表现为 AI「检索失败」 |
| `LLM_API_KEY` | **必填**。LLM 服务密钥 |
| `JAVA_BASE_URL` | agent-gateway 地址，默认 `http://localhost:8085` |
| `LLM_BASE_URL` / `LLM_MODEL` | 默认 `https://api.deepseek.com/v1` + `deepseek-chat`，可换成任意 OpenAI 兼容端点 |
| `REDIS_URL` | 会话记忆存储，默认 `redis://localhost:6379/1` |

> Python 侧**不需要** `JWT_SECRET` —— 它既不签发也不校验 JWT，只携带内部 API Key 调 Java 接口。

### 第六步 · 冒烟测试（全链路验证）

```bash
# 脚本：smoke_mainchain.js（k6 或 Node 执行，具体见 scripts/README.md）
node smoke_mainchain.js
# 流程：注册(玩家+店主) → 登录 → 建门店 → 建剧本 → 建场次 → 玩家下单 → 订单超时自动关单
```

***

## 📊 压测对比（Redis+Lua vs MySQL 乐观锁）

压测脚本：`benchmark_stock.js`（见 scripts/README.md）

核心结论：

- 单行热点下，把竞争从 MySQL 行锁挪到 Redis Lua，**冲突率 86.5% → 0**，**QPS 20 → 192**，且严格零超卖。

- Redis 降级链路已验证：Redis 宕机时仅捕获连接/系统级异常回退 MySQL，业务异常不吞。

完整叙述见 [docs/PROJECT\_NARRATIVE.md](docs/PROJECT_NARRATIVE.md)。

***

## 📚 更多文档

| 文档         | 路径                                                      | 用途                               |
| ---------- | ------------------------------------------------------- | -------------------------------- |
| 项目叙事（面试讲稿） | [docs/PROJECT\_NARRATIVE.md](docs/PROJECT_NARRATIVE.md) | 问题→方案→结果结构，面试自我介绍用               |
| 简历亮点摘要     | [docs/RESUME\_HIGHLIGHTS.md](docs/RESUME_HIGHLIGHTS.md) | 每条亮点都有量化结果                       |
| 脚本说明       | [scripts/README.md](scripts/README.md)                  | 根目录 benchmark / smoke 脚本的用途和运行方式 |
| 工程化改造记录    | [CHANGELOG\_工程化改造.md](CHANGELOG_工程化改造.md)               | 上传 GitHub 前的整理追溯清单（零源码变更）        |

