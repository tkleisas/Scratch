package com.tkleisas.scratchdj.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.CancellationException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.min

class TrackTooLongException(message: String) : Exception(message)

class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DecodedAudio(
    val pcm: ShortArray,
    val frames: Int,
    val sampleRate: Int,
)

data class DecodedPcmFile(
    val file: File,
    val frames: Long,
    val sampleRate: Int,
)

data class AudioSourceInfo(
    val sampleRate: Int,
    val channels: Int,
    val durationUs: Long,
)

private const val ENCODING_PCM_32BIT = 21

internal interface PcmSink {
    fun write(samples: ShortArray, count: Int)

    val supportsRaw: Boolean
        get() = false

    fun writeRaw(buffer: ByteBuffer, offset: Int, size: Int) = Unit
}

internal data class PcmDecodeInfo(
    val frames: Long,
    val sampleRate: Int,
)

object PcmDecoder {
    const val MAX_FRAMES = 48_000 * 60 * 10

    fun decode(
        context: Context,
        uri: Uri,
        targetSampleRate: Int,
        maxFrames: Int = MAX_FRAMES,
        isCancelled: () -> Boolean = { false },
    ): DecodedAudio {
        val sink = MemoryPcmSink(targetSampleRate, maxFrames.toLong())
        val info = decodeToSink(
            context = context,
            uri = uri,
            targetSampleRate = targetSampleRate,
            maxFrames = maxFrames.toLong(),
            isCancelled = isCancelled,
            onProgress = {},
            sink = sink,
            preferRaw = false,
        )
        return DecodedAudio(
            pcm = sink.toShortArray(),
            frames = info.frames.toInt(),
            sampleRate = info.sampleRate,
        )
    }
}

object PcmDiskDecoder {
    const val MAX_FRAMES = 48_000L * 60 * 60 * 4

    fun probe(context: Context, uri: Uri): AudioSourceInfo {
        val extractor = MediaExtractor()
        return try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw AudioDecodeException("Cannot open track")
            descriptor.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: throw AudioDecodeException("No audio stream found")
                val format = extractor.getTrackFormat(trackIndex)
                AudioSourceInfo(
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else {
                        2
                    },
                    durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION)
                    } else {
                        0L
                    },
                )
            }
        } finally {
            extractor.release()
        }
    }

    fun decodeToFile(
        context: Context,
        uri: Uri,
        targetSampleRate: Int,
        outputFile: File,
        maxFrames: Long = MAX_FRAMES,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ): DecodedPcmFile {
        outputFile.parentFile?.mkdirs()
        val temporary = File(outputFile.parentFile, outputFile.name + ".part")
        temporary.delete()

        val sink = FilePcmSink(temporary, outputFile.parentFile, maxFrames)
        try {
            val info = decodeToSink(
                context = context,
                uri = uri,
                targetSampleRate = targetSampleRate,
                maxFrames = maxFrames,
                isCancelled = isCancelled,
                onProgress = onProgress,
                sink = sink,
                preferRaw = true,
            )
            sink.close()
            if (outputFile.exists() && !outputFile.delete()) {
                throw AudioDecodeException("Cannot replace cached PCM file")
            }
            if (!temporary.renameTo(outputFile)) {
                throw AudioDecodeException("Cannot finalize cached PCM file")
            }
            return DecodedPcmFile(outputFile, info.frames, info.sampleRate)
        } catch (error: Throwable) {
            sink.abort()
            temporary.delete()
            throw error
        }
    }
}

