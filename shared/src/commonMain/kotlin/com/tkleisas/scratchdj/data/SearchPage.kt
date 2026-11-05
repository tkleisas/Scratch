package com.tkleisas.scratchdj.data

data class SearchPage(
    val tracks: List<Track>,
    val total: Int,
    val offset: Int,
) {
    val hasMore: Boolean
        get() = offset + tracks.size < total
}
