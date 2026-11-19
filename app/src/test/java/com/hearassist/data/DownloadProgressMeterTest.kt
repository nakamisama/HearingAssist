package com.hearassist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadProgressMeterTest {
    @Test
    fun receivedBytesAndSpeedRemainVisibleBeforeOnePercent() {
        var now = 0L
        val meter = DownloadProgressMeter { now }
        now = 1000
        val sample = requireNotNull(meter.sample(65536, 83726641))
        assertEquals(0, sample.percent)
        assertEquals(65536L, sample.received)
        assertEquals(65536L, sample.bytesPerSecond)
    }

    @Test
    fun unknownTotalStillReportsReceivedBytesAndSpeed() {
        var now = 0L
        val meter = DownloadProgressMeter { now }
        now = 1000
        val sample = requireNotNull(meter.sample(4096, -1))
        assertNull(sample.percent)
        assertEquals(4096L, sample.received)
        assertEquals(4096L, sample.bytesPerSecond)
    }

    @Test
    fun frequentChunksAreThrottledWithoutLosingAccumulatedBytes() {
        var now = 0L
        val meter = DownloadProgressMeter { now }
        now = 1000
        assertNotNull(meter.sample(1000, 10000))
        now = 1100
        assertNull(meter.sample(1500, 10000))
        now = 1250
        val sample = requireNotNull(meter.sample(2000, 10000))
        assertEquals(4000L, sample.bytesPerSecond)
        assertEquals(20, sample.percent)
    }

    @Test
    fun completionIsReportedEvenInsideThrottleWindow() {
        var now = 0L
        val meter = DownloadProgressMeter { now }
        now = 1000
        meter.sample(1000, 2000)
        now = 1100
        val sample = requireNotNull(meter.sample(2000, 2000, finished = true))
        assertEquals(100, sample.percent)
        assertEquals(2000L, sample.received)
    }

    @Test
    fun eachNewDownloadGetsANewMeterAndDecreasingCountsAreRejected() {
        val meter = DownloadProgressMeter { 1000 }
        meter.sample(1000, 10000)
        assertThrows(IllegalArgumentException::class.java) { meter.sample(0, 10000) }
        assertEquals(0L, requireNotNull(DownloadProgressMeter { 1000 }.sample(0, -1)).received)
    }
}
