package com.streamcast.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import com.streamcast.app.databinding.FloatingWindowBinding
import kotlin.math.abs

/**
 * 直播状态悬浮窗（需要 SYSTEM_ALERT_WINDOW 权限）。
 *
 * 两种形态：
 *  - 展开态：显示直播时长 / 码率 / 观众数，含「停止」「收起」按钮，可拖动；
 *  - 折叠态：静置若干秒后自动吸附到屏幕最近一侧的边缘，缩成一个半透明半圆，只留一个状态点。
 *
 * 点一下半圆即可重新展开。注意：状态刷新（每秒一次）不会重置折叠计时，
 * 否则直播过程中浮窗将永远不会折叠。
 */
class FloatingWindow(private val context: Context) {

    private var binding: FloatingWindowBinding? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var rootView: View? = null

    private val handler = Handler(Looper.getMainLooper())
    private var collapseTask: Runnable? = null

    private var collapsed = false
    private var onStopAction: (() -> Unit)? = null

    /** 展开态位置：用户拖动后记住，展开时回到这里 */
    private var panelX = 0
    private var panelY = 0

    private val density: Float get() = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    fun show(onStop: () -> Unit) {
        if (binding != null) return
        onStopAction = onStop

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val themed = ContextThemeWrapper(context, R.style.Theme_StreamCast)
        val b = FloatingWindowBinding.inflate(LayoutInflater.from(themed))
        binding = b
        rootView = b.root

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        panelX = dp(24)
        panelY = dp(180)
        lp.x = panelX
        lp.y = panelY
        params = lp

        b.btnFloatStop.setOnClickListener { onStopAction?.invoke() }
        b.btnFloatCollapse.setOnClickListener { collapse() }
        attachPanelTouch(b.panelExpanded)
        attachBubbleTouch(b.bubbleCollapsed)
        b.bubbleCollapsed.setBackgroundResource(R.drawable.bg_bubble_at_left)

        try {
            wm.addView(b.root, lp)
        } catch (e: Exception) {
            binding = null
            rootView = null
            return
        }
        scheduleCollapse()
    }

