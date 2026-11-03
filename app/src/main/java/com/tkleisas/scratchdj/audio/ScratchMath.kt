package com.tkleisas.scratchdj.audio

import kotlin.math.PI

object ScratchMath {
    const val RECORD_RPM = 33.333f
    const val MAX_RATE = 8f

    fun rateFor(deltaRadians: Float, deltaMillis: Long, rpm: Float = RECORD_RPM): Float {
        if (deltaMillis <= 0L || rpm <= 0f) return 0f
        val revolutionsPerSecond =
            (deltaRadians / (2.0 * PI).toFloat()) * (1000f / deltaMillis)
        val nominalRevolutionsPerSecond = rpm / 60f
        return (revolutionsPerSecond / nominalRevolutionsPerSecond).coerceIn(-MAX_RATE, MAX_RATE)
    }
}
