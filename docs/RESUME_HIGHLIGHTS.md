# 剧本杀在线预约系统 —— 简历亮点（问题 → 方案 → 结果）

> 项目：urban-script-reservation（在线剧本杀场次预约 / 高并发抢位系统）
> 架构：Spring Cloud 微服务（Spring Boot 3.2 / MyBatis-Plus / Spring Cloud Gateway）
> 中间件：MySQL 8 · Redis 7 · RabbitMQ 3.13 · Nacos 2.4 · Redisson
> 日期：2026-09-04

---

## 亮点一：高并发抢位「零超卖」—— 从 MySQL 乐观锁到 Redis+Lua

**问题（Problem）**
抢位是典型的秒杀式高并发场景。初版用 MySQL 乐观锁
`UPDATE session_info SET booked=booked+1 WHERE id=? AND status=1 AND booked+1<=capacity`，
单行热点数据在 2000 并发下被 InnoDB 行锁严重串行化：2000 个请求产生约 **24000 次 SQL 交互**、
DB CPU 持续飙高，且要先读后写存在竞态窗口。

**方案（Solution）**
- 用 **Redis+Lua 原子扣减** 前置挡并发：Lua 脚本内先判断 `booked + playerCnt <= capacity` 再 `HINCRBY`，
  判断与扣减同在一个脚本原子执行，天然杜绝先读后写的竞态；
  库存 key 采用 HASH `session:{id}`，配合**动态 TTL**，防止陈旧库存常驻。
- 配合 **Redisson 分布式锁** `reserve:lock:{sessionId}:{userId}`（tryLock 3s 等待 / 10s 持有）
  拦截同一用户重复提交。
- **双保险降级**：Redis 不可用时（仅捕获 RedisConnectionFailureException / RedisSystemException，
  避免吞掉业务异常）自动回退 MySQL 乐观锁，并**异步 forceRefreshBooked** 从 MySQL 校准 Redis 库存，
  保证 Redis 与 MySQL 最终一致。

**结果（Result）**
用 2000 并发压测（实测，2026-09-04）：

| 指标 | MySQL 乐观锁（方案 B） | Redis+Lua（方案 A） |
|---|---|---|
| 冲突率 | **86.5%** | **0.000%** |
| QPS | **≈ 20** | **≈ 192**（峰值 669） |
| 库存 booked | 10/10（字段未超卖） | 10/10，与成功数完全一致 |
| 订单层 | 出现 22>10 越量 | 严格 =capacity |

- 冲突率由 MySQL 乐观锁场景的 **86.5%** 降至 **0.000%**
- Redis+Lua **零超卖**：最终 booked = capacity，成功下单数严格等于名额
- 吞吐从 **QPS≈20 提升到 ≈192（峰值 669）**，2000 并发秒级清空

---

## 亮点二：超时关单 + 场次关闭的「最终一致性」

**问题（Problem）**
用户抢位后若不支付需自动取消；主办方关闭场次时需**批量取消所有未支付订单**并回滚库存。
两处都要保证：不超卖、不重复回滚、不误取消已支付订单。

**方案（Solution）**
- **RMQ TTL+DLX 延迟关单**：下单即发延迟消息（生产 15 分钟，压测 30s）到 `order.delay.queue`，
  设置 `x-dead-letter-exchange / x-dead-letter-routing-key`，到期转入 `order.dlx.queue`。
- 消费者幂等：**先查订单 status=0（待支付）** 才置为取消，否则跳过；取消后**双回落**
  Redis 库存（回滚 Lua/`forceRefresh`）与 MySQL `booked` 字段。
- **场次关闭** `closeSession()`：开场前 2 小时外才允许关闭；关闭后删除 Redis `session:{id}` 缓存
  key、把状态置 0，并由 `SessionCloseConsumer` 批量取消待支付订单、回滚库存。

**结果（Result）**
订单 15 分钟不支付自动关闭、场次关闭批量取消两条链路最终一致，
库存/订单字段经幂等校验无重复回滚、无超卖，故障与时钟边界均有兜底。

---

## 亮点三：微服务统一鉴权与服务间可信调用

**问题（Problem）**
5 个微服务拆开后，鉴权分散（网关要做、服务内角色也要做、服务间 Feign 调用还要防止越权）。

**方案（Solution）**
- 入口 **Spring Cloud Gateway `JwtAuthFilter`**：白名单放行（注册/登录/内部接口），
  有 token 则校验签名并把 `X-User-Id / X-User-Role` 写入下游请求头；无 token 透传由 `@RequireRole` 兜底。
- 服务内 **`@RequireRole` + `@Around` 切面**统一做角色校验（类/方法级）。
- 服务间可信调用：**`InternalApiKeyFilter`** 拦 `/**/internal/**` 校验 `X-Internal-Api-Key`，
  Feign 通过 **`InternalApiKeyInterceptor`** 自动注入该头；密钥统一放 **Nacos `urban-shared-config`**
  shared-config 管理，一处配置到处生效。

**结果（Result）**
从公网到服务内形成「网关 JWT → 切面角色 → 服务间密钥」三段式隔离，任意一个环缺失都无法越权；
密钥集中于 Nacos，多服务无需重复维护。

---

## 亮点四（可选）FEAT：接入 AI 剧本推荐，网关熔断兜底

- 用 **agent-gateway（Servlet 栈）** 承接浏览器无跨域调用的 AI 渠道，Feign 调 python-agent；
- **PythonAgentClient + FallbackFactory**：上游 AI 服务不可用时返回**硬编码兜底文案**，
  不让 AI 故障拖垮主链路（Sentinel 熔断降级）。

---

## 一句话版本
> 微服务在线预约系统，把「秒杀式高并发抢位」从 MySQL 乐观锁的高冲突，
> 改造成 Redis+Lua 原子扣减——实测 2000 并发下冲突率 86.5% → 0、QPS≈192（峰值 669）、零超卖；
> 并用 RMQ TTL+DLX 实现订单 15 分钟超时自动关单与场次关闭批量取消的最终一致性，
> 网关 JWT + 切面角色 + 服务间密钥三环鉴权封闭微服务安全边界。
