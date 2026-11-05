package com.tkleisas.scratchdj.desktop

import com.tkleisas.scratchdj.data.DeckSession
import com.tkleisas.scratchdj.data.SessionSnapshot
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

class DesktopSessionStore(
    private val file: File = defaultFile(),
) {
    fun load(): SessionSnapshot? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    return null
                }
                SessionSnapshot(
                    deckA = readDeck(input),
                    deckB = readDeck(input),
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun save(snapshot: SessionSnapshot) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            writeDeck(output, snapshot.deckA)
            writeDeck(output, snapshot.deckB)
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

    private fun writeDeck(output: DataOutputStream, deck: DeckSession) {
        writeTracks(output, deck.tracks)
        output.writeInt(deck.currentIndex)
        output.writeBoolean(deck.lastTrack != null)
        deck.lastTrack?.let { writeTrack(output, it) }
        output.writeLong(deck.positionMs)
    }

    private fun readDeck(input: DataInputStream): DeckSession {
        val tracks = readTracks(input)
        val currentIndex = input.readInt()
        val lastTrack = if (input.readBoolean()) readTrack(input) else null
        val positionMs = input.readLong()
        return DeckSession(tracks, currentIndex, lastTrack, positionMs)
    }

    private fun writeTracks(output: DataOutputStream, tracks: List<Track>) {
        output.writeInt(tracks.size)
        tracks.forEach { writeTrack(output, it) }
    }

    private fun readTracks(input: DataInputStream): List<Track> =
        List(input.readInt()) { readTrack(input) }

    private fun writeTrack(output: DataOutputStream, track: Track) {
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

    private fun readTrack(input: DataInputStream): Track = Track(
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

    private companion object {
        const val MAGIC = 0x53435353
        const val VERSION = 1

        fun defaultFile(): File {
            val config = System.getenv("XDG_CONFIG_HOME")
                ?: System.getenv("APPDATA")
                ?: "${System.getProperty("user.home")}/.config"
            return File(config, "scratch/session.bin")
        }
    }
}
