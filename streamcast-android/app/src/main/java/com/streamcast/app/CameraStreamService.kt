package com.streamcast.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 摄像头直播前台服务。
 *
 * 链路：camera2 → YUV_420_888 → I420 → MediaCodec(H.264) → Annex-B → WebSocket
 * 与屏幕直播（StreamService）复用同一套中继协议：join / meta / 0x03 视频帧，
 * 因此网页端、桌面端、安卓接收端都无需改动即可观看。
 *
 * 之所以单独一个 Service：屏幕采集需要 MediaProjection 授权，摄像头不需要，
 * 拆开可以让「只播摄像头」的用户免去每次开播都点一次系统录屏确认。
 */
class CameraStreamService : Service() {

    companion object {
        const val ACTION_START = "com.streamcast.app.action.CAMERA_START"
        const val ACTION_STOP = "com.streamcast.app.action.CAMERA_STOP"

        const val EXTRA_SERVER = "server"
        const val EXTRA_ROOM = "room"
        const val EXTRA_FPS = "fps"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_RESOLUTION = "resolution"
        const val EXTRA_FRONT = "front"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PRIVATE = "private"

        private const val CHANNEL_ID = "streamcast_camera"
        private const val NOTIFICATION_ID = 4202
        private const val STATS_INTERVAL_MS = 1000L
        private const val ABR_INTERVAL_MS = 500L

        @Volatile
        var onState: ((String) -> Unit)? = null

        @Volatile
        var onStats: ((StreamStats) -> Unit)? = null

        @Volatile
        var running: Boolean = false
            private set

        fun postState(text: String) {
            val cb = onState ?: return
            Handler(Looper.getMainLooper()).post { cb.invoke(text) }
        }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    private var camera: CameraCapture? = null
    private var encoder: YuvVideoEncoder? = null
    private var relay: RelayClient? = null
    private var adaptive: AdaptiveBitrate? = null
    private val gate = CongestionGate()

    private var width = 0
    private var height = 0
    private var fps = AppConfig.DEFAULT_CAMERA_FPS
    private var quality = AppConfig.DEFAULT_QUALITY
    private var preset = 720
    private var front = false

    private var serverUrl = ""
    private var roomCode = ""
    private var authToken = ""
    private var privateRoom = false
    private var roomTitle = ""
    private var displayName = ""

    @Volatile
    private var capturing = false

    private var startedAt = 0L
    private var statWindowBytes = 0L
    private var framesSentTotal = 0L
    @Volatile
    private var viewers = 0
    @Volatile
    private var turbo = false

    /** 队列积压时跳过的帧数，界面用于提示「网络吃紧」 */
    private var droppedLocal = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val t = HandlerThread("streamcast-camera-svc")
        t.start()
        workerThread = t
        worker = Handler(t.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything("已停止摄像头直播")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundCompat(intent)
                startStream(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(intent: Intent?) {
        roomCode = intent?.getStringExtra(EXTRA_ROOM) ?: ""
        val channel = NotificationChannel(
            CHANNEL_ID,
            "摄像头直播",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "摄像头直播进行中的常驻通知" }

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, CameraStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("流式频传输 · 摄像头直播中")
            .setContentText("房间 $roomCode")
            .setSmallIcon(R.drawable.ic_notify)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止直播", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startStream(intent: Intent?) {
        serverUrl = intent?.getStringExtra(EXTRA_SERVER) ?: AppConfig.DEFAULT_SERVER
        roomCode = intent?.getStringExtra(EXTRA_ROOM).orEmpty()
        authToken = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        privateRoom = intent?.getBooleanExtra(EXTRA_PRIVATE, false) ?: false
        roomTitle = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        fps = (intent?.getIntExtra(EXTRA_FPS, AppConfig.DEFAULT_CAMERA_FPS)
            ?: AppConfig.DEFAULT_CAMERA_FPS).coerceIn(1, 60)
        quality = (intent?.getIntExtra(EXTRA_QUALITY, AppConfig.DEFAULT_QUALITY)
            ?: AppConfig.DEFAULT_QUALITY).coerceIn(20, 95)
        preset = intent?.getIntExtra(EXTRA_RESOLUTION, 720) ?: 720
        front = intent?.getBooleanExtra(EXTRA_FRONT, false) ?: false
        val prefs = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE)
        displayName = prefs.getString(AppConfig.KEY_NICKNAME, "").orEmpty()
        // 记住本次参数，下次进直播间沿用
        prefs.edit()
            .putInt(AppConfig.KEY_FPS, fps)
            .putInt(AppConfig.KEY_QUALITY, quality)
            .putInt(AppConfig.KEY_RESOLUTION, preset)
            .apply()

        // 摄像头直播固定 H.264：JPEG 在移动网络下带宽不可接受
        val w = minOf(preset, 1920)
        val h = w * 9 / 16
        width = w - w % 2
        height = h - h % 2

        val base = AppConfig.estimateBitrate(width, height, fps, quality)
        adaptive = AdaptiveBitrate(
            minBitrate = maxOf(300_000, base / 6),
            maxBitrate = base
        )

        val enc = YuvVideoEncoder(
            android.media.MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height,
            adaptive?.currentBitrate() ?: base,
            fps,
            onConfig = { csd -> relay?.sendCodecConfig(csd, width, height, false) },
            onFrame = { au -> handleFrame(au) }
        )
        if (!enc.start()) {
            postState("启动摄像头编码失败：设备不支持 H.264 编码")
            stopEverything("启动摄像头编码失败")
            stopSelf()
            return
        }
        encoder = enc

        connectRelay()

        val cam = CameraCapture(
            this,
            worker ?: mainHandler,
            onFrame = { yuv, w, h ->
                // 相机实际输出尺寸可能与预设不同，以实际为准并通知接收端
                if (w != width || h != height) {
                    width = w
                    height = h
                    relay?.sendMeta(width, height, fps, quality, "h264")
                }
                encoder?.submit(yuv)
            },
            onError = { msg -> mainHandler.post { postState(msg) } }
        )
        camera = cam
        val targetW = (preset * 16 / 9).coerceIn(320, 1920)
        // 前置自拍是竖屏构图，取短边作为宽度基准
        cam.start(front, if (front) preset else targetW, fps)

        startedAt = SystemClock.elapsedRealtime()
        capturing = true
        running = true
        postState("摄像头直播已开始 · 房间 $roomCode")

        mainHandler.removeCallbacks(statsTask)
        mainHandler.postDelayed(statsTask, STATS_INTERVAL_MS)
        mainHandler.removeCallbacks(abrTask)
        mainHandler.postDelayed(abrTask, ABR_INTERVAL_MS)
    }

    private fun handleFrame(au: ByteArray) {
        val pending = relay?.pendingBytes() ?: 0L
        if (gate.shouldDrop(pending)) {
            droppedLocal++
            return
        }
        if (gate.consumeResync()) {
            relay?.sendResync()
            encoder?.requestKeyframe()
        }
        if (relay?.sendVideo(au, false) == true) {
            framesSentTotal++
            statWindowBytes += au.size
        }
    }

    private val abrTask = object : Runnable {
        override fun run() {
            if (!capturing) return
            val abr = adaptive ?: return
            val queue = relay?.pendingBytes() ?: 0L
            val next = abr.onSample(queue, SystemClock.elapsedRealtime())
            if (next != null) encoder?.setBitrate(next)
            mainHandler.postDelayed(this, ABR_INTERVAL_MS)
        }
    }

    private val statsTask = object : Runnable {
        override fun run() {
            if (!capturing) return
            val kbps = (statWindowBytes * 8 / 1000).toInt()
            statWindowBytes = 0
            val cb = onStats
            if (cb != null) {
                val stats = StreamStats(
                    viewers = viewers,
                    kbps = kbps,
                    frames = framesSentTotal,
                    dropped = (relay?.droppedFrames() ?: 0L) + droppedLocal,
                    turbo = turbo,
                    targetKbps = (adaptive?.currentBitrate() ?: 0) / 1000,
                    degraded = adaptive?.degraded == true
                )
                mainHandler.post { cb.invoke(stats) }
            }
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    private fun connectRelay() {
        postState("正在连接中继服务…")
        val client = RelayClient(
            serverUrl,
            roomCode,
            "sender",
            object : RelayClient.Listener {
                override fun onConnecting() = Unit

                override fun onConnected(room: String) {
                    postState("已进入房间 $room，等待观众接入")
                    relay?.sendMeta(width, height, fps, quality, "h264")
                    encoder?.requestKeyframe()
                }

                override fun onControl(type: String, json: JSONObject) {
                    when (type) {
                        "stats" -> {
                            viewers = json.optInt("receivers", 0)
                            turbo = json.optBoolean("turbo", false)
                        }
                        "request-keyframe" -> encoder?.requestKeyframe()
                        "peer-joined" -> {
                            if (json.optString("role") == "receiver") {
                                relay?.sendMeta(width, height, fps, quality, "h264")
                                encoder?.requestKeyframe()
                            }
                        }
                    }
                }

                override fun onFrame(bytes: ByteArray) = Unit

                override fun onClosed(reason: String) {
                    postState("中继连接已断开")
                }

                override fun onFailure(message: String) {
                    postState("连接失败：$message")
                }
            },
            authToken,
            privateRoom,
            roomTitle,
            displayName,
            AppConfig.MODE_CAMERA
        )
        relay = client
        client.connect()
    }

    private fun stopEverything(reason: String) {
        capturing = false
        running = false
        mainHandler.removeCallbacks(statsTask)
        mainHandler.removeCallbacks(abrTask)

        camera?.stop()
        camera = null
        encoder?.stop()
        encoder = null
        relay?.close()
        relay = null

        postState(reason)
    }

    override fun onDestroy() {
        stopEverything("摄像头直播已结束")
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        super.onDestroy()
    }
}
