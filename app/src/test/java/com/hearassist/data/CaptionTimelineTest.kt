package com.hearassist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionTimelineTest {
    private val session = CaptionSession(epoch = 1L, generation = 1L)

    @Test
    fun previewPendingCompleteKeepsOneVisibleLineWithTheSameId() {
        val timeline = CaptionTimeline()
        val id = CaptionId(session, segment = 1L)
        timeline.beginSession(session)

        timeline.preview(id, "第一句预览")
        assertSingleVisibleLine(timeline, CaptionLine(id, "第一句预览"))

        timeline.pending(id, "第一句等待")
        assertSingleVisibleLine(
            timeline,
            CaptionLine(id, "第一句预览", phase = CaptionPhase.PENDING),
        )

        timeline.complete(id, "第一句定稿")
        assertSingleVisibleLine(
            timeline,
            CaptionLine(id, "第一句定稿", phase = CaptionPhase.FINAL),
        )
    }

    @Test
    fun blankResetPreviewIsIgnoredBeforeAndAfterPending() {
        val timeline = CaptionTimeline()
        val id = CaptionId(session, segment = 1L)
        val resets = listOf("", " ", "\t\n")
        timeline.beginSession(session)
        val empty = timeline.state.value

        resets.forEach { reset ->
            timeline.preview(id, reset)
            assertEquals(empty, timeline.state.value)
        }

        timeline.preview(id, "保留的预览")
        val preview = timeline.state.value
        resets.forEach { reset ->
            timeline.preview(id, reset)
            assertEquals(preview, timeline.state.value)
        }

        timeline.pending(id, "")
        assertSingleVisibleLine(
            timeline,
            CaptionLine(id, "保留的预览", phase = CaptionPhase.PENDING),
        )
        val pending = timeline.state.value
        resets.forEach { reset ->
            timeline.preview(id, reset)
            assertEquals(pending, timeline.state.value)
        }
    }

    @Test
    fun lateFinalForEarlierLineChangesOnlyHistory() {
        val timeline = CaptionTimeline()
        val firstId = CaptionId(session, segment = 1L)
        val secondId = CaptionId(session, segment = 2L)
        val firstPending = CaptionLine(firstId, "第一句预览", phase = CaptionPhase.PENDING)
        val secondPreview = CaptionLine(secondId, "第二句预览")
        timeline.beginSession(session)
        timeline.preview(firstId, firstPending.text)
        timeline.pending(firstId, "")
        timeline.preview(secondId, secondPreview.text)

        val before = timeline.state.value.presentation(previewEnabled = true)
        assertEquals(CaptionPresentation(listOf(firstPending), secondPreview), before)

        timeline.complete(firstId, "第一句迟到的定稿")

        val firstFinal = firstPending.copy(text = "第一句迟到的定稿", phase = CaptionPhase.FINAL)
        val snapshot = timeline.state.value
        assertEquals(CaptionSnapshot(session, listOf(firstFinal, secondPreview)), snapshot)
        val after = snapshot.presentation(previewEnabled = true)
        assertEquals(listOf(firstFinal), after.history)
        assertEquals(before.latest, after.latest)
    }

    @Test
    fun finalizedLineIgnoresLatePreviewForTheSameId() {
        val timeline = CaptionTimeline()
        val id = CaptionId(session, segment = 1L)
        val color = 0xFF336699.toInt()
        val expected = CaptionLine(id, "已定稿", "speaker-1", color, CaptionPhase.FINAL)
        timeline.beginSession(session)
        timeline.preview(id, "原预览")
        timeline.complete(id, expected.text, speaker = expected.speaker, color = color)
        assertSingleVisibleLine(timeline, expected)
        val finalized = timeline.state.value

        timeline.preview(id, "迟到的预览不能覆盖定稿")

        assertEquals(finalized, timeline.state.value)
        assertSingleVisibleLine(timeline, expected)
    }

    @Test
    fun blankFinalPreservesPreviewAndAnEmptyIdStaysInvisible() {
        listOf("", " \t\n").forEach { blank ->
            val timeline = CaptionTimeline()
            val id = CaptionId(session, segment = 1L)
            val blankId = CaptionId(session, segment = 2L)
            val expected = CaptionLine(id, "不能丢失的预览", phase = CaptionPhase.FINAL)
            timeline.beginSession(session)
            timeline.preview(id, expected.text)

            timeline.complete(id, blank)

            assertSingleVisibleLine(timeline, expected)

            timeline.complete(blankId, blank)

            val snapshot = timeline.state.value
            val visible = CaptionPresentation(emptyList(), expected)
            assertEquals(visible, snapshot.presentation(previewEnabled = true))
            assertEquals(visible, snapshot.presentation(previewEnabled = false))
        }
    }

    @Test
    fun silenceAndBlankResultsNeverEvictExistingCaptions() {
        val timeline = CaptionTimeline(capacity = 3)
        timeline.beginSession(session)
        val id = CaptionId(session, 0L)
        timeline.complete(id, "保持可见")
        val before = timeline.state.value
        repeat(500) { index ->
            val silent = CaptionId(session, index.toLong() + 1)
            timeline.pending(silent, "")
            timeline.complete(silent, "")
        }
        assertEquals(before, timeline.state.value)
    }

    @Test
    fun stopSessionRejectsOldCompletions() {
        val timeline = CaptionTimeline()
        val firstId = CaptionId(session, segment = 1L)
        val secondId = CaptionId(session, segment = 2L)
        timeline.beginSession(session)
        timeline.pending(firstId, "等待中的第一句")
        timeline.preview(secondId, "预览中的第二句")

        timeline.stopSession()

        val stopped = CaptionSnapshot(
            lines = listOf(
                CaptionLine(firstId, "等待中的第一句", phase = CaptionPhase.FINAL),
                CaptionLine(secondId, "预览中的第二句", phase = CaptionPhase.FINAL),
            ),
        )
        assertNull(timeline.state.value.session)
        assertEquals(stopped, timeline.state.value)

        timeline.complete(firstId, "停后迟到的定稿", speaker = "旧说话人", color = 42)
        assertEquals(stopped, timeline.state.value)

        // 未出现过的旧 ID 也必须被拒绝，不能仅靠已有行的 FINAL 防重。
        timeline.complete(CaptionId(session, segment = 3L), "停后未登记段的定稿")
        assertEquals(stopped, timeline.state.value)
    }

    @Test
    fun newSessionRejectsOldResultsWithReusedSegmentIds() {
        // 分别改变 generation 和 epoch，验证复用 segment 时仍按完整会话隔离。
        val nextSessions = listOf(
            CaptionSession(epoch = 1L, generation = 2L),
            CaptionSession(epoch = 2L, generation = 1L),
        )
        nextSessions.forEach { nextSession ->
            val timeline = CaptionTimeline()
            val oldId = CaptionId(session, segment = 1L)
            val newId = CaptionId(nextSession, segment = 1L)
            timeline.beginSession(session)
            timeline.preview(oldId, "旧会话预览")
            timeline.pending(oldId, "")

            timeline.beginSession(nextSession)
            timeline.preview(newId, "新会话预览")

            val oldFinal = CaptionLine(oldId, "旧会话预览", phase = CaptionPhase.FINAL)
            val newPreview = CaptionLine(newId, "新会话预览")
            val expected = CaptionSnapshot(nextSession, listOf(oldFinal, newPreview))
            assertEquals(expected, timeline.state.value)
            assertEquals(
                CaptionPresentation(listOf(oldFinal), newPreview),
                timeline.state.value.presentation(previewEnabled = true),
            )

            listOf(oldId, CaptionId(session, segment = 2L)).forEach { staleId ->
                timeline.complete(staleId, "旧会话迟到结果", speaker = "旧说话人", color = 42)
                assertEquals(expected, timeline.state.value)
            }
        }
    }

    @Test
    fun capacityBoundsHistoryAndEvictedLateFinalIsNotReinserted() {
        val capacity = 3
        val timeline = CaptionTimeline(capacity = capacity)
        val ids = (1L..6L).map { CaptionId(session, segment = it) }
        timeline.beginSession(session)

        ids.forEachIndexed { index, id ->
            timeline.preview(id, "第${id.segment}句预览")
            assertTrue(timeline.state.value.lines.size <= capacity)
            timeline.pending(id, "")

            val retained = ids.take(index + 1).takeLast(capacity).map { retainedId ->
                CaptionLine(retainedId, "第${retainedId.segment}句预览", phase = CaptionPhase.PENDING)
            }
            val snapshot = timeline.state.value
            assertEquals(retained, snapshot.lines)
            assertEquals(
                CaptionPresentation(retained.dropLast(1), retained.last()),
                snapshot.presentation(previewEnabled = true),
            )
        }

        val bounded = timeline.state.value
        assertEquals(capacity, bounded.lines.size)
        assertEquals(capacity - 1, bounded.presentation(previewEnabled = true).history.size)

        // 被淘汰的行尚未定稿，确保验证淘汰保护，而不是 FINAL 防重。
        ids.dropLast(capacity).forEach { evictedId ->
            timeline.complete(evictedId, "淘汰后迟到的第${evictedId.segment}句定稿")
            assertEquals(bounded, timeline.state.value)
        }
    }

    @Test
    fun disabledPreviewUsesOnlyFinalLinesAndTheirLatest() {
        val timeline = CaptionTimeline()
        val firstFinal = CaptionLine(CaptionId(session, 1L), "第一句定稿", phase = CaptionPhase.FINAL)
        val secondFinal = CaptionLine(CaptionId(session, 2L), "第二句定稿", phase = CaptionPhase.FINAL)
        val pending = CaptionLine(CaptionId(session, 3L), "第三句等待", phase = CaptionPhase.PENDING)
        val preview = CaptionLine(CaptionId(session, 4L), "第四句预览")
        val empty = CaptionPresentation(emptyList(), null)
        timeline.beginSession(session)

        timeline.preview(firstFinal.id, "第一句预览")
        assertEquals(empty, timeline.state.value.presentation(previewEnabled = false))
        timeline.pending(firstFinal.id, "")
        assertEquals(empty, timeline.state.value.presentation(previewEnabled = false))

        timeline.complete(firstFinal.id, firstFinal.text)
        timeline.complete(secondFinal.id, secondFinal.text)
        timeline.pending(pending.id, pending.text)
        timeline.preview(preview.id, preview.text)

        val snapshot = timeline.state.value
        assertEquals(listOf(firstFinal, secondFinal, pending, preview), snapshot.lines)
        val finalsOnly = CaptionPresentation(listOf(firstFinal), secondFinal)
        assertEquals(finalsOnly, snapshot.presentation(previewEnabled = false))
        assertEquals(
            CaptionPresentation(listOf(firstFinal, secondFinal, pending), preview),
            snapshot.presentation(previewEnabled = true),
        )
        assertEquals(finalsOnly, snapshot.presentation(previewEnabled = false))
        assertEquals(snapshot, timeline.state.value)
    }

    @Test
    fun completeKeepsSpeakerAndColorOnTheCorrectLine() {
        val timeline = CaptionTimeline()
        val first = CaptionLine(
            CaptionId(session, 1L), "第一句定稿", "speaker-1", 0xFF336699.toInt(), CaptionPhase.FINAL,
        )
        val second = CaptionLine(
            CaptionId(session, 2L), "第二句定稿", "speaker-2", 0xFFCC5500.toInt(), CaptionPhase.FINAL,
        )
        timeline.beginSession(session)
        timeline.preview(first.id, "第一句预览")
        timeline.pending(first.id, "")
        timeline.preview(second.id, "第二句预览")
        timeline.pending(second.id, "")

        timeline.complete(second.id, second.text, speaker = second.speaker, color = second.color)
        timeline.complete(first.id, first.text, speaker = first.speaker, color = first.color)

        val snapshot = timeline.state.value
        assertEquals(listOf(first, second), snapshot.lines)
        val expected = CaptionPresentation(listOf(first), second)
        assertEquals(expected, snapshot.presentation(previewEnabled = true))
        assertEquals(expected, snapshot.presentation(previewEnabled = false))
    }

    @Test
    fun sameTextPhaseChangesKeepPresentationIdentity() {
        val timeline = CaptionTimeline()
        val id = CaptionId(session, segment = 1L)
        val preview = CaptionLine(id, "各阶段完全相同的文字")
        timeline.beginSession(session)

        timeline.preview(id, preview.text)
        assertSingleVisibleLine(timeline, preview)

        timeline.pending(id, preview.text)
        assertSingleVisibleLine(timeline, preview.copy(phase = CaptionPhase.PENDING))

        timeline.complete(id, preview.text)
        assertSingleVisibleLine(timeline, preview.copy(phase = CaptionPhase.FINAL))
    }

    @Test
    fun historyIsOrderedBySessionThenSegmentInsteadOfCallbackArrival() {
        val timeline = CaptionTimeline()
        val earlierSession = CaptionSession(epoch = 1L, generation = 9L)
        val restartedSession = CaptionSession(epoch = 1L, generation = 10L)
        val freshSession = CaptionSession(epoch = 2L, generation = 1L)
        val earlierFirst = CaptionLine(
            CaptionId(earlierSession, 1L), "旧会话第一句", phase = CaptionPhase.FINAL,
        )
        val earlierSecond = CaptionLine(
            CaptionId(earlierSession, 2L), "旧会话第二句", phase = CaptionPhase.FINAL,
        )
        val restartedFirst = CaptionLine(
            CaptionId(restartedSession, 1L), "重启会话第一句", phase = CaptionPhase.FINAL,
        )
        val restartedSecond = CaptionLine(
            CaptionId(restartedSession, 2L), "重启会话第二句", phase = CaptionPhase.FINAL,
        )
        val freshFirst = CaptionLine(
            CaptionId(freshSession, 1L), "新前台会话第一句", phase = CaptionPhase.FINAL,
        )
        val freshSecond = CaptionLine(CaptionId(freshSession, 2L), "新前台会话第二句预览")

        timeline.beginSession(earlierSession)
        timeline.preview(earlierSecond.id, earlierSecond.text)
        timeline.pending(earlierFirst.id, earlierFirst.text)
        timeline.beginSession(restartedSession)
        timeline.preview(restartedSecond.id, restartedSecond.text)
        timeline.pending(restartedFirst.id, restartedFirst.text)
        timeline.beginSession(freshSession)
        timeline.preview(freshSecond.id, freshSecond.text)
        timeline.complete(freshFirst.id, freshFirst.text)

        val expected = listOf(
            earlierFirst, earlierSecond, restartedFirst, restartedSecond, freshFirst, freshSecond,
        )
        val snapshot = timeline.state.value
        assertEquals(CaptionSnapshot(freshSession, expected), snapshot)
        assertEquals(
            CaptionPresentation(expected.dropLast(1), freshSecond),
            snapshot.presentation(previewEnabled = true),
        )
        assertEquals(
            CaptionPresentation(listOf(earlierFirst, earlierSecond, restartedFirst, restartedSecond), freshFirst),
            snapshot.presentation(previewEnabled = false),
        )
    }

    private fun assertSingleVisibleLine(timeline: CaptionTimeline, expected: CaptionLine) {
        val snapshot = timeline.state.value
        assertEquals(listOf(expected), snapshot.lines)
        assertEquals(
            CaptionPresentation(emptyList(), expected),
            snapshot.presentation(previewEnabled = true),
        )
    }
}
