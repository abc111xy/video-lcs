/**
 * 极简 .env 加载器（避免引入 dotenv 依赖）。
 * 仅支持 KEY=VALUE 形式，忽略注释与空行，自动去掉首尾引号。
 */
const fs = require('fs');
const path = require('path');

function loadEnv() {
  const file = path.join(__dirname, '..', '.env');
  if (!fs.existsSync(file)) return;
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (e) {
    return;
  }
  for (const line of raw.split(/\r?\n/)) {
    if (!line || /^\s*#/.test(line)) continue;
    const m = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
    if (!m) continue;
    const key = m[1];
    let value = m[2].trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    // 已存在的真实环境变量优先（方便平台注入覆盖本地文件）
    if (process.env[key] === undefined) process.env[key] = value;
  }
}

module.exports = { loadEnv };
