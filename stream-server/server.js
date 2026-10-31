/**
 * 流式频传输平台 · 中继后端
 * ------------------------------------------------------------
 * 协议（WebSocket /ws）：
 *   客户端 -> 服务端
 *     {"type":"join","room":"1234","role":"sender"|"receiver","name":"..."}
 *     {"type":"meta","width":1080,"height":2400,"fps":10,"quality":60}
 *     {"type":"request-keyframe"}
 *     <binary>  ← 一帧视频数据（JPEG）
 *   服务端 -> 客户端
 *     {"type":"joined","room":"1234","role":"sender","receivers":0}
 *     {"type":"peer-joined"|"peer-left","role":"sender"|"receiver"}
 *     {"type":"meta",...}         ← 给后加入的接收端补发流信息
 *     {"type":"stats","receivers":2}
 *     {"type":"error","message":"..."}
 *     <binary>  ← 转发给接收端的视频帧
 *
 * 房间内：1 个发送端 + N 个接收端。发送端的二进制帧被原样转发给同房间所有接收端。
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer, WebSocket } = require('ws');
const auth = require('./lib/auth');
const download = require('./lib/download');
const mailer = require('./lib/mailer');
const { initSchema, query } = require('./lib/db');

// 表结构初始化（失败不阻塞静态资源，但会明确报错）
initSchema().catch(() => { /* 已在 db.js 内打印 */ });

/** 取真实客户端 IP：部署在网关后，优先用 X-Forwarded-For */
function clientIp(req) {
  const xff = req.headers['x-forwarded-for'];
  if (xff) return String(xff).split(',')[0].trim();
  return req.socket.remoteAddress || '';
}

function json(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

/** 读取 JSON 请求体，限制 64KB */
function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > 256 * 1024) {
        reject(Object.assign(new Error('请求体过大'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch (e) {
        reject(Object.assign(new Error('请求体不是合法 JSON'), { status: 400 }));
      }
    });
    req.on('error', reject);
  });
}

function bearerToken(req) {
  // 注意：本部署环境的网关会注入它自己的 Authorization 头并覆盖客户端传入值，
  // 因此优先使用自定义头 X-Auth-Token，Authorization 仅作本地/直连调试兜底。
  const alt = req.headers['x-auth-token'];
  if (typeof alt === 'string' && alt.trim()) return alt.trim();
  const h = req.headers['authorization'] || '';
  const m = /^Bearer\s+(.+)$/i.exec(h);
  return m ? m[1].trim() : null;
}

const PORT = process.env.PORT || 3000;
const HOST = '0.0.0.0';

/** 静态文件类型表 */
const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.apk': 'application/vnd.android.package-archive',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
};

/** roomId -> { sender: ws|null, receivers: Set<ws>, meta: object|null, startedAt: number } */
const rooms = new Map();

/**
 * 远程控制（实验性功能）的服务端仲裁参数。
 * 服务端只做三件事：排队、授予唯一控制者、对输入限流；
 * 具体手势如何执行由发送端（被控手机）决定，服务端不解析也不存储控制内容。
 */
const CONTROL_REQUEST_TTL_MS = 45 * 1000;      // 申请多久无人处理自动失效
const CONTROL_SESSION_MAX_MS = 30 * 60 * 1000; // 单次控制最长 30 分钟
const CONTROL_INPUT_BURST = 90;                // 输入令牌桶容量（约 1.5 秒突发）
const CONTROL_INPUT_PER_SEC = 60;              // 输入速率上限，防止刷爆被控端

function getRoom(id) {
  let r = rooms.get(id);
  if (!r) {
    r = {
      id,
      sender: null,
      receivers: new Set(),
      meta: null,
      title: '',
      startedAt: Date.now(),
      turbo: false,          // 是否处于「独享加速」模式
      avgFrameBytes: 0,      // 帧大小滑动平均，用于估算缓冲预算
      droppedFrames: 0,      // 因接收端积压而丢弃的帧数
      controller: null,      // { cid, name, since } 当前唯一控制者
      pending: null,         // { cid, ws, name, since } 等待发送端处理的控制申请
    };
    rooms.set(id, r);
  }
  return r;
}

