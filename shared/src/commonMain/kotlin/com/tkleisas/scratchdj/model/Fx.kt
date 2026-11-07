package com.tkleisas.scratchdj.model

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

enum class FxRack(val label: String) {
    A("A"),
    B("B"),
    Master("Master"),
}

enum class FxChain(val label: String) {
    Insert("Insert"),
    Punch("Punch"),
}

enum class FxPadMode(val label: String) {
    Hold("Hold"),
    Latch("Latch"),
}

enum class FxValueFormat {
    Percent,
    Decibels,
    Milliseconds,
    PreDelay,
    Seconds,
    Hertz,
    FilterCutoff,
    Ratio,
    Bits,
    Toggle,
    Steps,
}

data class FxParamSpec(
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val format: FxValueFormat = FxValueFormat.Percent,
    val logarithmic: Boolean = false,
    val steps: List<String>? = null,
) {
    fun normalized(value: Float): Float {
        val clamped = value.coerceIn(min, max)
        return if (logarithmic && min > 0f) {
            (ln(clamped / min) / ln(max / min)).coerceIn(0f, 1f)
        } else {
            ((clamped - min) / (max - min)).coerceIn(0f, 1f)
        }
    }

    fun valueAt(normalized: Float): Float {
        val t = normalized.coerceIn(0f, 1f)
        return if (logarithmic && min > 0f) {
            min * (max / min).pow(t)
        } else {
            min + (max - min) * t
        }
    }

    fun format(value: Float): String {
        val clamped = value.coerceIn(min, max)
        return when (format) {
            FxValueFormat.Percent -> "${(clamped * 100f).roundToInt()}%"
            FxValueFormat.Decibels -> "${oneDecimal(clamped)} dB"
            FxValueFormat.Milliseconds -> "${clamped.roundToInt()} ms"
            FxValueFormat.PreDelay -> "${(clamped.coerceIn(0f, 1f) * 250f).roundToInt()} ms"
            FxValueFormat.Seconds -> "${twoDecimals(clamped)} s"
            FxValueFormat.Hertz -> if (clamped >= 1000f) {
                "${oneDecimal(clamped / 1000f)} kHz"
            } else {
                "${clamped.roundToInt()} Hz"
            }
            FxValueFormat.FilterCutoff -> {
                val hz = 20.0 * 1000.0.pow(clamped.coerceIn(0f, 1f).toDouble())
                if (hz >= 1000.0) "${oneDecimal((hz / 1000.0).toFloat())} kHz"
                else "${hz.roundToInt()} Hz"
            }
            FxValueFormat.Ratio -> "${oneDecimal(clamped)}:1"
            FxValueFormat.Bits -> "${clamped.roundToInt()} bit"
            FxValueFormat.Toggle -> if (clamped >= 0.5f) "ON" else "OFF"
            FxValueFormat.Steps -> steps
                ?.getOrNull(clamped.roundToInt().coerceIn(0, (steps.size - 1).coerceAtLeast(0)))
                ?: clamped.roundToInt().toString()
        }
    }
}

