package com.hearassist.ui

/** 自动跟随最新字幕，只有用户回看时暂停；内容增长不能自行关闭跟随。 */
class CaptionScrollPolicy {
    var followLatest: Boolean = true
        private set

    fun onUserScroll(position: Int, maximum: Int, tolerance: Int) {
        followLatest = position.coerceAtLeast(0) >= (maximum - tolerance.coerceAtLeast(0)).coerceAtLeast(0)
    }

    fun resume() {
        followLatest = true
    }
}
