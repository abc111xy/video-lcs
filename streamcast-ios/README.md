# StreamCast iOS

iPhone 摄像头直播端。与 Android / Windows / Linux / 网页端共用同一套中继协议，
不需要服务端做任何转码。

## 构建

需要 macOS + Xcode 15 或更高版本（iOS 17.0+）：

```bash
open StreamCast.xcodeproj
```

1. 选中 **StreamCast** target → *Signing & Capabilities*，选择你的开发者 Team；
2. 如果 App 名称冲突，改一下 *Bundle Identifier*（默认 `host.workbuddy.streamcast.ios`）；
3. 连接真机（模拟器没有摄像头），⌘R 运行。

首次开播会弹出摄像头授权，必须允许，否则无法采集。

## 用法

1. 用网页端注册的账号登录（账号体系与网页、Android 完全一致）；
2. 填房间号（4-12 位数字），可选昵称；
3. 点「开始直播」，把房间号发给观众，对方在网页 `/watch.html` 输入即可观看。

## 实现要点

| 文件 | 作用 |
|---|---|
| `StreamCastApp.swift` | App 入口 |
| `ContentView.swift` | 登录、参数、预览、开播/结束 |
| `CameraCapture.swift` | `AVCaptureSession` 采集 → 抽帧 → JPEG 压缩 |
| `RelayClient.swift` | `URLSessionWebSocketTask`，join / meta / 二进制帧 / stats |

协议与其他端一致：

```
→ {"type":"join","room":"1234","role":"sender","name":"...","token":"..."}
← {"type":"joined","room":"1234","role":"sender","receivers":0}
→ {"type":"meta","width":720,"height":1280,"fps":15,"quality":65,"codec":"jpeg"}
→ <二进制 JPEG 帧>   （每帧一条 WebSocket 二进制消息）
← {"type":"stats","receivers":2}
```

服务端把二进制帧原样转发给同房间的所有接收端，因此延迟只取决于网络与 JPEG 压缩耗时。

## 已知限制

- **本项目未在 Xcode 中编译验证**（开发环境为 Linux，无法运行 Xcode）。代码按 iOS 17 API 编写，
  若你的 Xcode 版本较低，请把 `onChange(of:) { _, new in }` 改回单参数写法。
- 仅摄像头，不含屏幕共享（iOS 系统不允许普通 App 录制其他 App 的画面）。
- 无 App 图标资源，Archive 前请自行添加 `Assets.xcassets/AppIcon`。
- 上架 App Store 需要开发者账号与审核；自用可直接用免费证书安装（7 天有效期）。
