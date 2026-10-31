/**
 * 时效下载票据
 * ------------------------------------------------------------
 * 目的：下载按钮不再暴露固定静态地址，改为「点一次、签一次」。
 *   - 票据 = 平台 + 过期时间 + 一次性随机串，用 HMAC-SHA256 签名；
 *   - 默认 10 分钟有效、同一票据最多 3 次（容忍断点重试），过期即失效；
 *   - 签发接口按 IP 限流，避免被脚本批量刷；
 *   - 发布包存放在 data/releases（非 public 目录），静态路由无法绕过票据。
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const RELEASE_DIR = path.join(ROOT, 'data', 'releases');
const KEY_FILE = path.join(ROOT, 'data', 'download.key');

const TTL_MS = 10 * 60 * 1000;
const MAX_USES = 3;

/** 平台清单：file 为 data/releases 下的实际文件名 */
const PLATFORMS = {
  android: { file: 'StreamCast-2.3.0.apk', name: 'StreamCast-Android-2.3.0.apk', label: 'Android', version: '2.3.0' },
  windows: { file: 'StreamCast-Windows-x64-1.0.0.zip', name: 'StreamCast-Windows-x64-1.0.0.zip', label: 'Windows', version: '1.0.0' },
  linux: { file: 'StreamCast-Linux-x86_64-1.0.0.AppImage', name: 'StreamCast-Linux-x86_64-1.0.0.AppImage', label: 'Linux', version: '1.0.0' },
  'ios-src': { file: 'StreamCast-iOS-src-1.0.0.zip', name: 'StreamCast-iOS-source-1.0.0.zip', label: 'iOS 源码', version: '1.0.0' },
};

let secret = null;

function getSecret() {
  if (secret) return secret;
  try {
    fs.mkdirSync(path.dirname(KEY_FILE), { recursive: true });
    if (fs.existsSync(KEY_FILE)) {
      const hex = fs.readFileSync(KEY_FILE, 'utf8').trim();
      if (/^[0-9a-f]{64}$/i.test(hex)) {
        secret = Buffer.from(hex, 'hex');
        return secret;
      }
    }
  } catch (e) { /* 落盘失败则退回进程内随机密钥 */ }
  secret = crypto.randomBytes(32);
  try { fs.writeFileSync(KEY_FILE, secret.toString('hex'), { mode: 0o600 }); } catch (e) { /* ignore */ }
  return secret;
}

function b64u(buf) {
  return Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function unb64u(str) {
  const s = str.replace(/-/g, '+').replace(/_/g, '/');
  return Buffer.from(s + '='.repeat((4 - (s.length % 4)) % 4), 'base64');
}

function sign(payload) {
  return b64u(crypto.createHmac('sha256', getSecret()).update(payload).digest());
}

/** 已签发票据的使用次数（仅内存，进程重启即作废，安全侧更严格） */
const issued = new Map();

function sweep(now) {
  for (const [nonce, rec] of issued) {
    if (rec.exp <= now) issued.delete(nonce);
  }
}

function buildToken(platform, ttlMs) {
  const now = Date.now();
  sweep(now);
  const meta = PLATFORMS[platform];
  const body = { f: platform, e: now + ttlMs, n: crypto.randomBytes(9).toString('hex') };
  const payload = b64u(JSON.stringify(body));
  issued.set(body.n, { exp: body.e, uses: 0 });
  return { token: payload + '.' + sign(payload), exp: body.e, meta };
}

/** 校验：签名 → 过期 → 平台匹配 → 次数；expectedPlatform 不匹配时不消耗次数 */
function checkToken(token, expectedPlatform) {
  if (typeof token !== 'string' || token.length > 400) return { ok: false, reason: 'invalid' };
  const dot = token.lastIndexOf('.');
  if (dot <= 0) return { ok: false, reason: 'invalid' };
  const payload = token.slice(0, dot);
  const mac = token.slice(dot + 1);
  const expect = sign(payload);
  if (mac.length !== expect.length || !crypto.timingSafeEqual(Buffer.from(mac), Buffer.from(expect))) {
    return { ok: false, reason: 'invalid' };
  }
  let body;
  try { body = JSON.parse(unb64u(payload).toString('utf8')); } catch (e) { return { ok: false, reason: 'invalid' }; }
  if (!body || !PLATFORMS[body.f]) return { ok: false, reason: 'invalid' };
  if (expectedPlatform && body.f !== expectedPlatform) return { ok: false, reason: 'invalid' };
  if (typeof body.e !== 'number' || body.e <= Date.now()) return { ok: false, reason: 'expired' };

  const rec = issued.get(body.n);
  // 进程重启后票据记录丢失 → 视为失效（宁可让用户重签，也不放宽校验）
  if (!rec || rec.exp !== body.e) return { ok: false, reason: 'expired' };
  if (rec.uses >= MAX_USES) return { ok: false, reason: 'exhausted' };
  rec.uses += 1;

  return { ok: true, platform: body.f, meta: PLATFORMS[body.f], remaining: MAX_USES - rec.uses };
}

function filePath(platform) {
  const meta = PLATFORMS[platform];
  if (!meta) return null;
  return path.join(RELEASE_DIR, meta.file);
}

function statFile(platform) {
  const p = filePath(platform);
  if (!p) return null;
  try {
    const st = fs.statSync(p);
    return { size: st.size, mtime: st.mtimeMs };
  } catch (e) {
    return null;
  }
}

/** 按 IP 限流：1 分钟 8 次、1 小时 40 次 */
const buckets = new Map();
const LIMIT_MIN = 8;
const LIMIT_HOUR = 40;

function allowIssue(ip) {
  const now = Date.now();
  const key = ip || 'unknown';
  let b = buckets.get(key);
  if (!b) { b = { min: 0, minStart: now, hour: 0, hourStart: now }; buckets.set(key, b); }
  if (now - b.minStart >= 60 * 1000) { b.min = 0; b.minStart = now; }
  if (now - b.hourStart >= 60 * 60 * 1000) { b.hour = 0; b.hourStart = now; }
  if (b.min >= LIMIT_MIN) return { ok: false, retryAfter: Math.ceil((b.minStart + 60000 - now) / 1000) };
  if (b.hour >= LIMIT_HOUR) return { ok: false, retryAfter: Math.ceil((b.hourStart + 3600000 - now) / 1000) };
  b.min += 1; b.hour += 1;
  return { ok: true };
}

module.exports = {
  PLATFORMS, TTL_MS, MAX_USES, RELEASE_DIR,
  buildToken, checkToken, filePath, statFile, allowIssue,
};
