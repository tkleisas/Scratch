package com.tkleisas.scratchdj.model

import kotlin.math.ln
import kotlin.math.pow

data class EqNode(
    val frequency: Float,
    val gainDb: Float = 0f,
    val q: Float = 1f,
)

object EqNodes {
    const val COUNT = 8
    const val MIN_HZ = 30f
    const val MAX_HZ = 18_000f

    private val defaultHz = floatArrayOf(
        80f, 160f, 320f, 640f, 1_250f, 2_500f, 5_000f, 10_000f,
    )
    private val range = ln(MAX_HZ / MIN_HZ)

    fun defaults(): List<EqNode> = defaultHz.mapIndexed { index, hz ->
        EqNode(
            frequency = hzToNorm(hz),
            gainDb = 0f,
            q = if (isShelf(index)) 0.707f else 1f,
        )
    }

    fun isShelf(index: Int): Boolean = index == 0 || index == COUNT - 1

    fun typeLabel(index: Int): String = when {
        index == 0 -> "LOW SHELF"
        index == COUNT - 1 -> "HIGH SHELF"
        else -> "PEAK $index"
    }

    fun hzToNorm(hz: Float): Float =
        (ln(hz.coerceIn(MIN_HZ, MAX_HZ) / MIN_HZ) / range).coerceIn(0f, 1f)

    fun normToHz(normalized: Float): Float =
        MIN_HZ * (MAX_HZ / MIN_HZ).pow(normalized.coerceIn(0f, 1f))
}
