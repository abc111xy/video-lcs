/**
 * 账号业务逻辑：验证码、注册、登录、会话校验、限流。
 * 全部数据落在 Postgres（表结构见 lib/db.js）。
 */
const crypto = require('crypto');
const { query } = require('./db');
const mailer = require('./mailer');

const CODE_TTL_MS = 10 * 60 * 1000;
const CODE_RESEND_MS = 60 * 1000;
const CODE_MAX_ATTEMPTS = 5;
const TOKEN_TTL_MS = 30 * 24 * 3600 * 1000;
const PBKDF2_ITERATIONS = 210000;
const TERMS_VERSION = '1.1';

// ---------------------------------------------------------------- 工具
function normalizeEmail(email) {
  return String(email || '').trim().toLowerCase();
}

function isValidEmail(email) {
  if (!email || email.length > 254) return false;
  return /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email);
}

function validatePassword(pw) {
  if (typeof pw !== 'string' || pw.length < 8 || pw.length > 64) {
    return '密码长度需为 8–64 位';
  }
  if (!/[A-Za-z]/.test(pw) || !/[0-9]/.test(pw)) {
    return '密码需同时包含字母和数字';
  }
  return null;
}

function sha256(v) {
  return crypto.createHash('sha256').update(String(v)).digest('hex');
}

function hashPassword(password, saltB64) {
  return crypto
    .pbkdf2Sync(password, Buffer.from(saltB64, 'base64'), PBKDF2_ITERATIONS, 32, 'sha256')
    .toString('base64');
}

function safeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

function httpError(status, message, extra) {
  const err = new Error(message);
  err.status = status;
  Object.assign(err, extra || {});
  return err;
}

// ---------------------------------------------------------------- 限流（内存滑动窗口）
const buckets = new Map();

function rateLimit(key, limit, windowMs) {
  const now = Date.now();
  const arr = (buckets.get(key) || []).filter((t) => now - t < windowMs);
  if (arr.length >= limit) {
    const retryAfter = Math.ceil((windowMs - (now - arr[0])) / 1000);
    throw httpError(429, '操作过于频繁，请稍后再试', { retryAfter });
  }
  arr.push(now);
  buckets.set(key, arr);
  if (buckets.size > 5000) {
    // 简单防止内存无限增长
    for (const [k, v] of buckets) {
      if (!v.length || now - v[v.length - 1] > windowMs) buckets.delete(k);
    }
  }
}

async function audit(email, action, ok, ip) {
  try {
    await query(
      'INSERT INTO auth_log (at, email, action, ok, ip) VALUES ($1,$2,$3,$4,$5)',
      [Date.now(), email || null, action, ok, ip || null],
    );
  } catch (e) {
    // 审计失败不影响主流程
    console.error('[auth] 审计写入失败:', e.message);
  }
}

function publicUser(row) {
  return {
    id: row.id,
    email: row.email,
    nickname: row.nickname || '',
    avatar: row.avatar || '',
    theme: row.theme || 'system',
    createdAt: Number(row.created_at),
    lastLoginAt: row.last_login_at ? Number(row.last_login_at) : null,
    consentVersion: row.consent_version,
  };
}

// ---------------------------------------------------------------- 资料
const NICKNAME_MAX = 20;
const AVATAR_MAX_BYTES = 64 * 1024;   // base64 解码后的上限，约 64KB
const THEMES = new Set(['dark', 'light', 'system']);

/** 去掉控制字符与首尾空白，避免把换行、零宽字符写进昵称 */
function cleanNickname(raw) {
  return String(raw || '')
    .replace(/[\u0000-\u001F\u007F\u200B-\u200F\u2028\u2029]/g, '')
    .trim();
}

function validateAvatar(dataUrl) {
  if (!dataUrl) return '';
  const m = /^data:image\/(png|jpeg|webp);base64,([A-Za-z0-9+/=]+)$/.exec(String(dataUrl));
  if (!m) {
    throw httpError(400, '头像格式不正确，仅支持 PNG / JPEG / WebP');
  }
  const bytes = Math.floor((m[2].length * 3) / 4);
  if (bytes > AVATAR_MAX_BYTES) {
    throw httpError(400, '头像过大，请压缩到 64KB 以内');
  }
  return dataUrl;
}

