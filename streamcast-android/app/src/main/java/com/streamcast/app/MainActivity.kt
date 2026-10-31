package com.streamcast.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.streamcast.app.databinding.ActivityMainBinding

/**
 * 首页：一屏内完成开播。
 *
 * 设计取舍：上一版把「连接配置 / 权限 / 参数 / 实验性功能 / 控制」五张卡片平铺，
 * 首屏要滚两屏才能找到「开始」，很多用户以为没有摄像头直播。
 * 现在改为：
 *   - 首屏只有「房间号 + 两个开播按钮 + 状态」，其余全部收进「高级设置」；
 *   - 摄像头直播成为主按钮（无需录屏授权，一步开播）；
 *   - 屏幕直播保留为次按钮，参数沿用高级设置里的选择。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE) }

    private var preset = AppConfig.DEFAULT_RESOLUTION
    private var audioEnabled = AppConfig.DEFAULT_AUDIO
    private var encoderMode = AppConfig.ENCODER_H264
    private var privateMode = false
    private var remoteControlEnabled = false
    private var token = ""
    private var accountEmail = ""
    private var advancedOpen = false

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCameraLive()
            } else {
                toast("未授予摄像头权限，无法进行摄像头直播")
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startScreenLive(result.resultCode, data)
            } else {
                toast("已取消屏幕采集授权，直播未开始")
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshPermissions()
            if (!granted) toast("通知权限被拒绝，直播通知可能不可见")
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            binding.switchAudio.isChecked = granted
            audioEnabled = granted
            updateAudioHint()
            if (!granted) toast("未授予录音权限，无法同步设备音频")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        token = prefs.getString(AppConfig.KEY_TOKEN, "").orEmpty()
        accountEmail = prefs.getString(AppConfig.KEY_USER_EMAIL, "").orEmpty()
        if (token.isEmpty()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        bindAccount()
        bindTarget()
        bindQuality()
        bindOptions()
        bindPermissions()
        restoreAdvanced()

        binding.btnCamera.setOnClickListener { onCameraClicked() }
        binding.cameraCard.setOnClickListener { onCameraClicked() }
        binding.btnScreen.setOnClickListener { onScreenClicked() }
        binding.btnBackToLive.setOnClickListener { openLive() }
        binding.btnToReceiver.setOnClickListener {
            startActivity(
                Intent(this, ReceiverActivity::class.java)
                    .putExtra(AppConfig.KEY_SERVER, server())
                    .putExtra(AppConfig.KEY_ROOM, room())
            )
        }
        binding.btnWeb.setOnClickListener { openWebReceiver() }
        binding.btnAdvanced.setOnClickListener { toggleAdvanced() }

        if (!prefs.getBoolean(KEY_SEEN_CAMERA_TIP, false)) {
            prefs.edit().putBoolean(KEY_SEEN_CAMERA_TIP, true).apply()
        }
        updateHint()
        refreshPermissions()
        refreshLiveState()
    }

    // ------------------------------------------------------------ 账号
    private fun bindAccount() {
        binding.tvAccount.visibility = View.GONE
        binding.userBar.setOnClickListener { openUserCenter() }
        binding.avatarBox.setOnClickListener { openUserCenter() }
        binding.btnUserCenter.setOnClickListener { openUserCenter() }
        renderUser()
        refreshProfile()
    }

    private fun openUserCenter() {
        startActivity(Intent(this, UserCenterActivity::class.java))
    }

    private fun renderUser() {
        val nick = prefs.getString(AppConfig.KEY_NICKNAME, "").orEmpty()
        val avatar = prefs.getString(AppConfig.KEY_AVATAR, "").orEmpty()
        binding.tvNickname.text = nick.ifBlank { accountEmail.ifBlank { "未设置昵称" } }
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

    private fun refreshProfile() {
        if (token.isEmpty()) return
        val base = AppConfig.httpBase(server())
        Thread {
            val resp = AuthApi.me(base, token)
            if (resp.code != 200) return@Thread
            val u = resp.body.optJSONObject("user") ?: return@Thread
            prefs.edit()
                .putString(AppConfig.KEY_NICKNAME, u.optString("nickname"))
                .putString(AppConfig.KEY_AVATAR, u.optString("avatar"))
                .apply()
            runOnUiThread { renderUser() }
        }.start()
    }

    // ------------------------------------------------------------ 房间
    private fun bindTarget() {
        binding.etRoom.setText(
            prefs.getString(AppConfig.KEY_ROOM, "").orEmpty().ifEmpty { AppConfig.randomRoom() }
        )
        binding.etRoom.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString(AppConfig.KEY_ROOM, s?.toString().orEmpty().trim()).apply()
                updateHint()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        binding.btnDice.setOnClickListener {
            binding.etRoom.setText(AppConfig.randomRoom())
        }
    }

    // ------------------------------------------------------------ 参数
    private fun bindQuality() {
        binding.sbFps.max = AppConfig.MAX_FPS - 1
        binding.sbFps.progress = (AppConfig.DEFAULT_FPS - 1).coerceAtLeast(0)
        binding.sbQuality.max = 70
        binding.sbQuality.progress = (AppConfig.DEFAULT_QUALITY - 25).coerceAtLeast(0)
        binding.sbFps.setOnSeekBarChangeListener(seekListener())
        binding.sbQuality.setOnSeekBarChangeListener(seekListener())
        updateParamLabels()

        preset = prefs.getInt(AppConfig.KEY_RESOLUTION, AppConfig.DEFAULT_RESOLUTION)
        // 摄像头只开放 480/720/1080 三档，超出范围时归一到 720
        if (preset !in AppConfig.CAMERA_PRESETS) preset = 720
        val chips = listOf(binding.chip480, binding.chip720, binding.chip1080)
        chips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                preset = AppConfig.CAMERA_PRESETS[index]
                updateChips()
                updateResolutionHint()
            }
        }
        updateChips()
        updateResolutionHint()

        encoderMode = prefs.getString(AppConfig.KEY_ENCODER, AppConfig.ENCODER_H264)
            ?: AppConfig.ENCODER_H264
        binding.chipEncoder.setOnClickListener { cycleEncoder() }
        updateEncoderChip()
    }

    private fun seekListener() = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) =
            updateParamLabels()

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun bindOptions() {
        privateMode = prefs.getBoolean(AppConfig.KEY_PRIVATE, false)
        binding.switchPrivate.isChecked = privateMode
        binding.switchPrivate.setOnCheckedChangeListener { _, checked ->
            privateMode = checked
            updatePrivateHint()
        }
        updatePrivateHint()

        audioEnabled = prefs.getBoolean(AppConfig.KEY_AUDIO, AppConfig.DEFAULT_AUDIO)
        binding.switchAudio.isChecked = audioEnabled
        binding.switchAudio.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasAudioPermission()) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                audioEnabled = checked
                updateAudioHint()
            }
        }
        updateAudioHint()

        remoteControlEnabled = prefs.getBoolean(AppConfig.KEY_REMOTE_CONTROL, false)
        binding.switchRemote.isChecked = remoteControlEnabled
        binding.switchRemote.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!ExpDisclaimer.isAccepted(this)) {
                    binding.switchRemote.isChecked = false
                    ExpDisclaimer.require(this, onAccept = { enableRemoteControl() })
                } else {
                    enableRemoteControl()
                }
            } else {
                remoteControlEnabled = false
                prefs.edit().putBoolean(AppConfig.KEY_REMOTE_CONTROL, false).apply()
                updateRemoteHint()
            }
        }
        updateRemoteHint()

        binding.tvRemoteControlManage.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                toast("无法打开无障碍设置，请手动前往系统设置")
            }
        }

        binding.btnServerEdit.setOnClickListener {
            val et = binding.etServer
            if (et.visibility == View.VISIBLE) {
                val v = et.text.toString().trim()
                if (!AppConfig.isValidServer(v)) {
                    toast("地址需以 ws:// 或 wss:// 开头")
                    return@setOnClickListener
                }
                prefs.edit().putString(AppConfig.KEY_SERVER, v).apply()
                et.visibility = View.GONE
                updateHint()
            } else {
                et.setText(server())
                et.visibility = View.VISIBLE
                et.requestFocus()
            }
        }
    }

    private fun bindPermissions() {
        binding.btnOverlay.setOnClickListener { requestOverlayPermission() }
        binding.btnNotify.setOnClickListener { requestNotificationPermission() }
    }

    private fun toggleAdvanced() {
        advancedOpen = !advancedOpen
        binding.advancedPanel.visibility = if (advancedOpen) View.VISIBLE else View.GONE
        binding.btnAdvanced.text = if (advancedOpen) "收起高级设置 ⌃" else "高级设置 ›"
        updateChips()
    }

    private fun restoreAdvanced() {
        advancedOpen = prefs.getBoolean(KEY_ADVANCED_OPEN, false)
        binding.advancedPanel.visibility = if (advancedOpen) View.VISIBLE else View.GONE
        binding.btnAdvanced.text = if (advancedOpen) "收起高级设置 ⌃" else "高级设置 ›"
    }

    override fun onPause() {
        prefs.edit().putBoolean(KEY_ADVANCED_OPEN, advancedOpen).apply()
        super.onPause()
    }

    // ------------------------------------------------------------ 开播
    private fun onCameraClicked() {
        if (isLive()) {
            openLive()
            return
        }
        saveOptions()
        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        startCameraLive()
    }

    private fun startCameraLive() {
        if (room().isEmpty()) {
            toast("请先填写房间号")
            return
        }
        if (!requiresOverlay()) {
            // 摄像头直播不强制浮窗：直播间页面本身就是控制台
            startActivity(
                Intent(this, LiveActivity::class.java)
                    .putExtra(AppConfig.KEY_MODE, AppConfig.MODE_CAMERA)
                    .putExtra(AppConfig.KEY_SERVER, server())
                    .putExtra(AppConfig.KEY_ROOM, room())
            )
            return
        }
        startActivity(
            Intent(this, LiveActivity::class.java)
                .putExtra(AppConfig.KEY_MODE, AppConfig.MODE_CAMERA)
                .putExtra(AppConfig.KEY_SERVER, server())
                .putExtra(AppConfig.KEY_ROOM, room())
        )
    }

    /** 摄像头直播直接进直播页（预览 + 一键开始），浮窗只在屏幕直播时用 */
    private fun requiresOverlay(): Boolean = false

    private fun onScreenClicked() {
        if (isLive()) {
            toast("直播进行中，请先结束当前直播")
            return
        }
        if (room().isEmpty()) {
            toast("请先填写房间号")
            return
        }
        saveOptions()
        if (!Settings.canDrawOverlays(this)) {
            toast("请先允许「显示在其他应用上层」，直播时需要它提供停止按钮")
            requestOverlayPermission()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startScreenLive(resultCode: Int, data: Intent) {
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamService.EXTRA_RESULT_DATA, data)
            putExtra(StreamService.EXTRA_SERVER, server())
            putExtra(StreamService.EXTRA_ROOM, room())
            putExtra(StreamService.EXTRA_FPS, currentFps())
            putExtra(StreamService.EXTRA_QUALITY, currentQuality())
            putExtra(StreamService.EXTRA_RESOLUTION, preset)
            putExtra(StreamService.EXTRA_AUDIO, audioEnabled)
            putExtra(StreamService.EXTRA_ENCODER, encoderMode)
            putExtra(StreamService.EXTRA_TOKEN, token)
            putExtra(StreamService.EXTRA_TITLE, "")
            putExtra(StreamService.EXTRA_PRIVATE, privateMode)
            putExtra(StreamService.EXTRA_REMOTE_CONTROL, remoteControlEnabled)
        }
        ContextCompat.startForegroundService(this, intent)
        toast("屏幕直播已开始，切到要分享的画面即可")
        binding.root.postDelayed({ refreshLiveState() }, 800)
    }

    private fun openLive() {
        startActivity(Intent(this, LiveActivity::class.java))
    }

    private fun openWebReceiver() {
        if (room().isEmpty()) {
            toast("请先填写房间号")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(AppConfig.webUrl(server(), room()))))
        } catch (e: Exception) {
            toast("无法打开浏览器：${e.message}")
        }
    }

    private fun saveOptions() {
        prefs.edit()
            .putString(AppConfig.KEY_SERVER, server())
            .putString(AppConfig.KEY_ROOM, room())
            .putInt(AppConfig.KEY_RESOLUTION, preset)
            .putBoolean(AppConfig.KEY_AUDIO, audioEnabled)
            .putString(AppConfig.KEY_ENCODER, encoderMode)
            .putBoolean(AppConfig.KEY_PRIVATE, privateMode)
            .putBoolean(AppConfig.KEY_REMOTE_CONTROL, remoteControlEnabled)
            .apply()
    }

    // ------------------------------------------------------------ 生命周期
    override fun onResume() {
        super.onResume()
        renderUser()
        refreshPermissions()
        refreshLiveState()
        if (remoteControlEnabled && !isAccessibilityEnabled()) {
            remoteControlEnabled = false
            binding.switchRemote.isChecked = false
            prefs.edit().putBoolean(AppConfig.KEY_REMOTE_CONTROL, false).apply()
        }
        updateRemoteHint()
    }

    override fun onStart() {
        super.onStart()
        StreamService.onStats = { stats -> runOnUiThread { renderLiveStats(stats) } }
        StreamService.onState = { text -> runOnUiThread { binding.tvLiveState.text = text } }
        refreshLiveState()
    }

    override fun onStop() {
        StreamService.onStats = null
        StreamService.onState = null
        super.onStop()
    }

    // ------------------------------------------------------------ 状态
    private fun isLive(): Boolean = StreamService.running || CameraStreamService.running

    private fun refreshLiveState() {
        val screen = StreamService.running
        val camera = CameraStreamService.running
        val live = screen || camera
        binding.liveBanner.visibility = if (live) View.VISIBLE else View.GONE
        binding.targetCard.visibility = if (live) View.GONE else View.VISIBLE
        binding.cameraCard.visibility = if (live) View.GONE else View.VISIBLE
        binding.btnCamera.text = if (live) "回到直播间" else "摄像头直播"
        binding.btnScreen.visibility = if (live) View.GONE else View.VISIBLE
        binding.tvHint.visibility = if (live) View.GONE else View.VISIBLE
        if (live) {
            binding.tvLiveState.text =
                if (camera) "摄像头直播中 · 房间 ${room()}" else "屏幕直播中 · 房间 ${room()}"
            binding.tvLiveStats.text = "正在等待统计…"
            val color = ContextCompat.getColor(this, R.color.ok)
            binding.dotLive.background.mutate().setTint(color)
        } else {
            binding.tvLiveStats.text = ""
        }
    }

    private fun renderLiveStats(stats: StreamStats) {
        if (!StreamService.running) return
        val turboTag = if (stats.turbo) " · 独享加速" else ""
        binding.tvLiveStats.text =
            "${stats.viewers} 人观看 · ${stats.kbps} kbps · ${stats.frames} 帧$turboTag"
    }

    private fun updateHint() {
        val r = room()
        binding.tvTargetHint.text = if (r.isEmpty()) {
            "填一个 4 位数字，观看的人输入同样的号码就能看到画面"
        } else {
            "网页观看：${AppConfig.webUrl(server(), r).removePrefix("https://")}"
        }
    }

    private fun updateParamLabels() {
        binding.tvFps.text = "${currentFps()} fps"
        binding.tvQuality.text = "${currentQuality()} %"
    }

    private fun currentFps(): Int = (binding.sbFps.progress + 1).coerceIn(1, AppConfig.MAX_FPS)

    private fun currentQuality(): Int = (binding.sbQuality.progress + 25).coerceIn(20, 95)

    private fun updateChips() {
        val chips = listOf(binding.chip480, binding.chip720, binding.chip1080)
        chips.forEachIndexed { index, chip ->
            val selected = AppConfig.CAMERA_PRESETS[index] == preset
            chip.setBackgroundResource(if (selected) R.drawable.bg_chip_on else R.drawable.bg_chip)
            chip.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.white else R.color.muted)
            )
        }
    }

    private fun updateResolutionHint() {
        binding.tvResolutionHint.text = when (preset) {
            480 -> "短边 480p：省流量，弱网首选"
            1080 -> "短边 1080p：画质最好，需要较好的网络"
            else -> "短边 720p：清晰与流量的平衡点"
        }
    }

    private fun cycleEncoder() {
        if (encoderMode == AppConfig.ENCODER_H265 && !ExpDisclaimer.isAccepted(this)) return
        encoderMode = if (encoderMode == AppConfig.ENCODER_H264) {
            AppConfig.ENCODER_JPEG
        } else if (encoderMode == AppConfig.ENCODER_JPEG) {
            AppConfig.ENCODER_H265
        } else {
            AppConfig.ENCODER_H264
        }
        if (encoderMode == AppConfig.ENCODER_H265 && !ExpDisclaimer.isAccepted(this)) {
            encoderMode = AppConfig.ENCODER_H264
            ExpDisclaimer.require(this, onAccept = {
                encoderMode = AppConfig.ENCODER_H265
                updateEncoderChip()
                toast("H.265 已启用（实验性），不支持时自动回退")
            })
        }
        updateEncoderChip()
    }

    private fun updateEncoderChip() {
        binding.chipEncoder.text = when (encoderMode) {
            AppConfig.ENCODER_H265 -> "H.265"
            AppConfig.ENCODER_JPEG -> "JPEG"
            else -> "H.264"
        }
        binding.tvEncoderHint.text = when (encoderMode) {
            AppConfig.ENCODER_H265 -> "实验：码率更低，需设备支持 HEVC"
            AppConfig.ENCODER_JPEG -> "兼容：什么设备都能播，但流量大"
            else -> "推荐：硬件编码，省电省流量"
        }
        binding.chipEncoder.setTextColor(
            ContextCompat.getColor(
                this,
                if (encoderMode == AppConfig.ENCODER_H265) R.color.warn else R.color.text
            )
        )
    }

    private fun updatePrivateHint() {
        binding.tvPrivateHint.text =
            if (privateMode) "已开启：只有知道房间号的人能进" else "关闭：房间会出现在大厅"
    }

    private fun updateAudioHint() {
        binding.tvAudioHint.text = when {
            !audioEnabled -> "关闭：只传画面"
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> "系统版本不支持内录"
            else -> "抓取手机播放的声音"
        }
    }

    private fun updateRemoteHint() {
        binding.tvRemoteHint.text = when {
            !remoteControlEnabled -> "关闭：观众只能看，不能操作"
            !isAccessibilityEnabled() -> "已开启，但无障碍服务未开，申请会被拒绝"
            else -> "开启：观众可申请操作，你能随时收回"
        }
    }

    // ------------------------------------------------------------ 权限
    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            toast("浮窗权限已授权")
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
            toast("通知权限已授权")
        }
    }

    private fun refreshPermissions() {
        val overlayOk = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text =
            getString(if (overlayOk) R.string.perm_granted else R.string.perm_denied)
        binding.tvOverlayStatus.setTextColor(
            ContextCompat.getColor(this, if (overlayOk) R.color.ok else R.color.warn)
        )
        binding.btnOverlay.text = if (overlayOk) "已开" else "开启"
        binding.btnOverlay.isEnabled = !overlayOk

        val notifyOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        binding.tvNotifyStatus.text =
            getString(if (notifyOk) R.string.perm_granted else R.string.perm_denied)
        binding.tvNotifyStatus.setTextColor(
            ContextCompat.getColor(this, if (notifyOk) R.color.ok else R.color.warn)
        )
        binding.btnNotify.text = if (notifyOk) "已开" else "开启"
        binding.btnNotify.isEnabled = !notifyOk
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ 实验性功能
    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${RemoteControlService::class.java.name}"
        val enabled = try {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            null
        } ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun enableRemoteControl() {
        remoteControlEnabled = true
        binding.switchRemote.isChecked = true
        prefs.edit().putBoolean(AppConfig.KEY_REMOTE_CONTROL, true).apply()
        updateRemoteHint()
        if (!isAccessibilityEnabled()) {
            toast("请在系统设置中启用「${getString(R.string.app_name)}」无障碍服务")
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                toast("无法打开无障碍设置，请手动前往系统设置")
            }
        } else {
            toast("远程控制已开启（实验性）")
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("退出后需要重新登录才能开播，确定吗？")
            .setPositiveButton("退出") { _, _ ->
                prefs.edit().remove(AppConfig.KEY_TOKEN).apply()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 工具
    private fun server(): String =
        (if (binding.etServer.visibility == View.VISIBLE) binding.etServer.text.toString().trim()
        else prefs.getString(AppConfig.KEY_SERVER, AppConfig.DEFAULT_SERVER).orEmpty())
            .ifBlank { AppConfig.DEFAULT_SERVER }

    private fun room(): String = binding.etRoom.text.toString().trim()

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val KEY_ADVANCED_OPEN = "advanced_open"
        const val KEY_SEEN_CAMERA_TIP = "seen_camera_tip"
    }
}