/** 连接级短 ID：接收端申请控制时用它指明「谁在申请」，不暴露任何账号信息 */
function newCid() {
  return Math.random().toString(36).slice(2, 10);
}

/** 昵称清洗：去掉控制字符与尖括号，限长，避免注入到浮窗/网页上 */
function cleanName(name, fallback) {
  const s = String(name == null ? '' : name)
    .replace(/[\u0000-\u001F\u007F<>]/g, '')
    .trim()
    .slice(0, 20);
  return s || fallback;
}

function clamp01(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return null;
  return Math.min(1, Math.max(0, n));
}

function controlAudit(roomId, cid, name, action, ip) {
  query(
    'INSERT INTO control_audit (at, room, cid, name, action, ip) VALUES ($1,$2,$3,$4,$5,$6)',
    [Date.now(), roomId, cid || null, name || null, action, ip || null],
  ).catch((e) => console.error('[audit] control_audit 写入失败:', e.message));
}

/** 输入令牌桶：超频直接丢弃，不报错（避免刷屏式错误回包） */
function allowControlInput(ws) {
  const now = Date.now();
  if (!ws.inputAt) {
    ws.inputAt = now;
    ws.inputTokens = CONTROL_INPUT_BURST;
  }
  ws.inputTokens = Math.min(
    CONTROL_INPUT_BURST,
    ws.inputTokens + ((now - ws.inputAt) / 1000) * CONTROL_INPUT_PER_SEC,
  );
  ws.inputAt = now;
  if (ws.inputTokens < 1) return false;
  ws.inputTokens -= 1;
  return true;
}

/** 把「当前是否有控制者」同步给房间内所有人（网页/App 据此禁用申请按钮） */
function broadcastControlState(room) {
  broadcast(room, {
    type: 'control-state',
    active: !!room.controller,
    name: room.controller ? room.controller.name : '',
    pending: room.pending ? room.pending.name : '',
  });
}

/** 结束控制会话（主动退出、超时、断线、直播结束都走这里） */
function endControl(room, reason) {
  const c = room.controller;
  if (!c) return false;
  room.controller = null;
  for (const r of room.receivers) {
    if (r.cid === c.cid) send(r, { type: 'control-end', reason });
  }
  send(room.sender, { type: 'control-end', reason, cid: c.cid });
  broadcastControlState(room);
  controlAudit(room.id, c.cid, c.name, 'end:' + reason, null);
  console.log(`[control] room=${room.id} 结束控制（${reason}）`);
  return true;
}

function clearPending(room, notifySender) {
  const p = room.pending;
  if (!p) return;
  room.pending = null;
  if (p.timer) clearTimeout(p.timer);
  if (notifySender) {
    send(room.sender, { type: 'control-cancelled', cid: p.cid });
  }
  if (p.ws) send(p.ws, { type: 'control-denied', reason: '控制申请已失效' });
}


// ---------------------------------------------------------------- 加速策略
/**
 * 单房间独享加速：
 *  - 同一时刻只有一个房间在直播时，把该房间视为独占资源，放宽积压预算，
 *    尽量不丢帧（保证画质与完整帧率）；
 *  - 多房间并存时收紧预算，避免某个慢速接收端把进程内存拖垮、影响所有人。
 * 预算按「时间」折算成字节，随实际码率自适应，高帧率下也不会积压出秒级延迟。
 */
const TURBO_BUFFER_SECONDS = 0.8;
const NORMAL_BUFFER_SECONDS = 0.3;
const MIN_BUFFER_BYTES = 256 * 1024;
const MAX_BUFFER_BYTES = 8 * 1024 * 1024;

function liveRoomCount() {
  let n = 0;
  for (const r of rooms.values()) {
    if (r.sender && r.sender.readyState === WebSocket.OPEN) n++;
  }
  return n;
}

/** 重新计算各房间的加速状态；房间增减、发送端上下线时调用 */
function refreshTurbo() {
  const live = liveRoomCount();
  for (const [id, r] of rooms.entries()) {
    const turbo = live === 1 && !!r.sender;
    if (r.turbo !== turbo) {
      r.turbo = turbo;
      console.log(`[mode] room=${id} ${turbo ? '独享加速' : '常规'}`);
      notifyStats(r);
    }
  }
}

