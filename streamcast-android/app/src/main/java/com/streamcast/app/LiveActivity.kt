package com.streamcast.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.streamcast.app.databinding.ActivityLiveBinding

/**
 * 直播间：预览 + 直播中控制台。
 *
 * 为什么单独一页、为什么不用浮窗：
 *   摄像头直播时用户本来就盯着取景画面，页面自己就是控制台——
 *   少一个「浮窗权限」的门槛，也让「打开就开播」少两步操作。
 *
 * 预览不引 CameraX：camera2 的预览面与推流帧共用同一个相机会话，
 * 一次采集两路输出，既省体积也省一次相机开关。
 */
class LiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveBinding
    private val prefs by lazy { getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE) }
    private val uiHandler = Handler(Looper.getMainLooper())

    private var mode = AppConfig.MODE_CAMERA
    private var server = AppConfig.DEFAULT_SERVER
    private var room = ""
    private var token = ""
    private var front = true
    private var preset = 720
    private var live = false
    private var startedAt = 0L

    /** 预览专用（未开播）；开播后由服务内部自己开相机，这里必须释放 */
    private var previewer: CameraCapture? = null
    private var pendingSurface: Surface? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startPreview()
            } else {
                setStatus("未授予摄像头权限，无法直播", true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(AppConfig.KEY_MODE) ?: AppConfig.MODE_CAMERA
        server = intent.getStringExtra(AppConfig.KEY_SERVER)
            ?: prefs.getString(AppConfig.KEY_SERVER, AppConfig.DEFAULT_SERVER).orEmpty()
        room = intent.getStringExtra(AppConfig.KEY_ROOM)
            ?: prefs.getString(AppConfig.KEY_ROOM, "").orEmpty()
        token = prefs.getString(AppConfig.KEY_TOKEN, "").orEmpty()
        front = prefs.getBoolean(KEY_FRONT, true)
        preset = prefs.getInt(AppConfig.KEY_RESOLUTION, 720).let {
            if (it in AppConfig.CAMERA_PRESETS) it else 720
        }

        binding.tvRoomLabel.text = "房间 $room"
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRotate.setOnClickListener { swapCamera() }
        binding.btnAction.setOnClickListener { onAction() }
        binding.btnShare.setOnClickListener { copyLink() }
        bindQuality()

        if (StreamService.running) {
            enterScreenLiveUi()
        } else if (CameraStreamService.running) {
            enterLiveUi()
            setStatus("直播进行中", false)
        } else {
            setStatus("准备就绪，点下方按钮开始直播", false)
            binding.previewView.surfaceTextureListener = surfaceListener
            binding.previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                applyPreviewTransform()
            }
        }

        StreamService.onState = { text -> runOnUiThread { setStatus(text, false) } }
        CameraStreamService.onState = { text -> runOnUiThread { setStatus(text, false) } }
        CameraStreamService.onStats = { stats -> runOnUiThread { renderStats(stats) } }
    }

    override fun onResume() {
        super.onResume()
        if (StreamService.running) {
            if (!live) enterScreenLiveUi()
        } else if (CameraStreamService.running) {
            if (!live) enterLiveUi()
        } else if (!live && hasCamera() && binding.previewView.isAvailable) {
            startPreview()
        }
    }

    override fun onPause() {
        // 离开页面就断开预览相机，避免与直播服务抢摄像头
        if (!live) stopPreview()
        super.onPause()
    }

    override fun onDestroy() {
        StreamService.onState = null
        CameraStreamService.onState = null
        CameraStreamService.onStats = null
        stopPreview()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 预览
    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            pendingSurface = Surface(st)
            startPreview()
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            applyPreviewTransform()
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            stopPreview()
            pendingSurface = null
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun startPreview() {
        if (live || StreamService.running) return
        if (!hasCamera()) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        val surface = pendingSurface
        if (surface == null || !surface.isValid) return

        stopPreview()
        val cam = CameraCapture(
            this,
            uiHandler,
            onFrame = { _, _, _ -> },
            onError = { msg -> setStatus(msg, true) }
        )
        previewer = cam
        cam.start(front, preset * 16 / 9, 24, surface)
        applyPreviewTransform()
    }

    private fun stopPreview() {
        previewer?.stop()
        previewer = null
    }

    /** TextureView 默认拉伸；按相机宽高比中心裁切，保证不变形 */
    private fun applyPreviewTransform() {
        val w = binding.previewView.width
        val h = binding.previewView.height
        if (w <= 0 || h <= 0) return
        val viewRatio = w.toFloat() / h
        val target = 9f / 16f
        val matrix = Matrix()
        if (viewRatio > target) {
            // 视图更宽：按高度铺满，横向裁切
            val scale = (target / viewRatio).coerceAtMost(1f)
            matrix.setScale(1f, 1f / scale)
        } else {
            val scale = (viewRatio / target).coerceAtMost(1f)
            matrix.setScale(1f / scale, 1f)
        }
        binding.previewView.setTransform(matrix)
    }

    private fun swapCamera() {
        if (StreamService.running) {
            toast("屏幕直播中无法切换镜头")
            return
        }
        front = !front
        prefs.edit().putBoolean(KEY_FRONT, front).apply()
        if (CameraStreamService.running) {
            // 直播中切镜头：重启采集，房间号不变，观众端自动重连画面
            restartCameraLive()
        } else {
            startPreview()
        }
    }

    // ------------------------------------------------------------ 开播
    private fun onAction() {
        when {
            live -> stopLive()
            StreamService.running -> stopScreenLive()
            !hasCamera() -> cameraPermission.launch(Manifest.permission.CAMERA)
            else -> beginLive()
        }
    }

    private fun beginLive() {
        if (room.isEmpty()) {
            setStatus("缺少房间号，请返回首页重新填写", true)
            return
        }
        stopPreview()
        ContextCompat.startForegroundService(this, cameraIntent())
        enterLiveUi()
        setStatus("正在连接…", false)
    }

    private fun restartCameraLive() {
        ContextCompat.startForegroundService(this, cameraIntent())
        setStatus("正在切换镜头…", false)
    }

    private fun cameraIntent(): Intent =
        Intent(this, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_START
            putExtra(CameraStreamService.EXTRA_SERVER, server)
            putExtra(CameraStreamService.EXTRA_ROOM, room)
            putExtra(
                CameraStreamService.EXTRA_FPS,
                prefs.getInt(AppConfig.KEY_FPS, AppConfig.DEFAULT_CAMERA_FPS)
            )
            putExtra(
                CameraStreamService.EXTRA_QUALITY,
                prefs.getInt(AppConfig.KEY_QUALITY, AppConfig.DEFAULT_QUALITY)
            )
            putExtra(CameraStreamService.EXTRA_RESOLUTION, preset)
            putExtra(CameraStreamService.EXTRA_FRONT, front)
            putExtra(CameraStreamService.EXTRA_TOKEN, token)
            putExtra(CameraStreamService.EXTRA_PRIVATE, prefs.getBoolean(AppConfig.KEY_PRIVATE, false))
        }

    private fun stopLive() {
        startService(
            Intent(this, CameraStreamService::class.java).setAction(CameraStreamService.ACTION_STOP)
        )
        exitLiveUi()
        setStatus("直播已结束", false)
    }

    private fun stopScreenLive() {
        startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
        exitLiveUi()
        setStatus("屏幕直播已结束", false)
    }

    // ------------------------------------------------------------ 界面状态
    private fun enterLiveUi() {
        live = true
        startedAt = SystemClock.elapsedRealtime()
        binding.btnAction.text = "结束直播"
        binding.btnAction.setBackgroundResource(R.drawable.btn_danger)
        binding.preRow.visibility = View.GONE
        binding.tvStats.visibility = View.VISIBLE
        binding.btnShare.visibility = View.VISIBLE
        binding.tvScreenTip.visibility = View.GONE
        hideSystemBars()
    }

    private fun enterScreenLiveUi() {
        enterLiveUi()
        binding.previewView.visibility = View.GONE
        binding.tvScreenTip.visibility = View.VISIBLE
        binding.btnRotate.visibility = View.GONE
        binding.btnAction.text = "结束屏幕直播"
        setStatus("屏幕直播进行中：切到要分享的画面即可", false)
    }

    private fun exitLiveUi() {
        live = false
        binding.btnAction.text = "开始直播"
        binding.btnAction.setBackgroundResource(R.drawable.btn_primary)
        binding.preRow.visibility = View.VISIBLE
        binding.tvStats.visibility = View.GONE
        binding.btnRotate.visibility = View.VISIBLE
        binding.tvScreenTip.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
        showSystemBars()
        binding.previewView.post { startPreview() }
    }

    private fun renderStats(stats: StreamStats) {
        if (!live) return
        val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
        val t = String.format("%02d:%02d", seconds / 60, seconds % 60)
        val turboTag = if (stats.turbo) " · 独享加速" else ""
        binding.tvStats.text = "已播 $t · ${stats.viewers} 人观看 · ${stats.kbps} kbps$turboTag"
    }

    private fun setStatus(text: String, warn: Boolean) {
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(this, if (warn) R.color.warn else R.color.text)
        )
        if (!warn) binding.tvStatus.setTextColor(0xFFE6EDF6.toInt())
    }

    private fun bindQuality() {
        val chips = listOf(binding.chipQ1, binding.chipQ2, binding.chipQ3)
        chips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                if (live) {
                    toast("直播中不能改画质，结束直播后可调整")
                    return@setOnClickListener
                }
                preset = AppConfig.CAMERA_PRESETS[index]
                prefs.edit().putInt(AppConfig.KEY_RESOLUTION, preset).apply()
                renderChips()
                startPreview()
            }
        }
        renderChips()
    }

    private fun renderChips() {
        val chips = listOf(binding.chipQ1, binding.chipQ2, binding.chipQ3)
        chips.forEachIndexed { index, chip ->
            val on = AppConfig.CAMERA_PRESETS[index] == preset
            chip.setBackgroundResource(
                if (on) R.drawable.bg_chip_dark_on else R.drawable.bg_chip_dark
            )
            chip.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFFA9B6C9.toInt())
        }
    }

    private fun copyLink() {
        val url = AppConfig.webUrl(server, room)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("观看链接", url))
        toast("观看链接已复制：$url")
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val KEY_FRONT = "camera_front"
    }
}