private fun decodeToSink(
    context: Context,
    uri: Uri,
    targetSampleRate: Int,
    maxFrames: Long,
    isCancelled: () -> Boolean,
    onProgress: (Float) -> Unit,
    sink: PcmSink,
    preferRaw: Boolean,
): PcmDecodeInfo {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var codecStarted = false

    try {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw AudioDecodeException("Cannot open track")
        descriptor.use { pfd ->
            extractor.setDataSource(pfd.fileDescriptor)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw AudioDecodeException("No audio stream found")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw AudioDecodeException("Unknown audio format")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            if (maxFrames > 0 && durationUs > 0) {
                val maxDurationUs = maxFrames * 1_000_000L / targetSampleRate
                if (durationUs > maxDurationUs) {
                    throw TrackTooLongException(
                        "Track is longer than ${maxFrames / targetSampleRate / 60} minutes",
                    )
                }
            }

            val writer = ResamplingWriter(targetSampleRate, maxFrames, sink)
            writer.configure(format)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            codecStarted = true

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outputFormatConfigured = false
            var lastProgress = -1f
            var lastProgressAt = 0L
            var rawMode: Boolean? = null
            var rawFrames = 0L

            fun configureOutput(format: MediaFormat) {
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else {
                    2
                }
                val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    targetSampleRate
                }
                rawMode = preferRaw && sink.supportsRaw &&
                    encoding == AudioFormat.ENCODING_PCM_16BIT &&
                    channels == 2 && rate == targetSampleRate
                if (rawMode != true) {
                    writer.configure(format)
                }
                outputFormatConfigured = true
            }

            fun reportProgress() {
                val now = System.currentTimeMillis()
                if (durationUs <= 0 || now - lastProgressAt < 100L) return
                val fraction = (extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f)
                if (fraction - lastProgress >= 0.005f) {
                    lastProgress = fraction
                    lastProgressAt = now
                    onProgress(fraction)
                }
            }

            while (!outputDone) {
                if (isCancelled()) {
                    throw CancellationException("Audio decode cancelled")
                }

                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw AudioDecodeException("Decoder input buffer unavailable")
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                extractor.sampleTime,
                                0,
                            )
                            extractor.advance()
                            reportProgress()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                            ?: throw AudioDecodeException("Decoder output buffer unavailable")
                        if (!outputFormatConfigured) {
                            configureOutput(decoder.outputFormat)
                        }
                        if (info.size > 0) {
                            if (rawMode == true) {
                                rawFrames += info.size / 4L
                                if (maxFrames > 0 && rawFrames > maxFrames) {
                                    throw TrackTooLongException(
                                        "Track is longer than " +
                                            "${maxFrames / targetSampleRate / 60} minutes",
                                    )
                                }
                                sink.writeRaw(outputBuffer, info.offset, info.size)
                            } else {
                                writer.feed(outputBuffer, info.offset, info.size, isCancelled)
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        configureOutput(decoder.outputFormat)
                    }

                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            }

            if (rawMode != true) {
                writer.finish()
            }
            if (durationUs > 0) {
                onProgress(1f)
            }
            return PcmDecodeInfo(
                if (rawMode == true) rawFrames else writer.framesWritten,
                targetSampleRate,
            )
        }
    } catch (error: TrackTooLongException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: AudioDecodeException) {
        throw error
    } catch (error: Throwable) {
        throw AudioDecodeException(error.message ?: "Audio decode failed", error)
    } finally {
        if (codec != null) {
            try {
                if (codecStarted) codec.stop()
            } catch (_: Throwable) {
            }
            try {
                codec.release()
            } catch (_: Throwable) {
            }
        }
        try {
            extractor.release()
        } catch (_: Throwable) {
        }
    }
}

private class ResamplingWriter(
    private val targetSampleRate: Int,
    private val maxFrames: Long,
    private val sink: PcmSink,
) {
    private var sourceRate = targetSampleRate
    private var channels = 2
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var chunk = FloatArray(4096)
    private var chunkStart = 0L
    private var nextSourcePosition = 0.0
    private var lastLeft = 0.0f
    private var lastRight = 0.0f

    private val output = ShortArray(8192)
    private var outputSamples = 0
    var framesWritten = 0L
        private set

    fun configure(format: MediaFormat) {
        val newRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            sourceRate
        }
        val newChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            channels
        }
        val newEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }

        if (newRate != sourceRate || newChannels != channels || newEncoding != encoding) {
            sourceRate = newRate
            channels = newChannels
            encoding = newEncoding
            chunkStart = 0L
            nextSourcePosition = 0.0
            lastLeft = 0.0f
            lastRight = 0.0f
        }
    }

    fun feed(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        isCancelled: () -> Boolean,
    ) {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT,
            ENCODING_PCM_32BIT,
            -> 4

            else -> 2
        }
        val frameCount = size / (bytesPerSample * channels)
        if (frameCount <= 0) return

        if (chunk.size < frameCount * 2) {
            chunk = FloatArray(frameCount * 2)
        }

        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        view.position(offset)
        view.limit(offset + size)

        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = view.asFloatBuffer()
                for (frame in 0 until frameCount) {
                    val base = frame * channels
                    val left = floats.get(base)
                    val right = if (channels > 1) floats.get(base + 1) else left
                    chunk[frame * 2] = left
                    chunk[frame * 2 + 1] = right
                }
            }

            ENCODING_PCM_32BIT -> {
                val ints = view.asIntBuffer()
                for (frame in 0 until frameCount) {
                    val base = frame * channels
                    val left = ints.get(base) / 2147483648.0f
                    val right = if (channels > 1) {
                        ints.get(base + 1) / 2147483648.0f
                    } else {
                        left
                    }
                    chunk[frame * 2] = left
                    chunk[frame * 2 + 1] = right
                }
            }

            else -> {
                val shorts = view.asShortBuffer()
                for (frame in 0 until frameCount) {
                    val base = frame * channels
                    val left = shorts.get(base) / 32768.0f
                    val right = if (channels > 1) {
                        shorts.get(base + 1) / 32768.0f
                    } else {
                        left
                    }
                    chunk[frame * 2] = left
                    chunk[frame * 2 + 1] = right
                }
            }
        }

        val ratio = sourceRate.toDouble() / targetSampleRate
        val chunkEnd = chunkStart + frameCount
        var position = nextSourcePosition

        while (position < chunkEnd - 1.0) {
            val index = floor(position).toLong()
            val fraction = (position - index).toFloat()
            val local = (index - chunkStart).toInt()
            val localNext = local + 1

            val left0 = if (local < 0) lastLeft else chunk[local * 2]
            val right0 = if (local < 0) lastRight else chunk[local * 2 + 1]
            val left1 = if (localNext < 0) lastLeft else chunk[localNext * 2]
            val right1 = if (localNext < 0) lastRight else chunk[localNext * 2 + 1]

            add(
                left0 + (left1 - left0) * fraction,
                right0 + (right1 - right0) * fraction,
            )
            position += ratio
            if (isCancelled()) {
                throw CancellationException("Audio decode cancelled")
            }
        }

        nextSourcePosition = position
        if (frameCount > 0) {
            lastLeft = chunk[(frameCount - 1) * 2]
            lastRight = chunk[(frameCount - 1) * 2 + 1]
        }
        chunkStart = chunkEnd
    }

    private fun add(left: Float, right: Float) {
        if (maxFrames > 0 && framesWritten >= maxFrames) {
            throw TrackTooLongException(
                "Track is longer than ${maxFrames / targetSampleRate / 60} minutes",
            )
        }
        if (outputSamples + 2 > output.size) {
            flush()
        }
        output[outputSamples++] = (left.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        output[outputSamples++] = (right.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        ++framesWritten
    }

    private fun flush() {
        if (outputSamples <= 0) return
        sink.write(output, outputSamples)
        outputSamples = 0
    }

    fun finish() {
        flush()
    }
}

private class MemoryPcmSink(targetRate: Int, private val maxFrames: Long) : PcmSink {
    private var data = ShortArray(targetRate * 2)
    private var size = 0

    override fun write(samples: ShortArray, count: Int) {
        if (size + count > maxFrames * 2) {
            throw TrackTooLongException("Decoded track is too long for memory")
        }
        ensureCapacity(size + count)
        samples.copyInto(data, size, 0, count)
        size += count
    }

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var newSize = data.size.coerceAtLeast(2)
        while (newSize < required) {
            newSize = newSize shl 1
            if (newSize <= 0) {
                throw TrackTooLongException("Decoded track is too large")
            }
        }
        data = data.copyOf(newSize)
    }

    fun toShortArray(): ShortArray = data.copyOf(size)
}

