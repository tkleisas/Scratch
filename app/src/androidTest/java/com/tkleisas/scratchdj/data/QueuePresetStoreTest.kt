package com.tkleisas.scratchdj.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueuePresetStoreTest {

    private lateinit var context: Context
    private lateinit var store: QueuePresetStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DB)
        store = QueuePresetStore(context, TEST_DB)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun savesAndLoadsOneDeckScope() {
        store.save("A set", "A", listOf(track(1), track(2)), emptyList())

        val summaries = store.summaries()
        assertEquals(1, summaries.size)
        assertEquals("A set", summaries[0].name)
        assertEquals("A", summaries[0].scope)

        val loaded = store.load("A set")
        assertEquals(listOf(1L, 2L), loaded?.deckA?.map { it.id })
        assertEquals(emptyList<Long>(), loaded?.deckB?.map { it.id })
    }

    @Test
    fun savesAndLoadsBothDecksAndOverwrites() {
        store.save("Both", "ALL", listOf(track(1)), listOf(track(2), track(3)))
        store.save("Both", "ALL", listOf(track(4)), listOf(track(5)))

        val loaded = store.load("Both")
        assertEquals(listOf(4L), loaded?.deckA?.map { it.id })
        assertEquals(listOf(5L), loaded?.deckB?.map { it.id })
        assertEquals(1, store.summaries().size)
    }

    @Test
    fun deletesPresetAndItems() {
        store.save("Delete me", "ALL", listOf(track(1)), listOf(track(2)))

        store.delete("Delete me")

        assertNull(store.load("Delete me"))
        assertEquals(0, store.summaries().size)
    }

    private fun track(id: Long) = Track(
        id = id,
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        displayName = "Track$id.wav",
        extension = "wav",
        durationMs = 60_000L,
        uri = "content://tracks/$id",
        bpm = 120f,
    )

    private companion object {
        const val TEST_DB = "scratch_queue_presets_test.db"
    }
}
