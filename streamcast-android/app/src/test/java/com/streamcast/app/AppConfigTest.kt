package com.streamcast.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分辨率换算逻辑测试。
 * 该函数同时驱动界面预览与实际采集，必须保证行为符合预期。
 */
class AppConfigTest {

    @Test
    fun portrait1080x2400_presets() {
        // 短边 1080，各预设按短边缩放，长边等比
        assertEquals(270 to 600, AppConfig.targetSize(1080, 2400, 270))
        assertEquals(480 to 1066, AppConfig.targetSize(1080, 2400, 480))
        assertEquals(720 to 1600, AppConfig.targetSize(1080, 2400, 720))
        assertEquals(1080 to 2400, AppConfig.targetSize(1080, 2400, 1080))
    }

    @Test
    fun native_never_scales() {
        assertEquals(1080 to 2400, AppConfig.targetSize(1080, 2400, AppConfig.RES_NATIVE))
        assertEquals(1440 to 3200, AppConfig.targetSize(1440, 3200, AppConfig.RES_NATIVE))
    }

    @Test
    fun landscape_uses_short_side() {
        // 横屏 2400×1080：短边仍是 1080
        assertEquals(1600 to 720, AppConfig.targetSize(2400, 1080, 720))
        assertEquals(600 to 270, AppConfig.targetSize(2400, 1080, 270))
    }

    @Test
    fun preset_larger_than_screen_does_not_upscale() {
        // 720×1280 的屏幕选 1080P，应保持原分辨率而不是放大
        assertEquals(720 to 1280, AppConfig.targetSize(720, 1280, 1080))
        assertEquals(480 to 852, AppConfig.targetSize(720, 1280, 480))
    }

    @Test
    fun results_are_even_and_positive() {
        val screens = listOf(
            1080 to 2400, 1081 to 2401, 720 to 1280, 1440 to 3120,
            2400 to 1080, 540 to 960, 3 to 3
        )
        for ((w, h) in screens) {
            for (preset in AppConfig.RESOLUTION_PRESETS) {
                val (tw, th) = AppConfig.targetSize(w, h, preset)
                assertTrue("尺寸需为正数: $w×$h -> $tw×$th", tw >= 2 && th >= 2)
                assertEquals("宽需为偶数: $w×$h preset=$preset -> $tw", 0, tw % 2)
                assertEquals("高需为偶数: $w×$h preset=$preset -> $th", 0, th % 2)
                // 不允许放大
                assertTrue("不应放大: $w×$h -> $tw×$th", tw <= w && th <= h)
            }
        }
    }

    @Test
    fun aspect_ratio_is_preserved() {
        val (tw, th) = AppConfig.targetSize(1080, 2400, 720)
        val srcRatio = 1080.0 / 2400.0
        val outRatio = tw.toDouble() / th.toDouble()
        assertTrue("宽高比应基本一致", Math.abs(srcRatio - outRatio) < 0.01)
    }

    @Test
    fun bitrate_scales_linearly_with_fps() {
        val at30 = AppConfig.estimateBitrate(720, 1600, 30, 50)
        val at60 = AppConfig.estimateBitrate(720, 1600, 60, 50)
        assertTrue("720P60 码率应落在合理区间: $at60", at60 in 4_000_000..8_000_000)
        assertTrue(
            "帧率翻倍码率应约翻倍: $at30 -> $at60",
            Math.abs(at60 - at30 * 2) <= 200_000
        )
    }

    @Test
    fun bitrate_is_clamped_both_ends() {
        // 极小分辨率不应低于 1Mbps，避免编码器给不出可用画质
        assertEquals(1_000_000, AppConfig.estimateBitrate(270, 600, 10, 25))
        // 极高分辨率 + 高画质封顶 24Mbps，避免打爆上行
        assertTrue(AppConfig.estimateBitrate(1440, 3200, 60, 95) <= 24_000_000)
    }

    @Test
    fun bitrate_rises_with_quality() {
        val low = AppConfig.estimateBitrate(720, 1600, 30, 25)
        val high = AppConfig.estimateBitrate(720, 1600, 30, 90)
        assertTrue("画质越高码率越高: $low -> $high", high > low)
    }
}
