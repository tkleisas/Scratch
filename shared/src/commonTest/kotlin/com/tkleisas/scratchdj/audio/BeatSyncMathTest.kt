package com.tkleisas.scratchdj.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BeatSyncMathTest {
    @Test
    fun phaseWrapsAroundBeatGrid() {
        assertEquals(0.25f, BeatSyncMath.phase(625L, 0f, 120f), 0.0001f)
        assertEquals(0.25f, BeatSyncMath.phase(1125L, 0f, 120f), 0.0001f)
    }

    @Test
    fun phaseUsesTrackOffset() {
        assertEquals(0f, BeatSyncMath.phase(250L, 250f, 120f), 0.0001f)
    }

    @Test
    fun alignedPositionMovesToClosestMatchingPhase() {
        val target = BeatSyncMath.alignedPositionMs(
            positionMs = 625L,
            offsetMs = 0f,
            bpm = 120f,
            targetPhase = 0f,
            durationMs = 60_000L,
        )
        assertNotNull(target)
        assertEquals(0f, BeatSyncMath.phase(target!!, 0f, 120f), 0.0001f)
    }
}
