package com.tkleisas.scratchdj.data

object SearchQuery {
    fun likePattern(term: String): String {
        val escaped = term.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }

    fun isBlank(term: String): Boolean = term.isBlank()
}
