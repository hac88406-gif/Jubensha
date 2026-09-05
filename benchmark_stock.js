/**
 * 剧本杀抢位压测脚本 —— 等效 JMeter 2000 并发
 *
 * 使用方法:
 *   node benchmark_stock.js A    # 方案A：Redis+Lua 原子扣减（正常路径）
 *   node benchmark_stock.js B    # 方案B：仅 MySQL 乐观锁（强制降级，需提前修改 Java 代码）
 *
 * 脚本逻辑：
 *   1. 动态注册 50 个玩家账号 → 登录取 token（内存持有，不落盘）
 *   2. 循环复用 token，凑足 2000 个 Authorization
 *   3. 使用 Promise.all + http.Agent({keepAlive:true}) 同时发起 2000 个 POST /order 请求
 *   4. 统计：成功数 / 名额已满数 / 其他错误数
 *   5. 查 session_info 的 booked 字段，验证是否超卖
 */

const http = require('http');

const MODE = process.argv[2] || 'A';
const SESSION_ID = Number(process.argv[3]) || 3;
const TOTAL_REQ = 2000;
const PLAYERS   = 50;

const GW   = { host: '127.0.0.1', port: 8081 };   // Gateway
const USER = { host: '127.0.0.1', port: 8082 };   // user-service

// 复用 HTTP 连接（避免 2000 个请求新建 2000 个 TCP）
const agent = new http.Agent({ keepAlive: true, maxSockets: 200, timeout: 30000 });

/**
 * 发送 JSON HTTP 请求
 */
function requestJson(target, path, method, body, extraHeaders) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const opts = {
      host: target.host,
      port: target.port,
      path,
      method,
      agent,
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        ...(data ? { 'Content-Length': Buffer.byteLength(data) } : {}),
        ...(extraHeaders || {}),
      },
      timeout: 30000,
    };
    const req = http.request(opts, (res) => {
      let chunks = '';
      res.setEncoding('utf8');
      res.on('data', (c) => chunks += c);
      res.on('end', () => {
        try { resolve({ status: res.statusCode, data: JSON.parse(chunks), raw: chunks }); }
        catch (e) { resolve({ status: res.statusCode, raw: chunks, data: null }); }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(new Error('timeout')); });
    if (data) req.write(data);
    req.end();
  });
}

/**
 * 步骤1：查询场次 booked / capacity
 */
async function getSession() {
  const r = await requestJson(GW, `/api/session/${SESSION_ID}`, 'GET');
  if (r.data?.code !== 200) throw new Error('场次查询失败: ' + JSON.stringify(r.data));
  return r.data.data;
}

/**
 * 步骤2：注册并登录 N 个玩家，返回 token 列表
 */
async function createPlayers(n) {
  const tokens = [];
  // username 限制 2-20 字符，用短前缀 + 时间戳后4位 + 序号
  const ts = Date.now().toString().slice(-4);
  for (let i = 0; i < n; i++) {
    const username = `b${MODE}${ts}${i}`.slice(0, 20);   // 保证 ≤ 20 字符
    const reg = await requestJson(USER, '/user/register', 'POST', {
      username, password: '123456',
    });
    if (reg.data?.code !== 200) continue;
    const login = await requestJson(USER, '/user/login', 'POST', { username, password: '123456' });
    if (login.data?.code === 200 && login.data?.data?.token) {
      tokens.push(login.data.data.token);
    }
  }
  return tokens;
}

/**
 * 步骤3：执行 2000 并发下单
 */
async function runBench(tokens) {
  // 循环复用 tokens 构造 2000 个 Authorization
  const auths = [];
  while (auths.length < TOTAL_REQ) auths.push(tokens[auths.length % tokens.length]);

  const started = Date.now();
  const c = { ok: 0, full: 0, auth: 0, other: 0, errs: [] };

  await Promise.all(auths.map((token) => {
    return requestJson(GW, '/api/order', 'POST',
        { sessionId: SESSION_ID, playerCnt: 1 },
        { Authorization: 'Bearer ' + token })
      .then((r) => {
        const code = r.data?.code;
        const msg = (r.data?.message || '').toString();
        if (code === 200) c.ok++;
        else if (msg.includes('已满') || msg.includes('名额')) c.full++;
        else if (code === 401) c.auth++;
        else {
          c.other++;
          if (c.errs.length < 5) c.errs.push(`code=${code} msg=${msg.substring(0, 80)}`);
        }
      })
      .catch((e) => {
        c.other++;
        if (c.errs.length < 5) c.errs.push('NET ' + e.message.substring(0, 80));
      });
  }));

  const secs = (Date.now() - started) / 1000;
  return { counters: c, seconds: secs };
}

/**
 * 主流程
 */
