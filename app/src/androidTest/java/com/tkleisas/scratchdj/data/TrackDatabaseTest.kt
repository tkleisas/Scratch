package com.tkleisas.scratchdj.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackDatabaseTest {

    private lateinit var context: Context
    private lateinit var database: TrackDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DB)
        database = TrackDatabase(context, TEST_DB)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun searchesTitleArtistAndAlbum() {
        database.replaceAll(
            listOf(
                track(1, title = "Midnight Drive", artist = "Neon", album = "Night"),
                track(2, title = "Sunrise", artist = "Midnight Band", album = "Day"),
                track(3, title = "Other", artist = "Someone", album = "Midnight Sessions"),
            ),
        )

        assertEquals(setOf(1L, 2L, 3L), database.search("midnight").map { it.id }.toSet())
    }

    @Test
    fun searchIsCaseInsensitive() {
        database.replaceAll(listOf(track(1, title = "Midnight Drive")))

        assertEquals(1, database.search("MIDNIGHT").size)
    }

    @Test
    fun escapesLikeWildcards() {
        database.replaceAll(
            listOf(
                track(1, title = "100% Pure"),
                track(2, title = "100 Pure"),
                track(3, title = "a_b"),
                track(4, title = "axb"),
            ),
        )

        assertEquals(listOf(1L), database.search("%").map { it.id })
        assertEquals(listOf(3L), database.search("_").map { it.id })
    }

    @Test
    fun replaceAllRemovesStaleTracks() {
        database.replaceAll(listOf(track(1), track(2)))

        database.replaceAll(listOf(track(2), track(3)))

        assertEquals(setOf(2L, 3L), database.search("").map { it.id }.toSet())
    }

    @Test
    fun blankSearchReturnsEverything() {
        database.replaceAll(listOf(track(1), track(2), track(3)))

        assertEquals(3, database.search("   ").size)
        assertEquals(3, database.count())
    }

    @Test
    fun updatesBpmAndPreservesItAcrossReplaceAll() {
        database.replaceAll(listOf(track(1), track(2)))
        database.updateBpm(1, 128.0f)

        database.replaceAll(listOf(track(1), track(2), track(3)))
        val byId = database.search("").associateBy { it.id }

        assertEquals(128.0f, byId.getValue(1L).bpm, 1e-3f)
        assertEquals(0.0f, byId.getValue(2L).bpm, 1e-3f)
    }

    @Test
    fun respectsLimit() {
        database.replaceAll((1L..10L).map { track(it) })

        assertTrue(database.search("", limit = 4).size == 4)
    }

    private fun track(
        id: Long,
        title: String = "Track $id",
        artist: String = "Artist $id",
        album: String = "Album $id",
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        displayName = "$title.wav",
        extension = "wav",
        durationMs = 60_000L,
        uri = "content://tracks/$id",
    )

    private companion object {
        const val TEST_DB = "scratch_library_test.db"
    }
}
