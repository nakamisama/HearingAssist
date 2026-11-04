package com.hearassist.data

import android.content.Context

/** 简单偏好存储：识别语言模式（0=自动 1=普通话）、声纹开关 */
object Settings {
    private const val PREFS = "hearassist_prefs"
    private const val KEY_LANG_MODE = "lang_mode"
    private const val KEY_VP_ENABLED = "vp_enabled"

    fun getLangMode(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LANG_MODE, 0)

    fun setLangMode(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LANG_MODE, mode).apply()
    }

    fun isVoiceprintEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VP_ENABLED, false)

    fun setVoiceprintEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VP_ENABLED, v).apply()
    }
}
