package com.hearassist.data

import android.content.Context

/** 简单偏好存储：识别语言模式（0=自动 1=普通话 2=粤语） */
object Settings {
    private const val PREFS = "hearassist_prefs"
    private const val KEY_LANG_MODE = "lang_mode"

    fun getLangMode(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LANG_MODE, 0)

    fun setLangMode(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LANG_MODE, mode).apply()
    }
}
