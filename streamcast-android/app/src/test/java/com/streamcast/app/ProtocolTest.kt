package com.streamcast.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议常量与实验性功能的约定测试。
 *
 * 这些值同时被发送端、接收端、中继服务与网页端依赖，
 * 任意一项被误改都会造成「画面突然不动」或「控制发不出去」，
 * 因此在单测里钉死。
 */
class ProtocolTest {

    @Test
    fun frame_markers_are_unique() {
        val markers = listOf(
            AppConfig.FRAME_AUDIO,
            AppConfig.FRAME_VIDEO_H264,
            AppConfig.FRAME_CODEC_CONFIG,
            AppConfig.FRAME_RESYNC,
            AppConfig.FRAME_VIDEO_HEVC,
            AppConfig.FRAME_CODEC_CONFIG_HEVC
        )
        assertEquals("帧标记数量", 6, markers.size)
        assertEquals("帧标记不能重复", markers.size, markers.toSet().size)
        // 裸 JPEG 以 0xFF 开头，不能与任何标记冲突
        for (m in markers) {
            assertNotEquals("标记不能是 JPEG 起始字节", 0xFF.toByte(), m)
        }
    }

    @Test
    fun hevc_markers_match_server_contract() {
        // 与 server.js / watch.html 中约定的数值保持一致
        assertEquals(0x02.toByte(), AppConfig.FRAME_AUDIO)
        assertEquals(0x03.toByte(), AppConfig.FRAME_VIDEO_H264)
        assertEquals(0x04.toByte(), AppConfig.FRAME_CODEC_CONFIG)
        assertEquals(0x05.toByte(), AppConfig.FRAME_RESYNC)
        assertEquals(0x06.toByte(), AppConfig.FRAME_VIDEO_HEVC)
        assertEquals(0x07.toByte(), AppConfig.FRAME_CODEC_CONFIG_HEVC)
    }

    @Test
    fun encoder_modes_are_distinct() {
        val modes = listOf(
            AppConfig.ENCODER_H264,
            AppConfig.ENCODER_H265,
            AppConfig.ENCODER_JPEG
        )
        assertEquals(3, modes.toSet().size)
        assertTrue(AppConfig.ENCODER_H265 != AppConfig.ENCODER_H264)
    }

    @Test
    fun experiment_version_is_pinned() {
        // 版本号变化意味着需要用户重新阅读并同意免责声明
        assertEquals("1.0", AppConfig.EXP_VERSION)
        assertEquals(30, AppConfig.CONTROL_MAX_MINUTES)
    }

    @Test
    fun disclaimer_url_derives_from_ws_address() {
        assertEquals(
            "https://example.com/disclaimer.html",
            AppConfig.disclaimerUrl("wss://example.com/ws")
        )
        assertEquals(
            "http://127.0.0.1:3000/disclaimer.html",
            AppConfig.disclaimerUrl("ws://127.0.0.1:3000/ws")
        )
    }
}