/** 把一帧视频转发给房间内所有接收端，并做积压保护 */
function relayFrame(room, data) {
  // EMA 估算平均帧大小
  room.avgFrameBytes = room.avgFrameBytes
    ? room.avgFrameBytes * 0.9 + data.length * 0.1
    : data.length;

  const fps = (room.meta && room.meta.fps) || 15;
  const budgetSeconds = room.turbo ? TURBO_BUFFER_SECONDS : NORMAL_BUFFER_SECONDS;
  const limit = Math.max(
    MIN_BUFFER_BYTES,
    Math.min(MAX_BUFFER_BYTES, room.avgFrameBytes * fps * budgetSeconds)
  );

  for (const r of room.receivers) {
    if (r.readyState !== WebSocket.OPEN) continue;
    // 该接收端已经积压太多：丢掉这一帧（JPEG 每帧独立，丢弃不会花屏），
    // 用丢帧换取低延迟，避免画面越拖越慢
    if (r.bufferedAmount > limit) {
      room.droppedFrames++;
      continue;
    }
    try { r.send(data, { binary: true }); } catch (e) {}
  }
}

function send(ws, obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    try { ws.send(JSON.stringify(obj)); } catch (e) { /* ignore */ }
  }
}

function broadcast(room, obj, except) {
  const payload = JSON.stringify(obj);
  if (room.sender && room.sender !== except && room.sender.readyState === WebSocket.OPEN) {
    try { room.sender.send(payload); } catch (e) {}
  }
  for (const r of room.receivers) {
    if (r !== except && r.readyState === WebSocket.OPEN) {
      try { r.send(payload); } catch (e) {}
    }
  }
}

function notifyStats(room) {
  send(room.sender, {
    type: 'stats',
    receivers: room.receivers.size,
    turbo: !!room.turbo,
    dropped: room.droppedFrames || 0,
  });
}

function cleanupRoom(id, room) {
  if (!room.sender && room.receivers.size === 0) {
    rooms.delete(id);
    console.log(`[room] ${id} 已解散`);
  }
}

// ---------------------------------------------------------------- 账号接口
async function handleAuth(p, req, res) {
  const ip = clientIp(req);
  try {
    if (p === '/api/auth/config' && req.method === 'GET') {
      return json(res, 200, {
        mailConfigured: mailer.mailConfigured(),
        provider: mailer.provider(),
        termsVersion: auth.TERMS_VERSION,
      });
    }

    if (p === '/api/auth/send-code' && req.method === 'POST') {
      const body = await readJson(req);
      const r = await auth.sendCode(body.email, ip);
      return json(res, 200, r);
    }

    if (p === '/api/auth/register' && req.method === 'POST') {
      const body = await readJson(req);
      const r = await auth.register({
        email: body.email,
        password: body.password,
        code: body.code,
        agreeTerms: body.agreeTerms === true,
        ip,
        ua: req.headers['user-agent'],
      });
      return json(res, 200, r);
    }

    if (p === '/api/auth/login' && req.method === 'POST') {
      const body = await readJson(req);
      const r = await auth.login({ email: body.email, password: body.password, ip });
      return json(res, 200, r);
    }

    if (p === '/api/auth/me' && req.method === 'GET') {
      const user = await auth.verifyToken(bearerToken(req));
      if (!user) {
        return json(res, 401, {
          error: '未登录或登录已过期',
          // 诊断用：帮助判断是「令牌无效」还是「请求头被网关剥离」
          debug: {
            hasAuthorization: !!req.headers['authorization'],
            hasXAuthToken: !!req.headers['x-auth-token'],
          },
        });
      }
      return json(res, 200, { user: auth.publicUser(user) });
    }

    if (p === '/api/auth/profile') {
      const user = await auth.verifyToken(bearerToken(req));
      if (!user) return json(res, 401, { error: '未登录或登录已过期' });

      if (req.method === 'GET') {
        return json(res, 200, { user: auth.publicUser(user) });
      }
      if (req.method === 'PATCH' || req.method === 'POST') {
        const body = await readJson(req);
        const updated = await auth.updateProfile(user.id, {
          nickname: body.nickname,
          avatar: body.avatar,
          theme: body.theme,
        });
        return json(res, 200, { user: auth.publicUser(updated) });
      }
      return json(res, 405, { error: '方法不允许' });
    }

    if (p === '/api/auth/delete-account' && req.method === 'POST') {
      const user = await auth.verifyToken(bearerToken(req));
      if (!user) return json(res, 401, { error: '未登录或登录已过期' });
      const body = await readJson(req);
      await auth.deleteAccount(user.id, body.password, ip);
      return json(res, 200, { ok: true });
    }

    if (p === '/api/auth/logout' && req.method === 'POST') {
      await auth.revokeToken(bearerToken(req));
      return json(res, 200, { ok: true });
    }

    return json(res, 404, { error: '接口不存在' });
  } catch (e) {
    const status = e.status || 500;
    if (status >= 500) console.error('[auth]', p, e.message);
    return json(res, status, {
      // 我们自己抛出的错误（e.status 存在）保留原文，便于用户判断如何处理；
      // 只有未预期的异常才隐藏细节
      error: e.status ? e.message : '服务器开小差了，请稍后重试',
      retryAfter: e.retryAfter,
    });
  }
}

