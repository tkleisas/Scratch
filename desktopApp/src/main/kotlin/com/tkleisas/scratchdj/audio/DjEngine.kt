package com.tkleisas.scratchdj.audio

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object DjEngine {
    init {
        val explicit = System.getProperty("scratch.native.lib")
        if (explicit != null && File(explicit).isFile) {
            System.load(explicit)
        } else {
            try {
                System.loadLibrary("scratchdj")
            } catch (missing: UnsatisfiedLinkError) {
                val windows = System.getProperty("os.name").lowercase().contains("win")
                val resourcePath = if (windows) {
                    "/native/windows-x64/scratchdj.dll"
                } else {
                    "/native/linux-x64/libscratchdj.so"
                }
                val suffix = if (windows) ".dll" else ".so"
                val resource = DjEngine::class.java
                    .getResourceAsStream(resourcePath)
                    ?: throw missing
                val extracted = Files.createTempFile("scratchdj", suffix).toFile()
                extracted.deleteOnExit()
                resource.use {
                    Files.copy(it, extracted.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                System.load(extracted.absolutePath)
            }
        }
    }

    fun start(): Boolean = nativeStart()
    fun shutdown() = nativeShutdown()
    fun isRunning(): Boolean = nativeIsRunning()
    fun loadTrack(deck: Int, fd: Int): Boolean = nativeLoadTrack(deck, fd)
    fun loadPcm(deck: Int, pcm: ShortArray): Boolean = nativeLoadPcm(deck, pcm)
    fun loadPcmFile(deck: Int, path: String, sampleRate: Int): Boolean =
        nativeLoadPcmFile(deck, path, sampleRate)
    fun lastError(): String = nativeGetLastError()
    fun positionMs(deck: Int): Long = nativeGetPositionMs(deck)
    fun durationMs(deck: Int): Long = nativeGetDurationMs(deck)
    fun isPlaying(deck: Int): Boolean = nativeIsPlaying(deck)
    fun togglePlaying(deck: Int) = nativeTogglePlaying(deck)
    fun setPlaying(deck: Int, playing: Boolean) = nativeSetPlaying(deck, playing)
    fun consumeEnded(deck: Int): Boolean = nativeConsumeEnded(deck)
    fun scratchBegin(deck: Int) = nativeScratchBegin(deck)
    fun setScratchRate(deck: Int, rate: Float) = nativeSetScratchRate(deck, rate)
    fun scratchEnd(deck: Int) = nativeScratchEnd(deck)
    fun seekFraction(deck: Int, fraction: Float) = nativeSeekFraction(deck, fraction)
    fun setCrossfader(value: Float) = nativeSetCrossfader(value)
    fun setMasterGain(value: Float) = nativeSetMasterGain(value)
    fun setDeckGain(deck: Int, value: Float) = nativeSetDeckGain(deck, value)
    fun setFxEnabled(rack: Int, chain: Int, effect: Int, enabled: Boolean) =
        nativeSetFxEnabled(rack, chain, effect, enabled)

    fun setFxOrder(rack: Int, chain: Int, order: IntArray) =
        nativeSetFxOrder(rack, chain, order)

    fun setFxParam(rack: Int, chain: Int, effect: Int, index: Int, value: Float) =
        nativeSetFxParam(rack, chain, effect, index, value)

    fun getFxParam(rack: Int, chain: Int, effect: Int, index: Int): Float =
        nativeGetFxParam(rack, chain, effect, index)

    fun getFxParamCount(rack: Int, chain: Int, effect: Int): Int =
        nativeGetFxParamCount(rack, chain, effect)

    fun setPunchActive(rack: Int, effect: Int, active: Boolean) =
        nativeSetPunchActive(rack, effect, active)

    fun setPunchSend(rack: Int, effect: Int, send: Float) =
        nativeSetPunchSend(rack, effect, send)

    fun getPunchSend(rack: Int, effect: Int): Float =
        nativeGetPunchSend(rack, effect)

    fun setFilterMode(deck: Int, mode: Int) = nativeSetFilterMode(deck, mode)
    fun setFilterCutoff(deck: Int, value: Float) = nativeSetFilterCutoff(deck, value)
    fun setEqNode(deck: Int, index: Int, frequency: Float, gainDb: Float, q: Float) =
        nativeSetEqNode(deck, index, frequency, gainDb, q)

    fun setEqBypassed(deck: Int, bypassed: Boolean) = nativeSetEqBypassed(deck, bypassed)

    fun eqResponse(deck: Int, count: Int): FloatArray = nativeGetEqResponse(deck, count)
    fun eqSpectra(deck: Int, count: Int): FloatArray = nativeGetEqSpectra(deck, count)
    fun setTempo(deck: Int, value: Float) = nativeSetTempo(deck, value)
    fun setKeyLock(deck: Int, enabled: Boolean) = nativeSetKeyLock(deck, enabled)
    fun setLoopEnabled(deck: Int, enabled: Boolean) = nativeSetLoopEnabled(deck, enabled)
    fun setLoopPoints(deck: Int, inFraction: Float, outFraction: Float) =
        nativeSetLoopPoints(deck, inFraction, outFraction)
    fun clearLoop(deck: Int) = nativeClearLoop(deck)
    fun waveform(deck: Int, buckets: Int): FloatArray = nativeGetWaveform(deck, buckets)
    fun analyzeBpm(deck: Int, expectedDurationMs: Long): Float =
        nativeAnalyzeBpm(deck, expectedDurationMs)
    fun spectrum(): FloatArray = nativeGetSpectrum()
    fun sampleRate(): Int = nativeGetSampleRate()
    fun latencyMs(): Int = nativeGetLatencyMs()
    fun bufferFrames(): Int = nativeGetBufferFrames()

    private external fun nativeStart(): Boolean
    private external fun nativeShutdown()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeLoadTrack(deck: Int, fd: Int): Boolean
    private external fun nativeLoadPcm(deck: Int, pcm: ShortArray): Boolean
    private external fun nativeLoadPcmFile(deck: Int, path: String, sampleRate: Int): Boolean
    private external fun nativeGetLastError(): String
    private external fun nativeGetPositionMs(deck: Int): Long
    private external fun nativeGetDurationMs(deck: Int): Long
    private external fun nativeIsPlaying(deck: Int): Boolean
    private external fun nativeTogglePlaying(deck: Int)
    private external fun nativeSetPlaying(deck: Int, playing: Boolean)
    private external fun nativeConsumeEnded(deck: Int): Boolean
    private external fun nativeScratchBegin(deck: Int)
    private external fun nativeSetScratchRate(deck: Int, rate: Float)
    private external fun nativeScratchEnd(deck: Int)
    private external fun nativeSeekFraction(deck: Int, fraction: Float)
    private external fun nativeSetCrossfader(value: Float)
    private external fun nativeSetMasterGain(value: Float)
    private external fun nativeSetDeckGain(deck: Int, value: Float)
    private external fun nativeSetFxEnabled(
        rack: Int,
        chain: Int,
        effect: Int,
        enabled: Boolean,
    )
    private external fun nativeSetFxOrder(rack: Int, chain: Int, order: IntArray)
    private external fun nativeSetFxParam(
        rack: Int,
        chain: Int,
        effect: Int,
        index: Int,
        value: Float,
    )
    private external fun nativeGetFxParam(
        rack: Int,
        chain: Int,
        effect: Int,
        index: Int,
    ): Float
    private external fun nativeGetFxParamCount(rack: Int, chain: Int, effect: Int): Int
    private external fun nativeSetPunchActive(rack: Int, effect: Int, active: Boolean)
    private external fun nativeSetPunchSend(rack: Int, effect: Int, send: Float)
    private external fun nativeGetPunchSend(rack: Int, effect: Int): Float

    private external fun nativeSetFilterMode(deck: Int, mode: Int)
    private external fun nativeSetFilterCutoff(deck: Int, value: Float)
    private external fun nativeSetEqBypassed(deck: Int, bypassed: Boolean)

    private external fun nativeSetEqNode(
        deck: Int,
        index: Int,
        frequency: Float,
        gainDb: Float,
        q: Float,
    )

    private external fun nativeGetEqResponse(deck: Int, count: Int): FloatArray
    private external fun nativeGetEqSpectra(deck: Int, count: Int): FloatArray
    private external fun nativeSetTempo(deck: Int, value: Float)
    private external fun nativeSetKeyLock(deck: Int, enabled: Boolean)
    private external fun nativeSetLoopEnabled(deck: Int, enabled: Boolean)
    private external fun nativeSetLoopPoints(deck: Int, inFraction: Float, outFraction: Float)
    private external fun nativeClearLoop(deck: Int)
    private external fun nativeGetWaveform(deck: Int, buckets: Int): FloatArray
    private external fun nativeAnalyzeBpm(deck: Int, expectedDurationMs: Long): Float
    private external fun nativeGetSpectrum(): FloatArray
    private external fun nativeGetSampleRate(): Int
    private external fun nativeGetLatencyMs(): Int
    private external fun nativeGetBufferFrames(): Int
}
