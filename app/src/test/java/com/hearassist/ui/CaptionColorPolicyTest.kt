package com.hearassist.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionColorPolicyTest {
    private val yellow = 0xFFFFD54F.toInt()
    private val normal = 0xFFF2F6FA.toInt()
    private val personal = 0xFF81C784.toInt()

    @Test
    fun latestBodyIsYellowEvenWithASpeakerColor() {
        assertEquals(yellow, CaptionColorPolicy.body(personal, true, normal, yellow))
    }

    @Test
    fun historicalBodyReturnsToThePersonalColor() {
        assertEquals(personal, CaptionColorPolicy.body(personal, false, normal, yellow))
        assertEquals(normal, CaptionColorPolicy.body(0, false, normal, yellow))
    }

    @Test
    fun latestSpeakerNameKeepsThePersonalColor() {
        assertEquals(personal, CaptionColorPolicy.speaker(personal, normal))
        assertEquals(normal, CaptionColorPolicy.speaker(0, normal))
    }
}
