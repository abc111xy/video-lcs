package com.streamcast.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 悬浮窗折叠/展开的位置计算测试 */
class FloatingGeometryTest {

    @Test
    fun panel_on_left_half_snaps_left() {
        assertTrue(FloatingGeometry.snapToLeft(0, 200, 1000))
        assertTrue(FloatingGeometry.snapToLeft(100, 200, 1000))
        assertEquals(0, FloatingGeometry.collapsedX(100, 200, 1000, 22))
    }

    @Test
    fun panel_on_right_half_snaps_right() {
        assertFalse(FloatingGeometry.snapToLeft(800, 200, 1000))
        assertEquals(978, FloatingGeometry.collapsedX(800, 200, 1000, 22))
    }

    @Test
    fun exact_middle_goes_right_deterministically() {
        // 中心点正好在中线时不抖动，固定吸附右侧
        assertFalse(FloatingGeometry.snapToLeft(400, 200, 1000))
        assertEquals(978, FloatingGeometry.collapsedX(400, 200, 1000, 22))
    }

    @Test
    fun collapsed_x_never_negative_on_tiny_screens() {
        // 屏幕比气泡还窄的极端情况不应产生负坐标
        assertEquals(0, FloatingGeometry.collapsedX(0, 10, 10, 22))
    }

    @Test
    fun clampY_keeps_window_inside_screen() {
        assertEquals(24, FloatingGeometry.clampY(-500, 24, 44, 1000))
        assertEquals(956, FloatingGeometry.clampY(5000, 24, 44, 1000))
        assertEquals(300, FloatingGeometry.clampY(300, 24, 44, 1000))
    }

    @Test
    fun clampY_handles_screen_smaller_than_view() {
        // 屏幕比浮窗还小时以最小值为准，不抛异常也不返回非法区间
        assertEquals(24, FloatingGeometry.clampY(0, 24, 2000, 1000))
        assertEquals(24, FloatingGeometry.clampY(99999, 24, 2000, 1000))
    }
}
