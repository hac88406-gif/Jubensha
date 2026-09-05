/*
 * 一键创建压测用场次：登录店主 -> 建场次(capacity) -> 打印 sessionId
 * 用法: node make_bench_session.js <capacity>
 */
const http = require('http');
const agent = new http.Agent({ keepAlive: true });
const cap = Number(process.argv[2] || 10);
const GW = { host: '127.0.0.1', port: 8081 };
function req(path, method, body, token) {
  return new Promise((res, rej) => {
    const data = body ? JSON.stringify(body) : null;
    const o = { host: GW.host, port: GW.port, path, method, agent,
      headers: { 'Content-Type': 'application/json', ...(data ? { 'Content-Length': Buffer.byteLength(data) } : {}), ...(token ? { Authorization: 'Bearer ' + token } : {}) }, timeout: 20000 };
    const r = http.request(o, (x) => { let c = ''; x.setEncoding('utf8'); x.on('data', (d) => c += d); x.on('end', () => res(JSON.parse(c))); });
    r.on('error', rej); if (data) r.write(data); r.end();
  });
}
(async () => {
  const d = new Date(Date.now() + 8 * 3600 * 1000);
  const s = new Date(d.getTime() + 4 * 3600 * 1000);
  const e = new Date(s.getTime() + 120 * 60 * 1000);
  const pad = n => String(n).padStart(2, '0');
  const date = `${s.getUTCFullYear()}-${pad(s.getUTCMonth()+1)}-${pad(s.getUTCDate())}`;
  const startTime = `${pad(s.getUTCHours())}:${pad(s.getUTCMinutes())}`, endTime = `${pad(e.getUTCHours())}:${pad(e.getUTCMinutes())}`;
  const login = await req('/api/user/login', 'POST', { username: 'shop_owner_jmeter', password: '123456' });
  const t = login.data.token;
  const r = await req('/api/session', 'POST', { scriptId: 4, shopId: 3, sessionDate: date, startTime, endTime, capacity: cap }, t);
  if (r.code !== 200) throw new Error('创建失败: ' + JSON.stringify(r));
  console.log('SESSION_ID=' + r.data + ' capacity=' + cap + ' date=' + date + ' start=' + startTime);
})().catch(e => { console.error(e); process.exit(1); });