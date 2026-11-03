package com.tkleisas.scratchdj.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class ScratchMathTest {

    @Test
    fun `one revolution per second is 1_8x at 33 rpm`() {
        val rate = ScratchMath.rateFor((2.0 * PI).toFloat(), 1000L)
        assertEquals(1.8f, rate, 1e-3f)
    }

    @Test
    fun `one revolution in 1_8 seconds is normal speed`() {
        val rate = ScratchMath.rateFor((2.0 * PI).toFloat(), 1800L)
        assertEquals(1.0f, rate, 1e-3f)
    }

    @Test
    fun `counter clockwise rotation is negative`() {
        val rate = ScratchMath.rateFor(-(2.0 * PI).toFloat(), 1000L)
        assertEquals(-1.8f, rate, 1e-3f)
    }

    @Test
    fun `zero elapsed time is zero rate`() {
        assertEquals(0f, ScratchMath.rateFor(1f, 0L), 0f)
    }

    @Test
    fun `rate is clamped to plus minus eight`() {
        val rate = ScratchMath.rateFor((200.0 * PI).toFloat(), 1L)
        assertEquals(8f, rate, 0f)
    }
}
