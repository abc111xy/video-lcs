/**
 * 直投邮件（Direct-to-MX SMTP）。
 *
 * 本部署环境的 SMTP 提交端口（465/587）被封，但出站 25 端口到收件方 MX 是通的，
 * 因此采用「解析收件域名的 MX 记录 → 直接投递」的方式，不需要任何第三方账号或密钥。
 *
 * 实现要点：
 *  - 按 MX 优先级依次尝试，遇到 4xx/5xx 换下一个 MX；
 *  - 对端声明 STARTTLS 时升级加密（不校验证书链，兼容部分自签 MX）；
 *  - 正文统一 base64 编码，标题用 RFC 2047 编码，避免中文乱码与 8bit 兼容问题；
 *  - 全流程带超时与明确错误码，失败不静默。
 */
const net = require('net');
const tls = require('tls');
const dns = require('dns').promises;
const crypto = require('crypto');

const CONNECT_TIMEOUT_MS = 12000;
const REPLY_TIMEOUT_MS = 20000;

class SmtpError extends Error {
  constructor(message, code, transcript) {
    super(message);
    this.code = code;
    this.transcript = transcript;
  }
}

/** 单个 SMTP 连接：支持多行应答、STARTTLS */
class Session {
  constructor(socket, transcript) {
    this.socket = socket;
    this.transcript = transcript;
    this.buffer = Buffer.alloc(0);
    this.secure = false;
    this.attach(socket);
  }

  attach(socket) {
    this.socket = socket;
    socket.setTimeout(REPLY_TIMEOUT_MS);
    socket.on('data', (chunk) => {
      this.buffer = Buffer.concat([this.buffer, chunk]);
    });
  }

  /** 读取一条完整应答（多行 250-xxx 会合并到 250 xxx） */
  readReply() {
    return new Promise((resolve, reject) => {
      const started = Date.now();
      const tick = () => {
        const text = this.buffer.toString('utf8');
        const lines = text.split('\r\n');
        // 最后一段可能不完整，保留
        for (let i = 0; i < lines.length - 1; i += 1) {
          const line = lines[i];
          if (/^\d{3} /.test(line)) {
            const consumed = lines.slice(0, i + 1).join('\r\n').length + 2;
            this.buffer = this.buffer.slice(consumed);
            resolve({ code: Number(line.slice(0, 3)), text: line });
            return;
          }
        }
        if (Date.now() - started > REPLY_TIMEOUT_MS) {
          reject(new SmtpError('等待服务器应答超时', 0, this.transcript));
          return;
        }
        setTimeout(tick, 30);
      };
      tick();
    });
  }

  async cmd(line, expect = [250]) {
    this.transcript.push(`C: ${line.length > 80 ? line.slice(0, 80) + '…' : line}`);
    this.socket.write(line + '\r\n');
    const reply = await this.readReply();
    this.transcript.push(`S: ${reply.text}`);
    if (!expect.includes(reply.code)) {
      throw new SmtpError(`SMTP ${reply.code}: ${reply.text}`, reply.code, this.transcript);
    }
    return reply;
  }

  async startTls(host) {
    this.transcript.push('C: STARTTLS');
    this.socket.write('STARTTLS\r\n');
    const reply = await this.readReply();
    this.transcript.push(`S: ${reply.text}`);
    if (reply.code !== 220) return false;
    this.buffer = Buffer.alloc(0);
    const secureSocket = await new Promise((resolve, reject) => {
      const s = tls.connect({ socket: this.socket, servername: host, rejectUnauthorized: false }, () => resolve(s));
      s.once('error', reject);
      s.setTimeout(REPLY_TIMEOUT_MS, () => reject(new Error('TLS 握手超时')));
    });
    this.attach(secureSocket);
    this.secure = true;
    return true;
  }

  close() {
    try { this.socket.destroy(); } catch (e) { /* ignore */ }
  }
}

function encodeHeaderValue(value) {
  const v = String(value || '');
  if (/^[\x20-\x7E]*$/.test(v)) return v;
  return `=?UTF-8?B?${Buffer.from(v, 'utf8').toString('base64')}?=`;
}

function base64Wrap(buf) {
  return buf.toString('base64').replace(/(.{76})/g, '$1\r\n');
}

function rfc2822Date(d = new Date()) {
  return d.toUTCString().replace('GMT', '+0000');
}

/**
 * 构造 MIME 邮件。正文全 base64，避免 8BITMIME / 行宽 / 中文编码问题。
 */
