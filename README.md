# StreamCast · 流式频传输平台

把手机或电脑的画面实时传给任何人：**采集 → 中继 → 播放**。自带账号体系、用户中心、隐私房间与设备音频同步，并提供 H.265 传输与远程控制两项实验性能力。

- **发送端**：Android App（摄像头 / 屏幕+内录音频）、Windows / Linux 桌面端（摄像头或整屏）、网页（仅摄像头）、iOS 工程源码
- **接收端**：Android App、任意浏览器
- **中继服务**：Node.js + WebSocket，房间式转发，可单机部署
- **网页接收端**：打开链接即可观看，支持 H.264 / H.265 解码、全屏与亮/暗主题
- **下载分发**：官网首屏即下载，链接由服务端现场签发（HMAC + 10 分钟时效 + 次数上限）
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
| H.265（实验） | HEVC 硬编硬解，同画质省 30%~50% 带宽；设备不支持时自动回退 H.264 |
| 远程控制（实验） | 主播授权后，观看端可点击/滑动其屏幕；浮窗确认、单控制者、30 分钟上限、随时收回 |
| 桌面开播 | Electron：摄像头或整屏采集 → JPEG 帧 → 中继，Windows 免安装 zip / Linux AppImage |
| 网页开播 | 浏览器 `getUserMedia` 采集摄像头，无需安装；设备切换、镜像、画质帧率可调 |
| 下载分发 | `/api/download/ticket` 签发 HMAC 票据；`/dl/:platform?t=` 校验后回源，过期/超次/跨平台一律拒绝 |

## 架构

```
Android 发送端（屏幕/音频）          中继服务                      接收端
┌──────────────────┐        ┌──────────────────┐         ┌──────────────┐
│ MediaProjection  │─H.264─▶│ 房间路由 / 背压控制 │──转发──▶│ Android 硬解 │
│ AudioPlayback    │─AAC───▶│ 独享加速 / 重同步   │         │ 网页 WebCodecs│
└──────────────────┘        └──────────────────┘         └──────────────┘
┌──────────────────┐                │
│ 桌面端 / 网页端   │──JPEG─────────┘
│ 摄像头 / 整屏     │      鉴权（登录后才可开播）→ PostgreSQL
└──────────────────┘
```

### 帧协议

| 首字节 | 含义 |
|---|---|
| `0x02` | 音频帧（ADTS AAC） |
| `0x03` | 视频帧（H.264 Annex-B 访问单元） |
| `0x04` | 编码器配置（SPS/PPS） |
| `0x05` | 重同步信号：接收端丢弃旧数据，从下一个 IDR 重新开始 |
| `0x06` | 视频帧（H.265 / HEVC Annex-B 访问单元，实验性） |
| `0x07` | H.265 编码器配置（VPS/SPS/PPS，实验性） |
| 裸 JPEG | 兼容模式，以 `0xFFD8` 开头，不加前缀 |

### 控制信令（WebSocket 文本帧，实验性）

| 方向 | 消息 | 说明 |
|---|---|---|
| 接收端 → 服务端 | `control-request` | 申请控制；服务端排队，同一时刻仅一个待处理申请 |
| 服务端 → 主播 | `control-request {from,name}` | 携带申请方 cid 与昵称，主播端浮窗弹出 |
| 主播 → 服务端 | `control-response {to,allow,reason}` | `to` 为申请方 cid |
| 接收端 → 服务端 → 主播 | `control-input {action,x,y,...}` | 仅当前控制者可发；`tap`/`swipe`/`key` |
| 任一方 | `control-end` | 退出或强制收回控制 |
| 服务端 → 房间 | `control-state {active,name}` | 让所有人知道当前是否有人控制 |

## 目录

```
stream-server/            中继后端
  server.js               HTTP + WebSocket 入口
  lib/db.js               PostgreSQL 连接与建表
  lib/auth.js             注册 / 登录 / 令牌 / 验证码 / 资料 / 注销
  lib/mailer.js           邮件投递（直投 MX / Resend / AgentMail，自动择一）
  lib/smtp-direct.js      直投实现（解析 MX + STARTTLS，无需第三方账号）
  lib/download.js         下载票据：HMAC 签名、时效、次数、按 IP 限流
  public/                 下载首页、网页开播、网页接收端、登录、维护页、协议与声明
  scripts/create-user.js  管理员开户（邮件不可用时的兜底）

streamcast-android/       Android 客户端（Kotlin）
  StreamService.kt        屏幕采集 + 推流前台服务 + 远程控制仲裁
  VideoEncoder.kt / VideoPlayer.kt 硬件编解码（H.264 / H.265）
  RemoteControlService.kt 无障碍服务：只执行手势，不读屏
  ExpDisclaimer.kt        实验性功能强制阅读弹窗
  AacPlayer.kt            音频解码播放
  RelayClient.kt          WebSocket 客户端（自适应码率）
  CongestionGate.kt       拥塞闸门（防缓冲膨胀）
  FloatingWindow.kt       悬浮窗（静置自动折叠为贴边半圆）
  UserCenterActivity.kt   用户中心（头像/昵称/主题/注销）
  ThemeUtil.kt            暗色 / 亮色 / 跟随系统

streamcast-desktop/       Windows / Linux 桌面端（Electron）
  main.js                 窗口、摄像头与屏幕共享权限、单实例
  app/index.html          采集、JPEG 编码、推流、界面

streamcast-ios/           iOS 端（SwiftUI，工程源码）
  CameraCapture.swift     AVCaptureSession 采集 → JPEG
  RelayClient.swift       URLSessionWebSocketTask 推流
```

