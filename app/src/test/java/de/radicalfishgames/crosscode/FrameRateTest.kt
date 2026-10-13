package de.radicalfishgames.crosscode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateTest {

    @Test
    fun aScreenIsOfferedOnlyTheLimitsItCanShow() {
        assertEquals(listOf(30, 45, 60), FrameRate.choicesFor(listOf(60)))
        assertEquals(listOf(30, 45, 60, 90, 120), FrameRate.choicesFor(listOf(60, 120)))
        assertEquals(listOf(30, 45, 60), FrameRate.choicesFor(emptyList()))
    }

    @Test
    fun theScreenRunsAtTheLowestRefreshRateTheLimitDividesEvenly() {
        val rates = listOf(60, 90, 120)
        assertEquals(60, FrameRate.refreshFor(30, rates))
        assertEquals(90, FrameRate.refreshFor(45, rates))
        assertEquals(60, FrameRate.refreshFor(60, rates))
        assertEquals(120, FrameRate.refreshFor(120, rates))
        assertEquals(120, FrameRate.refreshFor(FrameRate.OFF, rates))
        assertEquals(120, FrameRate.refreshFor(45, listOf(60, 120)))
        assertNull(FrameRate.refreshFor(60, emptyList()))
    }

    @Test
    fun storedLimitsSnapToWhatTheScreenOffersAndTheMenuCycles() {
        val sixty = FrameRate.choicesFor(listOf(60))
        assertEquals(FrameRate.OFF, FrameRate.normalize(0, sixty))
        assertEquals(60, FrameRate.normalize(120, sixty))
        assertEquals(45, FrameRate.normalize(45, sixty))
        assertEquals(listOf(0, 30, 45, 60, 0), generateSequence(FrameRate.OFF) { FrameRate.next(it, sixty) }.take(5).toList())
        assertEquals("Off", FrameRate.label(FrameRate.OFF))
        assertEquals("45 fps", FrameRate.label(45))
    }
}