enum class FxEffect(
    val label: String,
    val shortLabel: String,
    val params: List<FxParamSpec>,
    val mixParamIndex: Int = -1,
    val defaultSend: Float = 0.35f,
    val punchAmountLabel: String = "Send",
) {
    Filter(
        label = "Filter",
        shortLabel = "FLT",
        params = listOf(
            FxParamSpec("Cutoff", 0f, 1f, 0.566f, FxValueFormat.FilterCutoff),
            FxParamSpec("Resonance", 0.1f, 20f, 0.707f, FxValueFormat.Ratio),
            FxParamSpec("Type", 0f, 3f, 0f, FxValueFormat.Steps, steps = listOf("LP", "HP", "BP", "Notch")),
        ),
    ),
    Crush(
        label = "Crush",
        shortLabel = "CRS",
        params = listOf(
            FxParamSpec("Bits", 1f, 16f, 12f, FxValueFormat.Bits),
            FxParamSpec("Rate", 100f, 48000f, 24000f, FxValueFormat.Hertz, logarithmic = true),
            FxParamSpec("Pre Filter", 200f, 20000f, 20000f, FxValueFormat.Hertz, logarithmic = true),
            FxParamSpec("Dither", 0f, 1f, 0f, FxValueFormat.Toggle),
            FxParamSpec("Mix", 0f, 1f, 1f, FxValueFormat.Percent),
            FxParamSpec("OS 2x", 0f, 1f, 0f, FxValueFormat.Toggle),
        ),
        mixParamIndex = 4,
        defaultSend = 0.4f,
    ),
    Echo(
        label = "Echo",
        shortLabel = "ECH",
        params = listOf(
            FxParamSpec("Time", 1f, 4000f, 500f, FxValueFormat.Milliseconds, logarithmic = true),
            FxParamSpec("Feedback", 0f, 0.95f, 0.4f, FxValueFormat.Percent),
            FxParamSpec("Wet/Dry", 0f, 1f, 0.3f, FxValueFormat.Percent),
            FxParamSpec("Ping-Pong", 0f, 1f, 0f, FxValueFormat.Toggle),
            FxParamSpec("Filter", 200f, 20000f, 8000f, FxValueFormat.Hertz, logarithmic = true),
            FxParamSpec("Tempo Sync", 0f, 1f, 0f, FxValueFormat.Toggle),
        ),
        mixParamIndex = 2,
    ),
    Reverb(
        label = "Hall Reverb",
        shortLabel = "REV",
        params = listOf(
            FxParamSpec("Pre-Delay", 0f, 1f, 0.04f, FxValueFormat.PreDelay),
            FxParamSpec("Decay", 0f, 1f, 0.677f, FxValueFormat.Percent),
            FxParamSpec("Damping", 0f, 1f, 0.5f, FxValueFormat.Percent),
            FxParamSpec("Diffusion", 0f, 1f, 0.7f, FxValueFormat.Percent),
            FxParamSpec("Width", 0f, 1f, 1f, FxValueFormat.Percent),
            FxParamSpec("Wet/Dry", 0f, 1f, 0.3f, FxValueFormat.Percent),
        ),
        mixParamIndex = 5,
    ),
    Tape(
        label = "Tape",
        shortLabel = "TPE",
        params = listOf(
            FxParamSpec("Saturation", 0f, 1f, 0.3f, FxValueFormat.Percent),
            FxParamSpec("Wow", 0f, 1f, 0.15f, FxValueFormat.Percent),
            FxParamSpec("Flutter", 0f, 1f, 0.1f, FxValueFormat.Percent),
            FxParamSpec("Hiss", 0f, 1f, 0.05f, FxValueFormat.Percent),
            FxParamSpec("Tone", 200f, 20000f, 8000f, FxValueFormat.Hertz, logarithmic = true),
            FxParamSpec("Wet/Dry", 0f, 1f, 1f, FxValueFormat.Percent),
            FxParamSpec("OS 2x", 0f, 1f, 0f, FxValueFormat.Toggle),
        ),
        mixParamIndex = 5,
        defaultSend = 0.45f,
    ),
    Compressor(
        label = "Compressor",
        shortLabel = "CMP",
        params = listOf(
            FxParamSpec("Threshold", -60f, 0f, -20f, FxValueFormat.Decibels),
            FxParamSpec("Ratio", 1f, 20f, 4f, FxValueFormat.Ratio),
            FxParamSpec("Attack", 0.1f, 100f, 10f, FxValueFormat.Milliseconds, logarithmic = true),
            FxParamSpec("Release", 10f, 1000f, 100f, FxValueFormat.Milliseconds, logarithmic = true),
            FxParamSpec("Makeup", 0f, 24f, 0f, FxValueFormat.Decibels),
            FxParamSpec("Knee", 0f, 12f, 6f, FxValueFormat.Decibels),
        ),
        defaultSend = 1.0f,
        punchAmountLabel = "Mix",
    ),
    Limiter(
        label = "Limiter",
        shortLabel = "LIM",
        params = listOf(
            FxParamSpec("Ceiling", -24f, 0f, -0.3f, FxValueFormat.Decibels),
            FxParamSpec("Release", 1f, 1000f, 50f, FxValueFormat.Milliseconds, logarithmic = true),
            FxParamSpec("Lookahead", 0f, 20f, 5f, FxValueFormat.Milliseconds),
        ),
        defaultSend = 1.0f,
        punchAmountLabel = "Mix",
    );

    fun defaultParams(punch: Boolean): List<Float> = params.mapIndexed { index, spec ->
        if (punch && index == mixParamIndex) 1f else spec.default
    }

    fun defaultState(punch: Boolean): FxEffectState = FxEffectState(
        enabled = punch,
        params = defaultParams(punch),
        send = defaultSend,
    )
}

data class FxEffectState(
    val enabled: Boolean,
    val params: List<Float>,
    val send: Float,
)

data class FxChainState(
    val order: List<FxEffect>,
    val effects: Map<FxEffect, FxEffectState>,
) {
    fun effect(effect: FxEffect): FxEffectState =
        effects[effect] ?: effect.defaultState(false)

    fun withEnabled(effect: FxEffect, enabled: Boolean): FxChainState =
        copy(effects = effects + (effect to effect(effect).copy(enabled = enabled)))

    fun withParam(effect: FxEffect, index: Int, value: Float): FxChainState {
        val node = effect(effect)
        val params = node.params.toMutableList()
        if (index in params.indices) params[index] = value
        return copy(effects = effects + (effect to node.copy(params = params)))
    }

    fun withSend(effect: FxEffect, send: Float): FxChainState =
        copy(effects = effects + (effect to effect(effect).copy(send = send.coerceIn(0f, 1f))))

    fun withOrder(order: List<FxEffect>): FxChainState {
        val distinct = order.distinct()
        return copy(order = distinct + FxEffect.entries.filter { it !in distinct })
    }

    companion object {
        fun defaults(chain: FxChain): FxChainState {
            val punch = chain == FxChain.Punch
            return FxChainState(
                order = FxEffect.entries.toList(),
                effects = FxEffect.entries.associateWith { it.defaultState(punch) },
            )
        }
    }
}

