package com.tkleisas.scratchdj.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckQueueTest {

    private fun track(id: Long, title: String = "Track $id") = Track(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        displayName = "$title.wav",
        extension = "wav",
        durationMs = 1000L,
        uri = "content://tracks/$id",
    )

    @Test
    fun `takes tracks in fifo order`() {
        val queue = DeckQueue()
        queue.enqueue(track(1))
        queue.enqueue(track(2))

        assertEquals(listOf("Track 1", "Track 2"), queue.snapshot().map { it.title })
        assertEquals(1L, queue.takeNext()?.id)
        assertEquals(2L, queue.takeNext()?.id)
        assertNull(queue.takeNext())
    }

    @Test
    fun `removes by id`() {
        val queue = DeckQueue(listOf(track(1), track(2), track(3)))

        assertTrue(queue.remove(2))

        assertEquals(listOf(1L, 3L), queue.snapshot().map { it.id })
    }

    @Test
    fun `does not duplicate when removing missing id`() {
        val queue = DeckQueue(listOf(track(1)))

        assertTrue(!queue.remove(99))

        assertEquals(1, queue.size)
    }

    @Test
    fun `clears queue`() {
        val queue = DeckQueue(listOf(track(1), track(2)))

        queue.clear()

        assertEquals(0, queue.size)
    }
}
