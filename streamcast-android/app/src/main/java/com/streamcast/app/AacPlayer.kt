package com.streamcast.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat

/**
 * 接收端音频播放器：AAC（ADTS 封装）→ MediaCodec 解码 → AudioTrack 流式播放。
 *
 * 采用 ADTS 而非裸 AAC，解码器可直接从帧头解析采样率/声道，
 * 无需中继服务额外下发编解码配置（中继只转发二进制帧，不转发自定义信令）。
 */
class AacPlayer {

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null

    /** 已播放帧数，用于界面展示 */
    var framesPlayed: Long = 0
        private set

    /**
     * 投喂一帧 ADTS 音频数据。
     * @param data 原始消息
     * @param offset 有效数据起始位置（用于跳过帧类型标记字节）
     */
    fun feed(data: ByteArray, offset: Int = 0) {
        val size = data.size - offset
        // 至少要有 7 字节 ADTS 头才有意义
        if (size <= 7) return
        if (codec == null && !start()) return
        val c = codec ?: return
        try {
            val inIndex = c.dequeueInputBuffer(0)
            if (inIndex >= 0) {
                val buffer = c.getInputBuffer(inIndex)
                if (buffer != null) {
                    buffer.clear()
                    buffer.put(data, offset, size)
                    c.queueInputBuffer(inIndex, 0, size, 0, 0)
                }
            }
            drain()
        } catch (e: Exception) {
            // 单帧异常不影响整体播放
        }
    }

    private fun start(): Boolean {
        return try {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AppConfig.AUDIO_SAMPLE_RATE,
                AppConfig.AUDIO_CHANNELS
            )
            format.setInteger(MediaFormat.KEY_IS_ADTS, 1)
            c.configure(format, null, null, 0)
            c.start()
            codec = c

            val minBuffer = AudioTrack.getMinBufferSize(
                AppConfig.AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AppConfig.AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(if (minBuffer > 0) minBuffer * 2 else 8192)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            true
        } catch (e: Exception) {
            release()
            false
        }
    }

    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = try {
                c.dequeueOutputBuffer(info, 0)
            } catch (e: Exception) {
                return
            }
            if (outIndex < 0) return
            try {
                if (info.size > 0) {
                    val buffer = c.getOutputBuffer(outIndex)
                    if (buffer != null) {
                        val pcm = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(pcm)
                        // 非阻塞写入：音频积压时直接丢弃，避免阻塞 WebSocket 接收线程拖累画面
                        track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                        framesPlayed++
                    }
                }
            } catch (e: Exception) {
                // ignore
            } finally {
                try {
                    c.releaseOutputBuffer(outIndex, false)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
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
            track?.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            track?.release()
        } catch (e: Exception) {
            // ignore
        }
        track = null
    }
}
