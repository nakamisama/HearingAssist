package com.hearassist.ui

/** 黄色只覆盖最新句正文，姓名始终保留个人颜色，历史恢复原颜色。 */
object CaptionColorPolicy {
    fun body(personal: Int, latest: Boolean, normal: Int, highlight: Int): Int =
        if (latest) highlight else speaker(personal, normal)

    fun speaker(personal: Int, normal: Int): Int = if (personal != 0) personal else normal
}
