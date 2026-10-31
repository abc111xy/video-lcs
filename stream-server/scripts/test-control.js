/**
 * 远程控制信令端到端测试（仅本地联调用，不属于交付物）。
 * 用法：node scripts/test-control.js  （需先启动一个实例在 PORT=3199）
 */
const WebSocket = require('ws');
const { loadEnv } = require('../lib/env');
loadEnv();
const { query, pool } = require('../lib/db');
const { hashPassword } = require('../lib/auth');
const crypto = require('crypto');

const BASE = process.env.TEST_BASE || 'http://127.0.0.1:3199';
const WS = BASE.replace('http', 'ws') + '/ws';

let pass = 0;
let fail = 0;
function ok(name, cond, extra) {
  if (cond) {
    pass++;
    console.log(`  ✓ ${name}`);
  } else {
    fail++;
    console.log(`  ✗ ${name}${extra ? ' -> ' + JSON.stringify(extra) : ''}`);
  }
}

function connect(room, role, name, token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS);
    const inbox = [];
    const waiters = [];
    ws.on('message', (data, isBinary) => {
      if (isBinary) return;
      let m;
      try { m = JSON.parse(data.toString()); } catch (e) { return; }
      inbox.push(m);
      const idx = inbox.length - 1;
      for (let i = waiters.length - 1; i >= 0; i--) {
        const w = waiters[i];
        if (idx >= w.start && w.match(m)) {
          waiters.splice(i, 1);
          w.resolve(m);
        }
      }
    });
    ws.on('open', () => {
      const join = { type: 'join', room, role, name };
      if (token) join.token = token;
      ws.send(JSON.stringify(join));
    });
    ws.on('error', reject);
    const api = {
      ws,
      inbox,
      send: (o) => ws.send(JSON.stringify(o)),
      wait: (match, ms = 1500) => new Promise((res, rej) => {
        const hit = inbox.find(match);
        if (hit) return res(hit);
        const w = { match, start: 0, resolve: res };
        waiters.push(w);
        setTimeout(() => {
          const i = waiters.indexOf(w);
          if (i >= 0) { waiters.splice(i, 1); rej(new Error('timeout')); }
        }, ms);
      }),
      clear: () => { inbox.length = 0; },
      close: () => ws.close(),
      get cid() { return api._cid; },
    };
    const joined = api.wait((m) => m.type === 'joined', 5000);
    joined.then((m) => { api._cid = m.cid; resolve(api); }).catch(reject);
  });
}