async function updateProfile(userId, patch) {
  const sets = [];
  const params = [];
  let i = 1;

  if (patch.nickname !== undefined) {
    const nickname = cleanNickname(patch.nickname);
    if (nickname.length > NICKNAME_MAX) {
      throw httpError(400, `昵称最多 ${NICKNAME_MAX} 个字符`);
    }
    sets.push(`nickname = $${i++}`);
    params.push(nickname || null);
  }

  if (patch.avatar !== undefined) {
    const avatar = validateAvatar(patch.avatar);
    sets.push(`avatar = $${i++}`);
    params.push(avatar || null);
  }

  if (patch.theme !== undefined) {
    const theme = String(patch.theme);
    if (!THEMES.has(theme)) throw httpError(400, '主题取值不合法');
    sets.push(`theme = $${i++}`);
    params.push(theme);
  }

  if (!sets.length) throw httpError(400, '没有需要更新的内容');

  sets.push(`updated_at = $${i++}`);
  params.push(Date.now());
  params.push(userId);

  const r = await query(
    `UPDATE users SET ${sets.join(', ')} WHERE id = $${i} RETURNING *`,
    params,
  );
  if (!r.rowCount) throw httpError(404, '用户不存在');
  return r.rows[0];
}

/**
 * 注销账号：必须复核密码，删除用户后会话与验证码随外键级联/显式清理。
 * 直播中的房间会随连接断开自动解散。
 */
async function deleteAccount(userId, password, ip) {
  const r = await query('SELECT * FROM users WHERE id = $1', [userId]);
  if (!r.rowCount) throw httpError(404, '用户不存在');
  const user = r.rows[0];
  if (!safeEqual(hashPassword(String(password || ''), user.salt), user.pw_hash)) {
    throw httpError(403, '密码不正确，无法注销账号');
  }
  await query('DELETE FROM sessions WHERE user_id = $1', [userId]);
  await query('DELETE FROM email_codes WHERE email = $1', [user.email]);
  await query('DELETE FROM users WHERE id = $1', [userId]);
  await audit(user.email, 'account_deleted', true, ip);
  return { ok: true };
}

// ---------------------------------------------------------------- 验证码
async function sendCode(rawEmail, ip) {
  const email = normalizeEmail(rawEmail);
  if (!isValidEmail(email)) throw httpError(400, '邮箱格式不正确');

  rateLimit(`code:ip:${ip}`, 10, 3600 * 1000);
  rateLimit(`code:email:${email}`, 5, 3600 * 1000);
  // 全局配额：直投方式没有服务商在中间兜底，必须自控发送总量，
  // 否则被刷会让本机出口 IP 进黑名单，导致所有用户都收不到信。
  rateLimit('code:global:hour', 300, 3600 * 1000);

  const exists = await query('SELECT 1 FROM users WHERE email = $1', [email]);
  if (exists.rowCount > 0) throw httpError(409, '该邮箱已注册，请直接登录');

  const now = Date.now();
  const prev = await query('SELECT sent_at FROM email_codes WHERE email = $1', [email]);
  if (prev.rowCount > 0) {
    const last = Number(prev.rows[0].sent_at);
    if (now - last < CODE_RESEND_MS) {
      throw httpError(429, '发送过于频繁，请稍后再试', {
        retryAfter: Math.ceil((CODE_RESEND_MS - (now - last)) / 1000),
      });
    }
  }

  if (!mailer.mailConfigured()) {
    throw httpError(503, '邮件服务尚未配置，暂时无法发送验证码');
  }

  const code = String(crypto.randomInt(100000, 1000000));
  await query(
    `INSERT INTO email_codes (email, code_hash, expires_at, sent_at, attempts)
     VALUES ($1,$2,$3,$4,0)
     ON CONFLICT (email) DO UPDATE
       SET code_hash = EXCLUDED.code_hash,
           expires_at = EXCLUDED.expires_at,
           sent_at = EXCLUDED.sent_at,
           attempts = 0`,
    [email, sha256(code), now + CODE_TTL_MS, now],
  );

  const mail = mailer.verificationMail(code);
  try {
    await mailer.sendMail({ to: email, ...mail });
  } catch (e) {
    // 发送失败要清掉验证码，避免用户拿到一个永远收不到的码
    await query('DELETE FROM email_codes WHERE email = $1', [email]);
    await audit(email, 'send_code', false, ip);
    console.error('[auth] 邮件发送失败:', e.message);
    throw httpError(502, '验证码邮件投递失败，请确认邮箱地址无误后重试');
  }

  await audit(email, 'send_code', true, ip);
  return { sent: true, ttlSeconds: Math.floor(CODE_TTL_MS / 1000) };
}

