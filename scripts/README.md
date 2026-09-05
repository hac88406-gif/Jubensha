# 脚本说明（benchmark / smoke-test / 辅助工具）

> 仓库根目录下有若干 `.js` / `.py` 脚本，本文档说明其用途、运行方式、依赖工具，避免面试官误以为是乱文件。
>
> 说明：为保证脚本内部相对路径、引用路径不变，**脚本仍保留在仓库根目录**，本文件仅做分类说明。

---

## 一、压测脚本（benchmark）

### `benchmark_stock.js`
- **用途**：对比「Redis+Lua 原子扣减」与「MySQL 乐观锁」在单行热点（同一场次秒杀）下的性能差异。
- **核心指标**：冲突率、QPS、最终 booked 值、是否超卖、SQL 数量（乐观锁场景）。
- **运行工具**：k6（推荐）或 Node.js（需补 HTTP 客户端库）
  ```bash
  # 方式 A · k6 压测（需要先安装 k6：https://k6.io/docs/get-started/installation/）
  k6 run benchmark_stock.js

  # 方式 B · Node 直接跑（脚本已写好请求，并发用 Promise.all）
  node benchmark_stock.js
  ```
- **前置条件**：
  - order-service 已启动（端口 8084）或通过网关 8081；
  - Redis 可用（Redis+Lua 组）、MySQL 可用（乐观锁组）；
  - 脚本内置的 sessionId 需先创建好（capacity=10、status=1），或在脚本顶部修改变量。
- **输出**：控制台打印两组方案各自的成功数、失败数、耗时、QPS，并给出对比表格（可写入 `interview_bench_A.txt`，该 txt 已在 `.gitignore` 中，不入库）。

### `make_bench_session.js`
- **用途**：压测前的辅助脚本——批量创建「门店 → 剧本 → 场次」，为 `benchmark_stock.js` 准备好待抢的 sessionId。
- **运行工具**：Node.js
  ```bash
  node make_bench_session.js
  ```
- **输出**：控制台输出创建好的 `shopId / scriptId / sessionId`，直接复制粘贴到 `benchmark_stock.js` 顶部变量即可。

---

## 二、冒烟测试脚本（smoke-test · 全链路验证）

### `smoke_mainchain.js`
- **用途**：完整跑一遍「注册→登录→建门店→建剧本→建场次→玩家下单→关单/超时」的全链路主流程，
  验证所有服务都正常启动、接口契约无变化。
- **覆盖接口**：
  - user-service：`POST /api/user/register`、`POST /api/user/login`（玩家 + 店主两个账号）
  - shop-service：`POST /api/shop`、`POST /api/script`、`POST /api/session`
  - order-service：`POST /api/order`、`GET /api/order/{orderNo}`
  - 可选：超时关单（等待 RMQ TTL 触发）与场次关闭关单
- **运行工具**：Node.js（脚本用 fetch / axios 串 HTTP 请求）
  ```bash
  node smoke_mainchain.js
  ```
- **前置条件**：
  - 4 个业务服务 + gateway + agent-gateway 全部启动；
  - Python Agent 可选（冒烟测试只测业务主链路，AI 链路另测）。
- **临时产物（已被 .gitignore 忽略，不入库）**：
  - `smoke_mainchain_result.txt`：执行过程日志
  - `smoke_player_token.txt` / `smoke_owner_token.txt`：登录得到的 JWT（后续可复用）
  - `smoke_shopId.txt` / `smoke_scriptId.txt` / `smoke_sessionId.txt`：主流程创建的实体 ID
  - `smoke_orderNo.txt`：下单成功的订单号
  - `smoke_user.log` / `smoke_shop.log` / `smoke_order.log`：各服务的输出片段（debug 用）
  - `smoke_gateway.log` / `smoke_agent.log` / `smoke_python.log`：网关 / Agent 侧输出（debug 用）

---

## 三、代码截图辅助脚本（interview_shots）

### `interview_shots/make_code_shots.py`
- **用途**：把 `order-service/src/main/java/.../StockService.java` 按 1–95 / 96–190 / 191–285 / 286–380
  四段分别渲染成 PNG 长图，方便面试现场展示核心代码而不是翻 IDE。
- **运行工具**：Python 3.12（需要安装 playwright 或 carbon-now-cli / pypng 等）
  ```bash
  cd interview_shots
  pip install playwright
  python make_code_shots.py
  ```
- **输出**：生成 `code_stock_service_*.png` 和 `code_reserve_lua.png`（位于 `interview_shots/` 下，已入库）。

---

## 四、脚本目录说明（FAQ）

**Q：为什么脚本都放在根目录，不放到 scripts/ 文件夹里？**
A：脚本内部有相对路径引用（如 `require('../config')` 或 `interview_shots/xxx`），移动目录会导致执行失败。
为了**零改动源代码与脚本**、确保用户拿到仓库就能直接 `node xxx.js` 运行，脚本保留原位，用本 README 做分类说明即可。

**Q：脚本输出的 .txt / .log 临时文件会被提交吗？**
A：不会。所有 `smoke_*.txt`、`smoke_*.log`、`interview_bench_*.txt` 都已在根目录 `.gitignore` 中被忽略，
不会污染 Git 仓库。
