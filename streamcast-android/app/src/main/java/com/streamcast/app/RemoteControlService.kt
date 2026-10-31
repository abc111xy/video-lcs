package com.streamcast.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * 远程控制执行器（实验性功能）。
 *
 * 设计上刻意保持「最小能力」：
 *  - 只声明 canPerformGestures，不声明 canRetrieveWindowContent，
 *    因此无法读取屏幕文字、控件树、输入框内容；
 *  - 不监听任何界面事件（onAccessibilityEvent 空实现），不做后台采集；
 *  - 只在主播明确授权、且直播进行中时，由 StreamService 调用。
 *
 * 手势必须串行派发：Android 在同一个 AccessibilityService 上并发 dispatchGesture
 * 会取消前一个手势，表现为「点了没反应」或「滑到一半断掉」。
 */
class RemoteControlService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: RemoteControlService? = null

        /** 服务是否已连接（用户在系统设置中启用且系统已绑定） */
        fun isReady(): Boolean = instance != null

        fun tap(x: Float, y: Float): Boolean = instance?.let { svc ->
            svc.enqueue { svc.performTap(x, y) }
            true
        } ?: false

        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int): Boolean =
            instance?.let { svc ->
                svc.enqueue { svc.performSwipe(x1, y1, x2, y2, durationMs) }
                true
            } ?: false

        /** key: back / home / recents / notifications */
        fun key(key: String): Boolean = instance?.let { svc ->
            val action = when (key) {
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                else -> return@let false
            }
            svc.enqueue { svc.performKey(action) }
            true
        } ?: false
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val queue = ArrayDeque<() -> Unit>()
    private var busy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 刻意留空：本服务不读取、不监听界面内容
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onDestroy() {
        instance = null
        synchronized(lock) {
            queue.clear()
            busy = false
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------ 串行派发
    private fun enqueue(action: () -> Unit) {
        main.post {
            synchronized(lock) { queue.addLast(action) }
            pump()
        }
    }

    private fun pump() {
        val next: (() -> Unit)? = synchronized(lock) {
            if (busy) return
            val a = queue.removeFirstOrNull() ?: return
            busy = true
            a
        }
        next?.invoke()
    }

    private fun finish() {
        synchronized(lock) { busy = false }
        // 给系统一个极短的间隔，避免连续手势被判定为冲突而取消
        main.postDelayed({ pump() }, 24)
    }

    private val callback = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) = finish()
        override fun onCancelled(gestureDescription: GestureDescription?) = finish()
    }

    // ------------------------------------------------------------ 手势
    private fun performTap(x: Float, y: Float) {
        val clamped = clampPoint(x, y)
        val path = Path().apply { moveTo(clamped.first, clamped.second) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        if (!safeDispatch(gesture)) finish()
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
        val a = clampPoint(x1, y1)
        val b = clampPoint(x2, y2)
        val path = Path().apply {
            moveTo(a.first, a.second)
            lineTo(b.first, b.second)
        }
        val duration = durationMs.coerceIn(60, 1500).toLong()
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        if (!safeDispatch(gesture)) finish()
    }

    private fun performKey(action: Int) {
        try {
            performGlobalAction(action)
        } catch (e: Exception) {
            // 部分系统会限制全局操作，忽略
        }
        finish()
    }

    private fun safeDispatch(gesture: GestureDescription): Boolean = try {
        dispatchGesture(gesture, callback, null)
    } catch (e: Exception) {
        false
    }

    /** 约束到屏幕内，避免异常坐标导致手势被系统丢弃 */
    private fun clampPoint(x: Float, y: Float): Pair<Float, Float> {
        val m = resources.displayMetrics
        val maxX = (m.widthPixels - 1).toFloat()
        val maxY = (m.heightPixels - 1).toFloat()
        return x.coerceIn(0f, maxX) to y.coerceIn(0f, maxY)
    }
}
