package com.hearassist.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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

    /** 一条字幕：说话人（可空）、说话人颜色（0=默认）、文本 */
    data class SubtitleLine(val speaker: String?, val color: Int, val text: String)

    private val _subtitles = MutableStateFlow(listOf<SubtitleLine>())
    val subtitles: StateFlow<List<SubtitleLine>> = _subtitles

    /** 最近检测到的环境音事件（如“门铃”），null 表示无 */
    private val _envEvent = MutableStateFlow<String?>(null)
    val envEvent: StateFlow<String?> = _envEvent

    /** 环境音识别开关，默认关闭 */
    private val _envEnabled = MutableStateFlow(false)
    val envEnabled: StateFlow<Boolean> = _envEnabled

    /** 识别语言模式：0=自动 1=普通话 2=粤语 */
    private val _langMode = MutableStateFlow(0)
    val langMode: StateFlow<Int> = _langMode

    fun setLangMode(v: Int) { _langMode.value = v }

    fun setRunning(v: Boolean) { _running.value = v }
    fun setStatus(v: EngineStatus) { _status.value = v }
    fun setEnvEnabled(v: Boolean) { _envEnabled.value = v }

    fun addSubtitle(text: String, speaker: String? = null, color: Int = 0) {
        if (text.isBlank()) return
        // 原子更新：多个识别协程并发写入时避免互相覆盖导致丢字幕
        _subtitles.update { current ->
            val list = current.toMutableList()
            list.add(SubtitleLine(speaker, color, text))
            if (list.size > MAX_LINES) list.removeAt(0)
            list
        }
    }

    fun pushEnvEvent(name: String) { _envEvent.value = name }
    fun clearEnvEvent() { _envEvent.value = null }

    private const val MAX_LINES = 200
}
