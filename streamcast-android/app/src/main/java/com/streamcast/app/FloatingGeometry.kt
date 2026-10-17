package com.streamcast.app

/**
 * 悬浮窗折叠/展开的位置计算。
 * 刻意不依赖任何 Android 框架类，便于用 JVM 单元测试直接验证吸附与边界行为。
 */
object FloatingGeometry {

    /** 折叠后是否吸附到屏幕左边缘（以面板中心点与屏幕中线比较） */
    fun snapToLeft(panelX: Int, panelWidth: Int, screenWidth: Int): Boolean =
        panelX + panelWidth / 2 < screenWidth / 2

    /** 折叠后的横向坐标：左边缘贴 0，右边缘贴 screenWidth - bubbleWidth */
    fun collapsedX(panelX: Int, panelWidth: Int, screenWidth: Int, bubbleWidth: Int): Int =
        if (snapToLeft(panelX, panelWidth, screenWidth)) {
            0
        } else {
            (screenWidth - bubbleWidth).coerceAtLeast(0)
        }

    /**
     * 纵向位置夹取：保证浮窗完整落在屏幕内，且不钻到状态栏下面。
     * 当可放区域不足时（屏幕比浮窗还小），以下限为准，避免出现 min > max 的非法区间。
     */
    fun clampY(y: Int, minY: Int, viewHeight: Int, screenHeight: Int): Int {
        val maxY = (screenHeight - viewHeight).coerceAtLeast(minY)
        return y.coerceIn(minY, maxY)
    }
}