private class FilePcmSink(
    private val file: File,
    private val cacheDirectory: File?,
    private val maxFrames: Long,
) : PcmSink {
    private val output = BufferedOutputStream(FileOutputStream(file), 256 * 1024)
    private val byteBuffer = ByteBuffer.allocate(16 * 1024).order(ByteOrder.nativeOrder())
    private val rawScratch = ByteArray(64 * 1024)
    private var bytesSinceSpaceCheck = 0L
    private var frames = 0L

    override val supportsRaw: Boolean
        get() = true

    override fun write(samples: ShortArray, count: Int) {
        frames += count / 2
        if (maxFrames > 0 && frames > maxFrames) {
            throw TrackTooLongException("Decoded track is too long for the cache")
        }

        var offset = 0
        while (offset < count) {
            val chunkSamples = min(count - offset, byteBuffer.capacity() / 2)
            byteBuffer.clear()
            byteBuffer.asShortBuffer().put(samples, offset, chunkSamples)
            output.write(byteBuffer.array(), 0, chunkSamples * 2)
            offset += chunkSamples
        }

        checkSpace(count.toLong() * 2)
    }

    override fun writeRaw(buffer: ByteBuffer, offset: Int, size: Int) {
        frames += size / 4L
        if (maxFrames > 0 && frames > maxFrames) {
            throw TrackTooLongException("Decoded track is too long for the cache")
        }

        val view = buffer.duplicate()
        view.position(offset)
        view.limit(offset + size)
        while (view.hasRemaining()) {
            val chunk = min(view.remaining(), rawScratch.size)
            view.get(rawScratch, 0, chunk)
            output.write(rawScratch, 0, chunk)
        }

        checkSpace(size.toLong())
    }

    private fun checkSpace(bytes: Long) {
        bytesSinceSpaceCheck += bytes
        if (bytesSinceSpaceCheck >= 32L * 1024 * 1024) {
            bytesSinceSpaceCheck = 0
            if (cacheDirectory != null && cacheDirectory.usableSpace < MIN_FREE_SPACE) {
                throw AudioDecodeException("Not enough free space to cache this track")
            }
        }
    }

    fun close() {
        output.flush()
        output.close()
    }

    fun abort() {
        try {
            output.close()
        } catch (_: Throwable) {
        }
        file.delete()
    }

    private companion object {
        const val MIN_FREE_SPACE = 128L * 1024 * 1024
    }
}
