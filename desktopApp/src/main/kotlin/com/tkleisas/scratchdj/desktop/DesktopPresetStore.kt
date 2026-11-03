package com.tkleisas.scratchdj.desktop

import com.tkleisas.scratchdj.data.QueuePresetData
import com.tkleisas.scratchdj.data.QueuePresetSummary
import com.tkleisas.scratchdj.data.Track
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DesktopPresetStore(
    private val file: File = defaultFile(),
) {
    data class Snapshot(
        val eqPresets: Map<String, String>,
        val queuePresets: Map<String, QueuePresetData>,
    )

    fun load(): Snapshot {
        if (!file.isFile) return Snapshot(emptyMap(), emptyMap())
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    return@use Snapshot(emptyMap(), emptyMap())
                }

                val eqPresets = LinkedHashMap<String, String>()
                repeat(input.readInt()) {
                    eqPresets[input.readUTF()] = input.readUTF()
                }

                val queuePresets = LinkedHashMap<String, QueuePresetData>()
                repeat(input.readInt()) {
                    val name = input.readUTF()
                    val scope = input.readUTF()
                    val deckA = readTracks(input)
                    val deckB = readTracks(input)
                    queuePresets[name] = QueuePresetData(
                        QueuePresetSummary(name, scope),
                        deckA,
                        deckB,
                    )
                }
                Snapshot(eqPresets, queuePresets)
            }
        } catch (_: Throwable) {
            Snapshot(emptyMap(), emptyMap())
        }
    }

    fun save(snapshot: Snapshot) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)

            output.writeInt(snapshot.eqPresets.size)
            snapshot.eqPresets.forEach { (name, payload) ->
                output.writeUTF(name)
                output.writeUTF(payload)
            }

            output.writeInt(snapshot.queuePresets.size)
            snapshot.queuePresets.forEach { (name, preset) ->
                output.writeUTF(name)
                output.writeUTF(preset.summary.scope)
                writeTracks(output, preset.deckA)
                writeTracks(output, preset.deckB)
            }
        }

        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Throwable) {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun writeTracks(output: DataOutputStream, tracks: List<Track>) {
        output.writeInt(tracks.size)
        tracks.forEach { track ->
            output.writeLong(track.id)
            output.writeUTF(track.title)
            output.writeUTF(track.artist)
            output.writeUTF(track.album)
            output.writeUTF(track.displayName)
            output.writeUTF(track.extension)
            output.writeLong(track.durationMs)
            output.writeUTF(track.uri)
            output.writeFloat(track.bpm)
        }
    }

    private fun readTracks(input: DataInputStream): List<Track> =
        List(input.readInt()) {
            Track(
                id = input.readLong(),
                title = input.readUTF(),
                artist = input.readUTF(),
                album = input.readUTF(),
                displayName = input.readUTF(),
                extension = input.readUTF(),
                durationMs = input.readLong(),
                uri = input.readUTF(),
                bpm = input.readFloat(),
            )
        }

    private companion object {
        const val MAGIC = 0x53435250
        const val VERSION = 1

        fun defaultFile(): File {
            val config = System.getenv("XDG_CONFIG_HOME")
                ?: System.getenv("APPDATA")
                ?: "${System.getProperty("user.home")}/.config"
            return File(config, "scratch/presets.bin")
        }
    }
}
