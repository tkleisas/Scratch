package com.tkleisas.scratchdj.desktop

import com.tkleisas.scratchdj.data.DeckSession
import com.tkleisas.scratchdj.data.SessionSnapshot
import com.tkleisas.scratchdj.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopSessionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `round trips queues and paused deck state`() {
        val file = temporaryFolder.newFile("session.bin")
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

        DesktopSessionStore(file).save(snapshot)
        val loaded = DesktopSessionStore(file).load()

        assertEquals(snapshot, loaded)
    }

    @Test
    fun `corrupt session loads as null`() {
        val file = temporaryFolder.newFile("session.bin")
        file.writeText("not a session")

        assertNull(DesktopSessionStore(file).load())
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