(async () => {
  const email = `ctrl-test-${Date.now()}@example.com`;
  const password = 'Test12345678';
  const id = 'u_' + crypto.randomBytes(8).toString('hex');
  const salt = crypto.randomBytes(16).toString('base64');
  const now = Date.now();
  await query(
    `INSERT INTO users (id,email,salt,pw_hash,created_at,consent_version,consent_at,consent_ua)
     VALUES ($1,$2,$3,$4,$5,'1.1',$6,'control-test')`,
    [id, email, salt, hashPassword(password, salt), now, now],
  );

  const loginRes = await fetch(BASE + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const login = await loginRes.json();
  if (!login.token) throw new Error('登录失败: ' + JSON.stringify(login));
  const token = login.token;

  const room = '99' + Math.floor(Math.random() * 90 + 10);
  const sender = await connect(room, 'sender', '测试主播', token);
  const r1 = await connect(room, 'receiver', '观众甲');
  const r2 = await connect(room, 'receiver', '观众乙');
  ok('接收端拿到 cid', !!r1.cid && r1.cid !== r2.cid);

  // --- 申请 ---
  sender.clear(); r2.clear();
  r1.send({ type: 'control-request' });
  const req = await sender.wait((m) => m.type === 'control-request');
  ok('主播收到控制申请', req.from === r1.cid && req.name === '观众甲', req);
  const pending = await r1.wait((m) => m.type === 'control-pending');
  ok('申请方进入等待态', pending.type === 'control-pending');
  const st1 = await r2.wait((m) => m.type === 'control-state');
  ok('他人收到「有人申请」状态', st1.pending === '观众甲', st1);

  r2.send({ type: 'control-request' });
  const busy = await r2.wait((m) => m.type === 'control-denied');
  ok('并发申请被拒', /正在处理/.test(busy.reason), busy);

  // --- 拒绝 ---
  sender.send({ type: 'control-response', to: r1.cid, allow: false, reason: '正在忙' });
  const denied = await r1.wait((m) => m.type === 'control-denied');
  ok('主播拒绝后申请方收到理由', denied.reason === '正在忙', denied);

  // --- 批准 ---
  sender.clear();
  r1.send({ type: 'control-request' });
  await sender.wait((m) => m.type === 'control-request');
  sender.send({ type: 'control-response', to: r1.cid, allow: true });
  const granted = await r1.wait((m) => m.type === 'control-granted');
  ok('批准后收到 control-granted', granted.type === 'control-granted');
  const st2 = await r2.wait((m) => m.type === 'control-state' && m.active === true);
  ok('他人看到「甲正在控制」', st2.name === '观众甲', st2);

  // --- 输入转发与隔离 ---
  sender.clear();
  r1.send({ type: 'control-input', action: 'tap', x: 0.25, y: 0.75 });
  const tap = await sender.wait((m) => m.type === 'control-input');
  ok('点击坐标转发正确', tap.action === 'tap' && tap.x === 0.25 && tap.y === 0.75, tap);

  sender.clear();
  r1.send({ type: 'control-input', action: 'swipe', x: 0.1, y: 0.2, x2: 0.8, y2: 0.6, duration: 300 });
  const sw = await sender.wait((m) => m.type === 'control-input');
  ok('滑动参数转发正确', sw.action === 'swipe' && sw.x2 === 0.8 && sw.duration === 300, sw);

  sender.clear();
  r2.send({ type: 'control-input', action: 'tap', x: 0.5, y: 0.5 });
  await new Promise((r) => setTimeout(r, 300));
  ok('非控制者输入被丢弃', !sender.inbox.some((m) => m.type === 'control-input'), sender.inbox);

  // --- 越界与非法动作 ---
  sender.clear();
  r1.send({ type: 'control-input', action: 'tap', x: 9, y: -3 });
  const clamped = await sender.wait((m) => m.type === 'control-input');
  ok('坐标被夹到 0..1', clamped.x === 1 && clamped.y === 0, clamped);

  sender.clear();
  r1.send({ type: 'control-input', action: 'shell', cmd: 'rm -rf /' });
  await new Promise((r) => setTimeout(r, 250));
  ok('未知动作不透传', !sender.inbox.some((m) => m.type === 'control-input'));

  // --- 限流 ---
  sender.clear();
  for (let i = 0; i < 300; i++) r1.send({ type: 'control-input', action: 'tap', x: 0.5, y: 0.5 });
  await new Promise((r) => setTimeout(r, 600));
  const got = sender.inbox.filter((m) => m.type === 'control-input').length;
  ok('高频输入被限流（收到 < 300）', got > 0 && got < 300, { got });

  // --- 控制者退出 ---
  sender.clear();
  r1.send({ type: 'control-end' });
  const ended = await sender.wait((m) => m.type === 'control-end');
  ok('退出控制通知主播', ended.cid === r1.cid, ended);
  const st3 = await r2.wait((m) => m.type === 'control-state' && m.active === false);
  ok('房间控制状态归零', st3.active === false, st3);

  // --- 主播强制收回 ---
  r1.clear(); sender.clear();
  r1.send({ type: 'control-request' });
  await sender.wait((m) => m.type === 'control-request');
  sender.send({ type: 'control-response', to: r1.cid, allow: true });
  await r1.wait((m) => m.type === 'control-granted');
  sender.send({ type: 'control-end' });
  const forced = await r1.wait((m) => m.type === 'control-end');
  ok('主播可强制收回', !!forced);

  // --- 断线回收 ---
  r1.clear();
  sender.clear();
  r1.send({ type: 'control-request' });
  await sender.wait((m) => m.type === 'control-request');
  sender.send({ type: 'control-response', to: r1.cid, allow: true });
  await r1.wait((m) => m.type === 'control-granted');
  sender.clear();
  r1.close();
  const gone = await sender.wait((m) => m.type === 'control-end', 3000);
  ok('控制者断线自动收回', /断开/.test(gone.reason), gone);

  // --- 未登录不能当发送端 ---
  const bad = new WebSocket(WS);
  const badResult = await new Promise((resolve) => {
    bad.on('open', () => bad.send(JSON.stringify({ type: 'join', room: room + 'x', role: 'sender' })));
    bad.on('message', (d, bin) => {
      if (bin) return;
      const m = JSON.parse(d.toString());
      if (m.type === 'error') resolve(m);
    });
    setTimeout(() => resolve(null), 2000);
  });
  ok('无token不能开播', badResult && badResult.code === 'AUTH_REQUIRED', badResult);
  bad.close();

  sender.close(); r2.close();

  // 清理
  await query('DELETE FROM sessions WHERE user_id = $1', [id]);
  await query('DELETE FROM users WHERE id = $1', [id]);

  console.log(`\n结果: ${pass} 通过 / ${fail} 失败`);
  await pool.end();
  process.exit(fail ? 1 : 0);
})().catch(async (e) => {
  console.error('测试异常:', e.message);
  try { await pool.end(); } catch (e2) {}
  process.exit(1);
});
