/**
 * Postgres 连接池 + 表结构初始化。
 * 使用 Neon（serverless Postgres），连接串从环境变量 DATABASE_URL 读取。
 * 初始化语句全部 IF NOT EXISTS，可重复执行。
 */
const { Pool } = require('pg');
const { loadEnv } = require('./env');

loadEnv();

const connectionString = process.env.DATABASE_URL || process.env.DATABASE_URL_POOLED;
if (!connectionString) {
  console.error('[db] 缺少 DATABASE_URL，账号系统不可用');
}

const pool = new Pool({
  connectionString,
  ssl: connectionString && /sslmode=require|neon\.tech/.test(connectionString)
    ? { rejectUnauthorized: false }
    : undefined,
  max: Number(process.env.PG_POOL_MAX || 5),
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 10000,
});

pool.on('error', (e) => {
  console.error('[db] 连接池错误:', e.message);
});

const SCHEMA = `
CREATE TABLE IF NOT EXISTS users (
  id              TEXT PRIMARY KEY,
  email           TEXT UNIQUE NOT NULL,
  salt            TEXT NOT NULL,
  pw_hash         TEXT NOT NULL,
  created_at      BIGINT NOT NULL,
  last_login_at   BIGINT,
  consent_version TEXT NOT NULL,
  consent_at      BIGINT NOT NULL,
  consent_ip      TEXT,
  consent_ua      TEXT
);

-- 用户资料（增量迁移，兼容已有库）
ALTER TABLE users ADD COLUMN IF NOT EXISTS nickname TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar   TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS theme    TEXT NOT NULL DEFAULT 'system';
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_at BIGINT;

CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_exp ON sessions(expires_at);

CREATE TABLE IF NOT EXISTS email_codes (
  email      TEXT PRIMARY KEY,
  code_hash  TEXT NOT NULL,
  expires_at BIGINT NOT NULL,
  sent_at    BIGINT NOT NULL,
  attempts   INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS auth_log (
  id     BIGSERIAL PRIMARY KEY,
  at     BIGINT NOT NULL,
  email  TEXT,
  action TEXT NOT NULL,
  ok     BOOLEAN NOT NULL,
  ip     TEXT
);
CREATE INDEX IF NOT EXISTS idx_auth_log_at ON auth_log(at);

CREATE TABLE IF NOT EXISTS rooms_audit (
  id         BIGSERIAL PRIMARY KEY,
  at         BIGINT NOT NULL,
  room       TEXT NOT NULL,
  user_id    TEXT,
  email      TEXT,
  is_private BOOLEAN NOT NULL DEFAULT FALSE,
  ip         TEXT
);
`;

let ready = null;

/** 初始化表结构（幂等，进程内只跑一次） */
function initSchema() {
  if (!ready) {
    ready = pool.query(SCHEMA).then(() => {
      console.log('[db] 表结构就绪');
    }).catch((e) => {
      console.error('[db] 初始化失败:', e.message);
      ready = null;
      throw e;
    });
  }
  return ready;
}

async function query(text, params) {
  await initSchema();
  return pool.query(text, params);
}

module.exports = { pool, query, initSchema };
