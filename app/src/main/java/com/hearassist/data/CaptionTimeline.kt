package com.hearassist.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 语音段的身份跨预览、等待和定稿保持不变；不同收听会话互不覆盖。 */
data class CaptionSession(val epoch: Long, val generation: Long)
data class CaptionId(val session: CaptionSession, val segment: Long)
enum class CaptionPhase { PREVIEW, PENDING, FINAL }

data class CaptionLine(
    val id: CaptionId,
    val text: String,
    val speaker: String? = null,
    val color: Int = 0,
    val phase: CaptionPhase = CaptionPhase.PREVIEW,
)

data class CaptionPresentation(val history: List<CaptionLine>, val latest: CaptionLine?)
data class CaptionSnapshot(val session: CaptionSession? = null, val lines: List<CaptionLine> = emptyList()) {
    fun presentation(previewEnabled: Boolean): CaptionPresentation {
        val visible = lines.filter { it.text.isNotBlank() && (previewEnabled || it.phase == CaptionPhase.FINAL) }
        return CaptionPresentation(visible.dropLast(1), visible.lastOrNull())
    }
}

/** 单个原子快照避免“历史先追加、预览随后消失”的重复行和空窗。 */
class CaptionTimeline(private val capacity: Int = 200) {
    init { require(capacity > 0) }
    private val _state = MutableStateFlow(CaptionSnapshot())
    val state: StateFlow<CaptionSnapshot> = _state

    fun beginSession(session: CaptionSession) {
        _state.update { snapshot ->
            if (snapshot.session == session) snapshot
            else snapshot.copy(session = session, lines = snapshot.lines.map { it.copy(phase = CaptionPhase.FINAL) })
        }
    }

    fun stopSession() {
        _state.update { snapshot ->
            snapshot.copy(session = null, lines = snapshot.lines.map { it.copy(phase = CaptionPhase.FINAL) })
        }
    }

    fun preview(id: CaptionId, text: String) {
        if (text.isBlank()) return // reset 后的空预览不能抹掉正在等待定稿的文字。
        change(id) { previous ->
            if (previous != null && previous.phase != CaptionPhase.PREVIEW) previous
            else (previous ?: CaptionLine(id, text)).copy(text = text, phase = CaptionPhase.PREVIEW)
        }
    }

    fun pending(id: CaptionId, fallback: String) {
        change(id) { previous ->
            if (previous?.phase == CaptionPhase.FINAL) previous
            else (previous ?: CaptionLine(id, fallback)).copy(
                text = previous?.text?.takeIf { it.isNotBlank() } ?: fallback,
                phase = CaptionPhase.PENDING,
            )
        }
    }

    fun complete(id: CaptionId, text: String, speaker: String? = null, color: Int = 0) {
        change(id) { previous ->
            if (previous?.phase == CaptionPhase.FINAL) previous
            else (previous ?: CaptionLine(id, text)).copy(
                text = text.takeIf { it.isNotBlank() } ?: previous?.text.orEmpty(),
                speaker = speaker,
                color = color,
                phase = CaptionPhase.FINAL,
            )
        }
    }

    private fun change(id: CaptionId, transform: (CaptionLine?) -> CaptionLine) {
        _state.update { snapshot ->
            if (snapshot.session != id.session) return@update snapshot
            val previous = snapshot.lines.firstOrNull { it.id == id }
            if (previous == null && snapshot.lines.size >= capacity &&
                compare(id, snapshot.lines.first().id) < 0) return@update snapshot
            val next = transform(previous)
            if (next == previous || (previous == null && next.text.isBlank())) return@update snapshot
            val lines = (snapshot.lines.filterNot { it.id == id } + next)
                .sortedWith { left, right -> compare(left.id, right.id) }
                .takeLast(capacity)
            snapshot.copy(lines = lines)
        }
    }

    private fun compare(left: CaptionId, right: CaptionId): Int =
        compareValuesBy(left, right, { it.session.epoch }, { it.session.generation }, { it.segment })
}