data class FxRackState(
    val insert: FxChainState = FxChainState.defaults(FxChain.Insert),
    val punch: FxChainState = FxChainState.defaults(FxChain.Punch),
) {
    fun chain(chain: FxChain): FxChainState =
        if (chain == FxChain.Insert) insert else punch

    fun withChain(chain: FxChain, state: FxChainState): FxRackState =
        if (chain == FxChain.Insert) copy(insert = state) else copy(punch = state)
}

private fun oneDecimal(value: Float): String {
    val negative = value < 0f
    val scaled = (kotlin.math.abs(value) * 10f).roundToInt()
    val whole = scaled / 10
    val fraction = scaled % 10
    return (if (negative) "-" else "") + "$whole.$fraction"
}

private fun twoDecimals(value: Float): String {
    val negative = value < 0f
    val scaled = (kotlin.math.abs(value) * 100f).roundToInt()
    val whole = scaled / 100
    val fraction = scaled % 100
    val fractionText = if (fraction < 10) "0$fraction" else fraction.toString()
    return (if (negative) "-" else "") + "$whole.$fractionText"
}

object FxDefaults {
    fun racks(): Map<FxRack, FxRackState> = FxRack.entries.associateWith { FxRackState() }
}

data class FxPersistedState(
    val racks: Map<FxRack, FxRackState> = FxDefaults.racks(),
    val padMode: FxPadMode = FxPadMode.Hold,
)

object FxPresetCodec {
    private const val HEADER = "FX1"

    fun encode(state: FxPersistedState): String {
        val racks = FxRack.entries.joinToString("~") { rack ->
            val rackState = state.racks[rack] ?: FxRackState()
            encodeChain(rackState.insert) + "^" + encodeChain(rackState.punch)
        }
        return "$HEADER~${state.padMode.name}~$racks"
    }

    fun decode(payload: String?): FxPersistedState {
        if (payload.isNullOrBlank()) return FxPersistedState()
        return try {
            val parts = payload.split("~")
            if (parts.size < 5 || parts[0] != HEADER) return FxPersistedState()
            val mode = runCatching { FxPadMode.valueOf(parts[1]) }.getOrDefault(FxPadMode.Hold)
            val racks = FxRack.entries.mapIndexed { index, rack ->
                val rackParts = parts[index + 2].split("^")
                val insert = decodeChain(rackParts.getOrNull(0), FxChain.Insert)
                val punch = decodeChain(rackParts.getOrNull(1), FxChain.Punch)
                rack to FxRackState(insert, punch)
            }.toMap()
            FxPersistedState(racks, mode)
        } catch (_: Throwable) {
            FxPersistedState()
        }
    }

    private fun encodeChain(chain: FxChainState): String {
        val order = chain.order.joinToString(",") { it.ordinal.toString() }
        val enabled = FxEffect.entries.fold(0) { mask, effect ->
            if (chain.effect(effect).enabled) mask or (1 shl effect.ordinal) else mask
        }
        val effects = FxEffect.entries.joinToString("|") { effect ->
            val node = chain.effect(effect)
            node.send.toString() + ":" + node.params.joinToString(",") { it.toString() }
        }
        return "$order#$enabled#$effects"
    }

    private fun decodeChain(payload: String?, chain: FxChain): FxChainState {
        val defaults = FxChainState.defaults(chain)
        if (payload.isNullOrBlank()) return defaults
        return try {
            val parts = payload.split("#")
            if (parts.size < 3) return defaults
            val order = parts[0].split(",")
                .mapNotNull { value -> FxEffect.entries.getOrNull(value.toIntOrNull() ?: -1) }
                .distinct()
            val fullOrder = order + FxEffect.entries.filter { it !in order }
            val enabledMask = parts[1].toIntOrNull() ?: 0
            val encodedEffects = parts[2].split("|")
            val effects = FxEffect.entries.mapIndexed { index, effect ->
                val nodeDefault = defaults.effect(effect)
                val encoded = encodedEffects.getOrNull(index)
                if (encoded.isNullOrBlank()) {
                    effect to nodeDefault
                } else {
                    val fields = encoded.split(":")
                    val send = fields.getOrNull(0)?.toFloatOrNull()
                        ?.coerceIn(0f, 1f) ?: nodeDefault.send
                    val values = fields.getOrNull(1)?.split(",").orEmpty()
                    val params = effect.params.mapIndexed { paramIndex, spec ->
                        val raw = values.getOrNull(paramIndex)?.toFloatOrNull()
                        (raw ?: nodeDefault.params.getOrNull(paramIndex) ?: spec.default)
                            .coerceIn(spec.min, spec.max)
                    }
                    effect to FxEffectState(
                        enabled = (enabledMask and (1 shl effect.ordinal)) != 0,
                        params = params,
                        send = send,
                    )
                }
            }.toMap()
            FxChainState(fullOrder, effects)
        } catch (_: Throwable) {
            defaults
        }
    }
}
