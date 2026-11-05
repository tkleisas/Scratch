package com.tkleisas.scratchdj.data

class DeckQueue(initial: List<Track> = emptyList()) {
    private val items = ArrayList<Track>()
    private var index = -1

    init {
        items.addAll(initial)
    }

    val size: Int
        get() = items.size

    val currentIndex: Int
        get() = index

    val current: Track?
        get() = items.getOrNull(index)

    fun snapshot(): List<Track> = items.toList()

    fun enqueue(track: Track) {
        items.add(track)
    }

    fun contains(trackId: Long): Boolean = items.any { it.id == trackId }

    fun select(trackId: Long): Track? {
        val found = items.indexOfFirst { it.id == trackId }
        if (found < 0) return null
        index = found
        return items[found]
    }

    fun next(): Track? {
        if (index + 1 >= items.size) return null
        index += 1
        return items[index]
    }

    fun peekNext(): Track? = items.getOrNull(index + 1)

    fun hasNext(): Boolean = peekNext() != null

    fun previous(): Track? {
        if (index <= 0) return null
        index -= 1
        return items[index]
    }

    fun remove(trackId: Long): Boolean {
        val found = items.indexOfFirst { it.id == trackId }
        if (found < 0) return false
        items.removeAt(found)
        if (items.isEmpty()) {
            index = -1
        } else if (found <= index) {
            index -= 1
        }
        return true
    }

    fun replaceAll(tracks: List<Track>) {
        items.clear()
        items.addAll(tracks)
        index = -1
    }

    fun restore(tracks: List<Track>, currentIndex: Int) {
        items.clear()
        items.addAll(tracks)
        index = currentIndex.coerceIn(-1, items.lastIndex)
    }

    fun move(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        val item = items.removeAt(from)
        items.add(to, item)
        index = when {
            index == from -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }
        return true
    }

    fun clear() {
        items.clear()
        index = -1
    }
}
