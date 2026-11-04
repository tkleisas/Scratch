package com.tkleisas.scratchdj.model

enum class WaveformMode {
    Full,
    Scroll,
}

enum class WaveformWindowUnit {
    Seconds,
    Beats,
}

data class WaveformWindow(
    val startFraction: Float,
    val fraction: Float,
    val playheadFraction: Float,
)

object WaveformWindowMath {
    const val MIN_SECONDS = 5f
    const val MAX_SECONDS = 120f
    const val MIN_BEATS = 4f
    const val MAX_BEATS = 64f
    const val DEFAULT_SECONDS = 30f
    const val DEFAULT_BEATS = 32f
    private const val FALLBACK_BPM = 120f
    private const val MIN_WINDOW_MS = 1_000L

    fun windowMs(
        unit: WaveformWindowUnit,
        seconds: Float,
        beats: Float,
        bpm: Float,
    ): Long {
        val windowMs = when (unit) {
            WaveformWindowUnit.Seconds -> seconds.coerceIn(MIN_SECONDS, MAX_SECONDS) * 1000f
            WaveformWindowUnit.Beats -> {
                val effectiveBpm = if (bpm > 0f) bpm else FALLBACK_BPM
                beats.coerceIn(MIN_BEATS, MAX_BEATS) * 60_000f / effectiveBpm
            }
        }
        return windowMs.toLong().coerceAtLeast(MIN_WINDOW_MS)
    }

    fun windowFraction(windowMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 1f
        return (windowMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    fun viewport(progress: Float, windowFraction: Float): WaveformWindow {
        val fraction = windowFraction.coerceIn(0f, 1f)
        val position = progress.coerceIn(0f, 1f)
        if (fraction >= 1f) {
            return WaveformWindow(0f, 1f, position)
        }
        val start = (position - fraction / 2f).coerceIn(0f, 1f - fraction)
        val playhead = ((position - start) / fraction).coerceIn(0f, 1f)
        return WaveformWindow(start, fraction, playhead)
    }
}
