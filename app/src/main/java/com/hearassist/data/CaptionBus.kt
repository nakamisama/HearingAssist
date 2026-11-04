package com.hearassist.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 服务（后台识别）与界面（字幕显示）之间的共享状态。
 * 单例 + StateFlow，避免引入额外的架构依赖。
 */
enum class EngineStatus { IDLE, LISTENING, RECOGNIZING }

object CaptionBus {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _status = MutableStateFlow(EngineStatus.IDLE)
    val status: StateFlow<EngineStatus> = _status

    private val _subtitles = MutableStateFlow(listOf<String>())
    val subtitles: StateFlow<List<String>> = _subtitles

    /** 最近检测到的环境音事件（如“门铃”），null 表示无 */
    private val _envEvent = MutableStateFlow<String?>(null)
    val envEvent: StateFlow<String?> = _envEvent

    /** 环境音识别开关，默认关闭 */
    private val _envEnabled = MutableStateFlow(false)
    val envEnabled: StateFlow<Boolean> = _envEnabled

    fun setRunning(v: Boolean) { _running.value = v }
    fun setStatus(v: EngineStatus) { _status.value = v }
    fun setEnvEnabled(v: Boolean) { _envEnabled.value = v }

    fun addSubtitle(text: String) {
        if (text.isBlank()) return
        val list = _subtitles.value.toMutableList()
        list.add(text)
        if (list.size > MAX_LINES) list.removeAt(0)
        _subtitles.value = list
    }

    fun pushEnvEvent(name: String) { _envEvent.value = name }
    fun clearEnvEvent() { _envEvent.value = null }

    private const val MAX_LINES = 200
}