function buildMessage({ from, fromName, to, subject, text, html, messageId, extraHeaders }) {
  const boundary = 'sc_' + crypto.randomBytes(12).toString('hex');
  const headers = [
    `From: ${fromName ? `${encodeHeaderValue(fromName)} <${from}>` : `<${from}>`}`,
    `To: <${to}>`,
    `Subject: ${encodeHeaderValue(subject)}`,
    `Date: ${rfc2822Date()}`,
    `Message-ID: <${messageId}>`,
    'MIME-Version: 1.0',
    'X-Mailer: StreamCast Relay',
    ...(extraHeaders || []),
  ];

  let body;
  if (html) {
    headers.push(`Content-Type: multipart/alternative; boundary="${boundary}"`);
    body = [
      `--${boundary}`,
      'Content-Type: text/plain; charset="UTF-8"',
      'Content-Transfer-Encoding: base64',
      '',
      base64Wrap(Buffer.from(text || '', 'utf8')),
      `--${boundary}`,
      'Content-Type: text/html; charset="UTF-8"',
      'Content-Transfer-Encoding: base64',
      '',
      base64Wrap(Buffer.from(html, 'utf8')),
      `--${boundary}--`,
      '',
    ].join('\r\n');
  } else {
    headers.push('Content-Type: text/plain; charset="UTF-8"');
    headers.push('Content-Transfer-Encoding: base64');
    body = base64Wrap(Buffer.from(text || '', 'utf8')) + '\r\n';
  }
  return headers.join('\r\n') + '\r\n\r\n' + body;
}

/** 按 RFC 5321 做点填充 */
function dotStuff(message) {
  return message.replace(/(^|\r\n)\./g, '$1..');
}

async function mxHosts(domain) {
  try {
    const records = await dns.resolveMx(domain);
    if (records && records.length) {
      return records.sort((a, b) => a.priority - b.priority).map((r) => r.exchange.replace(/\.$/, ''));
    }
  } catch (e) { /* 回退到 A 记录 */ }
  return [domain];
}

async function connectHost(host, transcript) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port: 25 });
    socket.setTimeout(CONNECT_TIMEOUT_MS);
    const onError = (err) => { socket.destroy(); reject(err); };
    socket.once('error', onError);
    socket.once('timeout', () => onError(new Error('连接超时')));
    socket.once('connect', () => {
      socket.removeListener('error', onError);
      socket.setTimeout(0);
      resolve(socket);
    });
  });
}

/**
 * 投递一封邮件。返回 { ok, host, code, transcript }。
 * 失败时抛出 SmtpError（带逐条会话记录，便于排查）。
 */
async function deliver({ to, from, fromName, subject, text, html, extraHeaders, helo, timeoutMs }) {
  const domain = String(to).split('@')[1];
  if (!domain) throw new SmtpError('收件地址不合法', 0, []);
  const hosts = await mxHosts(domain);
  const messageId = `${Date.now()}.${crypto.randomBytes(8).toString('hex')}@${String(from).split('@')[1] || 'localhost'}`;
  const message = buildMessage({ from, fromName, to, subject, text, html, messageId, extraHeaders });
  const payload = dotStuff(message);

  const failures = [];
  const deadline = Date.now() + (timeoutMs || 25000);
  for (const host of hosts) {
    if (Date.now() > deadline) {
      failures.push(`${host}: 超出总耗时，跳过`);
      continue;
    }
    const transcript = [`# 目标 MX: ${host}`];
    let session = null;
    try {
      const socket = await connectHost(host, transcript);
      session = new Session(socket, transcript);
      const greeting = await session.readReply();
      transcript.push(`S: ${greeting.text}`);
      if (greeting.code !== 220) throw new SmtpError(`连接被拒绝: ${greeting.text}`, greeting.code, transcript);

      const ehloHost = helo || `mail.${String(from).split('@')[1] || 'localhost'}`;
      let ehlo = await session.cmd(`EHLO ${ehloHost}`, [250]);
      const supportsStartTls = /STARTTLS/i.test(ehlo.text) || transcript.some((l) => /STARTTLS/i.test(l));
      if (supportsStartTls) {
        const upgraded = await session.startTls(host);
        if (upgraded) {
          ehlo = await session.cmd(`EHLO ${ehloHost}`, [250]);
        }
      }

      await session.cmd(`MAIL FROM:<${from}>`, [250]);
      await session.cmd(`RCPT TO:<${to}>`, [250, 251]);
      await session.cmd('DATA', [354]);
      session.socket.write(payload + '\r\n.\r\n');
      const done = await session.readReply();
      transcript.push(`S: ${done.text}`);
      if (done.code !== 250) throw new SmtpError(`投递被拒: ${done.text}`, done.code, transcript);
      await session.cmd('QUIT', [221]).catch(() => {});
      session.close();
      return { ok: true, host, code: done.code, transcript };
    } catch (e) {
      failures.push(`${host}: ${e.message}`);
      if (session) session.close();
    }
  }
  throw new SmtpError(`所有 MX 均投递失败（${failures.join('; ')}）`, 0, failures);
}

module.exports = { deliver, SmtpError, buildMessage };