/** 下载票据校验失败时的提示页（不暴露具体文件路径） */
function sendDownloadError(res, reason) {
  const map = {
    expired: ['下载链接已过期', '链接有效期 10 分钟，请回到下载页重新获取。'],
    exhausted: ['下载链接已失效', '该链接的使用次数已达上限，请回到下载页重新获取。'],
    invalid: ['下载链接无效', '链接可能被截断或已损坏，请回到下载页重新获取。'],
    missing: ['安装包暂未发布', '该平台的版本还在打包中，请稍后再来看看。'],
  };
  const [title, desc] = map[reason] || map.invalid;
  const html = `<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title>
<style>
  body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#f6f8fb;color:#101828;
       font-family:-apple-system,"PingFang SC","Microsoft YaHei",system-ui,sans-serif;padding:24px}
  .c{max-width:420px;text-align:center;background:#fff;border:1px solid #e3e8f0;border-radius:16px;padding:36px 26px;
     box-shadow:0 18px 40px rgba(16,24,40,.07)}
  h1{font-size:20px;margin:0 0 10px}p{color:#667085;font-size:14px;line-height:1.8;margin:0 0 22px}
  a{display:inline-block;background:#2f6bf7;color:#fff;text-decoration:none;font-weight:600;font-size:14px;
    padding:11px 20px;border-radius:9px}
  @media (prefers-color-scheme:dark){
    body{background:#0b0e14;color:#e6edf6}.c{background:#141a24;border-color:#232c3b}
    p{color:#8b98ab}a{background:#2f81f7}
  }
</style>
<div class="c"><h1>${title}</h1><p>${desc}</p><a href="/">返回下载页</a></div></html>`;
  res.writeHead(403, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  res.end(html);
}

// ---------------------------------------------------------------- 维护模式
// 存在该文件即视为维护中（运行期实时判断，删除文件后下个请求即恢复，无需重启）
const MAINT_FLAG = path.join(__dirname, '.maintenance');
function maintenanceOn() {
  try { return fs.existsSync(MAINT_FLAG); } catch (e) { return false; }
}

function sendMaintenance(req, res, p) {
  if (p.startsWith('/api/')) {
    res.writeHead(503, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ error: '服务维护中，请稍后重试', maintenance: true, retryAfter: 300 }));
    return;
  }
  fs.readFile(path.join(__dirname, 'public', 'maintenance.html'), (err, data) => {
    const headers = {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store, no-cache, must-revalidate',
      'Retry-After': '300',
    };
    if (!err) headers['Content-Length'] = data.length;
    res.writeHead(503, headers);
    res.end(err ? '<!doctype html><meta charset="utf-8"><h1>服务维护中</h1>' : data);
  });
}

