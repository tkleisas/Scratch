package com.tkleisas.scratchdj.model

import com.tkleisas.scratchdj.data.Track

data class DeckUiState(
    val track: Track? = null,
    val peaks: FloatArray = FloatArray(0),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val filterMode: Int = 0,
    val filterCutoff: Float = 0.5f,
    val volume: Float = 1f,
    val bpm: Float = 0f,
    val eqNodes: List<EqNode> = EqNodes.defaults(),
    val eqBypassed: Boolean = false,
    val tempo: Float = 1f,
    val keyLock: Boolean = false,
    val loopIn: Float? = null,
    val loopOut: Float? = null,
    val loopEnabled: Boolean = false,
    val cuePoint: Float? = null,
    val hotCues: List<Float?> = List(4) { null },
    val slipMode: Boolean = false,
    val isLoading: Boolean = false,
    val loadingTitle: String? = null,
    val loadProgress: Float = 0f,
)
