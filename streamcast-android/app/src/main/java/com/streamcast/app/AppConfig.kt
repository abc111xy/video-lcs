package com.streamcast.app

import android.net.Uri

/**
 * 全局配置与默认值。
 * 后端为「发布为应用」部署的中继服务，默认值已内置，接入即可用。
 */
object AppConfig {
    /** 中继服务 WebSocket 地址 */
    const val DEFAULT_SERVER = "wss://spl.app.workbuddy.host/ws"

    const val PREFS = "streamcast_prefs"
    const val KEY_SERVER = "server"
    const val KEY_ROOM = "room"
    const val KEY_RESOLUTION = "resolution"
    const val KEY_AUDIO = "audio"
    const val KEY_TOKEN = "token"
    const val KEY_USER_EMAIL = "user_email"
    const val KEY_PRIVATE = "private_room"
    const val KEY_TITLE = "room_title"
    const val KEY_NICKNAME = "nickname"
    const val KEY_AVATAR = "avatar"
    const val KEY_THEME = "theme"

    const val DEFAULT_FPS = 8
    const val MAX_FPS = 60
    const val DEFAULT_QUALITY = 50

    /** 手机原生分辨率（不做缩放） */
    const val RES_NATIVE = 0

    /** 分辨率预设：取屏幕短边像素；0 表示原生分辨率 */
    val RESOLUTION_PRESETS = listOf(270, 480, 720, 1080, RES_NATIVE)

    const val DEFAULT_RESOLUTION = 720

    /** 设备音频同步默认关闭，需用户显式开启 */
    const val DEFAULT_AUDIO = false

    /** 音频参数（发送端编码 / 接收端解码 / 网页端解码共用） */
    const val AUDIO_SAMPLE_RATE = 44100
    const val AUDIO_CHANNELS = 2
    const val AUDIO_BIT_RATE = 96000

    /**
     * 二进制帧类型标记。
     * 视频帧在 JPEG 模式下保持原有的裸 JPEG（以 0xFFD8 开头），不加前缀；
     * 音频帧以 0x02 开头，H.264 视频帧以 0x03 开头，编解码配置以 0x04 开头。
     * JPEG 不可能以 0x02/0x03/0x04 开头，因此不会混淆。
     */
    const val FRAME_AUDIO: Byte = 0x02
    const val FRAME_VIDEO_H264: Byte = 0x03
    const val FRAME_CODEC_CONFIG: Byte = 0x04

    /** 发送端丢弃积压后发出的「请跳到下一个关键帧」提示 */
    const val FRAME_RESYNC: Byte = 0x05

    /** 编码方式 */
    const val ENCODER_H264 = "h264"
    const val ENCODER_JPEG = "jpeg"

    const val KEY_ENCODER = "encoder"

    /**
     * 依据分辨率/帧率/画质估算目标码率（bit/s）。
     * 经验系数约 0.08 bit/像素/帧，画质滑杆作为码率系数（0.5x ~ 1.6x）。
     */
    fun estimateBitrate(width: Int, height: Int, fps: Int, quality: Int): Int {
        val base = width.toDouble() * height.toDouble() * fps.toDouble() * 0.08
        val factor = (quality / 50.0).coerceIn(0.5, 1.6)
        return (base * factor).toLong().coerceIn(1_000_000L, 24_000_000L).toInt()
    }

    fun resolutionLabel(px: Int): String =
        if (px == RES_NATIVE) "原生" else "${px}P"

    /**
     * 按分辨率预设换算目标采集尺寸：预设值取屏幕「短边」像素，长边等比缩放。
     * 预设为原生（0）时不缩放；预设大于屏幕短边时不放大，避免无意义插值。
     * 界面预览与实际采集共用此函数，保证所见即所得。
     */
    fun targetSize(screenW: Int, screenH: Int, preset: Int): Pair<Int, Int> {
        val shortSide = minOf(screenW, screenH)
        val scale = if (preset == RES_NATIVE || shortSide <= 0) {
            1f
        } else {
            minOf(1f, preset.toFloat() / shortSide.toFloat())
        }
        var w = (screenW * scale).toInt()
        var h = (screenH * scale).toInt()
        // 采集面/编码器尺寸取偶数更稳妥
        w -= w % 2
        h -= h % 2
        return maxOf(w, 2) to maxOf(h, 2)
    }

    fun randomRoom(): String = (1000..9999).random().toString()

    // ---------------------------------------------------------------- 主题
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"
    const val THEME_SYSTEM = "system"

    /** 开源仓库（与用户协议中的地址保持一致） */
    const val REPO_URL = "https://github.com/abc111xy/video-lcs"

    /** 由 WebSocket 地址推导出网页接收端地址 */
    fun webUrl(server: String, room: String): String {
        var base = server.trim()
        base = base.removePrefix("wss://").removePrefix("ws://")
        base = base.removePrefix("https://").removePrefix("http://")
        if (base.endsWith("/ws")) base = base.dropLast(3)
        if (base.endsWith("/")) base = base.dropLast(1)
        return "https://$base/watch.html?room=${Uri.encode(room)}"
    }

    /** 由 WebSocket 地址推导出 HTTP API 根地址（账号接口用） */
    fun httpBase(server: String): String {
        val trimmed = server.trim()
        var base = trimmed.removePrefix("wss://").removePrefix("ws://")
        base = base.removePrefix("https://").removePrefix("http://")
        if (base.endsWith("/ws")) base = base.dropLast(3)
        if (base.endsWith("/")) base = base.dropLast(1)
        val secure = trimmed.startsWith("wss://") || trimmed.startsWith("https://")
        return (if (secure) "https://" else "http://") + base
    }

    /** 服务条款 / 隐私政策地址 */
    fun termsUrl(server: String): String = httpBase(server) + "/terms.html"
    fun privacyUrl(server: String): String = httpBase(server) + "/privacy.html"

    fun isValidServer(server: String): Boolean {
        val s = server.trim()
        return s.startsWith("ws://") || s.startsWith("wss://")
    }
}
