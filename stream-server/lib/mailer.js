/**
 * 邮件投递。
 *
 * 三种通道，按优先级自动选择：
 *  1) Resend（HTTPS，需 RESEND_API_KEY）
 *  2) AgentMail（HTTPS，需 AGENTMAIL_API_KEY + AGENTMAIL_INBOX）
 *  3) 直投（Direct-to-MX SMTP，默认启用，无需任何账号或密钥）
 *
 * 为什么直投可用：本环境封的是 SMTP 提交端口（465/587），但出站 25 端口到收件域 MX 是通的，
 * 所以直接解析收件域名 MX 并投递。若某天 25 端口也被封，可设置 MAIL_DISABLE_DIRECT=1 关闭，
 * 此时未配置 API Key 会明确返回「邮件服务未配置」，绝不伪造发送成功。
 */
const smtpDirect = require('./smtp-direct');

function provider() {
  if (process.env.RESEND_API_KEY) return 'resend';
  if (process.env.AGENTMAIL_API_KEY && process.env.AGENTMAIL_INBOX) return 'agentmail';
  if (process.env.MAIL_DISABLE_DIRECT === '1') return null;
  return 'direct';
}

function mailConfigured() {
  return provider() !== null;
}

function fromAddress() {
  return process.env.MAIL_FROM || 'no-reply@spl.app.workbuddy.host';
}

function fromName() {
  return process.env.MAIL_FROM_NAME || '流式频传输 StreamCast';
}

/** 从 "名字 <addr@x>" 形式里拆出纯地址 */
function bareAddress(value) {
  const m = /<([^>]+)>/.exec(value);
  return (m ? m[1] : value).trim();
}

async function sendViaResend({ to, subject, text, html }) {
  const res = await fetch('https://api.resend.com/emails', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${process.env.RESEND_API_KEY}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ from: fromAddress(), to: [to], subject, text, html }),
  });
  const body = await res.text();
  if (!res.ok) throw new Error(`Resend ${res.status}: ${body.slice(0, 300)}`);
  return body;
}

async function sendViaAgentMail({ to, subject, text, html }) {
  const inbox = process.env.AGENTMAIL_INBOX;
  const res = await fetch(
    `https://api.agentmail.to/v0/inboxes/${encodeURIComponent(inbox)}/messages/send`,
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${process.env.AGENTMAIL_API_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ to, subject, text, html }),
    },
  );
  const body = await res.text();
  if (!res.ok) throw new Error(`AgentMail ${res.status}: ${body.slice(0, 300)}`);
  return body;
}

async function sendDirect({ to, subject, text, html }) {
  const result = await smtpDirect.deliver({
    to,
    from: bareAddress(fromAddress()),
    fromName: fromName(),
    subject,
    text,
    html,
    helo: process.env.MAIL_HELO || (bareAddress(fromAddress()).split('@')[1] || undefined),
    timeoutMs: Number(process.env.MAIL_TIMEOUT_MS || 25000),
  });
  console.log(`[mail] 直投成功 ${to} via ${result.host} (${result.code})`);
  return result;
}

async function sendMail(msg) {
  const p = provider();
  if (!p) {
    const err = new Error('邮件服务未配置');
    err.code = 'MAIL_NOT_CONFIGURED';
    throw err;
  }
  try {
    if (p === 'resend') return await sendViaResend(msg);
    if (p === 'agentmail') return await sendViaAgentMail(msg);
    return await sendDirect(msg);
  } catch (e) {
    const detail = (e.transcript || []).slice(-6).join(' | ');
    console.error(`[mail] ${p} 发送失败 -> ${e.message}${detail ? ' || ' + detail : ''}`);
    throw e;
  }
}

/** 注册验证码邮件模板 */
function verificationMail(code) {
  const subject = `【流式频传输】邮箱验证码 ${code}`;
  const text =
    `您的注册验证码是：${code}\n\n` +
    `该验证码 10 分钟内有效，请勿转发给他人。\n` +
    `如果不是您本人操作，请忽略此邮件，您的邮箱不会被注册。\n\n` +
    `— 流式频传输 StreamCast\n` +
    `（本邮件由系统自动发出，请勿直接回复）`;

  const html = `<!doctype html><html><head><meta charset="utf-8"></head>
<body style="margin:0;padding:0;background:#f4f6f9">
  <div style="max-width:520px;margin:0 auto;padding:28px 20px;font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;color:#1b2230">
    <div style="background:#fff;border-radius:14px;padding:28px;border:1px solid #e6eaf0">
      <div style="font-size:13px;letter-spacing:.06em;color:#6b7685;text-transform:uppercase">StreamCast</div>
      <h2 style="margin:10px 0 6px;font-size:19px;font-weight:600">邮箱验证码</h2>
      <p style="margin:0 0 18px;font-size:13px;color:#6b7685">请将下面的验证码填入注册页面，10 分钟内有效。</p>
      <div style="background:#f4f7ff;border:1px solid #dbe5ff;border-radius:12px;padding:18px;text-align:center">
        <span style="font-size:32px;font-weight:700;letter-spacing:.18em;color:#2c5cf5">${code}</span>
      </div>
      <p style="margin:20px 0 0;font-size:12px;color:#8a94a3;line-height:1.7">
        如果这不是您本人的操作，请直接忽略本邮件，该邮箱不会被注册。<br>
        请勿将验证码转发给任何人，我们的工作人员不会向您索要验证码。
      </p>
    </div>
    <div style="text-align:center;font-size:11px;color:#9aa3b0;margin-top:14px">
      本邮件由系统自动发出，请勿直接回复
    </div>
  </div>
</body></html>`;

  return { subject, text, html };
}

module.exports = {
  provider,
  mailConfigured,
  sendMail,
  verificationMail,
  fromAddress,
};
