package com.tkleisas.scratchdj.audio

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class PcmDecoderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        DjEngine.shutdown()
    }

    @Test
    fun decodesMp3ToStereoPcmAtTargetRate() {
        val decoded = decodeAssetTone()

        assertEquals(48_000, decoded.sampleRate)
        assertEquals(48_000.0, decoded.frames.toDouble(), 4_800.0)
        assertTrue(decoded.pcm.any { it != 0.toShort() })
        val peak = decoded.pcm.maxOf { abs(it.toInt()) } / 32768.0
        assertTrue("Expected audible peak, got $peak", peak > 0.05)
    }

    @Test
    fun rejectsTracksLongerThanTheFrameLimit() {
        try {
            decodeAssetTone(maxFrames = 1_000)
            fail("Expected TrackTooLongException")
        } catch (_: TrackTooLongException) {
        }
    }

    @Test
    fun loadsDecodedPcmIntoTheAudioEngine() {
        if (!DjEngine.start()) {
            fail("Audio engine failed to start: ${DjEngine.lastError()}")
        }

        val frames = 48_000 * 2
        val pcm = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            val value = (sin(2.0 * Math.PI * 440.0 * frame / 48_000.0) * 16_000).toInt().toShort()
            pcm[frame * 2] = value
            pcm[frame * 2 + 1] = value
        }

        assertTrue(DjEngine.loadPcm(0, pcm))
        assertEquals(2_000.0, DjEngine.durationMs(0).toDouble(), 50.0)
        val peaks = DjEngine.waveform(0, 128)
        assertTrue(peaks.isNotEmpty())
        assertTrue(peaks.any { it > 0.3f })
    }

    @Test
    fun decodesToDiskCacheAndLoadsMappedPcm() {
        val source = copyAssetTone()
        val cache = File(context.cacheDir, "scratch_test_tone.pcm")
        cache.delete()
        try {
            val sourceInfo = PcmDiskDecoder.probe(context, Uri.fromFile(source))
            val decoded = PcmDiskDecoder.decodeToFile(
                context = context,
                uri = Uri.fromFile(source),
                targetSampleRate = sourceInfo.sampleRate,
                outputFile = cache,
            )

            assertEquals(sourceInfo.sampleRate, decoded.sampleRate)
            assertEquals(
                sourceInfo.sampleRate.toDouble(),
                decoded.frames.toDouble(),
                sourceInfo.sampleRate / 10.0,
            )
            assertEquals(decoded.frames * 4, cache.length())
            assertTrue(decoded.frames > 0)

            if (!DjEngine.start()) {
                fail("Audio engine failed to start: ${DjEngine.lastError()}")
            }
            assertTrue(DjEngine.loadPcmFile(0, cache.absolutePath, decoded.sampleRate))
            assertEquals(1_000.0, DjEngine.durationMs(0).toDouble(), 150.0)
            val peaks = DjEngine.waveform(0, 128)
            assertTrue(peaks.isNotEmpty())
            assertTrue(peaks.any { it > 0.03f })
        } finally {
            source.delete()
            cache.delete()
        }
    }

    @Test
    fun analyzesBpmFromNativePcm() {
        if (!DjEngine.start()) {
            fail("Audio engine failed to start: ${DjEngine.lastError()}")
        }
        val sampleRate = 48_000
        val seconds = 8
        val frames = sampleRate * seconds
        val pcm = ShortArray(frames * 2)
        val beatFrames = sampleRate * 60 / 120
        var start = 0
        while (start < frames) {
            for (i in 0 until 500) {
                if (start + i >= frames) break
                val envelope = kotlin.math.exp(-i / 90.0)
                val value = (0.8 * envelope *
                    sin(2.0 * Math.PI * 160.0 * i / sampleRate) * 16_000).toInt().toShort()
                pcm[(start + i) * 2] = value
                pcm[(start + i) * 2 + 1] = value
            }
            start += beatFrames
        }

        assertTrue(DjEngine.loadPcm(0, pcm))
        val bpm = DjEngine.analyzeBpm(0, seconds * 1000L)
        assertEquals(120.0, bpm.toDouble(), 3.0)
    }

    private fun decodeAssetTone(maxFrames: Int = PcmDecoder.MAX_FRAMES): DecodedAudio {
        val file = copyAssetTone()
        return try {
            PcmDecoder.decode(
                context = context,
                uri = Uri.fromFile(file),
                targetSampleRate = 48_000,
                maxFrames = maxFrames,
            )
        } finally {
            file.delete()
        }
    }

    private fun copyAssetTone(): File {
        val file = File(context.cacheDir, "scratch_test_tone.mp3")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("test_tone.mp3").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        return file
    }
}
