package com.tkleisas.scratchdj.model

data class EqPreset(
    val nodes: List<EqNode>,
    val bypassed: Boolean,
)

object EqPresetCodec {
    private const val PREFIX = "v1"

    fun encode(nodes: List<EqNode>, bypassed: Boolean): String {
        val body = nodes.joinToString(";") {
            "${it.frequency},${it.gainDb},${it.q}"
        }
        return "$PREFIX|${if (bypassed) "1" else "0"}|$body"
    }

    fun decode(payload: String): EqPreset? {
        val parts = payload.split("|", limit = 3)
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val bypassed = when (parts[1]) {
            "1" -> true
            "0" -> false
            else -> return null
        }

        val nodes = ArrayList<EqNode>(EqNodes.COUNT)
        for (token in parts[2].split(";")) {
            val fields = token.split(",")
            if (fields.size != 3) return null
            val frequency = fields[0].toFloatOrNull() ?: return null
            val gain = fields[1].toFloatOrNull() ?: return null
            val q = fields[2].toFloatOrNull() ?: return null
            nodes.add(
                EqNode(
                    frequency = frequency.coerceIn(0f, 1f),
                    gainDb = gain.coerceIn(-18f, 18f),
                    q = q.coerceIn(0.3f, 4f),
                ),
            )
        }
        if (nodes.size != EqNodes.COUNT) return null
        return EqPreset(nodes = nodes, bypassed = bypassed)
    }
}