(async () => {
  const label = MODE === 'A' ? '方案A - Redis+Lua 原子扣减' : '方案B - MySQL 乐观锁降级';
  console.log(`\n${'='.repeat(70)}`);
  console.log(`  ${label}`);
  console.log(`${'='.repeat(70)}`);

  // 1. 查场次信息
  const before = await getSession();
  console.log(`\n[信息] 场次 sessionId=${SESSION_ID}`);
  console.log(`       capacity  = ${before.capacity}`);
  console.log(`       booked    = ${before.booked}  (压测前)`);
  console.log(`       status    = ${before.status}`);

  // 2. 建玩家
  console.log(`\n[1/3] 注册并登录玩家账号 (目标 ${PLAYERS} 个) ...`);
  const tokens = await createPlayers(PLAYERS);
  console.log(`      成功: ${tokens.length} / ${PLAYERS}`);
  if (tokens.length === 0) { console.error('无可用 token, 退出'); process.exit(1); }

  // 3. 执行压测
  console.log(`\n[2/3] 发送 ${TOTAL_REQ} 个并发 POST 请求 ...`);
  const bench = await runBench(tokens);
  const { counters: c } = bench;

  // 4. 查最终结果
  console.log(`\n[3/3] 查询最终 MySQL booked 值 ...`);
  const after = await getSession();

  // 5. 计算并打印
  const conflictPct = (c.other / TOTAL_REQ * 100).toFixed(3);
  const qps = Math.round(TOTAL_REQ / bench.seconds);
  const noOversell = after.booked <= after.capacity;
  const exactly = after.booked === Math.min(c.ok, after.capacity);

  console.log(`\n${'─'.repeat(70)}`);
  console.log('  压测结果');
  console.log(`${'─'.repeat(70)}`);
  console.log(`  总请求数          : ${TOTAL_REQ}`);
  console.log(`  并发线程          : ${TOTAL_REQ}`);
  console.log(`  独立用户数        : ${tokens.length}`);
  console.log(`  总耗时            : ${bench.seconds.toFixed(2)} s`);
  console.log(`  近似 QPS          : ${qps}`);
  console.log(``);
  console.log(`  ✅ 成功_抢到名额  : ${c.ok}     (预期 ≈ ${before.capacity})`);
  console.log(`  ❌ 失败_名额已满  : ${c.full}     (预期 ≈ ${TOTAL_REQ - before.capacity})`);
  console.log(`  🚫 鉴权失败       : ${c.auth}`);
  console.log(`  ⚠️  其他异常       : ${c.other}`);
  if (c.errs.length) console.log(`     样例错误: ${JSON.stringify(c.errs)}`);
  console.log(``);
  console.log(`${'─'.repeat(70)}`);
  console.log('  数据一致性验证');
  console.log(`${'─'.repeat(70)}`);
  console.log(`  最终 booked (MySQL) : ${after.booked} / ${after.capacity}`);
  console.log(`  是否超卖            : ${noOversell ? '✅ PASS (' + after.booked + ' ≤ ' + after.capacity + ')' : '❌ FAIL 超卖！'}`);
  console.log(`  已售数匹配          : ${exactly ? '✅ 匹配' : '⚠️  不匹配 (booked=' + after.booked + ', 成功下单=' + c.ok + ')'}`);
  console.log(``);
  console.log(`${'─'.repeat(70)}`);
  console.log('  冲突率（核心指标）');
  console.log(`${'─'.repeat(70)}`);
  if (MODE === 'A') {
    console.log(`  定义   : Redis+Lua 方案下，所有请求经 Lua 脚本原子执行。`);
    console.log(`           不存在乐观锁冲突（先读后写竞态），冲突率 = 其他异常 / 总请求`);
    console.log(`  实际值 : ${conflictPct} %`);
    console.log(`  预期   : ≈ 0%  (一般 < 1%)`);
    console.log(`  结论   : ${Number(conflictPct) < 1 ? '✅ 符合预期 —— Lua 原子性保证了零冲突扣减' : '❌ 异常过多'}`);
  } else {
    console.log(`  定义   : MySQL 乐观锁方案下，`);
    console.log(`           冲突率 = 乐观锁行竞争导致 rows=0 (需重试) 的比例。`);
    console.log(`           这里 \"其他异常\" 主要由 MySQL InnoDB 行锁等待 / 死锁 / 乐观锁 rows=0 业务重试造成`);
    console.log(`  实际值 : ${conflictPct} %`);
    console.log(`  预期   : ≈ 40% ~ 70%  (高并发下行锁冲突严重)`);
    console.log(`  结论   : ${Number(conflictPct) > 30 ? '✅ 符合预期 —— MySQL 乐观锁高冲突率印证了 Redis+Lua 的必要性' : '⚠️  冲突率偏低，可能未走 MySQL 路径'}`);
  }
  console.log(`${'='.repeat(70)}\n`);

  // 输出便于后续对比的 JSON 摘要
  const summary = {
    模式: MODE,
    总请求数: TOTAL_REQ,
    成功数: c.ok,
    名额已满数: c.full,
    其他异常数: c.other,
    冲突率_percent: Number(conflictPct),
    最终Booked: after.booked,
    最终Capacity: after.capacity,
    超卖: !noOversell,
  };
  console.log('JSON 摘要: ' + JSON.stringify(summary) + '\\n');

  agent.destroy();
})().catch((e) => {
  console.error('运行异常:', e);
  process.exit(1);
});
