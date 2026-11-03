package com.tkleisas.scratchdj.data

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val displayName: String,
    val extension: String,
    val durationMs: Long,
    val uri: String,
    val bpm: Float = 0f,
)
