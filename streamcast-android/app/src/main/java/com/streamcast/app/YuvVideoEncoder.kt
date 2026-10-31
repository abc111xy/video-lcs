package com.streamcast.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle

/**
 * 摄像头直播用的软件编码器（YUV 输入）。
 *
 * 屏幕采集走 Surface 输入（GPU 直接喂硬件编码器），而 camera2 的 YUV 帧在 CPU 里，
 * 用 ByteBuffer 模式输入即可，无需再转 Surface（少一次拷贝，也避免兼容问题）。
 *
 * 输出 Annex-B，关键帧自带 SPS/PPS，接收端可自描述解码。
 */
class YuvVideoEncoder(
    private val mime: String,
    private val width: Int,
    private val height: Int,
    bitrate: Int,
    fps: Int,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (ByteArray) -> Unit
) {

    private var codec: MediaCodec? = null
    private var drainThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var frameIndex = 0L
        private set

    private val bitrate = bitrate
    private val fps = fps

    val isHevc: Boolean
        get() = mime == MediaFormat.MIMETYPE_VIDEO_HEVC

    /**
     * 启动编码器。
     *
     * 颜色格式优先 YUV420Flexible，个别设备只认 Semi-Planar（NV12）或 Planar（I420），
     * 因此按候选列表逐个尝试，避免一上来就失败。
     */
    fun start(): Boolean {
        for (colorFormat in COLOR_CANDIDATES) {
            if (tryStart(colorFormat)) return true
        }
        return false
    }

    private fun tryStart(colorFormat: Int): Boolean {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        return try {
            val c = MediaCodec.createEncoderByType(mime)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            running = true
            val t = Thread({ drain(c) }, "streamcast-yuv-enc")
            drainThread = t
            t.start()
            true
        } catch (e: Exception) {
            stopQuietly()
            false
        }
    }

    private fun stopQuietly() {
        running = false
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
    }

    /** 送一帧 I420；编码器输入缓冲不足时直接丢弃这一帧（直播宁可丢帧也不排队） */
    fun submit(yuv: ByteArray): Boolean {
        if (!running) return false
        val c = codec ?: return false
        return try {
            val index = c.dequeueInputBuffer(0)
            if (index < 0) return false
            val buffer = c.getInputBuffer(index) ?: return false
            buffer.clear()
            buffer.put(yuv, 0, minOf(yuv.size, buffer.capacity()))
            c.queueInputBuffer(
                index, 0, minOf(yuv.size, buffer.capacity()),
                frameIndex * 1_000_000L / fps.coerceAtLeast(1), 0
            )
            frameIndex++
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var configSent = false
        while (running) {
            val index = try {
                c.dequeueOutputBuffer(info, 10_000)
            } catch (e: Exception) {
                break
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    val csd = c.outputFormat.getByteBuffer("csd-0")?.let { buf ->
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                    if (!configSent) {
                        configSent = true
                        onConfig(csd ?: ByteArray(0))
                    }
                } catch (e: Exception) {
                    // ignore
                }
                continue
            }
            if (index < 0) continue
            try {
                if (info.size > 0) {
                    val buffer = c.getOutputBuffer(index)
                    if (buffer != null) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val au = ByteArray(info.size)
                        buffer.get(au)
                        onFrame(au)
                    }
                }
            } catch (e: Exception) {
                // ignore
            } finally {
                try {
                    c.releaseOutputBuffer(index, false)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    fun setBitrate(bps: Int) {
        val c = codec ?: return
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
            c.setParameters(params)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun requestKeyframe() {
        val c = codec ?: return
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            c.setParameters(params)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun stop() {
        running = false
        try {
            drainThread?.join(300)
        } catch (e: Exception) {
            // ignore
        }
        drainThread = null
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
    }

    private companion object {
        /** 编码器输入颜色格式候选：灵活 → NV12 → I420 → YV12 */
        val COLOR_CANDIDATES = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            0x13 // COLOR_FormatYUV420PackedSemiPlanar，部分老设备取值为 19
        )
    }
}
