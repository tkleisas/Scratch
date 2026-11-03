package com.tkleisas.scratchdj

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.tkleisas.scratchdj.audio.AudioDecodeException
import com.tkleisas.scratchdj.audio.DjEngine
import com.tkleisas.scratchdj.audio.PcmCache
import com.tkleisas.scratchdj.audio.PcmDiskDecoder
import com.tkleisas.scratchdj.audio.SyncMath
import com.tkleisas.scratchdj.audio.TrackTooLongException
import com.tkleisas.scratchdj.data.AudioFormats
import com.tkleisas.scratchdj.data.DeckQueue
import com.tkleisas.scratchdj.data.LibraryRepository
import com.tkleisas.scratchdj.data.Track
import com.tkleisas.scratchdj.data.TrackDatabase
import com.tkleisas.scratchdj.model.DeckSide
import com.tkleisas.scratchdj.model.DeckUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class DjController(private val context: Context) {

    private val database = TrackDatabase(context)
    private val repository = LibraryRepository(context, database)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val queueA = DeckQueue()
    private val queueB = DeckQueue()
    private val historyA = ArrayDeque<Track>()
    private val historyB = ArrayDeque<Track>()
    private var loadJobA: Job? = null
    private var loadJobB: Job? = null
    private var loadGenerationA = 0
    private var loadGenerationB = 0

    val deckA = mutableStateOf(DeckUiState())
    val deckB = mutableStateOf(DeckUiState())
    val queueAState = mutableStateOf<List<Track>>(emptyList())
    val queueBState = mutableStateOf<List<Track>>(emptyList())
    val crossfader = mutableFloatStateOf(0.5f)
    val libraryCount = mutableIntStateOf(0)
    val isSyncing = mutableStateOf(false)
    val message = mutableStateOf<String?>(null)
    val latencyMs = mutableIntStateOf(0)
    val bufferFrames = mutableIntStateOf(0)
    val sampleRate = mutableIntStateOf(48000)
    val spectrum = mutableStateOf(FloatArray(32))

    init {
        if (!DjEngine.start()) {
            message.value = DjEngine.lastError().ifBlank { "Audio engine failed to start" }
        }
        DjEngine.setCrossfader(crossfader.value)
        scope.launch(Dispatchers.IO) { PcmCache.prune(context) }
        scope.launch { pollEngine() }
    }

    fun syncLibrary() {
        if (isSyncing.value) return
        scope.launch {
            isSyncing.value = true
            try {
                val count = repository.sync()
                libraryCount.intValue = count
                if (count == 0) {
                    message.value = "No supported audio files found in local storage"
                }
            } catch (error: Throwable) {
                message.value = "Library scan failed: ${error.message}"
            } finally {
                isSyncing.value = false
            }
        }
    }

    suspend fun search(term: String): List<Track> = withContext(Dispatchers.IO) {
        repository.search(term)
    }

    fun deck(side: DeckSide) = if (side == DeckSide.A) deckA else deckB

    fun queue(side: DeckSide) = if (side == DeckSide.A) queueAState else queueBState

    private fun rawQueue(side: DeckSide) = if (side == DeckSide.A) queueA else queueB

    private fun history(side: DeckSide) = if (side == DeckSide.A) historyA else historyB

    private fun loadJob(side: DeckSide): Job? = if (side == DeckSide.A) loadJobA else loadJobB

    private fun setLoadJob(side: DeckSide, job: Job?) {
        if (side == DeckSide.A) loadJobA = job else loadJobB = job
    }

    fun enqueue(track: Track, side: DeckSide) {
        val queue = rawQueue(side)
        queue.enqueue(track)
        publishQueue(side)

        val state = deck(side).value
        if (state.track == null && !state.isLoading) {
            val next = queue.takeNext() ?: return
            publishQueue(side)
            loadTrack(next, side)
        }
    }

    fun removeFromQueue(trackId: Long, side: DeckSide) {
        rawQueue(side).remove(trackId)
        publishQueue(side)
    }

    fun playFromQueue(trackId: Long, side: DeckSide) {
        val queue = rawQueue(side)
        val track = queue.snapshot().firstOrNull { it.id == trackId } ?: return
        queue.remove(trackId)
        publishQueue(side)
        loadTrack(track, side, autoPlay = true)
    }

    fun skipToNext(side: DeckSide) {
        val queue = rawQueue(side)
        val next = queue.takeNext()
        if (next == null) {
            message.value = "Deck ${side.letter} queue is empty"
            return
        }
        publishQueue(side)
        loadTrack(next, side, autoPlay = true)
    }

    fun previousTrack(side: DeckSide) {
        val previous = history(side).removeLastOrNull()
        if (previous == null) {
            seekFraction(side, 0f)
            return
        }
        loadTrack(
            previous,
            side,
            autoPlay = deck(side).value.isPlaying,
            recordHistory = false,
        )
    }

    private fun loadTrack(
        track: Track,
        side: DeckSide,
        autoPlay: Boolean = false,
        recordHistory: Boolean = true,
    ) {
        val holder = deck(side)
        val current = holder.value.track
        val pushedHistory = recordHistory && current != null && current.id != track.id
        if (pushedHistory) {
            val history = history(side)
            history.addLast(current)
            while (history.size > 20) {
                history.removeFirst()
            }
        }

        val generation = if (side == DeckSide.A) ++loadGenerationA else ++loadGenerationB
        loadJob(side)?.cancel()
        holder.value = holder.value.copy(
            isLoading = true,
            loadingTitle = track.title,
            loadProgress = 0f,
        )

        val job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (AudioFormats.isNativePlayable(track.displayName)) {
                        val loaded = context.contentResolver
                            .openFileDescriptor(Uri.parse(track.uri), "r")
                            ?.use { descriptor -> DjEngine.loadTrack(side.index, descriptor.fd) }
                            ?: false
                        if (loaded) LoadResult.Success else {
                            LoadResult.Failure(
                                DjEngine.lastError().ifBlank { "Could not load ${track.title}" },
                            )
                        }
                    } else {
                        val source = PcmDiskDecoder.probe(context, Uri.parse(track.uri))
                        val sampleRate = source.sampleRate
                        val cacheFile = PcmCache.fileFor(context, track, sampleRate)
                        val reportProgress: (Float) -> Unit = { progress ->
                            scope.launch(Dispatchers.Main) {
                                if (generation == currentGeneration(side) &&
                                    holder.value.isLoading
                                ) {
                                    holder.value =
                                        holder.value.copy(loadProgress = progress)
                                }
                            }
                        }

                        if (!PcmCache.isValid(cacheFile)) {
                            PcmCache.ensureSpace(
                                context,
                                PcmCache.estimatedBytes(track, sampleRate),
                            )
                            PcmDiskDecoder.decodeToFile(
                                context = context,
                                uri = Uri.parse(track.uri),
                                targetSampleRate = sampleRate,
                                outputFile = cacheFile,
                                isCancelled = { !isActive },
                                onProgress = reportProgress,
                            )
                        }

                        var loaded = DjEngine.loadPcmFile(
                            side.index,
                            cacheFile.absolutePath,
                            sampleRate,
                        )
                        if (!loaded) {
                            cacheFile.delete()
                            PcmDiskDecoder.decodeToFile(
                                context = context,
                                uri = Uri.parse(track.uri),
                                targetSampleRate = sampleRate,
                                outputFile = cacheFile,
                                isCancelled = { !isActive },
                                onProgress = reportProgress,
                            )
                            loaded = DjEngine.loadPcmFile(
                                side.index,
                                cacheFile.absolutePath,
                                sampleRate,
                            )
                        }

                        if (loaded) {
                            LoadResult.Success
                        } else {
                            LoadResult.Failure(
                                DjEngine.lastError().ifBlank { "Could not load ${track.title}" },
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (tooLong: TrackTooLongException) {
                    LoadResult.Failure(tooLong.message ?: "Track is too long")
                } catch (decode: AudioDecodeException) {
                    LoadResult.Failure(decode.message ?: "Could not decode ${track.title}")
                } catch (error: Throwable) {
                    LoadResult.Failure(error.message ?: "Could not load ${track.title}")
                }
            }

            if (generation !=
                if (side == DeckSide.A) loadGenerationA else loadGenerationB
            ) {
                return@launch
            }

            when (result) {
                LoadResult.Success -> {
                    val peaks = DjEngine.waveform(side.index, WAVEFORM_BUCKETS)
                    val duration = DjEngine.durationMs(side.index)
                    holder.value = holder.value.copy(
                        track = track,
                        peaks = peaks,
                        isPlaying = false,
                        positionMs = 0L,
                        durationMs = duration,
                        isLoading = false,
                        loadingTitle = null,
                        loadProgress = 0f,
                        bpm = track.bpm,
                        loopIn = null,
                        loopOut = null,
                        loopEnabled = false,
                    )
                    if (autoPlay) {
                        DjEngine.setPlaying(side.index, true)
                    }
                    if (track.bpm <= 0f) {
                        analyzeBpm(side, track, generation)
                    }
                }

                is LoadResult.Failure -> {
                    if (pushedHistory) {
                        val history = history(side)
                        if (history.lastOrNull()?.id == current.id) {
                            history.removeLastOrNull()
                        }
                    }
                    holder.value = holder.value.copy(
                        isLoading = false,
                        loadingTitle = null,
                        loadProgress = 0f,
                    )
                    message.value = result.message
                }
            }
        }
        setLoadJob(side, job)
    }

    private fun publishQueue(side: DeckSide) {
        val queue = rawQueue(side)
        if (side == DeckSide.A) {
            queueAState.value = queue.snapshot()
        } else {
            queueBState.value = queue.snapshot()
        }
    }

    private fun currentGeneration(side: DeckSide): Int =
        if (side == DeckSide.A) loadGenerationA else loadGenerationB

    private suspend fun pollEngine() {
        while (scope.isActive) {
            updateDeckState(DeckSide.A)
            updateDeckState(DeckSide.B)

            if (DjEngine.consumeEnded(DeckSide.A.index) && !advanceQueue(DeckSide.A)) {
                deckA.value = deckA.value.copy(isPlaying = false)
            }
            if (DjEngine.consumeEnded(DeckSide.B.index) && !advanceQueue(DeckSide.B)) {
                deckB.value = deckB.value.copy(isPlaying = false)
            }

            latencyMs.intValue = DjEngine.latencyMs()
            bufferFrames.intValue = DjEngine.bufferFrames()
            sampleRate.intValue = DjEngine.sampleRate()
            spectrum.value = DjEngine.spectrum()
            delay(33)
        }
    }

    private fun updateDeckState(side: DeckSide) {
        val holder = deck(side)
        val current = holder.value
        val position = DjEngine.positionMs(side.index)
        val duration = DjEngine.durationMs(side.index)
        val playing = DjEngine.isPlaying(side.index)
        holder.value = current.copy(
            positionMs = position,
            durationMs = if (duration > 0) duration else current.durationMs,
            isPlaying = playing,
        )
    }

    private fun advanceQueue(side: DeckSide): Boolean {
        val queue = rawQueue(side)
        val next = queue.takeNext() ?: return false
        publishQueue(side)
        loadTrack(next, side, autoPlay = true)
        return true
    }

    fun togglePlay(side: DeckSide) {
        if (deck(side).value.track == null) return
        DjEngine.togglePlaying(side.index)
        val holder = deck(side)
        holder.value = holder.value.copy(isPlaying = DjEngine.isPlaying(side.index))
    }

    fun seekFraction(side: DeckSide, fraction: Float) {
        if (deck(side).value.track == null) return
        DjEngine.seekFraction(side.index, fraction)
    }

    fun setFilterMode(side: DeckSide, mode: Int) {
        val holder = deck(side)
        holder.value = holder.value.copy(filterMode = mode)
        DjEngine.setFilterMode(side.index, mode)
    }

    fun setFilterCutoff(side: DeckSide, value: Float) {
        val holder = deck(side)
        holder.value = holder.value.copy(filterCutoff = value)
        DjEngine.setFilterCutoff(side.index, value)
    }

    fun setEqNode(side: DeckSide, index: Int, node: com.tkleisas.scratchdj.model.EqNode) {
        val holder = deck(side)
        val nodes = holder.value.eqNodes.toMutableList()
        if (index !in nodes.indices) return
        nodes[index] = node
        holder.value = holder.value.copy(eqNodes = nodes)
        DjEngine.setEqNode(side.index, index, node.frequency, node.gainDb, node.q)
    }

    fun flattenEq(side: DeckSide) {
        val holder = deck(side)
        val nodes = holder.value.eqNodes.map { it.copy(gainDb = 0f) }
        holder.value = holder.value.copy(eqNodes = nodes)
        nodes.forEachIndexed { index, node ->
            DjEngine.setEqNode(side.index, index, node.frequency, 0f, node.q)
        }
    }

    fun setTempo(side: DeckSide, value: Float) {
        val holder = deck(side)
        holder.value = holder.value.copy(tempo = value)
        DjEngine.setTempo(side.index, value)
    }

    fun toggleKeyLock(side: DeckSide) {
        val holder = deck(side)
        val enabled = !holder.value.keyLock
        holder.value = holder.value.copy(keyLock = enabled)
        DjEngine.setKeyLock(side.index, enabled)
    }

    fun toggleLoop(side: DeckSide) {
        val holder = deck(side)
        val state = holder.value
        if (state.track == null) return
        val hasPoints = state.loopIn != null && state.loopOut != null &&
            state.loopOut!! > state.loopIn
        if (!hasPoints) {
            val (start, end) = defaultLoop(state)
            applyLoop(side, start, end, enabled = true)
        } else {
            val enabled = !state.loopEnabled
            DjEngine.setLoopEnabled(side.index, enabled)
            holder.value = state.copy(loopEnabled = enabled)
        }
    }

    fun setLoopIn(side: DeckSide) {
        val state = deck(side).value
        if (state.track == null || state.durationMs <= 0) return
        val start = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 0.999f)
        val existingEnd = state.loopOut
        val end = if (existingEnd != null && existingEnd > start) {
            existingEnd
        } else {
            min(1f, start + LOOP_SECONDS * 1000f / state.durationMs)
        }
        applyLoop(side, start, end, enabled = true)
    }

    fun setLoopOut(side: DeckSide) {
        val state = deck(side).value
        if (state.track == null || state.durationMs <= 0) return
        val end = (state.positionMs.toFloat() / state.durationMs).coerceIn(0.001f, 1f)
        val existingStart = state.loopIn
        val start = if (existingStart != null && existingStart < end) {
            existingStart
        } else {
            max(0f, end - LOOP_SECONDS * 1000f / state.durationMs)
        }
        applyLoop(side, start, end, enabled = true)
    }

    fun clearLoop(side: DeckSide) {
        DjEngine.clearLoop(side.index)
        val holder = deck(side)
        holder.value = holder.value.copy(loopIn = null, loopOut = null, loopEnabled = false)
    }

    private fun defaultLoop(state: DeckUiState): Pair<Float, Float> {
        val duration = state.durationMs.toFloat().coerceAtLeast(1f)
        var start = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
        var end = min(1f, start + LOOP_SECONDS * 1000f / duration)
        if (end - start < 0.01f) {
            start = max(0f, end - LOOP_SECONDS * 1000f / duration)
        }
        return start to end
    }

    private fun applyLoop(side: DeckSide, startFraction: Float, endFraction: Float, enabled: Boolean) {
        var start = startFraction.coerceIn(0f, 1f)
        var end = endFraction.coerceIn(0f, 1f)
        if (end <= start) {
            end = min(1f, start + LOOP_SECONDS * 1000f /
                deck(side).value.durationMs.toFloat().coerceAtLeast(1f))
        }
        if (end <= start) {
            start = max(0f, end - 0.01f)
        }
        DjEngine.setLoopPoints(side.index, start, end)
        DjEngine.setLoopEnabled(side.index, enabled)
        val holder = deck(side)
        holder.value = holder.value.copy(
            loopIn = start,
            loopOut = end,
            loopEnabled = enabled,
        )
    }

    fun syncToOtherDeck(side: DeckSide) {
        val state = deck(side).value
        val other = deck(side.other()).value
        val targetBpm = other.bpm * other.tempo
        val ratio = SyncMath.tempoRatio(state.bpm, targetBpm)
        if (ratio == null) {
            message.value = "BPM is not ready for both decks yet"
            return
        }
        setTempo(side, ratio)
        if (!state.keyLock) {
            toggleKeyLock(side)
        }
    }

    private fun analyzeBpm(side: DeckSide, track: Track, generation: Int) {
        scope.launch(Dispatchers.IO) {
            val bpm = DjEngine.analyzeBpm(side.index, track.durationMs)
            if (bpm <= 0f) {
                return@launch
            }
            try {
                database.updateBpm(track.id, bpm)
            } catch (_: Throwable) {
            }
            withContext(Dispatchers.Main) {
                val holder = deck(side)
                if (generation == currentGeneration(side) &&
                    holder.value.track?.id == track.id
                ) {
                    holder.value = holder.value.copy(bpm = bpm)
                }
            }
        }
    }

    fun setCrossfader(value: Float) {
        crossfader.floatValue = value
        DjEngine.setCrossfader(value)
    }

    fun scratchBegin(side: DeckSide) {
        if (deck(side).value.track != null) {
            DjEngine.scratchBegin(side.index)
        }
    }

    fun scratchRate(side: DeckSide, rate: Float) {
        DjEngine.setScratchRate(side.index, rate)
    }

    fun scratchEnd(side: DeckSide) {
        DjEngine.scratchEnd(side.index)
    }

    fun shutdown() {
        scope.cancel()
        DjEngine.shutdown()
        database.close()
    }

    private sealed interface LoadResult {
        data object Success : LoadResult
        data class Failure(val message: String) : LoadResult
    }

    private companion object {
        const val WAVEFORM_BUCKETS = 1024
        const val LOOP_SECONDS = 4
    }
}
