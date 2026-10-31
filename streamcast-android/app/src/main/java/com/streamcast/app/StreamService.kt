package com.streamcast.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 直播实时统计，供界面展示。
 */
data class StreamStats(
    val viewers: Int,
    val kbps: Int,
    val frames: Long,
    val dropped: Long,
    val turbo: Boolean,
    /** 当前自适应目标码率（kbps），0 表示未启用 */
    val targetKbps: Int,
    /** 是否已因拥塞降档 */
    val degraded: Boolean
)

/**
 * 屏幕直播前台服务：
 *   MediaProjection 抓屏 → VirtualDisplay + ImageReader → 缩放到目标分辨率
 *   → JPEG 压缩 → WebSocket 推送到中继服务 → 其他端拉流播放
 *
 * 同时负责维护悬浮窗状态控制条。
 */
class StreamService : Service() {

    companion object {
        const val ACTION_START = "com.streamcast.app.action.START"
        const val ACTION_STOP = "com.streamcast.app.action.STOP"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_SERVER = "server"
        const val EXTRA_ROOM = "room"
        const val EXTRA_FPS = "fps"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_RESOLUTION = "resolution"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_ENCODER = "encoder"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_PRIVATE = "private"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REMOTE_CONTROL = "remoteControl"

        private const val MODE_H264 = 1
        private const val MODE_JPEG = 2

        /** 自适应码率的采样周期 */
        private const val ABR_INTERVAL_MS = 500L

        /** 统计发布周期 */
        private const val STATS_INTERVAL_MS = 1000L

        private const val CHANNEL_ID = "streamcast_live"
        private const val NOTIFICATION_ID = 4201

        /** 状态回调：同进程内 Activity 直接订阅 */
        @Volatile
        var onState: ((String) -> Unit)? = null

        /** (观众数, 实时码率 kbps, 已发送帧数, 丢帧数, 是否独享加速) */
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

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var relay: RelayClient? = null
    private var floating: FloatingWindow? = null

    private var captureWidth = 0
    private var captureHeight = 0
    private var frameIntervalMs = 125L
    private var jpegQuality = 50

    /** 分辨率预设（短边像素，0 表示手机原生） */
    private var resolutionPreset = AppConfig.DEFAULT_RESOLUTION

    // ---- 视频编码：默认硬件 H.264，可选实验性 H.265，失败逐级回退 ----
    private var useH264 = true
    /** 实验性：使用 H.265（HEVC）硬件编码 */
    private var hevcMode = false
    private var captureMode = MODE_JPEG
    private var encoder: VideoEncoder? = null
    private var encoderSurface: Surface? = null
    private var h264FramesSent = 0L

    /** 自适应码率控制器（H.264 模式下启用） */
    private var adaptive: AdaptiveBitrate? = null

    /** 拥塞跳帧闸门：积压过大时跳过帧，避免延迟越积越高 */
    private val gate = CongestionGate()

    /** 每秒发布一次统计（与编码模式无关，不依赖取帧回调） */
    private val statsTask = object : Runnable {
        override fun run() {
            if (!capturing) return
            publishStats()
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    private var displayManager: DisplayManager? = null

    // ---- 设备音频内录（AudioPlaybackCapture，非麦克风） ----
    private var audioEnabled = false
    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var audioThread: Thread? = null

    @Volatile
    private var audioRunning = false
    private var audioFramesSent = 0L

    private var serverUrl = ""
    private var roomCode = ""
    private var authToken = ""
    private var privateRoom = false
    private var roomTitle = ""

    // ---- 远程控制（实验性功能） ----
    private var remoteControlEnabled = false
    @Volatile
    private var pendingCid: String? = null
    @Volatile
    private var activeCid: String? = null
    @Volatile
    private var activeName: String? = null
    private var displayName = ""

    @Volatile
    private var capturing = false

    private var lastFrameAt = 0L
    private var lastStatAt = 0L
    private var startedAt = 0L
    private var statWindowBytes = 0L
    private var bytesSentTotal = 0L
    private var framesSentTotal = 0L
    @Volatile
    private var viewers = 0

    /** 中继是否把本房间置为「独享加速」 */
    @Volatile
    private var turboMode = false
    private var currentFps = 8

    private var paddedBitmap: Bitmap? = null
    private val jpegBuffer = ByteArrayOutputStream(256 * 1024)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("streamcast-capture")
        thread.start()
        workerThread = thread
        worker = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything("用户已停止直播")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 必须尽快进入前台（5 秒内），否则系统会抛异常
                startForegroundCompat(intent)
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
                }
                startProjection(resultCode, resultData, intent)
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ 前台通知
    private fun startForegroundCompat(intent: Intent?) {
        roomCode = intent?.getStringExtra(EXTRA_ROOM) ?: ""
        val channel = NotificationChannel(
            CHANNEL_ID,
            "屏幕直播",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "屏幕直播进行中的常驻通知" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("流式频传输 · 直播中")
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------ 启动采集
    private fun startProjection(resultCode: Int, resultData: Intent?, intent: Intent?) {
        serverUrl = intent?.getStringExtra(EXTRA_SERVER) ?: AppConfig.DEFAULT_SERVER
        roomCode = intent?.getStringExtra(EXTRA_ROOM) ?: roomCode
        authToken = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        privateRoom = intent?.getBooleanExtra(EXTRA_PRIVATE, false) ?: false
        roomTitle = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        currentFps = (intent?.getIntExtra(EXTRA_FPS, AppConfig.DEFAULT_FPS) ?: AppConfig.DEFAULT_FPS)
            .coerceIn(1, AppConfig.MAX_FPS)
        jpegQuality = (intent?.getIntExtra(EXTRA_QUALITY, AppConfig.DEFAULT_QUALITY)
            ?: AppConfig.DEFAULT_QUALITY).coerceIn(20, 95)
        resolutionPreset = intent?.getIntExtra(EXTRA_RESOLUTION, AppConfig.DEFAULT_RESOLUTION)
            ?: AppConfig.DEFAULT_RESOLUTION
        audioEnabled = intent?.getBooleanExtra(EXTRA_AUDIO, AppConfig.DEFAULT_AUDIO)
            ?: AppConfig.DEFAULT_AUDIO
        val encMode = intent?.getStringExtra(EXTRA_ENCODER) ?: AppConfig.ENCODER_H264
        useH264 = encMode != AppConfig.ENCODER_JPEG
        hevcMode = encMode == AppConfig.ENCODER_H265
        remoteControlEnabled = intent?.getBooleanExtra(EXTRA_REMOTE_CONTROL, false) ?: false
        displayName = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE)
            .getString(AppConfig.KEY_NICKNAME, "").orEmpty()
        // 60fps 对应 16ms 间隔；不设更小值，避免把采集线程压到无意义的忙等
        frameIntervalMs = (1000L / currentFps).coerceAtLeast(16L)

        if (resultData == null || resultCode == 0) {
            postState("屏幕采集授权失败，请重新发起直播")
            stopEverything("屏幕采集授权失败")
            stopSelf()
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mpm.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            postState("无法创建屏幕采集会话（系统拒绝）")
            stopEverything("无法创建屏幕采集会话")
            stopSelf()
            return
        }
        projection = mp

        // API 34 起要求在创建 VirtualDisplay 之前注册回调
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    if (capturing) {
                        stopEverything("系统已结束屏幕共享")
                        stopSelf()
                    }
                }
            }
        }, worker)

