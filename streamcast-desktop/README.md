# StreamCast Desktop

Windows / Linux 桌面开播端。摄像头或整屏采集，编码为 JPEG 帧后推给中继服务，
与 Android、网页端、iOS 端使用同一套协议。

## 开发

```bash
npm install
npm start
```

> 依赖 Electron 32。国内网络建议先设置镜像：
> `export ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/`

## 打包

```bash
npm run dist    # Windows(zip) + Linux(AppImage/tar.gz)
```

产物在 `dist/`：

| 文件 | 说明 |
|---|---|
| `StreamCast-1.0.0-win.zip` | 解压后双击 `StreamCast.exe`，免安装 |
| `StreamCast-1.0.0.AppImage` | `chmod +x` 后直接运行 |

Electron 的跨平台打包特性：在 Linux 上也能产出 Windows 包（本项目已关闭
`signAndEditExecutable` 以免依赖 wine，因此 exe 使用的是 Electron 默认图标与元信息）。

## 功能

- 采集源：摄像头 / 整屏（`getDisplayMedia`，默认选主屏）
- 分辨率 640×480 / 1280×720 / 1920×1080；帧率 10 / 15 / 24 / 30
- 画质 35–88（JPEG 质量）
- 房间号、昵称、服务器地址（默认 `https://spl.app.workbuddy.host`）
- 应用内登录（账号与网页端通用），登录态存 `localStorage`
- 实时统计：观看人数、帧率、码率、直播时长

## 结构

```
main.js      主进程：窗口、摄像头/屏幕权限、单实例
preload.js   仅暴露版本信息，不开 Node 能力给页面
app/index.html  渲染层：采集、编码、WebSocket 推流、UI
build/icon.*    图标
```

## 注意

- 未配置代码签名。Windows SmartScreen 可能提示「未知发布者」，选择「仍要运行」即可。
- Linux AppImage 的启动项带 `--no-sandbox`（electron-builder 在 root 环境下生成），
  如需完整沙箱可自行修改 `.desktop` 中的 `Exec`。
- 屏幕采集在 Wayland 下取决于桌面环境是否支持 PipeWire 屏幕共享。
