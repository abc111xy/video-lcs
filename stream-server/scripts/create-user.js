#!/usr/bin/env node
/**
 * 管理员开户脚本（邮箱服务未配置时的兜底手段）。
 *
 * 用法：
 *   node scripts/create-user.js <邮箱> [密码]
 *   不传密码则自动生成一个 12 位随机密码并打印。
 *
 * 该账号视为「已完成邮箱验证」，并在 consent_ua 中标记来源为管理员开通，
 * 便于日后审计区分。
 */
const crypto = require('crypto');
const { loadEnv } = require('../lib/env');
loadEnv();

const { query, initSchema, pool } = require('../lib/db');
const { hashPassword, normalizeEmail, isValidEmail, validatePassword, TERMS_VERSION } = require('../lib/auth');

function randomPassword() {
  // 去掉易混淆字符，保证同时含字母与数字
  const letters = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ';
  const digits = '23456789';
  const all = letters + digits;
  let out = '';
  for (let i = 0; i < 11; i++) out += all[crypto.randomInt(0, all.length)];
  return out + digits[crypto.randomInt(0, digits.length)];
}

(async () => {
  const rawEmail = process.argv[2];
  if (!rawEmail) {
    console.error('用法: node scripts/create-user.js <邮箱> [密码]');
    process.exit(1);
  }
  const email = normalizeEmail(rawEmail);
  if (!isValidEmail(email)) {
    console.error('邮箱格式不正确');
    process.exit(1);
  }

  const password = process.argv[3] || randomPassword();
  const pwErr = validatePassword(password);
  if (pwErr) {
    console.error('密码不符合要求：' + pwErr);
    process.exit(1);
  }

  await initSchema();

  const exists = await query('SELECT 1 FROM users WHERE email = $1', [email]);
  if (exists.rowCount > 0) {
    console.error('该邮箱已存在，未做修改');
    await pool.end();
    process.exit(3);
  }

  const id = 'u_' + crypto.randomBytes(8).toString('hex');
  const salt = crypto.randomBytes(16).toString('base64');
  const now = Date.now();

  await query(
    `INSERT INTO users (id, email, salt, pw_hash, created_at, consent_version, consent_at, consent_ip, consent_ua)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
    [id, email, salt, hashPassword(password, salt), now, TERMS_VERSION, now, null, 'admin-provisioned'],
  );

  console.log('账号已创建');
  console.log('  邮箱: ' + email);
  console.log('  密码: ' + password);
  console.log('  ID  : ' + id);
  await pool.end();
})().catch(async (e) => {
  console.error('创建失败:', e.message);
  try { await pool.end(); } catch (e2) { /* ignore */ }
  process.exit(1);
});