    // ------------------------------------------------------------ 展开态
    private fun attachPanelTouch(panel: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        panel.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    cancelCollapse() // 按住期间不折叠
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > dp(10) || abs(dy) > dp(10))) dragging = true
                    if (dragging) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        clampPanel(lp)
                        updateLayout()
                        panelX = lp.x
                        panelY = lp.y
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 拖动或轻点都会重新计时
                    scheduleCollapse()
                    true
                }

                else -> true
            }
        }
    }

    // ------------------------------------------------------------ 折叠态
    private fun attachBubbleTouch(bubble: View) {
        var downY = 0f
        var startY = 0
        var moved = false

        bubble.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startY = lp.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (!moved && abs(dy) > dp(8)) moved = true
                    if (moved) {
                        lp.y = FloatingGeometry.clampY(
                            (startY + dy).toInt(), dp(MIN_Y_DP), dp(BUBBLE_HEIGHT_DP), screenHeight()
                        )
                        updateLayout()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        panelY = lp.y
                    } else {
                        expand()
                    }
                    true
                }

                else -> true
            }
        }
    }

    /** 折叠成贴边半圆，吸附到距离较近的那一侧 */
    fun collapse() {
        val b = binding ?: return
        val lp = params ?: return
        if (collapsed) return
        cancelCollapse()

        panelX = lp.x
        panelY = lp.y

        collapsed = true
        val panelWidth = if (b.panelExpanded.width > 0) b.panelExpanded.width else dp(220)
        b.panelExpanded.visibility = View.GONE
        b.bubbleCollapsed.visibility = View.VISIBLE

        val screenW = screenWidth()
        val bubbleW = dp(BUBBLE_WIDTH_DP)

        if (FloatingGeometry.snapToLeft(lp.x, panelWidth, screenW)) {
            b.bubbleCollapsed.setBackgroundResource(R.drawable.bg_bubble_at_left)
        } else {
            b.bubbleCollapsed.setBackgroundResource(R.drawable.bg_bubble_at_right)
        }
        lp.x = FloatingGeometry.collapsedX(lp.x, panelWidth, screenW, bubbleW)
        lp.y = FloatingGeometry.clampY(lp.y, dp(MIN_Y_DP), dp(BUBBLE_HEIGHT_DP), screenHeight())
        updateLayout()
    }

    /** 展开回状态面板 */
    fun expand() {
        val b = binding ?: return
        val lp = params ?: return
        if (!collapsed) {
            scheduleCollapse()
            return
        }
        collapsed = false

        b.bubbleCollapsed.visibility = View.GONE
        b.panelExpanded.visibility = View.VISIBLE
        b.panelExpanded.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val panelW = if (b.panelExpanded.measuredWidth > 0) b.panelExpanded.measuredWidth else dp(220)
        val panelH = if (b.panelExpanded.measuredHeight > 0) b.panelExpanded.measuredHeight else dp(48)
        lp.x = panelX.coerceIn(0, (screenWidth() - panelW).coerceAtLeast(0))
        lp.y = FloatingGeometry.clampY(panelY, dp(MIN_Y_DP), panelH, screenHeight())
        updateLayout()

        scheduleCollapse()
    }

    fun isCollapsed(): Boolean = collapsed

    // ------------------------------------------------------------ 折叠计时
    private fun scheduleCollapse() {
        cancelCollapse()
        if (collapsed) return
        val task = Runnable { collapse() }
        collapseTask = task
        handler.postDelayed(task, COLLAPSE_DELAY_MS)
    }

    private fun cancelCollapse() {
        collapseTask?.let { handler.removeCallbacks(it) }
        collapseTask = null
    }

    // ------------------------------------------------------------ 状态刷新
    /**
     * 刷新显示内容。此方法每秒都会被调用，因此**不能**重置折叠计时，
     * 否则有观众进出、码率变化时浮窗就永远折叠不了。
     */
    fun update(title: String, sub: String, active: Boolean) {
        val b = binding ?: return
        b.tvFloatTitle.text = title
        b.tvFloatSub.text = sub
        b.tvFloatSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        val colorRes = if (active) R.color.ok else R.color.warn
        b.dotFloat.background.mutate().setTint(context.getColor(colorRes))
        b.dotBubble.background.mutate().setTint(context.getColor(colorRes))
    }

    fun hide() {
        val b = binding ?: return
        binding = null
        cancelCollapse()
        try {
            windowManager?.removeView(b.root)
        } catch (e: Exception) {
            // ignore
        }
        rootView = null
    }

    // ------------------------------------------------------------ 工具
    private fun updateLayout() {
        val root = rootView ?: return
        val lp = params ?: return
        try {
            windowManager?.updateViewLayout(root, lp)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun clampPanel(lp: WindowManager.LayoutParams) {
        val b = binding ?: return
        val w = if (b.panelExpanded.width > 0) b.panelExpanded.width else dp(220)
        val h = if (b.panelExpanded.height > 0) b.panelExpanded.height else dp(48)
        lp.x = lp.x.coerceIn(0, (screenWidth() - w).coerceAtLeast(0))
        lp.y = FloatingGeometry.clampY(lp.y, dp(MIN_Y_DP), h, screenHeight())
    }

    private fun screenWidth(): Int = screenSize().first

    private fun screenHeight(): Int = screenSize().second

    private fun screenSize(): Pair<Int, Int> {
        val wm = windowManager
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                return bounds.width() to bounds.height()
            }
            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            return point.x to point.y
        }
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private companion object {
        /** 静置多久后自动折叠 */
        const val COLLAPSE_DELAY_MS = 4000L
        const val BUBBLE_WIDTH_DP = 22
        const val BUBBLE_HEIGHT_DP = 44

        /** 避免贴到状态栏/刘海下面 */
        const val MIN_Y_DP = 24
    }
}
