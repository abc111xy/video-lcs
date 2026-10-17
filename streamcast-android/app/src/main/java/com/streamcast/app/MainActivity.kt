package com.streamcast.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.streamcast.app.databinding.ActivityMainBinding

/**
 * 发送端控制台：申请权限（浮窗 / 通知）→ 发起屏幕采集授权 → 启动直播服务。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var resolutionPreset = AppConfig.DEFAULT_RESOLUTION
    private var audioEnabled = AppConfig.DEFAULT_AUDIO
    private var encoderMode = AppConfig.ENCODER_H264
    private var privateMode = false
    private var token = ""
    private var accountEmail = ""

    /** 编码方式选项，与 encoderModes 一一对应 */
    private val encoderChips: List<TextView> by lazy {
        listOf(binding.chipH264, binding.chipJpeg)
    }
    private val encoderModes = listOf(AppConfig.ENCODER_H264, AppConfig.ENCODER_JPEG)

    /** 分辨率选项与 AppConfig.RESOLUTION_PRESETS 一一对应 */
    private val resolutionChips: List<TextView> by lazy {
        listOf(binding.chip270, binding.chip480, binding.chip720, binding.chip1080, binding.chipNative)
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startStream(result.resultCode, data)
            } else {
                setStatus("已取消屏幕采集授权，直播未开始", LEVEL_WARN)
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshPermissions()
            if (!granted) setStatus("通知权限被拒绝，直播通知可能不可见", LEVEL_WARN)
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 权限结果决定开关状态，避免出现「开关开着但录不了音」的假象
            binding.switchAudio.isChecked = granted
            audioEnabled = granted
            updateAudioHint()
            if (!granted) setStatus("未授予录音权限，无法同步设备音频", LEVEL_WARN)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE)
        binding.etServer.setText(prefs.getString(AppConfig.KEY_SERVER, AppConfig.DEFAULT_SERVER))
        binding.etRoom.setText(prefs.getString(AppConfig.KEY_ROOM, "").orEmpty().ifEmpty { AppConfig.randomRoom() })

        // 未登录不允许进入控制台（开播需要账号）
        token = prefs.getString(AppConfig.KEY_TOKEN, "").orEmpty()
        accountEmail = prefs.getString(AppConfig.KEY_USER_EMAIL, "").orEmpty()
        if (token.isEmpty()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        binding.tvAccount.visibility = View.GONE
        binding.tvUserEmail.text = accountEmail
        binding.userBar.setOnClickListener { openUserCenter() }
        binding.avatarBox.setOnClickListener { openUserCenter() }
        binding.btnUserCenter.setOnClickListener { openUserCenter() }
        binding.etTitle.setText(prefs.getString(AppConfig.KEY_TITLE, "").orEmpty())
        binding.etTitle.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString(AppConfig.KEY_TITLE, s?.toString().orEmpty().trim()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        renderUser()
        refreshProfile()

        binding.sbFps.max = AppConfig.MAX_FPS - 1
        binding.sbFps.progress = (AppConfig.DEFAULT_FPS - 1).coerceAtLeast(0)
        binding.sbQuality.max = 70
        binding.sbQuality.progress = (AppConfig.DEFAULT_QUALITY - 25).coerceAtLeast(0)

        binding.sbFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateParamLabels()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.sbQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateParamLabels()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updateParamLabels()

        // 分辨率选项
        resolutionPreset = prefs.getInt(AppConfig.KEY_RESOLUTION, AppConfig.DEFAULT_RESOLUTION)
        resolutionChips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                val preset = AppConfig.RESOLUTION_PRESETS.getOrNull(index) ?: return@setOnClickListener
                resolutionPreset = preset
                updateResolutionChips()
                updateResolutionHint()
            }
        }
        updateResolutionChips()
        updateResolutionHint()

        // 编码方式（硬件 H.264 / JPEG 兼容）
        encoderMode = prefs.getString(AppConfig.KEY_ENCODER, AppConfig.ENCODER_H264)
            ?: AppConfig.ENCODER_H264
        encoderChips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                encoderMode = encoderModes.getOrElse(index) { AppConfig.ENCODER_H264 }
                updateEncoderChips()
            }
        }
        updateEncoderChips()

        // 设备音频同步开关
        audioEnabled = prefs.getBoolean(AppConfig.KEY_AUDIO, AppConfig.DEFAULT_AUDIO)
        binding.switchAudio.isChecked = audioEnabled
        binding.switchAudio.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasAudioPermission()) {
                // 先要录音权限（AudioRecord 内录同样需要它），再决定开关状态
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                audioEnabled = checked
                updateAudioHint()
            }
        }
        updateAudioHint()

        // 隐私模式：开启后房间不出现在大厅列表
        privateMode = prefs.getBoolean(AppConfig.KEY_PRIVATE, false)
        binding.switchPrivate.isChecked = privateMode
        binding.switchPrivate.setOnCheckedChangeListener { _, checked ->
            privateMode = checked
            updatePrivateHint()
        }
        updatePrivateHint()

        binding.btnOverlay.setOnClickListener { requestOverlayPermission() }
        binding.btnNotify.setOnClickListener { requestNotificationPermission() }
        binding.btnStart.setOnClickListener { startFlow() }
        binding.btnStop.setOnClickListener {
            startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
            setStatus("正在停止直播…", LEVEL_NORMAL)
        }
        binding.btnDice.setOnClickListener {
            binding.etRoom.setText(AppConfig.randomRoom())
            updateServerHint()
        }
        binding.btnWeb.setOnClickListener { openWebReceiver() }
        binding.btnToReceiver.setOnClickListener {
            startActivity(
                Intent(this, ReceiverActivity::class.java)
                    .putExtra(AppConfig.KEY_SERVER, binding.etServer.text.toString().trim())
                    .putExtra(AppConfig.KEY_ROOM, binding.etRoom.text.toString().trim())
            )
        }
        binding.etRoom.setOnFocusChangeListener { _, _ -> updateServerHint() }

        updateServerHint()
        refreshPermissions()
        refreshControlState()
    }

    // ------------------------------------------------------------ 用户中心
    private fun openUserCenter() {
        startActivity(Intent(this, UserCenterActivity::class.java))
    }

    private fun renderUser() {
        val prefs = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE)
        val nick = prefs.getString(AppConfig.KEY_NICKNAME, "").orEmpty()
        val avatar = prefs.getString(AppConfig.KEY_AVATAR, "").orEmpty()
        binding.tvNickname.text = nick.ifBlank { "未设置昵称" }
        val bmp = decodeAvatar(avatar)
        if (bmp != null) {
            binding.ivAvatar.setImageBitmap(bmp)
            binding.tvAvatarInitial.visibility = View.GONE
        } else {
            binding.ivAvatar.setImageDrawable(null)
            binding.ivAvatar.setBackgroundResource(R.drawable.bg_avatar)
            binding.tvAvatarInitial.visibility = View.VISIBLE
            val seed = nick.ifBlank { accountEmail }
            binding.tvAvatarInitial.text = if (seed.isBlank()) "U" else seed.take(1).uppercase()
        }
    }

    private fun decodeAvatar(data: String): android.graphics.Bitmap? {
        if (data.isBlank()) return null
        return try {
            val comma = data.indexOf(',')
            val payload = if (comma >= 0) data.substring(comma + 1) else data
            val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /** 从服务端拉一次最新资料，避免换手机后头像/昵称为空 */
    private fun refreshProfile() {
        if (token.isEmpty()) return
        val base = AppConfig.httpBase(binding.etServer.text.toString().trim())
        Thread {
            val resp = AuthApi.me(base, token)
            if (resp.code != 200) return@Thread
            val u = resp.body.optJSONObject("user") ?: return@Thread
            getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).edit()
                .putString(AppConfig.KEY_NICKNAME, u.optString("nickname"))
                .putString(AppConfig.KEY_AVATAR, u.optString("avatar"))
                .apply()
            runOnUiThread { renderUser() }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        renderUser()
        StreamService.onState = { text ->
            setStatus(text, LEVEL_NORMAL)
            // 悬浮窗上点「停止」时，本界面可能正处于前台，需要同步按钮状态
            syncControls()
        }
        StreamService.onStats = { stats ->
            val turboTag = if (stats.turbo) " ｜ 独享加速" else ""
            val abrTag = when {
                stats.targetKbps <= 0 -> ""
                stats.degraded -> " ｜ 自适应 ${stats.targetKbps}k（已降档）"
                else -> " ｜ 自适应 ${stats.targetKbps}k"
            }
            binding.tvViewers.text =
                "观众 ${stats.viewers} 人 ｜ 码率 ${stats.kbps} kbps ｜ 已发送 ${stats.frames} 帧 ｜ " +
                        "丢帧 ${stats.dropped}$turboTag$abrTag"
        }
        refreshControlState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshControlState()
    }

    override fun onStop() {
        StreamService.onState = null
        StreamService.onStats = null
        super.onStop()
    }

    // ------------------------------------------------------------ 权限
    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            setStatus("浮窗权限已授权", LEVEL_OK)
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            setStatus("通知权限已授权", LEVEL_OK)
        }
    }

    private fun refreshPermissions() {
        val overlayOk = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text = getString(if (overlayOk) R.string.perm_granted else R.string.perm_denied)
        binding.tvOverlayStatus.setTextColor(
            ContextCompat.getColor(this, if (overlayOk) R.color.ok else R.color.warn)
        )

        val notifyOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        binding.tvNotifyStatus.text = getString(if (notifyOk) R.string.perm_granted else R.string.perm_denied)
        binding.tvNotifyStatus.setTextColor(
            ContextCompat.getColor(this, if (notifyOk) R.color.ok else R.color.warn)
        )
    }

    // ------------------------------------------------------------ 直播流程
    private fun startFlow() {
        val server = binding.etServer.text.toString().trim()
        val room = binding.etRoom.text.toString().trim()

        if (!AppConfig.isValidServer(server)) {
            setStatus("中继地址需以 ws:// 或 wss:// 开头", LEVEL_ERR)
            return
        }
        if (room.isEmpty()) {
            setStatus("请填写房间号", LEVEL_ERR)
            return
        }
        getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).edit()
            .putString(AppConfig.KEY_SERVER, server)
            .putString(AppConfig.KEY_ROOM, room)
            .putInt(AppConfig.KEY_RESOLUTION, resolutionPreset)
            .putBoolean(AppConfig.KEY_AUDIO, audioEnabled)
            .putString(AppConfig.KEY_ENCODER, encoderMode)
            .putBoolean(AppConfig.KEY_PRIVATE, privateMode)
            .apply()

        if (!Settings.canDrawOverlays(this)) {
            setStatus("请先授予浮窗权限（用于显示直播状态条）", LEVEL_WARN)
            requestOverlayPermission()
            return
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        setStatus("请在弹出的系统窗口中允许屏幕采集…", LEVEL_NORMAL)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startStream(resultCode: Int, data: Intent) {
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamService.EXTRA_RESULT_DATA, data)
            putExtra(StreamService.EXTRA_SERVER, binding.etServer.text.toString().trim())
            putExtra(StreamService.EXTRA_ROOM, binding.etRoom.text.toString().trim())
            putExtra(StreamService.EXTRA_FPS, currentFps())
            putExtra(StreamService.EXTRA_QUALITY, currentQuality())
            putExtra(StreamService.EXTRA_RESOLUTION, resolutionPreset)
            putExtra(StreamService.EXTRA_AUDIO, audioEnabled)
            putExtra(StreamService.EXTRA_ENCODER, encoderMode)
            putExtra(StreamService.EXTRA_TOKEN, token)
            putExtra(StreamService.EXTRA_TITLE, binding.etTitle.text.toString().trim())
            putExtra(StreamService.EXTRA_PRIVATE, privateMode)
        }
        ContextCompat.startForegroundService(this, intent)
        setStatus("正在启动屏幕直播…", LEVEL_NORMAL)
        binding.root.postDelayed({ refreshControlState() }, 800)
    }

    private fun openWebReceiver() {
        val server = binding.etServer.text.toString().trim()
        val room = binding.etRoom.text.toString().trim()
        if (room.isEmpty()) {
            setStatus("请先填写房间号", LEVEL_WARN)
            return
        }
        val url = AppConfig.webUrl(server, room)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            setStatus("无法打开浏览器：${e.message}", LEVEL_ERR)
        }
    }

    // ------------------------------------------------------------ UI 辅助
    private fun currentFps(): Int = (binding.sbFps.progress + 1).coerceIn(1, AppConfig.MAX_FPS)

    private fun currentQuality(): Int = (binding.sbQuality.progress + 25).coerceIn(20, 95)

    private fun updateParamLabels() {
        binding.tvFps.text = "${currentFps()} fps"
        binding.tvQuality.text = "${currentQuality()} %"
    }

    private fun updateResolutionChips() {
        resolutionChips.forEachIndexed { index, chip ->
            val preset = AppConfig.RESOLUTION_PRESETS.getOrNull(index) ?: return@forEachIndexed
            val selected = preset == resolutionPreset
            chip.setBackgroundResource(if (selected) R.drawable.bg_chip_on else R.drawable.bg_chip)
            chip.setTextColor(ContextCompat.getColor(this, if (selected) R.color.white else R.color.muted))
        }
    }

    private fun updateEncoderChips() {
        encoderChips.forEachIndexed { index, chip ->
            val mode = encoderModes.getOrElse(index) { AppConfig.ENCODER_H264 }
            val selected = mode == encoderMode
            chip.setBackgroundResource(if (selected) R.drawable.bg_chip_on else R.drawable.bg_chip)
            chip.setTextColor(ContextCompat.getColor(this, if (selected) R.color.white else R.color.muted))
        }
        binding.tvEncoderHint.text = if (encoderMode == AppConfig.ENCODER_H264) {
            "硬件编码：CPU 与带宽低一个数量级，60fps 首选；若出现黑屏可切到兼容模式"
        } else {
            "逐帧压缩：兼容性最好，但高帧率下 CPU 占用高、带宽大"
        }
    }

    /** 用与服务端完全一致的算法预览实际输出尺寸 */
    private fun updateResolutionHint() {
        val (screenW, screenH) = screenSize()
        val (w, h) = AppConfig.targetSize(screenW, screenH, resolutionPreset)
        val note = when {
            resolutionPreset == AppConfig.RES_NATIVE -> "（手机原生，不做缩放）"
            resolutionPreset > minOf(screenW, screenH) -> "（预设高于屏幕，按屏幕输出）"
            else -> ""
        }
        binding.tvResolutionHint.text = "实际输出 ${w}×${h}$note"
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun updateAudioHint() {
        binding.tvAudioHint.text = when {
            !audioEnabled -> "关闭：只传输画面，不传声音"
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
                "当前系统低于 Android 10，不支持设备音频内录"
            else -> "抓取系统播放的声音（不含麦克风）；部分 App 会禁止被内录"
        }
    }

    private fun updatePrivateHint() {
        binding.tvPrivateHint.text = if (privateMode) {
            "已开启：房间不出现在大厅，凭房间号仍可进入"
        } else {
            "关闭：房间会出现在大厅，任何人可见"
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("退出后需要重新登录才能开播，确定吗？")
            .setPositiveButton("退出") { _, _ ->
                getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).edit()
                    .remove(AppConfig.KEY_TOKEN)
                    .apply()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
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

    private fun updateServerHint() {
        val room = binding.etRoom.text.toString().trim()
        val server = binding.etServer.text.toString().trim()
        binding.tvServerHint.text = if (room.isEmpty()) {
            "同一房间号的设备才能互相看到画面"
        } else {
            "网页观看地址：${AppConfig.webUrl(server, room)}"
        }
    }

    private fun syncControls() {
        val live = StreamService.running
        binding.btnStart.visibility = if (live) View.GONE else View.VISIBLE
        binding.btnStop.visibility = if (live) View.VISIBLE else View.GONE
        if (!live) binding.tvViewers.text = ""

        // 直播中参数不可改：改动会在下次开播时生效，禁用可避免误解
        binding.sbFps.isEnabled = !live
        binding.sbQuality.isEnabled = !live
        binding.switchAudio.isEnabled = !live
        binding.switchPrivate.isEnabled = !live
        encoderChips.forEach { chip ->
            chip.isEnabled = !live
            chip.alpha = if (live) 0.5f else 1f
        }
        resolutionChips.forEach { chip ->
            chip.isEnabled = !live
            chip.alpha = if (live) 0.5f else 1f
        }
    }

    private fun refreshControlState() {
        syncControls()
        if (!StreamService.running) {
            setStatus("待机中 · 配置好后点击「开始屏幕直播」", LEVEL_NORMAL)
        }
    }

    private fun setStatus(text: String, level: Int) {
        binding.tvStatus.text = text
        val color = when (level) {
            LEVEL_OK -> R.color.ok
            LEVEL_WARN -> R.color.warn
            LEVEL_ERR -> R.color.err
            else -> R.color.muted
        }
        binding.dotStatus.background.mutate().setTint(ContextCompat.getColor(this, color))
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, if (level == LEVEL_NORMAL) R.color.text else color))
    }

    private companion object {
        const val LEVEL_NORMAL = 0
        const val LEVEL_OK = 1
        const val LEVEL_WARN = 2
        const val LEVEL_ERR = 3
    }
}
