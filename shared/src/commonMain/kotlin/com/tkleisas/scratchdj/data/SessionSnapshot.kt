package com.tkleisas.scratchdj.data

data class DeckSession(
    val tracks: List<Track>,
    val currentIndex: Int,
    val lastTrack: Track?,
    val positionMs: Long,
)

data class SessionSnapshot(
    val deckA: DeckSession,
    val deckB: DeckSession,
)
