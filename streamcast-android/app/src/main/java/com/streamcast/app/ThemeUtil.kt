package com.streamcast.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 主题（暗色 / 亮色 / 跟随系统）。
 *
 * 实现方式：AppCompat 的日夜模式 + `values` / `values-night` 两套语义色。
 * `values` 为亮色，`values-night` 为暗色，因此「跟随系统」不需要额外逻辑。
 */
object ThemeUtil {

    fun saved(context: Context): String {
        val v = context.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)
            .getString(AppConfig.KEY_THEME, AppConfig.THEME_SYSTEM)
        return if (v == AppConfig.THEME_DARK || v == AppConfig.THEME_LIGHT || v == AppConfig.THEME_SYSTEM) {
            v!!
        } else {
            AppConfig.THEME_SYSTEM
        }
    }

    /** 在 setContentView 之前调用，避免先按旧主题渲染再重建 */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (saved(context)) {
                AppConfig.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                AppConfig.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun set(context: Context, theme: String) {
        context.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)
            .edit().putString(AppConfig.KEY_THEME, theme).apply()
        apply(context)
    }
}
