package com.streamcast.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.streamcast.app.databinding.ActivityReceiverBinding
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 接收端：连接同一房间，接收中继转发的画面与音频。
 *
 * 支持两种视频编码：
 *  - H.264（默认）：MediaCodec 硬件解码，直接渲染到 TextureView，CPU 与带宽占用低；
 *  - JPEG（兼容模式）：逐帧解码为 Bitmap 显示，作为老格式与硬件解码不可用时的兜底。
 */
class ReceiverActivity : AppCompatActivity(), RelayClient.Listener {

    private lateinit var binding: ActivityReceiverBinding
    private var relay: RelayClient? = null

    /** JPEG 解码线程（H.264 走硬件解码，不占用此线程） */
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val decoding = AtomicBoolean(false)
    private val uiHandler = Handler(Looper.getMainLooper())

    private var frameCount = 0L
    private var windowFrames = 0
    private var windowBytes = 0L
    private var resolution = "-"

    private var currentBitmap: Bitmap? = null

    @Volatile
    private var aacPlayer: AacPlayer? = null

    @Volatile
    private var audioFrames = 0L

    private var fullscreen = false

    @Volatile
    private var h264Mode = false

    /** 当前是否为 H.265 硬件解码 */
    @Volatile
    private var hevcMode = false

    // ---- 远程控制（实验性） ----
    /** 0 未申请 / 1 等待主播确认 / 2 控制中 */
    private var controlState = 0
    private var controllerName = ""
    private var ctrlDownAt = 0L
    private var ctrlDownX = 0f
    private var ctrlDownY = 0f
    private var ctrlMoved = false

    @Volatile
    private var videoWidth = 0

    @Volatile
    private var videoHeight = 0

    private val videoPlayer: VideoPlayer by lazy { VideoPlayer(binding.textureView) }

