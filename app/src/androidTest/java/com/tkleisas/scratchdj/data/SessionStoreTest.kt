package com.tkleisas.scratchdj.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = File(context.cacheDir, "session-store-test.bin")

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun roundTripsQueuesAndPausedDeckState() {
        val trackA = track(1, "Deck A")
        val trackB = track(2, "Deck B")
        val snapshot = SessionSnapshot(
            deckA = DeckSession(
                tracks = listOf(trackA, track(3, "Next A")),
                currentIndex = 0,
                lastTrack = trackA,
                positionMs = 42_000L,
            ),
            deckB = DeckSession(
                tracks = listOf(trackB),
                currentIndex = 0,
                lastTrack = trackB,
                positionMs = 7_500L,
            ),
        )

        SessionStore(context, file).save(snapshot)
        val loaded = SessionStore(context, file).load()

        assertEquals(snapshot, loaded)
    }

    @Test
    fun corruptSessionLoadsAsNull() {
        file.writeText("not a session")

        assertNull(SessionStore(context, file).load())
    }

    private fun track(id: Long, title: String) = Track(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        displayName = "$title.wav",
        extension = "wav",
        durationMs = 60_000L,
        uri = "content://tracks/$id",
        bpm = 128f,
    )
}
