package com.tkleisas.scratchdj.data

object AudioFormats {
    val supportedExtensions = setOf(
        "mp3",
        "flac",
        "ogg",
        "oga",
        "opus",
        "wav",
        "aiff",
        "aif",
    )

    private val nativePcmExtensions = setOf("wav", "aiff", "aif")

    fun extensionOf(displayName: String): String? {
        val dot = displayName.lastIndexOf('.')
        if (dot <= 0 || dot == displayName.lastIndex) return null
        return displayName.substring(dot + 1).lowercase()
    }

    fun isSupported(displayName: String): Boolean =
        extensionOf(displayName) in supportedExtensions

    fun isNativePlayable(displayName: String): Boolean =
        extensionOf(displayName) in nativePcmExtensions
}