    private val statsTask = object : Runnable {
        override fun run() {
            val audioPart = if (audioFrames > 0) " ｜ 音频 $audioFrames 帧" else ""
            val codec = when {
                hevcMode -> "H.265"
                h264Mode -> "H.264"
                else -> "JPEG"
            }
            val text = "帧率 $windowFrames fps ｜ $codec ｜ 分辨率 $resolution ｜ " +
                    "${"%.1f".format(windowBytes / 1024.0)} KB/s$audioPart"
            binding.tvStats.text = text
            binding.tvFullStats.text = text
            windowFrames = 0
            windowBytes = 0
            if (relay != null) uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE)
        binding.etServer.setText(
            intent.getStringExtra(AppConfig.KEY_SERVER)
                ?: prefs.getString(AppConfig.KEY_SERVER, AppConfig.DEFAULT_SERVER)
        )
        binding.etRoom.setText(
            intent.getStringExtra(AppConfig.KEY_ROOM)
                ?: prefs.getString(AppConfig.KEY_ROOM, "")
        )

        binding.btnConnect.setOnClickListener { connect() }
        binding.btnCtrl.setOnClickListener { onControlButton() }
        binding.btnCtrlExit.setOnClickListener { exitControl() }
        binding.btnKeyBack.setOnClickListener { sendControlKey("back") }
        binding.btnKeyHome.setOnClickListener { sendControlKey("home") }
        binding.btnKeyRecents.setOnClickListener { sendControlKey("recents") }
        // 控制模式下拦截画面触摸；其余情况返回 false，保持「点击全屏」等原有交互
        binding.textureView.setOnTouchListener { v, ev -> handleControlTouch(v, ev) }
        binding.ivFrame.setOnTouchListener { v, ev -> handleControlTouch(v, ev) }
        updateControlUi()
        binding.btnDisconnect.setOnClickListener { disconnect("已断开") }
        binding.btnFullscreen.setOnClickListener { setFullscreen(!fullscreen) }
        binding.videoHolder.setOnClickListener {
            if (h264Mode || currentBitmap != null) setFullscreen(!fullscreen)
        }

        binding.videoHolder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (h264Mode) applyVideoAspect()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) {
                    setFullscreen(false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setStatus("未连接", R.color.muted)
        updatePlaceholder()
    }

    // ------------------------------------------------------------ 渲染模式
    private fun applyRenderMode() {
        if (h264Mode) {
            binding.textureView.visibility = View.VISIBLE
            binding.ivFrame.visibility = View.GONE
            binding.tvPlaceholder.visibility = View.GONE
            applyVideoAspect()
        } else {
            binding.textureView.visibility = View.GONE
            updatePlaceholder()
        }
    }

    private fun updatePlaceholder() {
        if (h264Mode) {
            binding.tvPlaceholder.visibility = View.GONE
            return
        }
        val has = currentBitmap != null
        binding.ivFrame.visibility = if (has) View.VISIBLE else View.GONE
        binding.tvPlaceholder.visibility = if (has) View.GONE else View.VISIBLE
    }

    /** 按视频宽高比把 TextureView 等比缩放到画面区内（居中） */
    private fun applyVideoAspect() {
        val w = videoWidth
        val h = videoHeight
        if (w <= 0 || h <= 0) return
        val holder = binding.videoHolder
        if (holder.width <= 0 || holder.height <= 0) return
        val scale = minOf(holder.width.toFloat() / w, holder.height.toFloat() / h)
        val lp = binding.textureView.layoutParams
        lp.width = (w * scale).toInt().coerceAtLeast(1)
        lp.height = (h * scale).toInt().coerceAtLeast(1)
        binding.textureView.layoutParams = lp
    }

    // ------------------------------------------------------------ 全屏
    private fun setFullscreen(on: Boolean) {
        if (fullscreen == on) return
        fullscreen = on

        binding.headerBlock.visibility = if (on) View.GONE else View.VISIBLE
        binding.configCard.visibility = if (on) View.GONE else View.VISIBLE
        binding.tvStats.visibility = if (on) View.GONE else View.VISIBLE
        binding.tvFullStats.visibility = if (on) View.VISIBLE else View.GONE

        val pad = if (on) 0 else dp(18)
        binding.contentRoot.setPadding(pad, pad, pad, pad)

        val holderLp = binding.videoHolder.layoutParams as LinearLayout.LayoutParams
        holderLp.topMargin = if (on) 0 else dp(14)
        binding.videoHolder.layoutParams = holderLp

        if (on) {
            binding.videoHolder.setBackgroundColor(Color.BLACK)
        } else {
            binding.videoHolder.setBackgroundResource(R.drawable.bg_card)
        }

        val imgLp = binding.ivFrame.layoutParams as FrameLayout.LayoutParams
        val m = if (on) 0 else dp(8)
        imgLp.setMargins(m, m, m, m)
        binding.ivFrame.layoutParams = imgLp

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        binding.videoHolder.post { applyVideoAspect() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ 连接
    private fun connect() {
        val server = binding.etServer.text.toString().trim()
        val room = binding.etRoom.text.toString().trim()
        if (!AppConfig.isValidServer(server)) {
            setStatus("中继地址需以 ws:// 或 wss:// 开头", R.color.err)
            return
        }
        if (room.isEmpty()) {
            setStatus("请填写房间号", R.color.err)
            return
        }
        getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).edit()
            .putString(AppConfig.KEY_SERVER, server)
            .putString(AppConfig.KEY_ROOM, room)
            .apply()

        disconnect(null)

        frameCount = 0
        windowFrames = 0
        windowBytes = 0
        resolution = "-"
        audioFrames = 0
        videoWidth = 0
        videoHeight = 0
        h264Mode = false
        releaseAudio()
        videoPlayer.release()
        currentBitmap?.let { if (!it.isRecycled) it.recycle() }
        currentBitmap = null
        binding.ivFrame.setImageDrawable(null)
        applyRenderMode()

        val client = RelayClient(server, room, "receiver", this)
        relay = client
        client.connect()
        uiHandler.postDelayed(statsTask, 1000)
    }

    private fun disconnect(statusText: String?) {
        relay?.close()
        relay = null
        controlState = 0
        controllerName = ""
        updateControlUi()
        uiHandler.removeCallbacks(statsTask)
        releaseAudio()
        videoPlayer.release()
        if (statusText != null) setStatus(statusText, R.color.muted)
    }

    private fun releaseAudio() {
        aacPlayer?.release()
        aacPlayer = null
    }

    // ------------------------------------------------------------ RelayClient.Listener
    override fun onConnecting() {
        runOnUiThread { setStatus("连接中…", R.color.warn) }
    }

    override fun onConnected(room: String) {
        relay?.requestKeyframe()
        runOnUiThread { setStatus("已加入房间 $room，等待画面…", R.color.warn) }
    }

    override fun onControl(type: String, json: JSONObject) {
        when (type) {
            "meta" -> {
                val w = json.optInt("width", 0)
                val h = json.optInt("height", 0)
                if (w > 0 && h > 0 && videoWidth == 0) resolution = "$w×$h"
                runOnUiThread { setStatus("直播中 · 房间 ${binding.etRoom.text}", R.color.ok) }
            }
            "peer-joined" -> if (json.optString("role") == "sender") {
                runOnUiThread { setStatus("发送端已接入，等待画面…", R.color.warn) }
            }
            "peer-left" -> if (json.optString("role") == "sender") {
                runOnUiThread {
                    setStatus("发送端已断开", R.color.err)
                    releaseAudio()
                    audioFrames = 0
                    videoPlayer.release()
                    h264Mode = false
                    hevcMode = false
                    currentBitmap?.let { if (!it.isRecycled) it.recycle() }
                    currentBitmap = null
                    binding.ivFrame.setImageDrawable(null)
                    applyRenderMode()
                }
            }

            // ---------------- 远程控制（实验性） ----------------
            "control-pending" -> runOnUiThread {
                controlState = 1
                updateControlUi()
                setStatus("控制申请已发送，等待主播确认…", R.color.warn)
            }
            "control-granted" -> runOnUiThread {
                controlState = 2
                controllerName = ""
                updateControlUi()
                setStatus("已获得控制权：点按或滑动画面即可操作对方手机", R.color.ok)
            }
            "control-denied" -> runOnUiThread {
                controlState = 0
                updateControlUi()
                setStatus(json.optString("reason", "主播拒绝了控制申请"), R.color.err)
            }
            "control-end" -> runOnUiThread {
                val had = controlState == 2
                controlState = 0
                controllerName = ""
                updateControlUi()
                if (had) setStatus(json.optString("reason", "控制已结束"), R.color.warn)
            }
            "control-state" -> runOnUiThread {
                val active = json.optBoolean("active", false)
                controllerName = if (active) json.optString("name") else ""
                // 自己正在控制时，不被房间广播覆盖
                if (controlState != 2) updateControlUi()
            }
        }
    }

    override fun onFrame(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        when (bytes[0]) {
            AppConfig.FRAME_AUDIO -> {
                var player = aacPlayer
                if (player == null) {
                    player = AacPlayer()
                    aacPlayer = player
                }
                player.feed(bytes, 1)
                audioFrames = player.framesPlayed
                return
            }

            AppConfig.FRAME_CODEC_CONFIG -> {
                handleCodecConfig(bytes, false)
                return
            }

            AppConfig.FRAME_CODEC_CONFIG_HEVC -> {
                handleCodecConfig(bytes, true)
                return
            }

            AppConfig.FRAME_RESYNC -> {
                // 发送端丢弃了积压，立刻跳到下一个关键帧，把延迟拉回实时
                videoPlayer.resync()
                return
            }

            AppConfig.FRAME_VIDEO_H264 -> {
                if (!h264Mode) {
                    h264Mode = true
                    runOnUiThread { applyRenderMode() }
                }
                videoPlayer.submit(bytes, 1, false)
                frameCount++
                windowFrames++
                windowBytes += bytes.size
                if (videoWidth > 0) resolution = "${videoWidth}×${videoHeight}"
                return
            }

            AppConfig.FRAME_VIDEO_HEVC -> {
                if (!h264Mode) {
                    h264Mode = true
                    runOnUiThread { applyRenderMode() }
                }
                videoPlayer.submit(bytes, 1, true)
                frameCount++
                windowFrames++
                windowBytes += bytes.size
                if (videoWidth > 0) resolution = "${videoWidth}×${videoHeight}"
                return
            }
        }

        // 其余按裸 JPEG 处理（兼容模式）
        frameCount++
        windowFrames++
        windowBytes += bytes.size

        if (!decoding.compareAndSet(false, true)) return
        decodeExecutor.execute {
            try {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    runOnUiThread { showJpegFrame(bitmap) }
                }
            } catch (e: Exception) {
                // 忽略坏帧
            } finally {
                decoding.set(false)
            }
        }
    }

    /** 0x04 / 0x07：宽(2 字节) + 高(2 字节) + 参数集 */
    private fun handleCodecConfig(bytes: ByteArray, hevc: Boolean) {
        if (bytes.size < 6) return
        val w = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        val h = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
        if (w <= 0 || h <= 0) return
        val csd = bytes.copyOfRange(5, bytes.size)

        videoWidth = w
        videoHeight = h
        resolution = "$w×$h"
        hevcMode = hevc
        videoPlayer.onConfig(w, h, csd, hevc)

        h264Mode = true
        runOnUiThread {
            applyRenderMode()
            binding.videoHolder.post { applyVideoAspect() }
        }
        // 新的配置意味着编码器刚重启，主动要一个关键帧
        relay?.requestKeyframe()
    }

    private fun showJpegFrame(bitmap: Bitmap) {
        val old = currentBitmap
        currentBitmap = bitmap
        binding.ivFrame.setImageBitmap(bitmap)
        if (!h264Mode) updatePlaceholder()
        if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
    }

    override fun onClosed(reason: String) {
        runOnUiThread { setStatus("连接已断开", R.color.muted) }
    }

    override fun onFailure(message: String) {
        runOnUiThread { setStatus("连接失败：$message", R.color.err) }
    }

    // ------------------------------------------------------------ 远程控制（实验性）
    private fun onControlButton() {
        if (relay == null) {
            setStatus("请先连接房间，再申请控制", R.color.warn)
            return
        }
        if (controlState == 2) {
            exitControl()
            return
        }
        if (controlState == 1) return

        ExpDisclaimer.require(this, onAccept = {
            relay?.sendJson(JSONObject().put("type", "control-request"))
            controlState = 1
            updateControlUi()
            setStatus("已发送控制申请，等待主播在悬浮窗确认…", R.color.warn)
        })
    }

    private fun exitControl() {
        relay?.sendControlEnd()
        controlState = 0
        updateControlUi()
        setStatus("已退出控制", R.color.muted)
    }

    private fun updateControlUi() {
        when {
            controlState == 2 -> {
                binding.btnCtrl.text = "退出控制"
                binding.btnCtrl.isEnabled = true
                binding.btnCtrl.setTextColor(ContextCompat.getColor(this, R.color.err))
                binding.ctrlBar.visibility = View.VISIBLE
            }
            controlState == 1 -> {
                binding.btnCtrl.text = "等待主播确认…"
                binding.btnCtrl.isEnabled = false
                binding.btnCtrl.setTextColor(ContextCompat.getColor(this, R.color.muted))
                binding.ctrlBar.visibility = View.GONE
            }
            controllerName.isNotBlank() -> {
                binding.btnCtrl.text = "$controllerName 正在控制"
                binding.btnCtrl.isEnabled = false
                binding.btnCtrl.setTextColor(ContextCompat.getColor(this, R.color.muted))
                binding.ctrlBar.visibility = View.GONE
            }
            else -> {
                binding.btnCtrl.text = "申请控制（实验）"
                binding.btnCtrl.isEnabled = true
                binding.btnCtrl.setTextColor(ContextCompat.getColor(this, R.color.warn))
                binding.ctrlBar.visibility = View.GONE
            }
        }
    }

    /**
     * 控制模式下的画面触摸：归一化为 0..1 后交给发送端换算成真实屏幕坐标。
     * 因为 TextureView / ImageView 都按视频宽高比等比缩放，视图坐标系即画面坐标系。
     */
    private fun handleControlTouch(v: View, ev: android.view.MotionEvent): Boolean {
        if (controlState != 2) return false
        if (v.width <= 0 || v.height <= 0) return false
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                ctrlDownAt = android.os.SystemClock.elapsedRealtime()
                ctrlDownX = (ev.x / v.width).coerceIn(0f, 1f)
                ctrlDownY = (ev.y / v.height).coerceIn(0f, 1f)
                ctrlMoved = false
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val nx = (ev.x / v.width).coerceIn(0f, 1f)
                val ny = (ev.y / v.height).coerceIn(0f, 1f)
                if (kotlin.math.abs(nx - ctrlDownX) > 0.012f ||
                    kotlin.math.abs(ny - ctrlDownY) > 0.012f
                ) ctrlMoved = true
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                val nx = (ev.x / v.width).coerceIn(0f, 1f)
                val ny = (ev.y / v.height).coerceIn(0f, 1f)
                val dt = (android.os.SystemClock.elapsedRealtime() - ctrlDownAt).toInt()
                if (!ctrlMoved && dt < 320) {
                    sendControlTap(ctrlDownX, ctrlDownY)
                } else {
                    sendControlSwipe(ctrlDownX, ctrlDownY, nx, ny, dt)
                }
                return true
            }
        }
        return false
    }

    private fun sendControlTap(x: Float, y: Float) {
        relay?.sendJson(
            JSONObject()
                .put("type", "control-input")
                .put("action", "tap")
                .put("x", x.toDouble())
                .put("y", y.toDouble())
        )
    }

    private fun sendControlSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
        relay?.sendJson(
            JSONObject()
                .put("type", "control-input")
                .put("action", "swipe")
                .put("x", x1.toDouble())
                .put("y", y1.toDouble())
                .put("x2", x2.toDouble())
                .put("y2", y2.toDouble())
                .put("duration", durationMs.coerceIn(60, 1200))
        )
    }

    private fun sendControlKey(key: String) {
        if (controlState != 2) return
        relay?.sendJson(
            JSONObject()
                .put("type", "control-input")
                .put("action", "key")
                .put("key", key)
        )
    }

    private fun setStatus(text: String, colorRes: Int) {
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.dotStatus.background.mutate().setTint(ContextCompat.getColor(this, colorRes))
    }

    override fun onPause() {
        if (fullscreen) setFullscreen(false)
        super.onPause()
    }

    override fun onDestroy() {
        disconnect(null)
        decodeExecutor.shutdownNow()
        currentBitmap?.let { if (!it.isRecycled) it.recycle() }
        currentBitmap = null
        super.onDestroy()
    }
}
