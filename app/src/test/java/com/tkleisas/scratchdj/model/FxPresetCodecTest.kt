package com.tkleisas.scratchdj.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FxPresetCodecTest {
    @Test
    fun roundTripsRackChainsAndPadMode() {
        val defaults = FxPersistedState()
        val filter = FxEffect.Filter
        val rack = defaults.racks.getValue(FxRack.A)
        val insert = rack.insert
            .withEnabled(filter, true)
            .withParam(filter, 0, 0.42f)
            .withOrder(listOf(FxEffect.Reverb, FxEffect.Filter))
        val punch = rack.punch
            .withSend(FxEffect.Echo, 0.73f)
            .withEnabled(FxEffect.Echo, false)
        val state = defaults.copy(
            racks = defaults.racks + (FxRack.A to rack.copy(insert = insert, punch = punch)),
            padMode = FxPadMode.Latch,
        )

        val decoded = FxPresetCodec.decode(FxPresetCodec.encode(state))

        assertEquals(FxPadMode.Latch, decoded.padMode)
        assertEquals(state.racks[FxRack.A]?.insert, decoded.racks[FxRack.A]?.insert)
        assertEquals(state.racks[FxRack.A]?.punch, decoded.racks[FxRack.A]?.punch)
        assertEquals(state.racks[FxRack.Master], decoded.racks[FxRack.Master])
    }

    @Test
    fun malformedPayloadFallsBackToDefaults() {
        val decoded = FxPresetCodec.decode("not-a-scratch-fx-payload")
        assertEquals(FxPadMode.Hold, decoded.padMode)
        FxRack.entries.forEach { rack ->
            val state = decoded.racks.getValue(rack)
            assertTrue(state.insert.effects.values.none { it.enabled })
            assertTrue(state.punch.effects.values.all { it.enabled })
        }
    }
}