        prepareCapture()
        connectRelay()
        showFloatingWindow()

        startedAt = SystemClock.elapsedRealtime()
        capturing = true
        running = true
        postState("直播已开始 · 房间 $roomCode")

        // 统计独立计时，不依赖取帧回调（H.264 模式没有 ImageReader）
        mainHandler.removeCallbacks(statsTask)
        mainHandler.postDelayed(statsTask, STATS_INTERVAL_MS)

        if (audioEnabled) startAudioCapture()
    }

    // ------------------------------------------------------------ 设备音频内录
    /**
     * 开启设备音频同步。使用 AudioPlaybackCaptureConfiguration 抓取「系统播放的声音」，
     * 不使用麦克风。需要 Android 10（API 29）及以上。
     */
    private fun startAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            postState("系统版本低于 Android 10，无法内录设备音频")
            return
        }
        val mp = projection
        if (mp == null) {
            postState("音频开启失败：屏幕采集会话不可用")
            return
        }
        try {
            startAudioCaptureInternal(mp)
        } catch (e: Exception) {
            postState("音频开启失败：${e.message ?: e.javaClass.simpleName}")
            stopAudioCapture()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startAudioCaptureInternal(mp: MediaProjection) {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AppConfig.AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            AppConfig.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            postState("音频开启失败：设备不支持该音频参数")
            return
        }

        val record = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuffer * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            postState("音频开启失败：无法初始化内录（可能未授予录音权限）")
            return
        }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AppConfig.AUDIO_SAMPLE_RATE,
            AppConfig.AUDIO_CHANNELS
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AppConfig.AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        audioRecord = record
        audioEncoder = encoder
        audioFramesSent = 0
        audioRunning = true

        val thread = Thread({ audioLoop(record, encoder) }, "streamcast-audio")
        audioThread = thread
        thread.start()

        postState("已开启设备音频同步")
    }

    private fun audioLoop(record: AudioRecord, encoder: MediaCodec) {
        val pcm = ByteArray(4096)
        val info = MediaCodec.BufferInfo()
        try {
            record.startRecording()
            while (audioRunning) {
                val read = record.read(pcm, 0, pcm.size)
                if (read > 0) {
                    val inIndex = encoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = encoder.getInputBuffer(inIndex)
                        if (buffer != null) {
                            buffer.clear()
                            buffer.put(pcm, 0, read)
                            encoder.queueInputBuffer(inIndex, 0, read, 0, 0)
                        }
                    }
                }
                drainAudioEncoder(encoder, info)
            }
        } catch (e: Exception) {
            // 采集过程中失败（例如系统强制停止）时静默退出，不影响视频直播
        } finally {
            try {
                record.stop()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /** 取出编码后的 AAC 裸数据并封装为 ADTS 帧发出 */
    private fun drainAudioEncoder(encoder: MediaCodec, info: MediaCodec.BufferInfo) {
        while (audioRunning) {
            val outIndex = try {
                encoder.dequeueOutputBuffer(info, 0)
            } catch (e: Exception) {
                return
            }
            if (outIndex < 0) return
            try {
                // 跳过编解码器配置帧，只发真正的音频数据帧
                if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val buffer = encoder.getOutputBuffer(outIndex)
                    if (buffer != null) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val aac = ByteArray(info.size)
                        buffer.get(aac)
                        relay?.sendAudioFrame(buildAdtsFrame(aac))
                        audioFramesSent++
                    }
                }
            } catch (e: Exception) {
                // 单帧失败忽略
            } finally {
                try {
                    encoder.releaseOutputBuffer(outIndex, false)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    /**
     * 为 AAC 裸数据帧补上 7 字节 ADTS 头。
     * 采用 ADTS 而非裸 AAC，是为了让接收端无需额外传输编解码配置即可自描述解码
     * （中继服务只转发二进制帧，不转发自定义信令）。
     */
    private fun buildAdtsFrame(aac: ByteArray): ByteArray {
        val frameLength = aac.size + 7
        val out = ByteArray(frameLength)
        val profile = 1 // AAC LC：audioObjectType(2) - 1
        val sfIndex = 4 // 44100Hz
        val channels = AppConfig.AUDIO_CHANNELS

        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte() // sync + MPEG-4 + layer 0 + protection absent
        out[2] = ((profile shl 6) or (sfIndex shl 2) or ((channels shr 2) and 0x01)).toByte()
        out[3] = (((channels and 0x03) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
        out[4] = ((frameLength shr 3) and 0xFF).toByte()
        out[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
        System.arraycopy(aac, 0, out, 7, aac.size)
        return out
    }

    private fun stopAudioCapture() {
        audioRunning = false
        try {
            audioThread?.join(300)
        } catch (e: Exception) {
            // ignore
        }
        audioThread = null

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioRecord = null

        try {
            audioEncoder?.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            audioEncoder?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioEncoder = null
    }

    private fun prepareCapture() {
        val (screenW, screenH) = screenSize()
        val (w, h) = computeTargetSize(screenW, screenH)
        captureWidth = w
        captureHeight = h

        // 码率上限按分辨率/帧率/画质估算；下限保证缩到最低时仍有可用画质
        val baseBitrate = AppConfig.estimateBitrate(w, h, currentFps, jpegQuality)
        adaptive = AdaptiveBitrate(
            minBitrate = maxOf(400_000, baseBitrate / 6),
            maxBitrate = baseBitrate
        )

        // 优先硬件 H.264（CPU 与带宽都低一个数量级）；初始化失败则自动回退 JPEG
        if (useH264 && startHardwareCapture(w, h)) {
            registerDisplayListener()
            startH264Watchdog()
            startAbrTicker()
            return
        }
        useH264 = false
        startJpegCapture(w, h)
        registerDisplayListener()
    }

    /**
     * 硬件编码：VirtualDisplay 直接输出到编码器输入 Surface，全程不经过 Bitmap。
     * H.265 为实验性功能，设备不支持时自动回退 H.264，再不行回退 JPEG。
     */
    private fun startHardwareCapture(width: Int, height: Int): Boolean {
        if (tryStartHardware(width, height, hevcMode)) return true
        if (hevcMode) {
            hevcMode = false
            postState("本机不支持 H.265 硬件编码，已回退 H.264")
            if (tryStartHardware(width, height, false)) return true
        }
        return false
    }

    private fun tryStartHardware(width: Int, height: Int, hevc: Boolean): Boolean {
        var enc: VideoEncoder? = null
        return try {
            enc = buildEncoder(width, height, hevc)
            val surface = enc.start()
            val vd = projection?.createVirtualDisplay(
                "streamcast-capture",
                width,
                height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                worker
            )
            if (vd == null) {
                enc.stop()
                return false
            }
            encoder = enc
            encoderSurface = surface
            virtualDisplay = vd
            captureMode = MODE_H264
            true
        } catch (e: Exception) {
            try {
                enc?.stop()
            } catch (ignore: Exception) {
                // ignore
            }
            encoder = null
            encoderSurface = null
            false
        }
    }

    /** JPEG 兼容模式：ImageReader 取帧后逐帧压缩 */
    private fun startJpegCapture(width: Int, height: Int) {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> handleImage(r) }, worker)
        val vd = projection?.createVirtualDisplay(
            "streamcast-capture",
            width,
            height,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            worker
        )
        imageReader = reader
        virtualDisplay = vd
        captureMode = MODE_JPEG
    }

    private fun buildEncoder(width: Int, height: Int, hevc: Boolean) = VideoEncoder(
        if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC,
        width,
        height,
        currentFps,
        adaptive?.currentBitrate()
            ?: AppConfig.estimateBitrate(width, height, currentFps, jpegQuality),
        onConfig = { csd -> relay?.sendCodecConfig(csd, width, height, hevc) },
        onFrame = { au -> handleVideoFrame(au, hevc) }
    )

    /** 当前实际编码类型，随 meta 下发给接收端 */
    private fun codecName(): String = when {
        captureMode == MODE_JPEG -> "jpeg"
        hevcMode -> "hevc"
        else -> "h264"
    }

    /**
     * H.264 帧出口：先过拥塞闸门。
     * 积压超阈值时直接丢弃，降到低水位后再发一条重同步提示并请求关键帧，
     * 让接收端一次性跳到直播边缘（而不是继续消费几十秒前的画面）。
     */
    private fun handleVideoFrame(au: ByteArray, hevc: Boolean) {
        val pending = relay?.pendingBytes() ?: 0L
        if (gate.shouldDrop(pending)) return

        if (gate.consumeResync()) {
            relay?.sendResync()
            encoder?.requestKeyframe()
        }

        if (relay?.sendVideo(au, hevc) == true) {
            h264FramesSent++
            framesSentTotal++
            bytesSentTotal += au.size
            statWindowBytes += au.size
        }
    }

    // ------------------------------------------------------------ 自适应码率
    private val abrTask = object : Runnable {
        override fun run() {
            if (!capturing) return
            val abr = adaptive ?: return
            val queue = relay?.pendingBytes() ?: 0L
            val next = abr.onSample(queue, SystemClock.elapsedRealtime())
            if (next != null && captureMode == MODE_H264) {
                encoder?.setBitrate(next)
            }
            mainHandler.postDelayed(this, ABR_INTERVAL_MS)
        }
    }

    private fun startAbrTicker() {
        mainHandler.removeCallbacks(abrTask)
        mainHandler.postDelayed(abrTask, ABR_INTERVAL_MS)
    }

    private fun stopAbrTicker() {
        mainHandler.removeCallbacks(abrTask)
    }

    /**
     * 看门狗：部分机型编码器能起来但拿不到画面（VirtualDisplay 没往 Surface 送帧），
     * 此时静默回退到 JPEG，避免用户面对一块黑屏。
     */
    private fun startH264Watchdog() {
        val baseline = h264FramesSent
        mainHandler.postDelayed({
            if (capturing && captureMode == MODE_H264 && h264FramesSent == baseline) {
                postState("硬件编码无输出，已自动切换到兼容模式")
                switchToJpeg()
            }
        }, 3000)
    }

    /** 运行中从 H.264 切到 JPEG（复用同一个 VirtualDisplay，避免 Android 14 的限制） */
    private fun switchToJpeg() {
        val vd = virtualDisplay ?: return
        val old = encoder
        encoder = null
        encoderSurface = null
        try {
            val reader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2
            )
            reader.setOnImageAvailableListener({ r -> handleImage(r) }, worker)
            vd.setSurface(reader.surface)
            vd.resize(captureWidth, captureHeight, resources.displayMetrics.densityDpi)
            imageReader = reader
            captureMode = MODE_JPEG
            useH264 = false
            hevcMode = false
            old?.stop()
            relay?.sendMeta(captureWidth, captureHeight, currentFps, jpegQuality, codecName())
        } catch (e: Exception) {
            postState("切换兼容模式失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 按分辨率预设换算目标采集尺寸（与界面预览共用同一算法）。
     */
    private fun computeTargetSize(screenW: Int, screenH: Int): Pair<Int, Int> =
        AppConfig.targetSize(screenW, screenH, resolutionPreset)

    // ------------------------------------------------------------ 屏幕方向变化
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            // 回调运行在采集线程，与 handleImage 同线程，无需额外同步
            if (displayId != android.view.Display.DEFAULT_DISPLAY) return
            if (!capturing) return
            val (screenW, screenH) = screenSize()
            val (w, h) = computeTargetSize(screenW, screenH)
            if (w == captureWidth && h == captureHeight) return
            if (!reconfigureCapture(w, h)) return
            postState("画面已适配 ${w}×${h}")
        }
    }

    private fun registerDisplayListener() {
        if (displayManager != null) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager = dm
        dm.registerDisplayListener(displayListener, worker)
    }

    private fun unregisterDisplayListener() {
        val dm = displayManager ?: return
        try {
            dm.unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
            // ignore
        }
        displayManager = null
    }

    /**
     * 屏幕旋转 / 尺寸变化时重建采集面。
     *
     * 关键点：Android 14 起一个 MediaProjection 实例只允许创建一个 VirtualDisplay，
     * 因此这里不能新建 VirtualDisplay，而是复用现有实例，通过
     * setSurface + resize 换掉输出面与尺寸。
     */
    private fun reconfigureCapture(width: Int, height: Int): Boolean =
        if (captureMode == MODE_H264) reconfigureHardware(width, height)
        else reconfigureJpeg(width, height)

    /** 旋转时重建硬件编码器，VirtualDisplay 复用（Android 14 不允许建第二个） */
    private fun reconfigureHardware(width: Int, height: Int): Boolean {
        val vd = virtualDisplay ?: return false
        val old = encoder
        var enc: VideoEncoder? = null
        return try {
            enc = buildEncoder(width, height, hevcMode)
            val surface = enc.start()
            vd.setSurface(surface)
            vd.resize(width, height, resources.displayMetrics.densityDpi)
            encoder = enc
            encoderSurface = surface
            captureWidth = width
            captureHeight = height
            old?.stop()
            relay?.sendMeta(captureWidth, captureHeight, currentFps, jpegQuality, codecName())
            true
        } catch (e: Exception) {
            try {
                enc?.stop()
            } catch (ignore: Exception) {
                // ignore
            }
            // 重建失败时退回 JPEG，至少保证还有画面
            postState("分辨率切换失败，已回退兼容模式")
            encoder = old
            switchToJpeg()
            false
        }
    }

    private fun reconfigureJpeg(width: Int, height: Int): Boolean {
        val vd = virtualDisplay ?: return false
        val old = imageReader
        val reader = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        } catch (e: Exception) {
            return false
        }

        try {
            vd.setSurface(reader.surface)
            vd.resize(width, height, resources.displayMetrics.densityDpi)
        } catch (e: Exception) {
            // 失败则回滚，保证采集面尺寸与采集器一致，不留下错位状态
            try {
                reader.close()
            } catch (ignore: Exception) {
                // ignore
            }
            if (old != null) {
                try {
                    vd.setSurface(old.surface)
                } catch (ignore: Exception) {
                    // ignore
                }
            }
            postState("画面尺寸切换失败，保持原尺寸")
            return false
        }

        reader.setOnImageAvailableListener({ r -> handleImage(r) }, worker)
        imageReader = reader
        captureWidth = width
        captureHeight = height

        // 旧的填充位图尺寸已失效
        paddedBitmap?.recycle()
        paddedBitmap = null

        try {
            old?.setOnImageAvailableListener(null, null)
            old?.close()
        } catch (e: Exception) {
            // ignore
        }

        // 通知接收端新的分辨率
        relay?.sendMeta(captureWidth, captureHeight, currentFps, jpegQuality, codecName())
        return true
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            point.x to point.y
        }
    }

    // ------------------------------------------------------------ 采集循环
    private fun handleImage(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            if (!capturing) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < frameIntervalMs) return
            lastFrameAt = now

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * captureWidth

            val paddedWidth = captureWidth + rowPadding / pixelStride
            var bmp = paddedBitmap
            if (bmp == null || bmp.width != paddedWidth || bmp.height != captureHeight) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888)
                paddedBitmap = bmp
            }
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)

            val target = if (rowPadding == 0) {
                bmp
            } else {
                Bitmap.createBitmap(bmp, 0, 0, captureWidth, captureHeight)
            }

            jpegBuffer.reset()
            target.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpegBuffer)
            if (target !== bmp) target.recycle()

            val frame = jpegBuffer.toByteArray()

            // JPEG 每帧独立，拥塞时直接丢帧即可，无需重同步
            val pending = relay?.pendingBytes() ?: 0L
            if (gate.shouldDrop(pending)) {
                gate.consumeResync()
            } else {
                val sent = relay?.sendFrame(frame) ?: false
                if (sent) {
                    framesSentTotal++
                    bytesSentTotal += frame.size
                    statWindowBytes += frame.size
                }
            }
        } catch (e: Exception) {
            // 单帧失败不影响整体直播
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun publishStats() {
        val kbps = (statWindowBytes * 8 / 1000).toInt()
        statWindowBytes = 0
        val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
        val elapsed = String.format("%02d:%02d", seconds / 60, seconds % 60)
        val dropped = relay?.droppedFrames() ?: 0L

        // 悬浮窗是 View，必须在主线程更新（本方法由采集线程按秒调用）
        // 悬浮窗只保留最关键的两项信息，避免一屏标签挡住内容；
        // 分辨率/音频/加速等细节统一在 App 主界面查看
        val title = "直播中 $elapsed"
        val sub = if (viewers > 0) "$viewers 人 · $kbps kbps" else "等待接入 · $kbps kbps"
        val active = viewers > 0
        mainHandler.post { floating?.update(title, sub, active) }

        val cb = onStats
        if (cb != null) {
            val stats = StreamStats(
                viewers = viewers,
                kbps = kbps,
                frames = framesSentTotal,
                dropped = dropped,
                turbo = turboMode,
                targetKbps = (adaptive?.currentBitrate() ?: 0) / 1000,
                degraded = adaptive?.degraded == true
            )
            mainHandler.post { cb.invoke(stats) }
        }
    }

    // ------------------------------------------------------------ 网络
    private fun connectRelay() {
        postState("正在连接中继服务…")
        val client = RelayClient(
            serverUrl,
            roomCode,
            "sender",
            object : RelayClient.Listener {
                override fun onConnecting() {
                    postState("正在连接中继服务…")
                }

                override fun onConnected(room: String) {
                    postState("已进入房间 $room，等待观众接入")
                    relay?.sendMeta(captureWidth, captureHeight, currentFps, jpegQuality, codecName())
                }

                override fun onControl(type: String, json: JSONObject) {
                    when (type) {
                        "stats" -> {
                            // 仅更新计数，实际 UI 刷新由采集线程按秒节流统一发出
                            viewers = json.optInt("receivers", 0)
                            turboMode = json.optBoolean("turbo", false)
                        }
                        "request-keyframe" -> encoder?.requestKeyframe()
                        "peer-joined" -> {
                            if (json.optString("role") == "receiver") {
                                relay?.sendMeta(
                                    captureWidth, captureHeight, currentFps, jpegQuality, codecName()
                                )
                                // 新观众进来，立刻给一个关键帧，避免等 2 秒才出画
                                encoder?.requestKeyframe()
                            }
                        }
                        // ---------------- 远程控制（实验性） ----------------
                        "control-request" -> onControlRequest(json)
                        "control-input" -> handleControlInput(json)
                        "control-end" -> {
                            val reason = json.optString("reason", "控制已结束")
                            mainHandler.post { clearControlState(reason) }
                        }
                        "control-cancelled" -> mainHandler.post {
                            pendingCid = null
                            floating?.clearControlPanel()
                        }
                    }
                }

                override fun onFrame(bytes: ByteArray) {
                    // 发送端不接收视频帧
                }

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
            displayName
        )
        relay = client
        client.connect()
    }

    // ------------------------------------------------------------ 远程控制（实验性）
    /**
     * 收到观看端的控制申请。
     *
     * 三道前置校验缺一不可：主播开启了远程控制、系统无障碍服务在位、当前没有其他控制者。
     * 任一不满足都直接替主播拒绝，避免把无法执行或会互相打架的申请弹到浮窗上。
     */
    private fun onControlRequest(json: JSONObject) {
        val cid = json.optString("from")
        val name = json.optString("name", "观众")
        if (cid.isBlank()) return

        if (!remoteControlEnabled) {
            relay?.sendControlResponse(cid, false, "主播未开启远程控制")
            return
        }
        if (!RemoteControlService.isReady()) {
            relay?.sendControlResponse(cid, false, "主播尚未授予无障碍服务权限")
            mainHandler.post { postState("收到 $name 的控制申请，但无障碍服务未开启，已自动拒绝") }
            return
        }
        if (activeCid != null) {
            relay?.sendControlResponse(cid, false, "已有其他观众在控制")
            return
        }

        pendingCid = cid
        mainHandler.post {
            floating?.showControlRequest(
                name,
                onAllow = { approveControl(cid, name) },
                onDeny = { denyControl(cid) }
            )
            postState("$name 申请远程控制，请在悬浮窗处理")
        }
    }

    /** 主播点「通过」：授予控制权，浮窗切换为「退出控制」 */
    private fun approveControl(cid: String, name: String) {
        if (!RemoteControlService.isReady()) {
            relay?.sendControlResponse(cid, false, "主播尚未授予无障碍服务权限")
            pendingCid = null
            floating?.clearControlPanel()
            postState("请先在系统设置中开启无障碍服务，再通过申请")
            return
        }
        relay?.sendControlResponse(cid, true)
        activeCid = cid
        activeName = name
        pendingCid = null
        floating?.showControlActive(name) { endControlByOwner() }
        postState("$name 已获得控制权（可随时退出）")
    }

    private fun denyControl(cid: String) {
        relay?.sendControlResponse(cid, false, "主播拒绝了控制申请")
        pendingCid = null
        floating?.clearControlPanel()
        postState("已拒绝控制申请")
    }

    /** 主播主动收回控制权 */
    private fun endControlByOwner() {
        relay?.sendControlEnd()
        clearControlState("已收回控制权")
    }

    private fun clearControlState(reason: String) {
        val had = activeCid != null || pendingCid != null
        activeCid = null
        activeName = null
        pendingCid = null
        floating?.clearControlPanel()
        if (had) postState(reason)
    }

    /**
     * 执行观看端发来的手势。
     * 坐标是相对画面的 0..1 归一化值，这里按当前屏幕尺寸还原为像素；
     * 屏幕旋转后 screenSize() 立即返回新尺寸，无需额外处理。
     */
    private fun handleControlInput(json: JSONObject) {
        if (!remoteControlEnabled) return
        if (activeCid == null) return

        if (!RemoteControlService.isReady()) {
            // 用户中途关闭了无障碍服务：立即结束控制，不能让对方继续发指令
            mainHandler.post {
                relay?.sendControlEnd()
                clearControlState("无障碍服务已关闭，控制已结束")
            }
            return
        }

        val (sw, sh) = screenSize()
        when (json.optString("action")) {
            "tap" -> {
                val x = json.optDouble("x", 0.0).toFloat() * sw
                val y = json.optDouble("y", 0.0).toFloat() * sh
                RemoteControlService.tap(x, y)
            }
            "swipe" -> {
                val x1 = json.optDouble("x", 0.0).toFloat() * sw
                val y1 = json.optDouble("y", 0.0).toFloat() * sh
                val x2 = json.optDouble("x2", 0.0).toFloat() * sw
                val y2 = json.optDouble("y2", 0.0).toFloat() * sh
                val duration = json.optInt("duration", 220)
                RemoteControlService.swipe(x1, y1, x2, y2, duration)
            }
            "key" -> RemoteControlService.key(json.optString("key"))
        }
    }

    // ------------------------------------------------------------ 悬浮窗
    private fun showFloatingWindow() {
        if (floating != null) return
        val window = FloatingWindow(this)
        floating = window
        try {
            window.show { stopSelfAndReport() }
            window.update("直播中 00:00", "准备中…", active = true)
        } catch (e: Exception) {
            floating = null
        }
    }

    private fun stopSelfAndReport() {
        stopEverything("用户已停止直播")
        stopSelf()
    }

    // ------------------------------------------------------------ 停止
    private fun stopEverything(reason: String) {
        // 注意：这里不能提前 return。只要悬浮窗还挂着就必须继续走完清理流程，
        // 否则「浮窗已显示、采集还没真正起来」的中间态会让浮窗永久残留。
        capturing = false
        running = false

        unregisterDisplayListener()
        stopAudioCapture()
        stopAbrTicker()
        mainHandler.removeCallbacks(statsTask)

        try {
            encoder?.stop()
        } catch (e: Exception) {
            // ignore
        }
        encoder = null
        encoderSurface = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (e: Exception) {
            // ignore
        }
        imageReader = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            // ignore
        }
        virtualDisplay = null

        try {
            projection?.stop()
        } catch (e: Exception) {
            // ignore
        }
        projection = null

        if (activeCid != null || pendingCid != null) {
            // 直播结束 = 授权立即失效，先通知服务端与对方，再断开
            try { relay?.sendControlEnd() } catch (e: Exception) { /* ignore */ }
            activeCid = null
            activeName = null
            pendingCid = null
            floating?.clearControlPanel()
        }

        relay?.close()
        relay = null

        // 关键修复：必须先取出引用再置空字段。
        // 原写法 `post { floating?.hide() }` 后紧跟 `floating = null`，
        // 等 runnable 在主线程执行时字段已为 null，removeView 永远不会被调用，
        // 于是悬浮窗残留在屏幕上。
        val window = floating
        floating = null
        if (window != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                window.hide()
            } else {
                mainHandler.post { window.hide() }
            }
        }

        paddedBitmap?.recycle()
        paddedBitmap = null

        postState(reason)
    }

    override fun onDestroy() {
        stopEverything("直播已结束")
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        super.onDestroy()
    }
}
