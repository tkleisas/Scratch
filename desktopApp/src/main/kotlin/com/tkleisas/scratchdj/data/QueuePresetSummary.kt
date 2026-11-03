package com.tkleisas.scratchdj.data

data class QueuePresetSummary(
    val name: String,
    val scope: String,
)

data class QueuePresetData(
    val summary: QueuePresetSummary,
    val deckA: List<Track>,
    val deckB: List<Track>,
)
