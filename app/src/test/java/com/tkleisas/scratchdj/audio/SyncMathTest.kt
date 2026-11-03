package com.tkleisas.scratchdj.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncMathTest {

    @Test
    fun `matching tempo is unity`() {
        assertEquals(1f, SyncMath.tempoRatio(120f, 120f)!!, 1e-4f)
    }

    @Test
    fun `syncs slower deck up`() {
        assertEquals(128f / 120f, SyncMath.tempoRatio(120f, 128f)!!, 1e-4f)
    }

    @Test
    fun `syncs faster deck down`() {
        assertEquals(120f / 128f, SyncMath.tempoRatio(128f, 120f)!!, 1e-4f)
    }

    @Test
    fun `clamps extreme ratios`() {
        assertEquals(2f, SyncMath.tempoRatio(40f, 200f)!!, 1e-4f)
        assertEquals(0.5f, SyncMath.tempoRatio(200f, 40f)!!, 1e-4f)
    }

    @Test
    fun `missing bpm has no ratio`() {
        assertNull(SyncMath.tempoRatio(0f, 120f))
        assertNull(SyncMath.tempoRatio(120f, 0f))
    }
}
