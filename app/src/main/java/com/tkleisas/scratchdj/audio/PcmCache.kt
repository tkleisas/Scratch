package com.tkleisas.scratchdj.audio

import android.content.Context
import com.tkleisas.scratchdj.data.Track
import java.io.File

object PcmCache {
    private const val MAX_BYTES = 3L * 1024 * 1024 * 1024
    private const val MIN_FREE_BYTES = 256L * 1024 * 1024

    fun directory(context: Context): File =
        File(context.cacheDir, "pcm").apply { mkdirs() }

    fun fileFor(context: Context, track: Track, sampleRate: Int): File =
        File(
            directory(context),
            "track_${track.id}_${track.durationMs}_$sampleRate.pcm",
        )

    fun isValid(file: File): Boolean =
        file.isFile && file.length() > 0 && file.length() % 4 == 0L

    fun estimatedBytes(track: Track, sampleRate: Int): Long =
        (track.durationMs.coerceAtLeast(0) * sampleRate * 4L) / 1000L

    fun ensureSpace(context: Context, estimatedBytes: Long) {
        val free = directory(context).usableSpace
        if (estimatedBytes > 0 && free - estimatedBytes < MIN_FREE_BYTES) {
            throw AudioDecodeException(
                "Not enough free space to cache this track " +
                    "(${estimatedBytes / (1024 * 1024)} MB needed)",
            )
        }
    }

    fun prune(context: Context, maxBytes: Long = MAX_BYTES) {
        val directory = directory(context)
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".part") }
            ?.forEach { it.delete() }

        val files = directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".pcm") }
            ?.sortedBy { it.lastModified() }
            ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            val length = file.length()
            if (file.delete()) {
                total -= length
            }
        }
    }
}
