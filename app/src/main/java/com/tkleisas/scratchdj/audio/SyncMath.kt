package com.tkleisas.scratchdj.audio

object SyncMath {
    fun tempoRatio(sourceBpm: Float, targetBpm: Float): Float? {
        if (sourceBpm <= 0f || targetBpm <= 0f) return null
        return (targetBpm / sourceBpm).coerceIn(0.5f, 2.0f)
    }
}
