package com.hearassist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPolicyTest {

    @Test
    fun backgroundRejectsCaptionStartRestartAndEnrollment() {
        val policy = SessionPolicy()

        assertFalse(policy.isForeground())
        assertFalse(policy.isVisible(policy.visibilityTicket()))
        assertNull(policy.startCaption())
        assertNull(policy.restartCaption())
        assertNull(policy.beginEnrollment())
        assertFalse(policy.wantsCaption())
    }

    @Test
    fun foregroundStartReturnsValidCaptionAndVisibilityTickets() {
        val policy = SessionPolicy()
        policy.enterForeground()
        val visibility = policy.visibilityTicket()

        assertTrue(policy.isForeground())
        assertTrue(policy.isVisible(visibility))
        assertFalse(policy.wantsCaption())
        assertNull(policy.restartCaption())

        val caption = requireNotNull(policy.startCaption())

        assertEquals(visibility.epoch, caption.epoch)
        assertTrue(policy.wantsCaption())
        assertTrue(policy.allowsCaption(caption))
    }

    @Test
    fun backgroundRevokesOldTicketsAndReturningForegroundDoesNotAutoResume() {
        val policy = SessionPolicy()
        policy.enterForeground()
        val visibility = policy.visibilityTicket()
        val started = requireNotNull(policy.startCaption())
        val restarted = requireNotNull(policy.restartCaption())
        assertTrue(policy.allowsCaption(restarted))

        policy.enterBackground()

        assertFalse(policy.isForeground())
        assertFalse(policy.isVisible(visibility))
        assertFalse(policy.allowsCaption(started))
        assertFalse(policy.allowsCaption(restarted))
        assertFalse(policy.wantsCaption())
        assertNull(policy.startCaption())
        assertNull(policy.restartCaption())
        assertNull(policy.beginEnrollment())

        policy.enterForeground()

        assertTrue(policy.isForeground())
        assertTrue(policy.isVisible(policy.visibilityTicket()))
        assertFalse(policy.isVisible(visibility))
        assertFalse(policy.allowsCaption(started))
        assertFalse(policy.allowsCaption(restarted))
        assertFalse(policy.wantsCaption())
        assertNull(policy.restartCaption())

        // Returning to the foreground needs a new, explicit user start.
        val fresh = requireNotNull(policy.startCaption())
        assertTrue(policy.allowsCaption(fresh))
        assertFalse(policy.allowsCaption(started))
        assertFalse(policy.allowsCaption(restarted))
        assertFalse(policy.isVisible(visibility))
    }

    @Test
    fun enrollmentPausesCaptionAndCanResumeOnceInTheSameForeground() {
        val policy = SessionPolicy()
        policy.enterForeground()
        val caption = requireNotNull(policy.startCaption())
        assertTrue(policy.allowsCaption(caption))

        val enrollment = requireNotNull(policy.beginEnrollment())

        assertTrue(policy.wantsCaption())
        assertTrue(policy.allowsEnrollment(enrollment))
        assertFalse(policy.allowsCaption(caption))
        assertNull(policy.startCaption())
        assertNull(policy.restartCaption())
        assertNull(policy.beginEnrollment())

        val resumed = requireNotNull(policy.finishEnrollment(enrollment, resumeCaption = true))

        assertTrue(policy.wantsCaption())
        assertTrue(policy.allowsCaption(resumed))
        assertFalse(policy.allowsCaption(caption))
        assertFalse(policy.allowsEnrollment(enrollment))
        assertNull(policy.finishEnrollment(enrollment, resumeCaption = true))
        assertTrue(policy.allowsCaption(resumed))
    }

    @Test
    fun enrollmentFinishAfterBackgroundCannotResumeOrInterfereWithANewEnrollment() {
        val policy = SessionPolicy()
        policy.enterForeground()
        val oldCaption = requireNotNull(policy.startCaption())
        val oldEnrollment = requireNotNull(policy.beginEnrollment())
        assertTrue(policy.allowsEnrollment(oldEnrollment))

        policy.enterBackground()

        assertFalse(policy.allowsEnrollment(oldEnrollment))
        assertNull(policy.finishEnrollment(oldEnrollment, resumeCaption = true))
        assertFalse(policy.wantsCaption())

        policy.enterForeground()

        assertNull(policy.finishEnrollment(oldEnrollment, resumeCaption = true))
        assertFalse(policy.wantsCaption())
        assertFalse(policy.allowsCaption(oldCaption))
        assertNull(policy.restartCaption())

        val freshCaption = requireNotNull(policy.startCaption())
        val freshEnrollment = requireNotNull(policy.beginEnrollment())

        // A delayed old finish must not clear the new enrollment or its resume intent.
        assertNull(policy.finishEnrollment(oldEnrollment, resumeCaption = false))
        assertTrue(policy.allowsEnrollment(freshEnrollment))
        assertTrue(policy.wantsCaption())
        assertFalse(policy.allowsCaption(freshCaption))

        val resumed = requireNotNull(policy.finishEnrollment(freshEnrollment, resumeCaption = true))
        assertTrue(policy.allowsCaption(resumed))
        assertFalse(policy.allowsCaption(oldCaption))
        assertFalse(policy.allowsCaption(freshCaption))
        assertFalse(policy.allowsEnrollment(oldEnrollment))
    }

    @Test
    fun manualStopRevokesRestartAndResumeTicketsAndPreventsPendingEnrollmentResume() {
        val policy = SessionPolicy()
        policy.enterForeground()
        val started = requireNotNull(policy.startCaption())
        val restarted = requireNotNull(policy.restartCaption())
        assertTrue(policy.allowsCaption(restarted))

        policy.stopCaption()

        assertFalse(policy.wantsCaption())
        assertFalse(policy.allowsCaption(started))
        assertFalse(policy.allowsCaption(restarted))
        assertNull(policy.restartCaption())

        val fresh = requireNotNull(policy.startCaption())
        assertTrue(policy.allowsCaption(fresh))
        assertFalse(policy.allowsCaption(restarted))
        val enrollment = requireNotNull(policy.beginEnrollment())
        val resumed = requireNotNull(policy.finishEnrollment(enrollment, resumeCaption = true))
        assertTrue(policy.allowsCaption(resumed))

        policy.stopCaption()

        assertFalse(policy.wantsCaption())
        assertFalse(policy.allowsCaption(resumed))
        assertNull(policy.restartCaption())
        assertNull(policy.finishEnrollment(enrollment, resumeCaption = true))

        val nextCaption = requireNotNull(policy.startCaption())
        assertTrue(policy.allowsCaption(nextCaption))
        assertFalse(policy.allowsCaption(resumed))
        val pendingEnrollment = requireNotNull(policy.beginEnrollment())

        // A previously requested resume must not undo a manual stop during enrollment.
        policy.stopCaption()

        assertNull(policy.finishEnrollment(pendingEnrollment, resumeCaption = true))
        assertFalse(policy.wantsCaption())
        assertFalse(policy.allowsCaption(nextCaption))
        assertFalse(policy.allowsCaption(restarted))
        assertFalse(policy.allowsCaption(resumed))
        assertFalse(policy.allowsEnrollment(pendingEnrollment))
        assertNull(policy.restartCaption())
    }

    @Test
    fun endingAnOldServiceCannotStopTheNewSession() {
        val policy = SessionPolicy()
        policy.enterForeground()
        val first = requireNotNull(policy.startCaption())
        val next = requireNotNull(policy.restartCaption())
        assertFalse(policy.endCaption(first))
        assertTrue(policy.allowsCaption(next))
        assertTrue(policy.endCaption(next))
        assertFalse(policy.wantsCaption())
        assertFalse(policy.allowsCaption(next))
        assertFalse(policy.endCaption(next))
    }

    @Test
    fun delayedServiceEndDuringEnrollmentDoesNotRemoveResumeIntent() {
        val policy = SessionPolicy()
        policy.enterForeground()
        val caption = requireNotNull(policy.startCaption())
        val enrollment = requireNotNull(policy.beginEnrollment())
        assertFalse(policy.endCaption(caption))
        assertTrue(policy.wantsCaption())
        val restored = requireNotNull(policy.finishEnrollment(enrollment, resumeCaption = true))
        assertTrue(policy.allowsCaption(restored))
    }

    @Test
    fun rapidRestartsOnlyAllowTheLatestTicket() {
        val policy = SessionPolicy()
        policy.enterForeground()
        val tickets = mutableListOf(requireNotNull(policy.startCaption()))

        repeat(8) {
            tickets += requireNotNull(policy.restartCaption())
        }

        assertTrue(policy.wantsCaption())
        assertEquals(tickets.size, tickets.toSet().size)
        tickets.dropLast(1).forEach { ticket ->
            assertFalse("Superseded restart ticket must stay invalid: $ticket", policy.allowsCaption(ticket))
        }
        assertTrue(policy.allowsCaption(tickets.last()))
    }

    @Test
    fun enrollmentDoesNotResumeWithoutCaptionIntentOrWhenResumeIsFalse() {
        val policy = SessionPolicy()
        policy.enterForeground()

        // Permission to resume is not itself a request to start captions.
        val withoutCaption = requireNotNull(policy.beginEnrollment())
        assertNull(policy.finishEnrollment(withoutCaption, resumeCaption = true))
        assertFalse(policy.wantsCaption())
        assertFalse(policy.allowsEnrollment(withoutCaption))
        assertNull(policy.restartCaption())

        val caption = requireNotNull(policy.startCaption())
        val enrollment = requireNotNull(policy.beginEnrollment())
        assertTrue(policy.wantsCaption())

        assertNull(policy.finishEnrollment(enrollment, resumeCaption = false))
        assertFalse(policy.wantsCaption())
        assertFalse(policy.allowsCaption(caption))
        assertFalse(policy.allowsEnrollment(enrollment))
        assertNull(policy.restartCaption())
        assertNull(policy.finishEnrollment(enrollment, resumeCaption = true))

        val fresh = requireNotNull(policy.startCaption())
        assertTrue(policy.allowsCaption(fresh))
        assertFalse(policy.allowsCaption(caption))
    }
}