async function consumeCode(email, code) {
  const rows = await query(
    'SELECT code_hash, expires_at, attempts FROM email_codes WHERE email = $1',
    [email],
  );
  if (rows.rowCount === 0) throw httpError(400, '请先获取验证码');

  const rec = rows.rows[0];
  if (Number(rec.expires_at) <= Date.now()) {
    await query('DELETE FROM email_codes WHERE email = $1', [email]);
    throw httpError(400, '验证码已过期，请重新获取');
  }
  if (rec.attempts >= CODE_MAX_ATTEMPTS) {
    throw httpError(429, '尝试次数过多，请重新获取验证码');
  }

  await query('UPDATE email_codes SET attempts = attempts + 1 WHERE email = $1', [email]);

  if (!safeEqual(rec.code_hash, sha256(String(code || '').trim()))) {
    throw httpError(400, '验证码不正确');
  }
  await query('DELETE FROM email_codes WHERE email = $1', [email]);
}

// ---------------------------------------------------------------- 注册 / 登录
async function register({ email: rawEmail, password, code, agreeTerms, ip, ua }) {
  const email = normalizeEmail(rawEmail);
  if (!isValidEmail(email)) throw httpError(400, '邮箱格式不正确');
  const pwErr = validatePassword(password);
  if (pwErr) throw httpError(400, pwErr);
  if (!agreeTerms) throw httpError(400, '请先阅读并同意《用户协议》与《隐私政策》');

  rateLimit(`register:ip:${ip}`, 20, 3600 * 1000);

  // 先查重再消费验证码：否则重复注册会提示「请先获取验证码」，误导用户
  const exists = await query('SELECT 1 FROM users WHERE email = $1', [email]);
  if (exists.rowCount > 0) throw httpError(409, '该邮箱已注册，请直接登录');

  await consumeCode(email, code);

  const id = 'u_' + crypto.randomBytes(8).toString('hex');
  const salt = crypto.randomBytes(16).toString('base64');
  const pwHash = hashPassword(password, salt);
  const now = Date.now();

  await query(
    `INSERT INTO users (id, email, salt, pw_hash, created_at, consent_version, consent_at, consent_ip, consent_ua)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
    [id, email, salt, pwHash, now, TERMS_VERSION, now, ip || null, (ua || '').slice(0, 200)],
  );

  const token = await issueToken(id);
  await audit(email, 'register', true, ip);
  return { token: token.token, expiresAt: token.expiresAt, user: { id, email, createdAt: now, consentVersion: TERMS_VERSION } };
}

async function login({ email: rawEmail, password, ip }) {
  const email = normalizeEmail(rawEmail);
  if (!isValidEmail(email)) throw httpError(400, '邮箱格式不正确');

  rateLimit(`login:ip:${ip}`, 30, 15 * 60 * 1000);
  rateLimit(`login:email:${email}`, 8, 15 * 60 * 1000);

  const rows = await query('SELECT * FROM users WHERE email = $1', [email]);
  if (rows.rowCount === 0) {
    await audit(email, 'login', false, ip);
    // 不区分「邮箱不存在」与「密码错误」，避免账号枚举
    throw httpError(401, '邮箱或密码不正确');
  }
  const user = rows.rows[0];
  if (!safeEqual(hashPassword(password, user.salt), user.pw_hash)) {
    await audit(email, 'login', false, ip);
    throw httpError(401, '邮箱或密码不正确');
  }

  await query('UPDATE users SET last_login_at = $1 WHERE id = $2', [Date.now(), user.id]);
  const token = await issueToken(user.id);
  await audit(email, 'login', true, ip);
  return { token: token.token, expiresAt: token.expiresAt, user: publicUser(user) };
}

async function issueToken(userId) {
  const token = crypto.randomBytes(32).toString('hex');
  const now = Date.now();
  const expiresAt = now + TOKEN_TTL_MS;
  await query('DELETE FROM sessions WHERE expires_at <= $1', [now]);
  await query(
    'INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES ($1,$2,$3,$4)',
    [sha256(token), userId, now, expiresAt],
  );
  return { token, expiresAt };
}

async function verifyToken(token) {
  if (!token || typeof token !== 'string' || token.length < 32) return null;
  const rows = await query(
    `SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id
     WHERE s.token_hash = $1 AND s.expires_at > $2`,
    [sha256(token), Date.now()],
  );
  if (rows.rowCount === 0) return null;
  return rows.rows[0];
}

async function revokeToken(token) {
  if (!token) return;
  await query('DELETE FROM sessions WHERE token_hash = $1', [sha256(token)]);
}

async function countUsers() {
  const r = await query('SELECT COUNT(*)::int AS n FROM users');
  return r.rows[0].n;
}

module.exports = {
  TERMS_VERSION,
  normalizeEmail,
  isValidEmail,
  validatePassword,
  hashPassword,
  sendCode,
  register,
  login,
  verifyToken,
  revokeToken,
  publicUser,
  updateProfile,
  deleteAccount,
  countUsers,
  audit,
};
