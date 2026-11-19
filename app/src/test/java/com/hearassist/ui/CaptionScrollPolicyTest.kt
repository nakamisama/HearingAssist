package com.hearassist.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionScrollPolicyTest {
    @Test
    fun longCaptionGrowthDoesNotDisableFollowing() {
        val policy = CaptionScrollPolicy()
        repeat(1000) { assertTrue(policy.followLatest) }
    }

    @Test
    fun scrollingBackPausesFollowingUntilTheUserReturnsToTheBottom() {
        val policy = CaptionScrollPolicy()
        policy.onUserScroll(position = 400, maximum = 10000, tolerance = 24)
        assertFalse(policy.followLatest)
        policy.onUserScroll(position = 9990, maximum = 10000, tolerance = 24)
        assertTrue(policy.followLatest)
    }

    @Test
    fun jumpToLatestRestoresFollowingAfterHistoryGrows() {
        val policy = CaptionScrollPolicy()
        policy.onUserScroll(position = 0, maximum = 20000, tolerance = 24)
        assertFalse(policy.followLatest)
        policy.resume()
        assertTrue(policy.followLatest)
    }

    @Test
    fun captionsShorterThanViewportKeepFollowing() {
        val policy = CaptionScrollPolicy()
        policy.onUserScroll(position = 0, maximum = 0, tolerance = 24)
        assertTrue(policy.followLatest)
    }
}
