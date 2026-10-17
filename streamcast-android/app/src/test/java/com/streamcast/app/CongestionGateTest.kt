package com.streamcast.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CongestionGateTest {

    private fun gate() = CongestionGate(highBytes = 512 * 1024, lowBytes = 128 * 1024)

    @Test
    fun healthy_queue_does_not_drop() {
        val g = gate()
        repeat(10) { assertFalse(g.shouldDrop(100 * 1024)) }
        assertFalse(g.isDropping())
    }

    @Test
    fun backlog_enters_drop_mode() {
        val g = gate()
        assertFalse(g.shouldDrop(300 * 1024))
        assertTrue("超过高水位应开始丢帧", g.shouldDrop(600 * 1024))
        assertTrue(g.isDropping())
    }

    @Test
    fun hysteresis_keeps_dropping_until_low_watermark() {
        val g = gate()
        g.shouldDrop(600 * 1024)
        assertTrue("仍高于低水位，继续丢", g.shouldDrop(400 * 1024))
        assertTrue("仍高于低水位，继续丢", g.shouldDrop(200 * 1024))
        assertFalse("降到低水位以下才停", g.shouldDrop(100 * 1024))
        assertFalse(g.isDropping())
    }

    @Test
    fun exiting_drop_requests_exactly_one_resync() {
        val g = gate()
        g.shouldDrop(600 * 1024)
        assertFalse("丢帧过程中不应请求重同步", g.consumeResync())
        g.shouldDrop(50 * 1024)
        assertTrue("退出丢帧应请求一次重同步", g.consumeResync())
        assertFalse("标记应被清除", g.consumeResync())
    }

    @Test
    fun reset_clears_state() {
        val g = gate()
        g.shouldDrop(600 * 1024)
        g.reset()
        assertFalse(g.isDropping())
        assertFalse(g.consumeResync())
    }
}
