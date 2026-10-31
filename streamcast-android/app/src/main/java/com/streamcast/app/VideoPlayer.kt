package com.streamcast.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.TextureView
import java.nio.ByteBuffer

/**
 * 接收端硬件视频解码：MediaCodec 解码 → 直接渲染到 TextureView 的 Surface。
 * 全程不产生 Bitmap，配合硬件解码可轻松跑满 60fps。
 *
 * 同时支持 H.264 与 H.265（HEVC，实验性）。两者共用同一条渲染管线，
 * 只在 MIME、参数集与关键帧判定上不同。
 */
class VideoPlayer(private val textureView: TextureView) {

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var csd: ByteArray? = null

    /** 当前解码器对应的编码类型 */
    private var hevc = false

    var framesRendered: Long = 0
        private set

    /** 等待下一个关键帧：期间丢弃所有非同步帧 */
    @Volatile
    private var awaitingKeyframe = false

    /**
     * 发送端丢弃积压后的重同步请求。
     * 立刻释放解码器，并只接受带参数集/IDR 的帧，
     * 这样积压在管道里的旧帧全部被跳过，画面直接跳到直播边缘。
     */
    fun resync() {
        release()
        awaitingKeyframe = true
    }

    /** 收到编解码配置（0x04 / 0x07）。编码类型、分辨率或参数集变化时重建解码器。 */
    fun onConfig(w: Int, h: Int, config: ByteArray, isHevc: Boolean) {
        if (w <= 0 || h <= 0) return
        val sameParams = config.isEmpty() || csd?.contentEquals(config) == true
        val same = codec != null && w == width && h == height && isHevc == hevc && sameParams
        width = w
        height = h
        if (config.isNotEmpty()) csd = config
        hevc = isHevc
        if (!same) {
            // 发送端重建了编码器（例如旋转屏幕、切换编码），这里必须跟着重建
            release()
        }
    }

    /** 投喂一个访问单元（Annex-B）；offset 用于跳过帧类型标记字节 */
    fun submit(au: ByteArray, offset: Int = 0, isHevc: Boolean = false) {
        val size = au.size - offset
        if (size <= 0) return
        // 编码类型变了：旧解码器无法复用，先释放并等待新的参数集
        if (codec != null && isHevc != hevc) {
            release()
            awaitingKeyframe = true
        }
        if (awaitingKeyframe) {
            if (!hasSyncNal(au, offset, isHevc)) return
            awaitingKeyframe = false
        }
        if (!ensureStarted(isHevc)) return
        val c = codec ?: return
        try {
            val index = c.dequeueInputBuffer(0)
            if (index >= 0) {
                val buffer = c.getInputBuffer(index)
                if (buffer != null) {
                    buffer.clear()
                    buffer.put(au, offset, size)
                    c.queueInputBuffer(index, 0, size, 0, 0)
                }
            }
            drain()
        } catch (e: Exception) {
            // 单帧异常忽略
        }
    }

    private fun ensureStarted(isHevc: Boolean): Boolean {
        if (codec != null) return true
        if (width <= 0 || height <= 0) return false
        // TextureView 还没 attach 时拿不到 SurfaceTexture，等下一帧再试
        if (!textureView.isAvailable) return false
        val texture = textureView.surfaceTexture ?: return false

        val mime = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val s = Surface(texture)
        return try {
            texture.setDefaultBufferSize(width, height)
            val format = MediaFormat.createVideoFormat(mime, width, height)
            // csd 可能为空（编码器未提供参数集）：此时依赖码流内联的 VPS/SPS/PPS
            csd?.takeIf { it.isNotEmpty() }?.let { c ->
                format.setByteBuffer("csd-0", ByteBuffer.wrap(c))
                // HEVC 部分实现要求把参数集也放进 csd-1，重复写入一份成本极低
                if (isHevc) format.setByteBuffer("csd-1", ByteBuffer.wrap(c))
            }
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            surface = s
            hevc = isHevc
            true
        } catch (e: Exception) {
            try {
                s.release()
            } catch (ignore: Exception) {
                // ignore
            }
            false
        }
    }

    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = try {
                c.dequeueOutputBuffer(info, 0)
            } catch (e: Exception) {
                return
            }
            if (index < 0) return
            val render = info.size > 0
            try {
                c.releaseOutputBuffer(index, render)
            } catch (e: Exception) {
                // ignore
            }
            if (render) framesRendered++
        }
    }

    /**
     * 扫描访问单元里是否含随机接入点。
     * H.264：SPS(7) 或 IDR(5)；
     * H.265：VPS(32)/SPS(33) 或 IRAP（16..23，含 BLA/IDR/CRA）。
     */
    private fun hasSyncNal(data: ByteArray, offset: Int, isHevc: Boolean): Boolean {
        var i = offset
        while (i + 4 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (isHevc) {
                    val t = (data[i + 3].toInt() shr 1) and 0x3F
                    if (t in 16..23 || t == 32 || t == 33) return true
                } else {
                    val t = data[i + 3].toInt() and 0x1F
                    if (t == 7 || t == 5) return true
                }
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    fun release() {
        try {
            codec?.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            // ignore
        }
        codec = null
        try {
            surface?.release()
        } catch (e: Exception) {
            // ignore
        }
        surface = null
    }
}
