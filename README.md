# StreamCast · 流式频传输平台

把一台 Android 手机的屏幕实时传到另一台手机或浏览器：**采集 → 中继 → 播放**。自带账号体系、用户中心、隐私房间与设备音频同步。

- **发送端 / 接收端同一个 APK**（原生 Kotlin）
- **中继服务**：Node.js + WebSocket，房间式转发，可单机部署
- **网页接收端**：打开链接即可观看，支持 H.264 硬解、全屏与亮/暗主题
- **数据存储**：PostgreSQL（用户、会话、验证码、资料、开播审计）
- **邮件**：默认「直投 MX」，无需任何第三方账号

## 功能

| 模块 | 说明 |
|---|---|
| 屏幕直播 | MediaProjection → 硬件 H.264 → WebSocket → 接收端硬解 |
| 设备音频 | AudioPlaybackCapture（内录，非麦克风）→ AAC → 接收端播放 |
| 分辨率 | 270P / 480P / 720P / 1080P / 原生，屏幕旋转自动重建采集 |
| 房间 | 自定义房间号与房间名；隐私模式下不进大厅，凭房间号可进 |
| 账号 | 邮箱验证码注册、PBKDF2 口令、30 天会话 |
| 用户中心 | 头像（Base64 入库）、昵称、主题（暗/亮/跟随系统）、协议、仓库、退出、注销 |
| 传输优化 | 自适应码率、拥塞闸门、重同步、单房间独享加速 |

## 架构

```
Android 发送端                     中继服务                      接收端
┌──────────────┐            ┌──────────────────┐         ┌──────────────┐
│ MediaProjection │──H.264──▶│ 房间路由 / 背压控制 │──转发──▶│ Android 硬解 │
│ AudioPlayback  │──AAC────▶│ 独享加速 / 重同步   │         │ 网页 WebCodecs│
└──────────────┘            └──────────────────┘         └──────────────┘
         │                            │
         └──── 鉴权（登录后才可开播）───┘──── PostgreSQL（Neon）
```

### 帧协议

| 首字节 | 含义 |
|---|---|
| `0x02` | 音频帧（ADTS AAC） |
| `0x03` | 视频帧（H.264 Annex-B 访问单元） |
| `0x04` | 编码器配置（SPS/PPS） |
| `0x05` | 重同步信号：接收端丢弃旧数据，从下一个 IDR 重新开始 |
| 裸 JPEG | 兼容模式，以 `0xFFD8` 开头，不加前缀 |

## 目录

```
stream-server/            中继后端
  server.js               HTTP + WebSocket 入口
  lib/db.js               PostgreSQL 连接与建表
  lib/auth.js             注册 / 登录 / 令牌 / 验证码 / 资料 / 注销
  lib/mailer.js           邮件投递（直投 MX / Resend / AgentMail，自动择一）
  lib/smtp-direct.js      直投实现（解析 MX + STARTTLS，无需第三方账号）
  public/                 首页、网页接收端、登录、用户中心、协议
  scripts/create-user.js  管理员开户（邮件不可用时的兜底）

streamcast-android/       Android 客户端（Kotlin）
  StreamService.kt        屏幕采集 + 推流前台服务
  H264Encoder.kt / H264Player.kt   硬件编解码
  AacPlayer.kt            音频解码播放
  RelayClient.kt          WebSocket 客户端（自适应码率）
  CongestionGate.kt       拥塞闸门（防缓冲膨胀）
  FloatingWindow.kt       悬浮窗（静置自动折叠为贴边半圆）
  UserCenterActivity.kt   用户中心（头像/昵称/主题/注销）
  ThemeUtil.kt            暗色 / 亮色 / 跟随系统
```

## 快速开始

### 后端

```bash
cd stream-server
npm install
cat > .env <<'ENV'
DATABASE_URL="postgresql://user:pass@host/db?sslmode=require"
MAIL_FROM="no-reply@your-domain"
MAIL_FROM_NAME="流式频传输 StreamCast"
ENV
node server.js          # 默认 3000 端口
```

### Android

```bash
cd streamcast-android
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

### 网页接收端

```
https://你的域名/watch.html?room=房间号&auto=1
```

## 邮件投递

默认**直投**：解析收件域 MX 后直接 SMTP 投递，不需要账号或密钥（多数云环境只封 465/587，不封出站 25）。

- 若 25 也被封：`MAIL_DISABLE_DIRECT=1` 并配置 `RESEND_API_KEY`
- 内置配额（IP / 邮箱 / 全局每小时）保护出口 IP 信誉
- 无 SPF/DKIM 的自建投递可能进垃圾箱，生产环境建议接入正规发信服务

## 安全设计

- 口令 PBKDF2-SHA256 / 210,000 次迭代 / 每用户随机盐，不存明文
- 会话令牌 32 字节随机数，服务端只存 SHA-256 哈希，退出即失效
- 验证码 10 分钟有效、最多 5 次、60 秒内不可重发
- 未登录无法开播（服务端强制），开播写审计表
- 隐私模式房间不进大厅；注销需复核密码并级联删除数据
- 头像仅接受 PNG/JPEG/WebP DataURL 且限制 64KB，防 XSS 与滥用

## 许可

AGPL-3.0，见 [LICENSE](LICENSE)。

> 使用本软件向公众提供服务时，AGPL 要求向用户提供对应源代码。