// ---------------------------------------------------------------- HTTP
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const p = url.pathname;

  if (maintenanceOn()) {    // 探活仍需可用，否则平台会判定进程已死
    if (p === '/health' || p === '/api/health') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ ok: true, maintenance: true, service: 'stream-relay-server', uptime: process.uptime() }));
      return;
    }
    sendMaintenance(req, res, p);
    return;
  }

  if (p === '/health' || p === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ ok: true, service: 'stream-relay-server', rooms: rooms.size, uptime: process.uptime() }));
    return;
  }

  // 桌面端（file:// 页面）需要跨域调用账号接口；
  // 接口本身靠令牌鉴权、不使用 Cookie，因此放开来源是安全的。
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Auth-Token, Authorization');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PATCH, DELETE, OPTIONS');
  res.setHeader('Access-Control-Max-Age', '86400');
  if (req.method === 'OPTIONS' && p.startsWith('/api/')) {
    res.writeHead(204);
    res.end();
    return;
  }

  if (p === '/api/rooms') {
    // 隐私模式的房间不出现在大厅列表里（仅影响列表，凭房间号仍可直接进入）
    const list = [...rooms.entries()]
      .filter(([, r]) => !r.private)
      .map(([id, r]) => ({
        room: id,
        title: r.title || '',
        online: !!r.sender,
        receivers: r.receivers.size,
        meta: r.meta,
        startedAt: r.startedAt,
        turbo: !!r.turbo,
        dropped: r.droppedFrames || 0,
        control: !!r.controller,
      }));
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ rooms: list }));
    return;
  }

  // ---------------------------------------------------------------- 下载票据
  if (p === '/api/download/ticket' && req.method === 'POST') {
    const ip = clientIp(req);
    const gate = download.allowIssue(ip);
    if (!gate.ok) {
      res.setHeader('Retry-After', String(gate.retryAfter));
      return json(res, 429, { error: '请求过于频繁，请稍后再试', retryAfter: gate.retryAfter });
    }
    let body;
    try { body = await readJson(req); } catch (e) { return json(res, 400, { error: '请求格式不正确' }); }
    const platform = String(body.platform || '');
    if (!download.PLATFORMS[platform]) return json(res, 400, { error: '未知的下载平台' });
    const st = download.statFile(platform);
    if (!st) return json(res, 404, { error: '该平台的安装包尚未发布' });

    const { token, exp, meta } = download.buildToken(platform, download.TTL_MS);
    console.log(`[download] 签发 ${platform} ip=${ip} exp=${new Date(exp).toISOString()}`);
    return json(res, 200, {
      url: `/dl/${platform}?t=${encodeURIComponent(token)}`,
      file: meta.name,
      version: meta.version,
      size: st.size,
      expiresAt: exp,
      expiresIn: Math.round((exp - Date.now()) / 1000),
    });
  }

  if (p.startsWith('/dl/') && (req.method === 'GET' || req.method === 'HEAD')) {
    const platform = p.slice(4);
    const token = url.searchParams.get('t') || '';
    const r = download.checkToken(token, platform);
    if (!r.ok) {
      return sendDownloadError(res, r.reason || 'invalid');
    }
    const fp = download.filePath(platform);
    let st;
    try { st = fs.statSync(fp); } catch (e) {
      return sendDownloadError(res, 'missing');
    }
    const ext = path.extname(fp).toLowerCase();
    const headers = {
      'Content-Type': MIME_TYPES[ext] || 'application/octet-stream',
      'Content-Length': st.size,
      'Content-Disposition': `attachment; filename="${r.meta.name}"`,
      'Cache-Control': 'no-store, no-cache, must-revalidate',
      'X-Content-Type-Options': 'nosniff',
    };
    res.writeHead(200, headers);
    if (req.method === 'HEAD') return res.end();
    fs.createReadStream(fp).pipe(res);
    return;
  }

  // ---------------------------------------------------------------- 账号
  if (p.startsWith('/api/auth/')) {
    handleAuth(p, req, res);
    return;
  }

  // 发布包不放在 public 下，此处再兜一层，防止静态路径被拼出来
  if (p.startsWith('/download/') || p.startsWith('/data/')) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('404 Not Found');
    return;
  }

  // 下载页别名：便于口头传播 /download
  if (p === '/download' || p === '/download.html') {
    res.writeHead(302, { Location: '/' });
    res.end();
    return;
  }

  // 网页接收端 / 首页
  const file = p === '/' ? 'index.html' : p.replace(/^\//, '');
  const safe = path.join(__dirname, 'public', path.normalize(file).replace(/^(\.\.[/\\])+/, ''));
  fs.readFile(safe, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404 Not Found');
      return;
    }
    const ext = path.extname(safe).toLowerCase();
    const type = MIME_TYPES[ext] || 'application/octet-stream';
    const headers = {
      'Content-Type': type,
      // 显式给出长度：否则走 chunked 编码，浏览器下载时没有进度条
      'Content-Length': data.length,
    };
    if (ext === '.apk') {
      headers['Content-Disposition'] = 'attachment; filename="StreamCast.apk"';
      // APK 更新频繁，避免浏览器/CDN 缓存旧包
      headers['Cache-Control'] = 'no-cache, must-revalidate';
    }
    res.writeHead(200, headers);
    res.end(data);
  });
});

