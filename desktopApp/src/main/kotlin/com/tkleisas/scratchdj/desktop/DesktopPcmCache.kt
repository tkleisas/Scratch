package com.tkleisas.scratchdj.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

object DesktopPcmCache {
    fun ensureDecoded(source: File, sampleRate: Int): File {
        val base = System.getenv("XDG_CACHE_HOME")
            ?: System.getenv("LOCALAPPDATA")
            ?: "${System.getProperty("user.home")}/.cache"
        val cacheDir = File(base, "scratch/pcm").apply { mkdirs() }

        val key = "${source.absolutePath}|${source.length()}|${source.lastModified()}|$sampleRate"
        val name = source.nameWithoutExtension
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48)
        val cache = File(cacheDir, "${name}_${key.hashCode().toUInt()}_$sampleRate.pcm")
        if (cache.isFile && cache.length() > 0 && cache.length() % 4 == 0L) {
            return cache
        }

        val temporary = File(cacheDir, cache.name + ".part")
        temporary.delete()
        val ffmpeg = System.getenv("SCRATCH_FFMPEG") ?: "ffmpeg"
        val process = ProcessBuilder(
            ffmpeg,
            "-v", "error",
            "-y",
            "-i", source.absolutePath,
            "-vn",
            "-f", "s16le",
            "-acodec", "pcm_s16le",
            "-ac", "2",
            "-ar", sampleRate.toString(),
            temporary.absolutePath,
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(10, TimeUnit.MINUTES) || process.exitValue() != 0) {
            process.destroyForcibly()
            temporary.delete()
            error("ffmpeg could not decode ${source.name}: ${output.trim()}")
        }
        if (!temporary.isFile || temporary.length() <= 0) {
            temporary.delete()
            error("ffmpeg produced no audio for ${source.name}")
        }

        try {
            Files.move(
                temporary.toPath(),
                cache.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Throwable) {
            Files.move(
                temporary.toPath(),
                cache.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return cache
    }
}
