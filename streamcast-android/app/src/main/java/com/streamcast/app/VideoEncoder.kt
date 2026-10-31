package com.streamcast.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer

/**
 * 硬件视频编码器（Surface 输入），支持 H.264 / H.265（HEVC）。
 *
 * 关键点：画面直接从 VirtualDisplay 送进编码器的输入 Surface，
 * 全程不经过 Bitmap、不做 JPEG 压缩，因此 CPU 占用和带宽都比 JPEG 方案低一个数量级，
 * 这也是云游戏能做到 1080p60 的根本原因。
 *
 * H.265 为实验性功能：同画质下码率约比 H.264 低 30%~50%，
 * 但依赖设备具备 HEVC 硬件编码器；创建失败时由调用方回退到 H.264。
 *
 * 输出为 Annex-B 码流；开启 PREPEND_HEADER_TO_SYNC_FRAMES 后，
 * 每个关键帧前面都会带上参数集（H.264: SPS/PPS；H.265: VPS/SPS/PPS），
 * 接收端无需额外信令也能自描述解码。
 */
class VideoEncoder(
    private val mime: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (ByteArray) -> Unit
) {

    val isHevc: Boolean
        get() = mime == MediaFormat.MIMETYPE_VIDEO_HEVC

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var configSent = false

    /** 启动编码器并返回供 VirtualDisplay 写入的输入 Surface */
    fun start(): Surface {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
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
            // 关键帧自带参数集，接收端可自描述解码
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }

        val c = MediaCodec.createEncoderByType(mime)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()
        c.start()

        codec = c
        inputSurface = surface
        running = true

        val t = Thread({ drain() }, if (isHevc) "streamcast-hevc" else "streamcast-h264")
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
                    // 少部分设备的 HEVC 编码器不返回 csd-0；
                    // 此时也要把尺寸下发，接收端可依赖码流内联的 VPS/SPS/PPS 自描述解码
                    if (!configSent) {
                        configSent = true
                        onConfig(csd ?: ByteArray(0))
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

    companion object {
        /** 设备是否具备 HEVC 硬件编码能力（用于界面提示，不参与编解码决策） */
        fun hevcEncodeSupported(): Boolean = hasCodec(MediaFormat.MIMETYPE_VIDEO_HEVC, true)

        /** 设备是否具备 HEVC 硬件解码能力（接收端用） */
        fun hevcDecodeSupported(): Boolean = hasCodec(MediaFormat.MIMETYPE_VIDEO_HEVC, false)

        /**
         * 遍历系统编解码器列表判断能力。
         * 不用 MediaCodecList 的静态查找方法：它们在部分 SDK stub 中不可见，
         * 直接遍历 codecInfos 更稳定。
         */
        private fun hasCodec(mime: String, encoder: Boolean): Boolean = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder == encoder && info.supportedTypes.any { it.equals(mime, true) }
            }
        } catch (e: Exception) {
            false
        }
    }
}
