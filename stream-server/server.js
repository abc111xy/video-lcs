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

function getRoom(id) {
  let r = rooms.get(id);
  if (!r) {
    r = {
      sender: null,
      receivers: new Set(),
      meta: null,
      title: '',
      startedAt: Date.now(),
      turbo: false,          // 是否处于「独享加速」模式
      avgFrameBytes: 0,      // 帧大小滑动平均，用于估算缓冲预算
      droppedFrames: 0,      // 因接收端积压而丢弃的帧数
    };
    rooms.set(id, r);
  }
  return r;
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

// ---------------------------------------------------------------- HTTP
const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const p = url.pathname;

  if (p === '/health' || p === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ ok: true, service: 'stream-relay-server', rooms: rooms.size, uptime: process.uptime() }));
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
      }));
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ rooms: list }));
    return;
  }

  // ---------------------------------------------------------------- 账号
  if (p.startsWith('/api/auth/')) {
    handleAuth(p, req, res);
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
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 16 * 1024 * 1024 });

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
          send(ws, { type: 'joined', room: roomId, role, receivers: room.receivers.size });
          if (role === 'receiver' && room.meta) send(ws, { type: 'meta', ...room.meta });
          broadcast(room, { type: 'peer-joined', role }, ws);
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
        room.meta = { width: msg.width, height: msg.height, fps: msg.fps, quality: msg.quality };
        broadcast(room, { type: 'meta', ...room.meta }, ws);
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
    room.sender = null;
    room.meta = null;
    broadcast(room, { type: 'peer-left', role: 'sender' });
    console.log(`[left] room=${roomId} 发送端离线`);
  } else if (role === 'receiver') {
    room.receivers.delete(ws);
    broadcast(room, { type: 'peer-left', role: 'receiver' }, ws);
    notifyStats(room);
    console.log(`[left] room=${roomId} 接收端离线，剩余=${room.receivers.size}`);
  }
  cleanupRoom(roomId, room);
  refreshTurbo();
}

// 心跳：清掉假死连接
const heartbeat = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) { try { ws.terminate(); } catch (e) {} return; }
    ws.isAlive = false;
    try { ws.ping(); } catch (e) {}
  });
}, 30000);

wss.on('close', () => clearInterval(heartbeat));

server.listen(PORT, HOST, () => {
  console.log(`流式频传输中继服务已启动: http://${HOST}:${PORT}`);
  console.log(`  接收端页面: /watch.html?room=房间号`);
  console.log(`  WebSocket : /ws`);
});
