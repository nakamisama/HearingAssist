package com.hearassist.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 服务与界面共享状态；字幕内容和预览使用同一个原子快照。 */
enum class EngineStatus { IDLE, STARTING, LISTENING, RECOGNIZING }

object CaptionBus {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _status = MutableStateFlow(EngineStatus.IDLE)
    val status: StateFlow<EngineStatus> = _status
    private val timeline = CaptionTimeline()
    val captions: StateFlow<CaptionSnapshot> = timeline.state

    fun beginCaptions(ticket: SessionPolicy.CaptionTicket) {
        timeline.beginSession(CaptionSession(ticket.epoch, ticket.generation))
    }
    fun preview(id: CaptionId, text: String) = timeline.preview(id, text)
    fun pending(id: CaptionId, fallback: String) = timeline.pending(id, fallback)
    fun complete(id: CaptionId, text: String, speaker: String? = null, color: Int = 0) =
        timeline.complete(id, text, speaker, color)

    private val _startMsg = MutableStateFlow("")
    val startMsg: StateFlow<String> = _startMsg
    fun setStartMsg(message: String) { _startMsg.value = message }
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    fun setError(message: String?) { _error.value = message }
    private val _envEvent = MutableStateFlow<String?>(null)
    val envEvent: StateFlow<String?> = _envEvent
    private val _envEnabled = MutableStateFlow(false)
    val envEnabled: StateFlow<Boolean> = _envEnabled
    private val _langMode = MutableStateFlow(0)
    val langMode: StateFlow<Int> = _langMode

    fun stopUi() {
        _running.value = false
        _status.value = EngineStatus.IDLE
        timeline.stopSession()
        _startMsg.value = ""
        _envEvent.value = null
    }

    fun setLangMode(mode: Int) { _langMode.value = mode }
    fun setRunning(running: Boolean) { _running.value = running }
    fun setStatus(status: EngineStatus) { _status.value = status }
    fun setEnvEnabled(enabled: Boolean) { _envEnabled.value = enabled }
    fun pushEnvEvent(name: String) { _envEvent.value = name }
    fun clearEnvEvent() { _envEvent.value = null }
}
