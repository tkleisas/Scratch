package com.tkleisas.scratchdj.audio

import kotlin.math.floor
import kotlin.math.roundToLong

object BeatSyncMath {
    fun phase(positionMs: Long, offsetMs: Float, bpm: Float): Float {
        if (bpm <= 0f) return 0f
        val beatMs = 60_000f / bpm
        val relative = (positionMs - offsetMs) / beatMs
        return relative - floor(relative)
    }

    fun alignedPositionMs(
        positionMs: Long,
        offsetMs: Float,
        bpm: Float,
        targetPhase: Float,
        durationMs: Long,
    ): Long? {
        if (bpm <= 0f || durationMs <= 1L) return null
        val beatMs = 60_000.0 / bpm
        var deltaBeats = targetPhase - phase(positionMs, offsetMs, bpm)
        if (deltaBeats > 0.5f) deltaBeats -= 1f
        if (deltaBeats < -0.5f) deltaBeats += 1f
        val target = (positionMs + deltaBeats * beatMs).roundToLong()
        return target.coerceIn(0L, durationMs - 1L)
    }
}
