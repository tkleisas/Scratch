package com.tkleisas.scratchdj.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqPresetCodecTest {

    @Test
    fun `round trips nodes and bypass`() {
        val nodes = EqNodes.defaults().mapIndexed { index, node ->
            node.copy(gainDb = index.toFloat() - 4f, q = 0.75f + index * 0.1f)
        }

        val encoded = EqPresetCodec.encode(nodes, bypassed = true)
        val decoded = EqPresetCodec.decode(encoded)

        assertTrue(decoded != null)
        assertTrue(decoded!!.bypassed)
        assertEquals(nodes.size, decoded.nodes.size)
        nodes.indices.forEach { index ->
            assertEquals(nodes[index].frequency, decoded.nodes[index].frequency, 1e-6f)
            assertEquals(nodes[index].gainDb, decoded.nodes[index].gainDb, 1e-6f)
            assertEquals(nodes[index].q, decoded.nodes[index].q, 1e-6f)
        }
    }

    @Test
    fun `rejects malformed payloads`() {
        assertNull(EqPresetCodec.decode(""))
        assertNull(EqPresetCodec.decode("v2|0|1,2,3"))
        assertNull(EqPresetCodec.decode("v1|2|1,2,3"))
        assertNull(EqPresetCodec.decode("v1|0|bad"))
        assertNull(EqPresetCodec.decode("v1|0|0.5,0,1"))
    }

    @Test
    fun `clamps out of range values`() {
        val nodes = EqNodes.defaults().joinToString(";") { "2,99,99" }
        val decoded = EqPresetCodec.decode("v1|0|$nodes")
        assertTrue(decoded != null)
        assertTrue(decoded!!.nodes.all { it.frequency == 1f && it.gainDb == 18f && it.q == 4f })
    }
}
