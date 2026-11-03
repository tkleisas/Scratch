package com.tkleisas.scratchdj.model

enum class DeckSide(val index: Int, val letter: String) {
    A(0, "A"),
    B(1, "B"),
    ;

    fun other(): DeckSide = if (this == A) B else A
}
