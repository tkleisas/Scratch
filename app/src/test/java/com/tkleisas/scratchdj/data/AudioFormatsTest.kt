package com.tkleisas.scratchdj.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatsTest {

    @Test
    fun `recognizes all target formats case insensitively`() {
        assertTrue(AudioFormats.isSupported("Song.MP3"))
        assertTrue(AudioFormats.isSupported("Song.flac"))
        assertTrue(AudioFormats.isSupported("Song.ogg"))
        assertTrue(AudioFormats.isSupported("Song.opus"))
        assertTrue(AudioFormats.isSupported("Song.wav"))
        assertTrue(AudioFormats.isSupported("Song.aiff"))
        assertTrue(AudioFormats.isSupported("Song.AIF"))
    }

    @Test
    fun `rejects non audio extensions`() {
        assertFalse(AudioFormats.isSupported("cover.jpg"))
        assertFalse(AudioFormats.isSupported("notes.txt"))
        assertFalse(AudioFormats.isSupported("noextension"))
    }

    @Test
    fun `extracts lowercase extension`() {
        assertEquals("flac", AudioFormats.extensionOf("Track.FLAC"))
        assertEquals(null, AudioFormats.extensionOf("Track"))
    }

    @Test
    fun `native pcm formats are wav and aiff`() {
        assertTrue(AudioFormats.isNativePlayable("Track.wav"))
        assertTrue(AudioFormats.isNativePlayable("Track.aiff"))
        assertTrue(AudioFormats.isNativePlayable("Track.aif"))
        assertFalse(AudioFormats.isNativePlayable("Track.mp3"))
    }
}
