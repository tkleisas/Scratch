package com.tkleisas.scratchdj.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformWindowTest {
    @Test
    fun secondsWindowUsesRequestedDuration() {
        assertEquals(
            30_000L,
            WaveformWindowMath.windowMs(WaveformWindowUnit.Seconds, 30f, 32f, 120f),
        )
    }

    @Test
    fun beatsWindowUsesEffectiveBpm() {
        assertEquals(
            16_000L,
            WaveformWindowMath.windowMs(WaveformWindowUnit.Beats, 30f, 32f, 120f),
        )
        assertEquals(
            8_000L,
            WaveformWindowMath.windowMs(WaveformWindowUnit.Beats, 30f, 32f, 240f),
        )
    }

    @Test
    fun beatsWindowFallsBackWhenBpmIsMissing() {
        assertEquals(
            16_000L,
            WaveformWindowMath.windowMs(WaveformWindowUnit.Beats, 30f, 32f, 0f),
        )
    }

    @Test
    fun viewportSweepsPlayheadThenFollowsThenSweepsAgain() {
        val start = WaveformWindowMath.viewport(progress = 0.05f, windowFraction = 0.25f)
        assertEquals(0f, start.startFraction)
        assertEquals(0.2f, start.playheadFraction, 0.0001f)

        val middle = WaveformWindowMath.viewport(progress = 0.5f, windowFraction = 0.25f)
        assertEquals(0.375f, middle.startFraction, 0.0001f)
        assertEquals(0.5f, middle.playheadFraction, 0.0001f)

        val end = WaveformWindowMath.viewport(progress = 0.95f, windowFraction = 0.25f)
        assertEquals(0.75f, end.startFraction, 0.0001f)
        assertEquals(0.8f, end.playheadFraction, 0.0001f)
    }

    @Test
    fun shortTracksUseWholeWaveform() {
        val fraction = WaveformWindowMath.windowFraction(30_000L, 10_000L)
        assertEquals(1f, fraction)
        val viewport = WaveformWindowMath.viewport(0.4f, fraction)
        assertEquals(0f, viewport.startFraction)
        assertEquals(1f, viewport.fraction)
        assertEquals(0.4f, viewport.playheadFraction)
    }

    @Test
    fun windowFractionClampsToTrack() {
        val fraction = WaveformWindowMath.windowFraction(90_000L, 120_000L)
        assertTrue(fraction in 0f..1f)
        assertEquals(0.75f, fraction, 0.0001f)
    }
}
