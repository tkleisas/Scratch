package com.tkleisas.scratchdj.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("0:00", formatTime(0))
        assertEquals("1:05", formatTime(65_000))
        assertEquals("12:34", formatTime(754_000))
    }

    @Test
    fun `formats hours`() {
        assertEquals("1:00:00", formatTime(3_600_000))
        assertEquals("2:03:04", formatTime(7_384_000))
    }

    @Test
    fun `clamps negative values`() {
        assertEquals("0:00", formatTime(-500))
    }
}
