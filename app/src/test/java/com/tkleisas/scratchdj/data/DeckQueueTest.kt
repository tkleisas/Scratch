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
    fun `next and previous navigate without removing tracks`() {
        val queue = DeckQueue()
        queue.enqueue(track(1))
        queue.enqueue(track(2))
        queue.enqueue(track(3))

        assertEquals(1L, queue.next()?.id)
        assertEquals(2L, queue.next()?.id)
        assertEquals(3L, queue.next()?.id)
        assertNull(queue.next())

        assertEquals(2L, queue.previous()?.id)
        assertEquals(1L, queue.previous()?.id)
        assertNull(queue.previous())

        assertEquals(3, queue.size)
        assertEquals(listOf(1L, 2L, 3L), queue.snapshot().map { it.id })
    }

    @Test
    fun `peekNext does not advance the queue`() {
        val queue = DeckQueue(listOf(track(1), track(2), track(3)))
        assertEquals(1L, queue.next()?.id)
        assertEquals(2L, queue.peekNext()?.id)
        assertTrue(queue.hasNext())
        assertEquals(2L, queue.next()?.id)
        assertEquals(3L, queue.peekNext()?.id)
        assertEquals(3L, queue.next()?.id)
        assertNull(queue.peekNext())
        assertEquals(false, queue.hasNext())
    }

    @Test
    fun `selects a track by id`() {
        val queue = DeckQueue(listOf(track(1), track(2), track(3)))

        assertEquals(3L, queue.select(3)?.id)
        assertEquals(2, queue.currentIndex)
        assertEquals(2L, queue.previous()?.id)
    }

    @Test
    fun `removing current keeps next navigation correct`() {
        val queue = DeckQueue(listOf(track(1), track(2), track(3)))
        queue.next()
        queue.next()

        assertTrue(queue.remove(2))

        assertEquals(listOf(1L, 3L), queue.snapshot().map { it.id })
        assertEquals(3L, queue.next()?.id)
    }

    @Test
    fun `removing before current adjusts index`() {
        val queue = DeckQueue(listOf(track(1), track(2), track(3)))
        queue.next()
        queue.next()

        assertTrue(queue.remove(1))

        assertEquals(listOf(2L, 3L), queue.snapshot().map { it.id })
        assertEquals(3L, queue.next()?.id)
    }

    @Test
    fun `missing removal does not change queue`() {
        val queue = DeckQueue(listOf(track(1)))

        assertTrue(!queue.remove(99))

        assertEquals(1, queue.size)
    }

    @Test
    fun `replaceAll resets the cursor`() {
        val queue = DeckQueue(listOf(track(1), track(2)))
        queue.next()

        queue.replaceAll(listOf(track(3), track(4)))

        assertEquals(listOf(3L, 4L), queue.snapshot().map { it.id })
        assertEquals(3L, queue.next()?.id)
    }

    @Test
    fun `clears queue and cursor`() {
        val queue = DeckQueue(listOf(track(1), track(2)))
        queue.next()

        queue.clear()

        assertEquals(0, queue.size)
        assertNull(queue.current)
    }
}
