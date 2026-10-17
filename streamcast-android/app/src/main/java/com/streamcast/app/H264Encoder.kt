package com.streamcast.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer

/**
 * 硬件 H.264 编码器（Surface 输入）。
 *
 * 关键点：画面直接从 VirtualDisplay 送进编码器的输入 Surface，
 * 全程不经过 Bitmap、不做 JPEG 压缩，因此 CPU 占用和带宽都比 JPEG 方案低一个数量级，
 * 这也是云游戏能做到 1080p60 的根本原因。
 *
 * 输出为 Annex-B 格式的 H.264 码流；开启 PREPEND_HEADER_TO_SYNC_FRAMES 后，
 * 每个关键帧前面都会带上 SPS/PPS，接收端无需额外信令也能自描述解码。
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (ByteArray) -> Unit
) {

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var configSent = false

    /** 启动编码器并返回供 VirtualDisplay 写入的输入 Surface */
    fun start(): Surface {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // 1 秒一个关键帧：中途加入或丢帧重同步后最多 1 秒就能恢复画面
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            // 关键帧自带 SPS/PPS，接收端可自描述解码
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }

        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()
        c.start()

        codec = c
        inputSurface = surface
        running = true

        val t = Thread({ drain() }, "streamcast-h264")
        drainThread = t
        t.start()
        return surface
    }

    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
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
                    if (csd != null && csd.isNotEmpty() && !configSent) {
                        configSent = true
                        onConfig(csd)
                    }
                } catch (e: Exception) {
                    // 忽略
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
                // 单帧失败不影响后续
            } finally {
                try {
                    c.releaseOutputBuffer(index, false)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    /** 运行中动态调整目标码率（自适应码率用） */
    fun setBitrate(bps: Int) {
        val c = codec ?: return
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
            c.setParameters(params)
        } catch (e: Exception) {
            // 部分设备不支持动态调码率，忽略
        }
    }

    /** 请求立即产生关键帧（新观众加入时调用） */
    fun requestKeyframe() {
        val c = codec ?: return
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            c.setParameters(params)
        } catch (e: Exception) {
            // 部分设备不支持，忽略
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

        try {
            inputSurface?.release()
        } catch (e: Exception) {
            // ignore
        }
        inputSurface = null
    }
}
