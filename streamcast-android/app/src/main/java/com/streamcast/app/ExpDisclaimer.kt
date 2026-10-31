package com.streamcast.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 实验性功能免责声明（App 内强制阅读）。
 *
 * 与网页端 /disclaimer.html 同一版本号（[AppConfig.EXP_VERSION]）。
 * 交互上强制「滚动到末尾 → 才能勾选 → 才能继续」，
 * 避免一键通过让声明形同虚设。
 */
object ExpDisclaimer {

    private const val VERSION_LABEL = "v" + AppConfig.EXP_VERSION

    private val BODY = """
        一、实验性功能的性质
        H.265（HEVC）传输与远程控制均为实验性功能，按「现状」提供，可能不稳定、可能在某些设备上不可用，也可能随时调整或下线，我们不承诺其可用性。

        二、远程控制的真实风险
        主播点击「通过」后，对方可在授权期间操作你的手机整机界面（点击、滑动、返回、主页）。你设备上的聊天记录、相册、支付类应用都可能被看到或误操作。授权范围是整机，不存在「只操作某个应用」的隔离。

        三、使用者的承诺
        1. 只控制你本人拥有，或已取得设备所有人明确、可证明授权的设备；
        2. 仅用于设备协助、远程演示、售后支持等正当目的；
        3. 不用于窥探隐私、窃取数据、操作他人账户、诈骗或其他违法活动；
        4. 不使用欺骗、诱导方式使他人授权。

        未经授权控制他人设备，可能构成非法侵入计算机信息系统、非法获取计算机信息系统数据、侵犯公民个人信息等违法行为，情节严重的将承担刑事责任。

        四、能力边界与失效
        同一房间同一时刻仅一名控制者；单次控制最长 ${AppConfig.CONTROL_MAX_MINUTES} 分钟；主播可随时点击「退出控制」立即收回；接收端退出、断线或直播结束时授权立即失效。

        五、无障碍服务权限
        本服务仅将无障碍服务用于执行手势指令，未声明窗口内容检索能力，不读取屏幕文字、控件树或输入框内容，也不上传任何界面信息。你可以随时在系统设置中关闭该服务。

        六、数据处理
        控制指令经服务器实时转发，服务端不存储手势内容与画面。为安全审计，会记录房间号、申请方昵称及申请/批准/拒绝/结束的时间与结果。

        七、责任
        使用实验性功能的全部风险与后果由你自行承担。因功能不稳定、失效、误操作或被滥用导致的损失，我们在法律允许的最大范围内不承担责任；违反上述承诺的，由你独立承担全部法律责任。

        本声明为《用户协议》的组成部分，完整版本见服务端 /disclaimer.html。
    """.trimIndent()

    /** 是否已同意当前版本 */
    fun isAccepted(context: Context): Boolean =
        context.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)
            .getString(AppConfig.KEY_EXP_ACK, "") == AppConfig.EXP_VERSION

    /**
     * 展示声明。已同意则直接回调；否则弹窗，同意后持久化版本号并回调。
     * 取消时什么都不做（调用方负责把开关恢复原状）。
     */
    fun require(activity: Activity, onAccept: () -> Unit, onCancel: () -> Unit = {}) {
        if (isAccepted(activity)) {
            onAccept()
            return
        }
        show(activity, onAccept, onCancel)
    }

    private fun show(activity: Activity, onAccept: () -> Unit, onCancel: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val textColor = ContextCompat.getColor(activity, R.color.text)
        val muted = ContextCompat.getColor(activity, R.color.muted)
        val warn = ContextCompat.getColor(activity, R.color.warn)
        val accent = ContextCompat.getColor(activity, R.color.accent)

        val body = TextView(activity).apply {
            text = BODY
            textSize = 12.5f
            setTextColor(textColor)
            setLineSpacing(dp(3).toFloat(), 1.2f)
            setPadding(dp(18), dp(14), dp(18), dp(16))
            movementMethod = ScrollingMovementMethod()
        }

        val scroll = ScrollView(activity).apply {
            addView(body, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isFillViewport = true
        }

        val tip = TextView(activity).apply {
            text = "请向下滚动阅读至末尾"
            textSize = 11f
            setTextColor(warn)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }

        val check = CheckBox(activity).apply {
            text = "我已完整阅读并同意上述声明，并知晓远程控制的真实风险"
            textSize = 12.5f
            setTextColor(textColor)
            isEnabled = false
            alpha = 0.45f
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }

        val cancel = TextView(activity).apply {
            text = "取消"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(12), 0, dp(12))
        }
        val ok = TextView(activity).apply {
            text = "同意并开启"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = ContextCompat.getDrawable(activity, R.drawable.btn_primary)
            alpha = 0.45f
            isEnabled = false
            setPadding(0, dp(12), 0, dp(12))
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        row.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ok, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f).apply {
            marginStart = dp(10)
        })

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(tip)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)))
        root.addView(check)
        root.addView(row)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("实验性功能免责声明 $VERSION_LABEL")
            .setView(root)
            .setCancelable(false)
            .create()

        fun enableIfReady() {
            val atBottom = body.bottom - scroll.height - scroll.scrollY <= dp(24)
            if (atBottom || body.height <= scroll.height) {
                if (!check.isEnabled) {
                    check.isEnabled = true
                    check.alpha = 1f
                    tip.visibility = View.GONE
                }
            }
        }

        scroll.viewTreeObserver.addOnScrollChangedListener {
            val atBottom = body.bottom - scroll.height - scroll.scrollY <= dp(24)
            if (atBottom && !check.isEnabled) {
                check.isEnabled = true
                check.alpha = 1f
                tip.visibility = View.GONE
            }
        }

        check.setOnCheckedChangeListener { _, checked ->
            ok.isEnabled = checked
            ok.alpha = if (checked) 1f else 0.45f
        }
        ok.setOnClickListener {
            if (!check.isChecked) return@setOnClickListener
            activity.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(AppConfig.KEY_EXP_ACK, AppConfig.EXP_VERSION)
                .apply()
            dialog.dismiss()
            onAccept()
        }
        cancel.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }

        dialog.setOnShowListener {
            // 内容未超出可视区时（大屏）直接允许勾选
            root.post { enableIfReady() }
            scroll.postDelayed({ enableIfReady() }, 120)
        }
        dialog.show()
    }
}
