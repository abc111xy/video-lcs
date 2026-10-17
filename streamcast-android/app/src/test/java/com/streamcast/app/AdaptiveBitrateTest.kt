package com.streamcast.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自适应码率策略测试（含冷却、上下限、抖动抑制） */
class AdaptiveBitrateTest {

    private fun policy() = AdaptiveBitrate(
        minBitrate = 500_000,
        maxBitrate = 5_000_000,
        highWaterBytes = 384 * 1024,
        lowWaterBytes = 64 * 1024,
        downSamples = 2,
        upSamples = 6,
        cooldownMs = 1500
    )

    @Test
    fun starts_at_max() {
        val abr = policy()
        assertEquals(5_000_000, abr.currentBitrate())
        assertFalse(abr.degraded)
    }

    @Test
    fun sustained_backlog_triggers_downgrade() {
        val abr = policy()
        // 第一次积压只累计，不立刻调整
        assertNull(abr.onSample(1_000_000, 0))
        val next = abr.onSample(1_000_000, 500)
        assertNotNull("连续两次积压应触发降档", next)
        assertTrue("应降到 5M 的 70% 左右", next!! in 3_400_000..3_600_000)
        assertTrue(abr.degraded)
    }

    @Test
    fun single_spike_does_not_trigger() {
        val abr = policy()
        assertNull(abr.onSample(1_000_000, 0))
        assertNull("随后恢复正常，不应降档", abr.onSample(1_000, 500))
        assertEquals(5_000_000, abr.currentBitrate())
    }

    @Test
    fun cooldown_prevents_rapid_changes() {
        val abr = policy()
        abr.onSample(1_000_000, 0)
        assertNotNull(abr.onSample(1_000_000, 500))
        val afterFirst = abr.currentBitrate()
        // 冷却期内即使持续积压也不再变化
        assertNull(abr.onSample(1_000_000, 1000))
        assertEquals(afterFirst, abr.currentBitrate())
    }

    @Test
    fun never_drops_below_min() {
        val abr = policy()
        var t = 0L
        repeat(30) {
            abr.onSample(2_000_000, t)
            abr.onSample(2_000_000, t + 200)
            t += 2000
        }
        assertEquals(500_000, abr.currentBitrate())
    }

    @Test
    fun recovers_when_link_is_clear() {
        val abr = policy()
        abr.onSample(1_000_000, 0)
        abr.onSample(1_000_000, 100)
        val low = abr.currentBitrate()
        assertTrue(low < 5_000_000)

        var t = 2000L
        var recovered = false
        repeat(40) {
            if (abr.onSample(0, t) != null) recovered = true
            t += 500
        }
        assertTrue("链路空闲应逐步回升", recovered)
        assertEquals(5_000_000, abr.currentBitrate())
        assertFalse("回到满码率后不再标记降档", abr.degraded)
    }

    @Test
    fun mid_zone_neither_increases_nor_decreases() {
        val abr = policy()
        // 介于低水位与高水位之间：计数清零，不调整
        repeat(10) { assertNull(abr.onSample(200 * 1024, it * 500L)) }
        assertEquals(5_000_000, abr.currentBitrate())
    }
}
