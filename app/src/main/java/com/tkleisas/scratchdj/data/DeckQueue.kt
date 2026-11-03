package com.tkleisas.scratchdj.data

class DeckQueue(initial: List<Track> = emptyList()) {
    private val items = ArrayDeque<Track>()

    init {
        items.addAll(initial)
    }

    val size: Int
        get() = items.size

    fun snapshot(): List<Track> = items.toList()

    fun enqueue(track: Track) {
        items.addLast(track)
    }

    fun remove(id: Long): Boolean = items.removeAll { it.id == id }

    fun takeNext(): Track? = items.removeFirstOrNull()

    fun clear() {
        items.clear()
    }
}