### 下载分发（防直链盗刷）

发布包放在 `stream-server/data/releases`（**不在 public 下**，静态路由无法绕过）：

```
浏览器 ──POST /api/download/ticket {platform}──▶ 服务端
        ◀── {url:"/dl/android?t=<hmac>"} ────────  票据 10 分钟有效、最多 3 次
浏览器 ──GET /dl/android?t=...──▶ 校验签名/时效/次数/平台 ──▶ 回源文件
```

- 签名：HMAC-SHA256，密钥落在 `data/download.key`（首次启动生成）
- 限流：单 IP 每分钟 8 次、每小时 40 次，超出返回 429
- 失败提示：过期 / 超次 / 伪造分别返回 403 与对应说明页

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

两路开播入口，都在首屏：

| 入口 | 实现 | 是否需要系统授权 |
| --- | --- | --- |
| **摄像头直播** | camera2 → YUV_420_888 → MediaCodec H.264 → WebSocket | 仅需相机权限 |
| **屏幕直播** | MediaProjection → VirtualDisplay → 硬件 H.264 | 每次开播需确认录屏 + 浮窗权限 |

摄像头直播单独跑 `CameraStreamService`（前台服务类型 `camera`），与屏幕直播共用同一套中继协议（`join` / `meta` / `0x03` 视频帧），
因此网页端、桌面端、安卓接收端无需改动即可观看。预览与推流复用同一个相机会话（一次采集、两路输出），
切前后镜头会重启采集、房间号不变，观众端 1~2 秒自动恢复画面。

### 桌面端（Windows / Linux）

```bash
cd streamcast-desktop
npm install
npm start        # 开发运行
npm run dist     # 打包 zip(Windows) + AppImage(Linux)
```

### 网页接收端 / 网页开播

```
https://你的域名/watch.html?room=房间号&auto=1   # 观看
https://你的域名/broadcast.html                  # 用摄像头开播（需登录）
```

### iOS

需要 macOS + Xcode 15：打开 `streamcast-ios/StreamCast.xcodeproj`，选择签名 Team 后真机运行。

## 实验性功能

两项能力默认关闭，开启前必须阅读并同意《实验性功能免责声明》（网页 `/disclaimer.html`，App 内为强制滚动 + 勾选）。

### H.265（HEVC）

- 发送端选择「H.265 实验」后使用 `MediaCodec` HEVC 硬编；参数集随关键帧内联（`PREPEND_HEADER_TO_SYNC_FRAMES`）
- 设备无 HEVC 编码器时自动回退 H.264，再不支持则回退 JPEG，并在界面提示
- 网页端通过 WebCodecs 解码；浏览器不支持 HEVC 时明确提示改用 App 接收端

### 远程控制

```
观看端「申请控制」→ 服务端排队 → 主播浮窗变大，显示「×× 申请控制」→ 通过 / 拒绝
      → 通过后观看端点击/滑动画面 → 归一化坐标(0..1) → 主播端换算为屏幕像素
      → AccessibilityService.dispatchGesture 执行 → 任一方「退出控制」立即失效
```

安全边界：

- 无障碍服务只声明 `canPerformGestures`，**不声明** `canRetrieveWindowContent`，无法读屏
- 服务端维护唯一控制者，非控制者的输入直接丢弃；输入按 60 次/秒限流
- 单次最长 30 分钟；主播可随时收回；控制者断线、直播结束、关闭无障碍服务都会立即失效
- 申请/批准/拒绝/结束写入 `control_audit` 表，**不记录**手势内容与画面

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
- 远程控制由服务端仲裁唯一控制者，输入做白名单与限流校验，所有授权变化写审计表

## 许可

AGPL-3.0，见 [LICENSE](LICENSE)。

> 使用本软件向公众提供服务时，AGPL 要求向用户提供对应源代码。