// 降低转发延迟：关闭 Nagle 算法，帧生成后立即发出
server.on('connection', (socket) => {
  try { socket.setNoDelay(true); } catch (e) { /* ignore */ }
});

// ---------------------------------------------------------------- WebSocket
const wss = new WebSocketServer({
  server,
  path: '/ws',
  maxPayload: 16 * 1024 * 1024,
  // 维护期间直接拒绝握手，客户端会立刻收到 503 而不是卡在重连
  verifyClient: (info, cb) => {
    if (maintenanceOn()) return cb(false, 503, 'Service under maintenance');
    cb(true);
  },
});

wss.on('connection', (ws, req) => {
  ws.isAlive = true;
  ws.roomId = null;
  ws.role = null;
  ws.ip = clientIp(req);
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', async (data, isBinary) => {
    // ---- 二进制：视频帧，直接转发 ----
    // 注意：ws v8 中文本帧同样是 Buffer，必须用 isBinary 区分，不能用 Buffer.isBuffer
    if (isBinary) {
      if (ws.role !== 'sender' || !ws.roomId) return;
      const room = rooms.get(ws.roomId);
      if (!room) return;
      relayFrame(room, data);
      return;
    }

    // ---- 文本：控制信令 ----
    let msg;
    try { msg = JSON.parse(data.toString()); } catch (e) { return; }
    if (!msg || typeof msg !== 'object') return;

    switch (msg.type) {
      case 'join': {
        try {
          const roomId = String(msg.room || '').trim();
          const role = msg.role === 'sender' ? 'sender' : 'receiver';
          if (!roomId) { send(ws, { type: 'error', message: '缺少房间号' }); return; }
          if (ws.roomId) leaveRoom(ws);

          // 每次连接分配一个短 ID：控制申请/授予都以它为准，
          // 这样服务端不需要（也不应该）知道观众的具体身份
          ws.cid = newCid();
          ws.name = cleanName(msg.name, role === 'sender' ? '主播' : '观众');

          const room = getRoom(roomId);
          if (role === 'sender') {
            // 开播必须登录：既是内容治理的前提，也让每个直播可追溯到具体账号
            const user = await auth.verifyToken(msg.token);
            if (!user) {
              send(ws, {
                type: 'error',
                code: 'AUTH_REQUIRED',
                message: '请先登录后再开播',
              });
              return;
            }
            if (room.sender && room.sender.readyState === WebSocket.OPEN) {
              send(ws, { type: 'error', message: `房间 ${roomId} 已有发送端在直播` });
              return;
            }
            room.sender = ws;
            room.meta = null;
            room.startedAt = Date.now();
            room.private = msg.private === true;
            room.title = String(msg.title || '').replace(/[\u0000-\u001F\u007F]/g, '').trim().slice(0, 40);
            room.ownerId = user.id;
            room.ownerEmail = user.email;
            room.ownerName = ws.name;
            // 换主播即意味着上一轮控制授权全部失效
            if (room.controller) endControl(room, '主播更换');
            clearPending(room, false);

            auth.audit(user.email, 'broadcast_start', true, ws.ip).catch(() => {});
            query(
              `INSERT INTO rooms_audit (at, room, user_id, email, is_private, ip)
               VALUES ($1,$2,$3,$4,$5,$6)`,
              [Date.now(), roomId, user.id, user.email, room.private, ws.ip || null],
            ).catch((e) => console.error('[audit] rooms_audit 写入失败:', e.message));
          } else {
            room.receivers.add(ws);
          }
          ws.roomId = roomId;
          ws.role = role;
          console.log(`[join] room=${roomId} role=${role} 接收端=${room.receivers.size}`);
          send(ws, {
            type: 'joined',
            room: roomId,
            role,
            receivers: room.receivers.size,
            cid: ws.cid,
            // 后加入的接收端立刻知道「有没有人正在控制」
            control: room.controller ? { active: true, name: room.controller.name } : { active: false },
          });
          if (role === 'receiver' && room.meta) send(ws, { type: 'meta', ...room.meta });
          broadcast(room, { type: 'peer-joined', role }, ws);
          // 让新加入者（以及其他人）立刻拿到当前控制状态，避免误申请
          broadcastControlState(room);
          notifyStats(room);
          refreshTurbo();
        } catch (e) {
          console.error('[ws] join 失败:', e.message);
          send(ws, { type: 'error', message: '服务器处理加入请求失败' });
        }
        break;
      }
      case 'meta': {
        if (ws.role !== 'sender' || !ws.roomId) return;
        const room = rooms.get(ws.roomId);
        if (!room) return;
        const codec = msg.codec === 'hevc' ? 'hevc' : msg.codec === 'jpeg' ? 'jpeg' : 'h264';
        room.meta = {
          width: msg.width,
          height: msg.height,
          fps: msg.fps,
          quality: msg.quality,
          codec,
        };
        broadcast(room, { type: 'meta', ...room.meta }, ws);
        break;
      }

      // ---------------------------------------------------------- 远程控制（实验性）
      case 'control-request': {
        if (ws.role !== 'receiver' || !ws.roomId) return;
        const room = rooms.get(ws.roomId);
        if (!room) return;
        if (!room.sender || room.sender.readyState !== WebSocket.OPEN) {
          send(ws, { type: 'control-denied', reason: '主播不在线，无法申请控制' });
          return;
        }
        if (room.controller) {
          send(ws, {
            type: 'control-denied',
            reason: `${room.controller.name || '其他观众'}正在控制，请稍后再试`,
          });
          return;
        }
        if (room.pending) {
          send(ws, { type: 'control-denied', reason: '已有申请正在处理，请稍候' });
          return;
        }
        room.pending = { cid: ws.cid, ws, name: ws.name, since: Date.now() };
        // 超时自动失效：主播长时间不处理时，申请者不会一直卡在「等待中」
        room.pending.timer = setTimeout(() => {
          if (room.pending && room.pending.cid === ws.cid) clearPending(room, true);
        }, CONTROL_REQUEST_TTL_MS);

        send(room.sender, { type: 'control-request', from: ws.cid, name: ws.name });
        send(ws, { type: 'control-pending' });
        broadcastControlState(room);
        controlAudit(ws.roomId, ws.cid, ws.name, 'request', ws.ip);
        break;
      }

      case 'control-response': {
        if (ws.role !== 'sender' || !ws.roomId) return;
        const room = rooms.get(ws.roomId);
        if (!room) return;
        const p = room.pending;
        if (!p || p.cid !== String(msg.to || '')) {
          send(ws, { type: 'control-cancelled', cid: String(msg.to || '') });
          return;
        }
        room.pending = null;
        if (p.timer) clearTimeout(p.timer);

        if (msg.allow === true) {
          room.controller = { cid: p.cid, name: p.name, since: Date.now() };
          send(p.ws, { type: 'control-granted', name: room.ownerName || '主播' });
          controlAudit(ws.roomId, p.cid, p.name, 'grant', ws.ip);
          console.log(`[control] room=${ws.roomId} 授予 ${p.name}(${p.cid}) 控制权`);
        } else {
          const reason = cleanName(msg.reason, '主播拒绝了控制申请');
          send(p.ws, { type: 'control-denied', reason });
          controlAudit(ws.roomId, p.cid, p.name, 'deny', ws.ip);
        }
        broadcastControlState(room);
        break;
      }

      case 'control-input': {
        if (ws.role !== 'receiver' || !ws.roomId) return;
        const room = rooms.get(ws.roomId);
        if (!room || !room.controller || room.controller.cid !== ws.cid) return;
        if (!allowControlInput(ws)) return;

        const action = String(msg.action || '');
        if (action === 'tap') {
          const x = clamp01(msg.x);
          const y = clamp01(msg.y);
          if (x == null || y == null) return;
          send(room.sender, { type: 'control-input', action, x, y });
        } else if (action === 'swipe') {
          const x = clamp01(msg.x);
          const y = clamp01(msg.y);
          const x2 = clamp01(msg.x2);
          const y2 = clamp01(msg.y2);
          if (x == null || y == null || x2 == null || y2 == null) return;
          const duration = Math.min(1500, Math.max(50, Number(msg.duration) || 220));
          send(room.sender, { type: 'control-input', action, x, y, x2, y2, duration });
        } else if (action === 'key') {
          const key = String(msg.key || '');
          if (!['back', 'home', 'recents', 'notifications'].includes(key)) return;
          send(room.sender, { type: 'control-input', action, key });
        }
        // 其余动作一律丢弃：服务端不做「任意指令透传」
        break;
      }

      case 'control-end': {
        if (!ws.roomId) return;
        const room = rooms.get(ws.roomId);
        if (!room) return;
        if (ws.role === 'receiver') {
          if (room.pending && room.pending.cid === ws.cid) {
            clearPending(room, true);
            broadcastControlState(room);
          } else if (room.controller && room.controller.cid === ws.cid) {
            endControl(room, '接收端已退出控制');
          }
        } else if (ws.role === 'sender') {
          // 主播随时可以强制收回控制权
          if (room.pending) {
            clearPending(room, false);
            broadcastControlState(room);
          }
          endControl(room, '主播已收回控制权');
        }
        break;
      }

      case 'request-keyframe': {
        if (!ws.roomId) return;
        const room = rooms.get(ws.roomId);
        if (!room) return;
        send(room.sender, { type: 'request-keyframe' });
        break;
      }
      case 'ping':
        send(ws, { type: 'pong', t: msg.t });
        break;
      default:
        break;
    }
  });

  ws.on('close', () => leaveRoom(ws));
  ws.on('error', () => leaveRoom(ws));
});

