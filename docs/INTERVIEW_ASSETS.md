# 面试素材清单（interview_shots/ 目录）

> 用途：面试现场演示时，按模块快速调出对应截图，证明项目真实跑过、各组件都配置到位。
> 所有素材都位于仓库根目录的 `interview_shots/` 文件夹下。

---

## 一、架构图

| 文件名 | 用途 | 面试时怎么说 |
|---|---|---|
| `architecture_renderer.html` | 系统整体架构图（浏览器 → Gateway → 4 业务服务 → Agent 网关 → Python Agent，中间件 Nacos/Mysql/Redis/RMQ） | 打开该 HTML，指给面试官看「请求链路怎么走、鉴权怎么做、服务间怎么调」 |

---

## 二、Nacos 配置与服务注册

| 文件名 | 用途 | 关键信息 |
|---|---|---|
| `nacos_services.png` | Nacos 服务管理 → 服务列表截图 | 证明 5 个 Java 服务（user/shop/order/gateway/agent-gateway）都正确注册到 Nacos，健康实例数 ≥1 |
| `nacos_configs.png` | Nacos 配置管理 → 配置列表截图 | 证明 `urban-shared-config`（dataId）共享配置已发布，JWT 密钥和 Internal API Key 一处管理、多服务共享 |

**面试话术参考**：
> 「敏感配置我没有写死在 application.yml，全部抽到 Nacos 的 urban-shared-config 里，通过 shared-configs 引入，支持热刷新。服务间内部接口用的 X-Internal-Api-Key 也是从这里读的，多服务完全一致。」

---

## 三、RabbitMQ 交换机 / 队列

| 文件名 | 用途 | 关键信息 |
|---|---|---|
| `mq_overview.png` | RabbitMQ 管理控制台 Overview 页截图 | 证明节点正常运行、有连接/通道/消费者存活 |
| `mq_exchanges.png` | Exchanges 列表截图 | 展示 `order.delay.exchange`、`order.dlx.exchange`、`session.close.exchange` 三个业务交换机，以及 DLX 绑定关系 |
| `mq_queues.png` | Queues 列表截图 | 展示 `order.delay.queue`（带 TTL + x-dead-letter-exchange 参数）、`order.dlx.queue`、`session.close.queue` 三条队列，消费者数、消息数一目了然 |

**面试话术参考**：
> 「延迟关单我用的是 RabbitMQ 原生 TTL+DLX 方案，不依赖 delayed_message_exchange 插件，部署更稳。订单延迟队列 15 分钟 TTL 到期后自动转入死信队列，消费者收到后幂等取消待支付订单、双写回滚库存。」

---

## 四、核心代码截图（高并发库存扣减部分）

> 用途：亮点一（Redis+Lua vs MySQL 乐观锁）的代码证据。按逻辑拆成 4 张长图，展示 `StockService` + Lua 脚本全貌。

| 文件名 | 范围（StockService.java 行号） | 核心内容 |
|---|---|---|
| `code_stock_service_1-95.png` | 1–95 行 | 类级注解、依赖注入（RedissonClient / StringRedisTemplate / RedisScript 等）、`decreaseStock` 方法前半段：获取分布式锁 → 调用 Lua → Lua 返回处理 |
| `code_stock_service_96-190.png` | 96–190 行 | Lua 返回 0 时的降级分支：仅捕获 RedisConnectionFailureException / RedisSystemException → 回退 MySQL 乐观锁（`decreaseStockByMysql`）→ 异步 `forceRefreshBooked` 校准 Redis |
| `code_stock_service_191-285.png` | 191–285 行 | `rollbackStock` 双写回滚（Lua 回滚 + MySQL booked 回滚）、`decreaseStockByMysql` 乐观锁 CAS 实现、`forceRefreshBooked` 从 DB 回刷 Redis |
| `code_stock_service_286-380.png` | 286–380 行 | `increaseBookedInMysql`、`decreaseBookedInMysql`、`countDmConflict`（场次创建时 DM 时段冲突检测）等辅助方法 |
| `code_reserve_lua.png` | `reserve_stock.lua` + `reserve_rollback.lua` | 两个 Lua 脚本：扣减脚本先判断 booked+cnt ≤ capacity 再 HINCRBY，回滚脚本 HINCRBY 负数并兜底 HSET，操作都原子 |

---

## 五、接口文档（Knife4j / Swagger）

| 文件名 | 用途 |
|---|---|
| `knife4j_order.png` | 订单服务 Knife4j 文档页截图，展示 `/api/order` 系列接口（创建订单、我的订单、取消订单、内部接口 `/internal/` 等）的分组、入参、响应结构 |

> 其他服务（user/shop）的 Knife4j 页面同理可在本地启动后访问：http://localhost:{port}/doc.html

---

## 六、使用建议（面试演示顺序）

1. 先开 `architecture_renderer.html` → 讲整体架构（1 分钟）
2. 开 `nacos_services.png` + `nacos_configs.png` → 讲注册发现和配置中心（30 秒）
3. 开 `mq_queues.png` → 讲 TTL+DLX 延迟关单链路（30 秒）
4. 开 `code_reserve_lua.png` + 4 张 `code_stock_service_*.png` → 讲 Redis+Lua 扣减和降级（重点，2–3 分钟）
5. 最后翻 `docs/PROJECT_NARRATIVE.md` 或 `docs/RESUME_HIGHLIGHTS.md` → 讲量化对比数据（冲突率、QPS、零超卖）
