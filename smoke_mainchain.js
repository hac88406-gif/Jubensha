/**
 * 主链路 smoke：注册 -> 登录 -> 建店 -> 建剧本 -> 建场次 -> 抢位 -> 关场(触发取消未支付订单) -> 验证
 * 走网关 8081。
 */
const http = require('http');
const agent = new http.Agent({ keepAlive: true, maxSockets: 20 });

const GW = { host: '127.0.0.1', port: 8081 };

function req(path, method, body, token) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const opts = {
      host: GW.host, port: GW.port, path, method, agent,
      headers: {
        'Content-Type': 'application/json', 'Accept': 'application/json',
        ...(data ? { 'Content-Length': Buffer.byteLength(data) } : {}),
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
      },
      timeout: 20000,
    };
    const r = http.request(opts, (res) => {
      let c = ''; res.setEncoding('utf8');
      res.on('data', (x) => c += x);
      res.on('end', () => { try { resolve({ status: res.statusCode, data: JSON.parse(c) }); } catch (e) { resolve({ status: res.statusCode, raw: c }); } });
    });
    r.on('error', reject);
    if (data) r.write(data);
    r.end();
  });
}

// 计算 Asia/Shanghai 的 sessionDate / startTime（now + 4h，保证关场满足'开场前2小时以上'）
function sessionTimes(now) {
  const off = ((now.getTime()) + (8 * 3600 * 1000)); // 上海时区
  const d = new Date(off);
  const start = new Date(d.getTime() + 4 * 3600 * 1000);
  const end = new Date(start.getTime() + 120 * 60 * 1000);
  const pad = (n) => String(n).padStart(2, '0');
  return {
    date: `${start.getUTCFullYear()}-${pad(start.getUTCMonth()+1)}-${pad(start.getUTCDate())}`,
    startTime: `${pad(start.getUTCHours())}:${pad(start.getUTCMinutes())}`,
    endTime: `${pad(end.getUTCHours())}:${pad(end.getUTCMinutes())}`,
  };
}

(async () => {
  const ts = Date.now().toString().slice(-4);
  const owner = 'shop_owner_jmeter'; const player = `play${ts}`;

  // 1. 店主登录(复用已有店主账号) + 玩家注册登录
  let r = await req('/api/user/login', 'POST', { username: owner, password: '123456' });
  const ownerToken = r.data?.data?.token;
  if (!ownerToken) throw new Error('店主登录失败: ' + JSON.stringify(r.data));
  await req('/api/user/register', 'POST', { username: player, password: '123456' });
  r = await req('/api/user/login', 'POST', { username: player, password: '123456' });
  const playerToken = r.data?.data?.token;
  if (!playerToken) throw new Error('玩家登录失败: ' + JSON.stringify(r.data));
  console.log('[1] 登录 OK  owner=' + owner + ' player=' + player);

  // 2. 建店
  r = await req('/api/shop/create', 'POST', { name: '主链路烟测店' + ts, address: '烟测地址' }, ownerToken);
  const shopId = r.data?.data;
  if (!shopId) throw new Error('建店失败: ' + JSON.stringify(r.data));
  console.log('[2] 建店 OK shopId=' + shopId);

  // 3. 建剧本
  r = await req('/api/script/create', 'POST', { shopId, name: '主链路烟测本', author: 'test', scriptType: '机制', playerMin: 4, playerMax: 8, duration: 120 } , ownerToken);
  const scriptId = r.data?.data;
  if (!scriptId) throw new Error('建剧本失败: ' + JSON.stringify(r.data));
  console.log('[3] 建剧本 OK scriptId=' + scriptId);

  // 4. 建场次（capacity=3）
  const tm = sessionTimes(new Date());
  r = await req('/api/session', 'POST',
    { scriptId, shopId, sessionDate: tm.date, startTime: tm.startTime, endTime: tm.endTime, capacity: 3 }, ownerToken);
  const sessionId = r.data?.data;
  if (!sessionId) throw new Error('建场次失败: ' + JSON.stringify(r.data));
  console.log('[4] 建场次 OK sessionId=' + sessionId + ' ' + tm.date + ' ' + tm.startTime);

  // 5. 玩家抢位下单
  r = await req('/api/order', 'POST', { sessionId, playerCnt: 1 }, playerToken);
  const orderNo = r.data?.data;
  if (r.data?.code !== 200 || !orderNo) throw new Error('抢位失败: ' + JSON.stringify(r.data));
  console.log('[5] 抢位下单 OK orderNo=' + orderNo);

  // 6. 关场（应取消该未支付订单）
  r = await req('/api/session/' + sessionId + '/close', 'POST', null, ownerToken);
  console.log('[6] 关场 close code=' + r.data?.code + ' msg=' + r.data?.message);

  console.log('\n结果写入文本 for 后续断言...');
  require('fs').writeFileSync('smoke_mainchain_result.txt',
    JSON.stringify({ shopId, scriptId, sessionId, orderNo, owner, player }, null, 2));
})().catch((e) => { console.error('主链路异常:', e); process.exit(1); });