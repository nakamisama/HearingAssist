package com.hearassist.data

import android.content.Context

/** 简单偏好存储：识别语言模式、声纹开关、识别引擎模式 */
object Settings {
    private const val PREFS = "hearassist_prefs"
    private const val KEY_LANG_MODE = "lang_mode"
    private const val KEY_VP_ENABLED = "vp_enabled"
    private const val KEY_ASR_MODE = "asr_mode"
    private const val KEY_PREVIEW_ENABLED = "preview_enabled"
    private const val KEY_PREVIEW_INTERVAL = "preview_interval"

    // 逐字预览刷新间隔可选值（ms），默认 200
    val PREVIEW_INTERVALS = intArrayOf(100, 200, 300, 500, 1000)

    fun isPreviewEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PREVIEW_ENABLED, true)

    fun setPreviewEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PREVIEW_ENABLED, v).apply()
    }

    fun getPreviewInterval(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PREVIEW_INTERVAL, 200)

    fun setPreviewInterval(ctx: Context, ms: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PREVIEW_INTERVAL, ms).apply()
    }

    // 识别引擎：0=精准(SenseVoice整句) 1=实时(流式逐字)
    const val ASR_ACCURATE = 0
    const val ASR_REALTIME = 1

    fun getAsrMode(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ASR_MODE, ASR_ACCURATE)

    fun setAsrMode(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ASR_MODE, mode).apply()
    }

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