function leaveRoom(ws) {
  const { roomId, role } = ws;
  if (!roomId) return;
  const room = rooms.get(roomId);
  ws.roomId = null;
  ws.role = null;
  if (!room) return;
  if (role === 'sender' && room.sender === ws) {
    // 直播结束：所有控制授权与待处理申请立即失效
    if (room.controller) endControl(room, '直播已结束');
    if (room.pending) clearPending(room, false);
    room.sender = null;
    room.meta = null;
    broadcast(room, { type: 'peer-left', role: 'sender' });
    console.log(`[left] room=${roomId} 发送端离线`);
  } else if (role === 'receiver') {
    room.receivers.delete(ws);
    // 控制者或申请者掉线，必须立刻收回/作废，不能留下「幽灵授权」
    if (room.pending && room.pending.cid === ws.cid) {
      clearPending(room, true);
      broadcastControlState(room);
    }
    if (room.controller && room.controller.cid === ws.cid) {
      endControl(room, '控制者已断开');
    }
    broadcast(room, { type: 'peer-left', role: 'receiver' }, ws);
    notifyStats(room);
    console.log(`[left] room=${roomId} 接收端离线，剩余=${room.receivers.size}`);
  }
  cleanupRoom(roomId, room);
  refreshTurbo();
}

// 心跳：清掉假死连接；顺带回收过期的控制申请与超时控制会话
const heartbeat = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) { try { ws.terminate(); } catch (e) {} return; }
    ws.isAlive = false;
    try { ws.ping(); } catch (e) {}
  });

  const now = Date.now();
  for (const room of rooms.values()) {
    if (room.pending && now - room.pending.since > CONTROL_REQUEST_TTL_MS) {
      clearPending(room, true);
      broadcastControlState(room);
    }
    if (room.controller && now - room.controller.since > CONTROL_SESSION_MAX_MS) {
      endControl(room, '控制时长已达上限');
    }
  }
}, 30000);

wss.on('close', () => clearInterval(heartbeat));

server.listen(PORT, HOST, () => {
  console.log(`流式频传输中继服务已启动: http://${HOST}:${PORT}`);
  console.log(`  接收端页面: /watch.html?room=房间号`);
  console.log(`  WebSocket : /ws`);
});
