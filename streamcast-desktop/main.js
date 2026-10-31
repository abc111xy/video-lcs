const { app, BrowserWindow, session, desktopCapturer, shell, Menu } = require('electron');
const path = require('path');

// 单实例：重复打开时聚焦已有窗口，避免两个进程抢占同一房间
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) app.quit();

// 自动化验证用：允许注入假摄像头与调试端口（生产环境不会设置这些变量）
if (process.env.STREAMCAST_FAKE_MEDIA === '1') {
  app.commandLine.appendSwitch('use-fake-ui-for-media-stream');
  app.commandLine.appendSwitch('use-fake-device-for-media-stream');
}
if (process.env.STREAMCAST_DEBUG_PORT) {
  app.commandLine.appendSwitch('remote-debugging-port', process.env.STREAMCAST_DEBUG_PORT);
}

let win = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1040,
    height: 720,
    minWidth: 860,
    minHeight: 600,
    backgroundColor: '#0b0e14',
    title: 'StreamCast · 桌面开播',
    autoHideMenuBar: true,
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  win.loadFile(path.join(__dirname, 'app', 'index.html'));

  // 外链走系统浏览器，不在应用内开新窗口
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
}

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);

  // 摄像头权限：桌面端默认放行本应用自己的页面
  session.defaultSession.setPermissionRequestHandler((wc, permission, callback) => {
    const allowed = ['media', 'display-capture', 'audioCapture', 'videoCapture'];
    callback(allowed.includes(permission));
  });

  // 屏幕共享：选中主屏（Electron 不允许渲染进程直接枚举屏幕）
  session.defaultSession.setDisplayMediaRequestHandler((request, callback) => {
    desktopCapturer.getSources({ types: ['screen'] }).then((sources) => {
      if (!sources.length) return callback({});
      callback({ video: sources[0] });
    }).catch(() => callback({}));
  });

  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('second-instance', () => {
  if (win) {
    if (win.isMinimized()) win.restore();
    win.focus();
  }
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
