package com.tkleisas.scratchdj.desktop

import com.tkleisas.scratchdj.data.QueuePresetData
import com.tkleisas.scratchdj.data.QueuePresetSummary
import com.tkleisas.scratchdj.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopPresetStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `round trips eq and queue presets`() {
        val file = temporaryFolder.newFile("presets.bin")
        val store = DesktopPresetStore(file)
        val trackA = track(1, "Deck A track")
        val trackB = track(2, "Deck B track")
        val queue = QueuePresetData(
            QueuePresetSummary("Both", "ALL"),
            listOf(trackA),
            listOf(trackB),
        )

        store.save(
            DesktopPresetStore.Snapshot(
                eqPresets = mapOf("Bass" to "v1|1|0.5,6,1"),
                queuePresets = mapOf("Both" to queue),
            ),
        )
        val loaded = DesktopPresetStore(file).load()

        assertEquals("v1|1|0.5,6,1", loaded.eqPresets["Bass"])
        assertEquals("ALL", loaded.queuePresets["Both"]?.summary?.scope)
        assertEquals(trackA, loaded.queuePresets["Both"]?.deckA?.single())
        assertEquals(trackB, loaded.queuePresets["Both"]?.deckB?.single())
    }

    @Test
    fun `overwrites previous snapshot`() {
        val file = temporaryFolder.newFile("presets.bin")
        val store = DesktopPresetStore(file)
        store.save(DesktopPresetStore.Snapshot(mapOf("A" to "one"), emptyMap()))
        store.save(DesktopPresetStore.Snapshot(mapOf("A" to "two"), emptyMap()))

        assertEquals("two", DesktopPresetStore(file).load().eqPresets["A"])
    }

    @Test
    fun `corrupt file loads as empty`() {
        val file = temporaryFolder.newFile("presets.bin")
        file.writeText("not a preset file")

        val loaded = DesktopPresetStore(file).load()

        assertTrue(loaded.eqPresets.isEmpty())
        assertTrue(loaded.queuePresets.isEmpty())
    }

    private fun track(id: Long, title: String) = Track(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        displayName = "$title.wav",
        extension = "wav",
        durationMs = 60_000L,
        uri = "/tmp/$title.wav",
        bpm = 128f,
    )
}
