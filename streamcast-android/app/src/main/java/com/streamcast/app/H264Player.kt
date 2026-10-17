package com.streamcast.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.TextureView
import java.nio.ByteBuffer

/**
 * 接收端 H.264 硬件解码：MediaCodec 解码 → 直接渲染到 TextureView 的 Surface。
 * 全程不产生 Bitmap，配合硬件解码可轻松跑满 60fps。
 */
class H264Player(private val textureView: TextureView) {

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var csd: ByteArray? = null

    var framesRendered: Long = 0
        private set

    /** 等待下一个关键帧：期间丢弃所有非同步帧 */
    @Volatile
    private var awaitingKeyframe = false

    /**
     * 发送端丢弃积压后的重同步请求。
     * 立刻释放解码器，并只接受带 SPS/IDR 的帧，
     * 这样积压在管道里的旧帧全部被跳过，画面直接跳到直播边缘。
     */
    fun resync() {
        release()
        awaitingKeyframe = true
    }

    /** 收到编解码配置（0x04）。分辨率或参数集变化时重建解码器。 */
    fun onConfig(w: Int, h: Int, config: ByteArray) {
        if (config.isEmpty()) return
        val same = codec != null && w == width && h == height &&
                csd?.contentEquals(config) == true
        width = w
        height = h
        csd = config
        if (!same) {
            // 送端重建了编码器（例如旋转屏幕），这里必须跟着重建
            release()
        }
    }

    /** 投喂一个 H.264 访问单元（Annex-B）；offset 用于跳过帧类型标记字节 */
    fun submit(au: ByteArray, offset: Int = 0) {
        val size = au.size - offset
        if (size <= 0) return
        if (awaitingKeyframe) {
            if (!hasSyncNal(au, offset)) return
            awaitingKeyframe = false
        }
        if (!ensureStarted()) return
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

    private fun ensureStarted(): Boolean {
        if (codec != null) return true
        val config = csd ?: return false
        if (width <= 0 || height <= 0) return false
        // TextureView 还没 attach 时拿不到 SurfaceTexture，等下一帧再试
        if (!textureView.isAvailable) return false
        val texture = textureView.surfaceTexture ?: return false

        val s = Surface(texture)
        return try {
            texture.setDefaultBufferSize(width, height)
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                height
            )
            format.setByteBuffer("csd-0", ByteBuffer.wrap(config))
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            surface = s
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

    /** 扫描访问单元里是否含 SPS(7) 或 IDR(5) 分片 */
    private fun hasSyncNal(data: ByteArray, offset: Int): Boolean {
        var i = offset
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                val type = data[i + 3].toInt() and 0x1F
                if (type == 7 || type == 5) return true
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
