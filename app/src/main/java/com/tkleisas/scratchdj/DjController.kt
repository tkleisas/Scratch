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
import com.tkleisas.scratchdj.data.EqPresetStore
import com.tkleisas.scratchdj.data.LibraryRepository
import com.tkleisas.scratchdj.data.QueuePresetData
import com.tkleisas.scratchdj.data.QueuePresetStore
import com.tkleisas.scratchdj.data.SearchPage
import com.tkleisas.scratchdj.data.SettingsStore
import com.tkleisas.scratchdj.data.QueuePresetSummary
import com.tkleisas.scratchdj.data.DeckSession
import com.tkleisas.scratchdj.data.SessionSnapshot
import com.tkleisas.scratchdj.data.SessionStore
import com.tkleisas.scratchdj.data.Track
import com.tkleisas.scratchdj.data.TrackDatabase
import com.tkleisas.scratchdj.model.DeckSide
import com.tkleisas.scratchdj.model.DeckUiState
import com.tkleisas.scratchdj.model.EqPreset
import com.tkleisas.scratchdj.model.EqPresetCodec
import com.tkleisas.scratchdj.model.FxChain
import com.tkleisas.scratchdj.model.FxEffect
import com.tkleisas.scratchdj.model.FxPadMode
import com.tkleisas.scratchdj.model.FxPresetCodec
import com.tkleisas.scratchdj.model.FxRack
import com.tkleisas.scratchdj.model.FxRackState
import com.tkleisas.scratchdj.model.WaveformMode
import com.tkleisas.scratchdj.model.WaveformWindowMath
import com.tkleisas.scratchdj.model.WaveformWindowUnit
import com.tkleisas.scratchdj.ui.theme.ScratchThemeName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class DjController(private val context: Context) {

    private val database = TrackDatabase(context)
    private val repository = LibraryRepository(context, database)
    private val presetStore = EqPresetStore(context)
    private val queuePresetStore = QueuePresetStore(context)
    private val settingsStore = SettingsStore(context)
    private val sessionStore = SessionStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionSaveMutex = Mutex()
    private var sessionSaveJob: Job? = null
    private var lastSessionSaveAt = 0L
    private var restoredSession: SessionSnapshot? = null
    private var lastTracksRestored = false

    private val queueA = DeckQueue()
    private val queueB = DeckQueue()
    private val historyA = ArrayDeque<Track>()
    private val historyB = ArrayDeque<Track>()
    private var loadJobA: Job? = null
    private var loadJobB: Job? = null
    private var loadGenerationA = 0
    private var loadGenerationB = 0
    private var lastPlaybackState = false
    private val decodeMutex = Mutex()
    private var prefetchA: Prefetch? = null
    private var prefetchB: Prefetch? = null
    private var crossfadePrepA: CrossfadePrep? = null
    private var crossfadePrepB: CrossfadePrep? = null
    private var crossfadePreparedA: Long? = null
    private var crossfadePreparedB: Long? = null
    private var crossfadeNextA: Track? = null
    private var crossfadeNextB: Track? = null
    private val crossfadeGhostA = mutableStateOf<CrossfadeGhost?>(null)
    private val crossfadeGhostB = mutableStateOf<CrossfadeGhost?>(null)

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
    val eqPresetNames = mutableStateOf<List<String>>(emptyList())
    val queuePresetSummaries = mutableStateOf<List<QueuePresetSummary>>(emptyList())
    val autoplayA = mutableStateOf(settingsStore.autoplayA)
    val autoplayB = mutableStateOf(settingsStore.autoplayB)
    val crossfadeSeconds = mutableFloatStateOf(settingsStore.crossfadeSeconds)
    val theme = mutableStateOf(settingsStore.theme)
    val waveformMode = mutableStateOf(settingsStore.waveformMode)
    val waveformWindowUnit = mutableStateOf(settingsStore.waveformWindowUnit)
    val waveformWindowSeconds = mutableFloatStateOf(settingsStore.waveformWindowSeconds)
    val waveformWindowBeats = mutableFloatStateOf(settingsStore.waveformWindowBeats)
    val fxState = mutableStateOf(FxPresetCodec.decode(settingsStore.fxPayload))
    val fxPadActive = mutableStateOf<Set<Pair<FxRack, FxEffect>>>(emptySet())

    init {
        restoreSessionQueues()
        if (!DjEngine.start()) {
            message.value = DjEngine.lastError().ifBlank { "Audio engine failed to start" }
        }
        DjEngine.setCrossfader(crossfader.value)
        applyFxState()
        updateFxTempo()
        scope.launch(Dispatchers.IO) { PcmCache.prune(context) }
        scope.launch(Dispatchers.IO) {
            val names = presetStore.names()
            withContext(Dispatchers.Main) { eqPresetNames.value = names }
        }
        scope.launch(Dispatchers.IO) {
            val summaries = queuePresetStore.summaries()
            withContext(Dispatchers.Main) { queuePresetSummaries.value = summaries }
        }
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

    suspend fun searchPage(term: String, offset: Int, limit: Int): SearchPage =
        withContext(Dispatchers.IO) {
            repository.searchPage(term, offset, limit)
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
            queue.select(track.id)
            loadTrack(track, side)
        } else {
            schedulePrefetch(side)
        }
    }

    fun removeFromQueue(trackId: Long, side: DeckSide) {
        cancelCrossfade(side)
        rawQueue(side).remove(trackId)
        publishQueue(side)
        schedulePrefetch(side)
    }

    fun playFromQueue(trackId: Long, side: DeckSide) {
        val queue = rawQueue(side)
        val track = queue.select(trackId) ?: return
        publishQueue(side)
        loadTrack(track, side, autoPlay = true)
    }

    fun canSkipNext(side: DeckSide): Boolean = rawQueue(side).hasNext()

    fun skipToNext(side: DeckSide) {
        val next = rawQueue(side).next()
        if (next == null) {
            message.value = "No next track in Deck ${side.letter} queue"
            return
        }
        publishQueue(side)
        loadTrack(next, side, autoPlay = true)
    }

    fun previousTrack(side: DeckSide) {
        val state = deck(side).value
        val queue = rawQueue(side)
        val previousInQueue = if (queue.current?.id == state.track?.id) {
            queue.previous()
        } else {
            null
        }
        if (previousInQueue != null) {
            publishQueue(side)
            loadTrack(previousInQueue, side, autoPlay = state.isPlaying, recordHistory = false)
            return
        }

        val previous = history(side).removeLastOrNull()
        if (previous == null) {
            seekFraction(side, 0f)
            return
        }
        loadTrack(previous, side, autoPlay = state.isPlaying, recordHistory = false)
    }

    private fun loadTrack(
        track: Track,
        side: DeckSide,
        autoPlay: Boolean = false,
        recordHistory: Boolean = true,
        startPositionMs: Long = 0L,
        selectQueue: Boolean = true,
    ) {
        if (selectQueue) rawQueue(side).select(track.id)
        cancelCrossfade(side)
        cancelPrefetchIfDifferent(side, track.id)
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

                        decodeMutex.withLock {
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
                    val restoredPosition = if (startPositionMs > 0L && duration > 0L) {
                        startPositionMs.coerceIn(0L, duration - 1L)
                    } else {
                        0L
                    }
                    if (restoredPosition > 0L && duration > 0L) {
                        DjEngine.seekFraction(
                            side.index,
                            restoredPosition.toFloat() / duration.toFloat(),
                        )
                    }
                    holder.value = holder.value.copy(
                        track = track,
                        peaks = peaks,
                        isPlaying = false,
                        positionMs = restoredPosition,
                        durationMs = duration,
                        isLoading = false,
                        loadingTitle = null,
                        loadProgress = 0f,
                        bpm = track.bpm,
                        loopIn = null,
                        loopOut = null,
                        loopEnabled = false,
                        cuePoint = null,
                        hotCues = List(4) { null },
                    )
                    if (autoPlay) {
                        DjEngine.setPlaying(side.index, true)
                    }
                    if (track.bpm <= 0f) {
                        analyzeBpm(side, track, generation)
                    }
                    updateFxTempo()
                    schedulePrefetch(side)
                    scheduleSessionSave()
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

    private data class Prefetch(val trackId: Long, val job: Job)
    private data class CrossfadePrep(
        val trackId: Long,
        val job: Job?,
        val failed: Boolean = false,
    )

    data class CrossfadeGhost(
        val track: Track,
        val peaks: FloatArray,
        val durationMs: Long,
        val windowMs: Long,
        val positionMs: Long,
        val progress: Float,
    )

    fun crossfadeGhost(side: DeckSide): CrossfadeGhost? =
        if (side == DeckSide.A) crossfadeGhostA.value else crossfadeGhostB.value

    private fun setCrossfadeGhost(side: DeckSide, value: CrossfadeGhost?) {
        if (side == DeckSide.A) crossfadeGhostA.value = value else crossfadeGhostB.value = value
    }

    private fun prefetch(side: DeckSide): Prefetch? =
        if (side == DeckSide.A) prefetchA else prefetchB

    private fun setPrefetch(side: DeckSide, value: Prefetch?) {
        if (side == DeckSide.A) prefetchA = value else prefetchB = value
    }

    private fun cancelPrefetch(side: DeckSide) {
        prefetch(side)?.job?.cancel()
        setPrefetch(side, null)
    }

    private fun cancelPrefetchIfDifferent(side: DeckSide, trackId: Long) {
        val current = prefetch(side) ?: return
        if (current.trackId != trackId) {
            current.job.cancel()
            setPrefetch(side, null)
        }
    }

    private fun schedulePrefetch(side: DeckSide) {
        val state = deck(side).value
        if (state.isLoading) return
        val next = rawQueue(side).peekNext()
        if (next == null) {
            cancelPrefetch(side)
            return
        }
        val existing = prefetch(side)
        if (existing?.trackId == next.id && existing.job.isActive) return
        cancelPrefetch(side)
        if (AudioFormats.isNativePlayable(next.displayName)) return

        val job = scope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(next.uri)
                val source = PcmDiskDecoder.probe(context, uri)
                val cacheFile = PcmCache.fileFor(context, next, source.sampleRate)
                if (PcmCache.isValid(cacheFile)) return@launch
                decodeMutex.withLock {
                    if (PcmCache.isValid(cacheFile)) return@withLock
                    PcmCache.ensureSpace(
                        context,
                        PcmCache.estimatedBytes(next, source.sampleRate),
                    )
                    PcmDiskDecoder.decodeToFile(
                        context = context,
                        uri = uri,
                        targetSampleRate = source.sampleRate,
                        outputFile = cacheFile,
                        isCancelled = { !isActive },
                        onProgress = {},
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Prefetch is opportunistic; the real load path will report errors.
            }
        }
        setPrefetch(side, Prefetch(next.id, job))
    }

    private fun currentGeneration(side: DeckSide): Int =
        if (side == DeckSide.A) loadGenerationA else loadGenerationB

    private fun autoplayEnabled(side: DeckSide): Boolean =
        if (side == DeckSide.A) autoplayA.value else autoplayB.value

    private fun crossfadePrep(side: DeckSide): CrossfadePrep? =
        if (side == DeckSide.A) crossfadePrepA else crossfadePrepB

    private fun setCrossfadePrep(side: DeckSide, value: CrossfadePrep?) {
        if (side == DeckSide.A) crossfadePrepA = value else crossfadePrepB = value
    }

    private fun crossfadePrepared(side: DeckSide): Long? =
        if (side == DeckSide.A) crossfadePreparedA else crossfadePreparedB

    private fun setCrossfadePrepared(side: DeckSide, trackId: Long?) {
        if (side == DeckSide.A) crossfadePreparedA = trackId else crossfadePreparedB = trackId
    }

    private fun crossfadeNext(side: DeckSide): Track? =
        if (side == DeckSide.A) crossfadeNextA else crossfadeNextB

    private fun setCrossfadeNext(side: DeckSide, track: Track?) {
        if (side == DeckSide.A) crossfadeNextA = track else crossfadeNextB = track
    }

    private fun clearCrossfadePreparation(side: DeckSide) {
        crossfadePrep(side)?.job?.cancel()
        setCrossfadePrep(side, null)
        setCrossfadePrepared(side, null)
    }

    private fun cancelCrossfade(side: DeckSide) {
        clearCrossfadePreparation(side)
        setCrossfadeNext(side, null)
        setCrossfadeGhost(side, null)
        DjEngine.cancelCrossfade(side.index)
    }

    private fun manageCrossfade(side: DeckSide) {
        if (DjEngine.isCrossfading(side.index)) return
        val state = deck(side).value
        val seconds = crossfadeSeconds.floatValue
        val eligible = seconds > 0f &&
            autoplayEnabled(side) &&
            state.track != null &&
            state.isPlaying &&
            state.durationMs > 0L &&
            !state.isLoading &&
            !state.loopEnabled
        if (!eligible) {
            clearCrossfadePreparation(side)
            return
        }

        val next = rawQueue(side).peekNext()
        if (next == null) {
            clearCrossfadePreparation(side)
            return
        }

        val existingPrep = crossfadePrep(side)
        if (existingPrep != null && existingPrep.trackId != next.id) {
            clearCrossfadePreparation(side)
        }

        val remaining = state.durationMs - state.positionMs
        if (remaining <= 0L) return
        val crossfadeMs = (seconds * 1000f).toLong()
        val prepared = crossfadePrepared(side)
        if (prepared == next.id) {
            if (remaining <= crossfadeMs + CROSSFADE_START_SLOP_MS) {
                if (DjEngine.startCrossfade(side.index, seconds)) {
                    setCrossfadeNext(side, next)
                    val windowMs = DjEngine.crossfadeDurationMs(side.index)
                    setCrossfadeGhost(
                        side,
                        CrossfadeGhost(
                            track = next,
                            peaks = DjEngine.crossfadeWaveform(side.index, WAVEFORM_BUCKETS),
                            durationMs = next.durationMs,
                            windowMs = windowMs,
                            positionMs = 0L,
                            progress = 0f,
                        ),
                    )
                }
            }
            return
        }

        if (remaining > crossfadeMs + CROSSFADE_PREP_LEAD_MS) return
        val currentPrep = crossfadePrep(side)
        if (currentPrep?.trackId == next.id &&
            (currentPrep.job?.isActive == true || currentPrep.failed)
        ) {
            return
        }
        if (currentPrep != null) {
            currentPrep.job?.cancel()
            setCrossfadePrep(side, null)
        }

        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val loaded = try {
                if (AudioFormats.isNativePlayable(next.displayName)) {
                    context.contentResolver
                        .openFileDescriptor(Uri.parse(next.uri), "r")
                        ?.use { descriptor ->
                            DjEngine.loadCrossfadeTrack(side.index, descriptor.fd)
                        }
                        ?: false
                } else {
                    val uri = Uri.parse(next.uri)
                    val source = PcmDiskDecoder.probe(context, uri)
                    val cacheFile = PcmCache.fileFor(context, next, source.sampleRate)
                    if (!PcmCache.isValid(cacheFile)) {
                        decodeMutex.withLock {
                            if (!PcmCache.isValid(cacheFile)) {
                                PcmCache.ensureSpace(
                                    context,
                                    PcmCache.estimatedBytes(next, source.sampleRate),
                                )
                                PcmDiskDecoder.decodeToFile(
                                    context = context,
                                    uri = uri,
                                    targetSampleRate = source.sampleRate,
                                    outputFile = cacheFile,
                                    isCancelled = { !isActive },
                                    onProgress = {},
                                )
                            }
                        }
                    }
                    DjEngine.loadCrossfadePcmFile(
                        side.index,
                        cacheFile.absolutePath,
                        source.sampleRate,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            withContext(Dispatchers.Main) {
                val activePrep = crossfadePrep(side)
                if (activePrep?.trackId != next.id) return@withContext
                if (loaded) {
                    if (rawQueue(side).peekNext()?.id == next.id &&
                        crossfadeSeconds.floatValue > 0f &&
                        autoplayEnabled(side) &&
                        !DjEngine.isCrossfading(side.index)
                    ) {
                        setCrossfadePrepared(side, next.id)
                    }
                } else {
                    setCrossfadePrep(side, CrossfadePrep(next.id, null, failed = true))
                }
            }
        }
        setCrossfadePrep(side, CrossfadePrep(next.id, job))
        job.start()
    }

    private fun updateCrossfadeGhost(side: DeckSide) {
        val ghost = crossfadeGhost(side) ?: return
        if (!DjEngine.isCrossfading(side.index)) return
        val progress = DjEngine.crossfadeProgress(side.index)
        val position = DjEngine.crossfadePositionMs(side.index)
        setCrossfadeGhost(
            side,
            ghost.copy(progress = progress, positionMs = position),
        )
    }

    private fun finishCrossfade(side: DeckSide) {
        val next = crossfadeNext(side) ?: return
        setCrossfadeNext(side, null)
        setCrossfadeGhost(side, null)
        clearCrossfadePreparation(side)

        val queue = rawQueue(side)
        if (queue.peekNext()?.id == next.id) queue.next() else queue.select(next.id)
        publishQueue(side)

        val holder = deck(side)
        val previous = holder.value.track
        if (previous != null && previous.id != next.id) {
            val history = history(side)
            history.addLast(previous)
            while (history.size > 20) history.removeFirst()
        }

        val peaks = DjEngine.waveform(side.index, WAVEFORM_BUCKETS)
        val duration = DjEngine.durationMs(side.index)
        val position = DjEngine.positionMs(side.index)
        val playing = DjEngine.isPlaying(side.index)
        holder.value = holder.value.copy(
            track = next,
            peaks = peaks,
            isPlaying = playing,
            positionMs = position,
            durationMs = duration,
            isLoading = false,
            loadingTitle = null,
            loadProgress = 0f,
            bpm = next.bpm,
            loopIn = null,
            loopOut = null,
            loopEnabled = false,
            cuePoint = null,
            hotCues = List(4) { null },
        )
        reapplyDeckSettings(side, holder.value)
        if (next.bpm <= 0f) {
            analyzeBpm(side, next, currentGeneration(side))
        }
        updateFxTempo()
        schedulePrefetch(side)
    }

    private fun reapplyDeckSettings(side: DeckSide, state: DeckUiState) {
        DjEngine.setDeckGain(side.index, state.volume)
        DjEngine.setFilterMode(side.index, state.filterMode)
        DjEngine.setFilterCutoff(side.index, state.filterCutoff)
        DjEngine.setTempo(side.index, state.tempo)
        DjEngine.setKeyLock(side.index, state.keyLock)
        DjEngine.setEqBypassed(side.index, state.eqBypassed)
        state.eqNodes.forEachIndexed { index, node ->
            DjEngine.setEqNode(side.index, index, node.frequency, node.gainDb, node.q)
        }
        DjEngine.setSlipMode(side.index, state.slipMode)
    }

    private fun restoreSessionQueues() {
        val snapshot = sessionStore.load() ?: return
        restoredSession = snapshot
        queueA.restore(snapshot.deckA.tracks, snapshot.deckA.currentIndex)
        queueB.restore(snapshot.deckB.tracks, snapshot.deckB.currentIndex)
        publishQueue(DeckSide.A)
        publishQueue(DeckSide.B)
    }

    fun restoreLastTracks() {
        if (lastTracksRestored) return
        lastTracksRestored = true
        val snapshot = restoredSession ?: return
        restoreLastTrack(DeckSide.A, snapshot.deckA)
        restoreLastTrack(DeckSide.B, snapshot.deckB)
    }

    private fun restoreLastTrack(side: DeckSide, session: DeckSession) {
        val track = session.lastTrack ?: return
        val state = deck(side).value
        if (state.track != null || state.isLoading) return
        loadTrack(
            track = track,
            side = side,
            autoPlay = false,
            recordHistory = false,
            startPositionMs = session.positionMs,
            selectQueue = false,
        )
    }

    private fun sessionSnapshot(): SessionSnapshot {
        val stateA = deckA.value
        val stateB = deckB.value
        val restoredA = restoredSession?.deckA
        val restoredB = restoredSession?.deckB
        return SessionSnapshot(
            deckA = DeckSession(
                tracks = queueA.snapshot(),
                currentIndex = queueA.currentIndex,
                lastTrack = stateA.track ?: restoredA?.lastTrack,
                positionMs = if (stateA.track == null) {
                    restoredA?.positionMs ?: 0L
                } else {
                    stateA.positionMs
                },
            ),
            deckB = DeckSession(
                tracks = queueB.snapshot(),
                currentIndex = queueB.currentIndex,
                lastTrack = stateB.track ?: restoredB?.lastTrack,
                positionMs = if (stateB.track == null) {
                    restoredB?.positionMs ?: 0L
                } else {
                    stateB.positionMs
                },
            ),
        )
    }

    fun persistSession() {
        val snapshot = sessionSnapshot()
        scope.launch(Dispatchers.IO) {
            try {
                sessionSaveMutex.withLock { sessionStore.save(snapshot) }
            } catch (_: Throwable) {
            }
        }
    }

    private fun scheduleSessionSave() {
        sessionSaveJob?.cancel()
        sessionSaveJob = scope.launch {
            delay(350)
            persistSession()
        }
    }

    fun nextTrack(side: DeckSide): Track? = rawQueue(side).peekNext()

    fun moveQueueItem(side: DeckSide, trackId: Long, delta: Int): Boolean {
        val queue = rawQueue(side)
        val from = queue.snapshot().indexOfFirst { it.id == trackId }
        if (from < 0) return false
        return moveQueueItemTo(side, trackId, from + delta)
    }

    fun moveQueueItemTo(side: DeckSide, trackId: Long, targetIndex: Int): Boolean {
        val queue = rawQueue(side)
        val from = queue.snapshot().indexOfFirst { it.id == trackId }
        if (from < 0 || queue.size == 0) return false
        val to = targetIndex.coerceIn(0, queue.size - 1)
        if (from == to) return false
        cancelCrossfade(side)
        if (!queue.move(from, to)) return false
        publishQueue(side)
        return true
    }

    private suspend fun pollEngine() {
        while (scope.isActive) {
            if (DjEngine.consumeCrossfadeComplete(DeckSide.A.index)) {
                finishCrossfade(DeckSide.A)
            }
            if (DjEngine.consumeCrossfadeComplete(DeckSide.B.index)) {
                finishCrossfade(DeckSide.B)
            }
            updateDeckState(DeckSide.A)
            updateDeckState(DeckSide.B)
            manageCrossfade(DeckSide.A)
            manageCrossfade(DeckSide.B)
            updateCrossfadeGhost(DeckSide.A)
            updateCrossfadeGhost(DeckSide.B)
            val anyPlaying = anyDeckPlaying()
            if (anyPlaying != lastPlaybackState) {
                lastPlaybackState = anyPlaying
                if (anyPlaying) {
                    try {
                        PlaybackService.start(context)
                    } catch (_: Throwable) {
                        // Background-start restrictions can reject this; the
                        // in-app playback path still works.
                    }
                } else {
                    PlaybackService.stop(context)
                }
            }

            if (DjEngine.consumeEnded(DeckSide.A.index)) {
                if (!autoplayA.value || !advanceQueue(DeckSide.A)) {
                    deckA.value = deckA.value.copy(isPlaying = false)
                }
            }
            if (DjEngine.consumeEnded(DeckSide.B.index)) {
                if (!autoplayB.value || !advanceQueue(DeckSide.B)) {
                    deckB.value = deckB.value.copy(isPlaying = false)
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastSessionSaveAt >= 2_000L) {
                lastSessionSaveAt = now
                persistSession()
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
        val next = rawQueue(side).next() ?: return false
        publishQueue(side)
        loadTrack(next, side, autoPlay = true)
        return true
    }

    fun togglePlay(side: DeckSide) {
        if (deck(side).value.track == null) return
        cancelCrossfade(side)
        DjEngine.togglePlaying(side.index)
        val holder = deck(side)
        holder.value = holder.value.copy(isPlaying = DjEngine.isPlaying(side.index))
        scheduleSessionSave()
    }

    fun seekFraction(side: DeckSide, fraction: Float) {
        if (deck(side).value.track == null) return
        cancelCrossfade(side)
        DjEngine.seekFraction(side.index, fraction)
        scheduleSessionSave()
    }

    fun setVolume(side: DeckSide, value: Float) {
        val holder = deck(side)
        val clamped = value.coerceIn(0f, 1.5f)
        holder.value = holder.value.copy(volume = clamped)
        DjEngine.setDeckGain(side.index, clamped)
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

    fun saveEqPreset(side: DeckSide, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            message.value = "Preset name is required"
            return
        }
        val state = deck(side).value
        val payload = EqPresetCodec.encode(state.eqNodes, state.eqBypassed)
        scope.launch {
            val names = withContext(Dispatchers.IO) {
                presetStore.save(trimmed, payload)
                presetStore.names()
            }
            eqPresetNames.value = names
            message.value = "Saved preset “$trimmed”"
        }
    }

    fun loadEqPreset(side: DeckSide, name: String) {
        scope.launch {
            val preset = withContext(Dispatchers.IO) {
                presetStore.payload(name)?.let(EqPresetCodec::decode)
            }
            if (preset == null) {
                message.value = "Could not load preset “$name”"
                return@launch
            }
            applyEqPreset(side, preset)
            message.value = "Loaded preset “$name”"
        }
    }

    fun deleteEqPreset(name: String) {
        scope.launch {
            val names = withContext(Dispatchers.IO) {
                presetStore.delete(name)
                presetStore.names()
            }
            eqPresetNames.value = names
        }
    }

    fun saveQueuePreset(name: String, presetScope: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            message.value = "Preset name is required"
            return
        }
        val normalizedScope = presetScope.uppercase()
        val deckA = if (normalizedScope == "A" || normalizedScope == "ALL") {
            queueA.snapshot()
        } else {
            emptyList()
        }
        val deckB = if (normalizedScope == "B" || normalizedScope == "ALL") {
            queueB.snapshot()
        } else {
            emptyList()
        }
        if (deckA.isEmpty() && deckB.isEmpty()) {
            message.value = "Queue is empty"
            return
        }
        scope.launch {
            val summaries = withContext(Dispatchers.IO) {
                queuePresetStore.save(trimmed, normalizedScope, deckA, deckB)
                queuePresetStore.summaries()
            }
            queuePresetSummaries.value = summaries
            message.value = "Saved queue preset “$trimmed”"
        }
    }

    fun loadQueuePreset(name: String) {
        scope.launch {
            val data = withContext(Dispatchers.IO) { queuePresetStore.load(name) }
            if (data == null) {
                message.value = "Could not load queue preset “$name”"
                return@launch
            }
            applyQueuePreset(data)
            message.value = "Loaded queue preset “$name”"
        }
    }

    fun deleteQueuePreset(name: String) {
        scope.launch {
            val summaries = withContext(Dispatchers.IO) {
                queuePresetStore.delete(name)
                queuePresetStore.summaries()
            }
            queuePresetSummaries.value = summaries
        }
    }

    private fun applyQueuePreset(data: QueuePresetData) {
        val sides = when (data.summary.scope) {
            "A" -> listOf(DeckSide.A)
            "B" -> listOf(DeckSide.B)
            else -> listOf(DeckSide.A, DeckSide.B)
        }
        for (side in sides) {
            val tracks = if (side == DeckSide.A) data.deckA else data.deckB
            val queue = rawQueue(side)
            cancelCrossfade(side)
            queue.replaceAll(tracks)
            val current = deck(side).value.track
            if (current != null && tracks.any { it.id == current.id }) {
                queue.select(current.id)
            }
            publishQueue(side)
            if (deck(side).value.track == null && tracks.isNotEmpty()) {
                val first = queue.next() ?: tracks.first()
                loadTrack(first, side, autoPlay = false, recordHistory = false)
            } else {
                schedulePrefetch(side)
            }
        }
    }

    private fun applyEqPreset(side: DeckSide, preset: EqPreset) {
        val holder = deck(side)
        holder.value = holder.value.copy(
            eqNodes = preset.nodes,
            eqBypassed = preset.bypassed,
        )
        preset.nodes.forEachIndexed { index, node ->
            DjEngine.setEqNode(side.index, index, node.frequency, node.gainDb, node.q)
        }
        DjEngine.setEqBypassed(side.index, preset.bypassed)
    }

    fun setEqBypassed(side: DeckSide, bypassed: Boolean) {
        val holder = deck(side)
        holder.value = holder.value.copy(eqBypassed = bypassed)
        DjEngine.setEqBypassed(side.index, bypassed)
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
        updateFxTempo()
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
        cancelCrossfade(side)
        val loopIn = state.loopIn
        val loopOut = state.loopOut
        val hasPoints = loopIn != null && loopOut != null && loopOut > loopIn
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
        val holder = deck(side)
        val state = holder.value
        if (state.track == null || state.durationMs <= 0) return
        cancelCrossfade(side)
        val start = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 0.999f)
        val end = state.loopOut?.takeIf { it > start }
        DjEngine.setLoopEnabled(side.index, false)
        if (end != null) {
            DjEngine.setLoopPoints(side.index, start, end)
        } else {
            DjEngine.clearLoop(side.index)
        }
        holder.value = state.copy(loopIn = start, loopOut = end, loopEnabled = false)
    }

    fun setLoopOut(side: DeckSide) {
        val state = deck(side).value
        if (state.track == null || state.durationMs <= 0) return
        cancelCrossfade(side)
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
        cancelCrossfade(side)
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
        if (state.track == null || other.track == null) {
            message.value = "Load a track on both decks to sync"
            return
        }
        if (state.bpm > 0f && other.bpm > 0f) {
            applySync(side)
            return
        }

        message.value = "Analyzing BPM for sync…"
        scope.launch {
            var thisBpm = state.bpm
            var otherBpm = other.bpm
            if (thisBpm <= 0f) {
                thisBpm = withContext(Dispatchers.IO) {
                    DjEngine.analyzeBpm(side.index, state.durationMs)
                }
            }
            if (otherBpm <= 0f) {
                otherBpm = withContext(Dispatchers.IO) {
                    DjEngine.analyzeBpm(side.other().index, other.durationMs)
                }
            }

            val holder = deck(side)
            if (thisBpm > 0f && holder.value.track?.id == state.track?.id) {
                holder.value = holder.value.copy(bpm = thisBpm)
            }
            val otherHolder = deck(side.other())
            if (otherBpm > 0f && otherHolder.value.track?.id == other.track?.id) {
                otherHolder.value = otherHolder.value.copy(bpm = otherBpm)
            }
            updateFxTempo()
            if (thisBpm > 0f && otherBpm > 0f) {
                applySync(side)
            } else {
                message.value = "Could not detect BPM for both decks"
            }
        }
    }

    private fun applySync(side: DeckSide) {
        val state = deck(side).value
        val other = deck(side.other()).value
        val targetBpm = other.bpm * other.tempo
        val ratio = SyncMath.tempoRatio(state.bpm, targetBpm)
        if (ratio == null) {
            message.value = "BPM is not ready for both decks yet"
            return
        }
        setTempo(side, ratio)
        if (!state.keyLock) toggleKeyLock(side)
        message.value = "Deck ${side.letter} synced to " +
            String.format(java.util.Locale.US, "%.1f", targetBpm) + " BPM"
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
                updateFxTempo()
            }
        }
    }

    fun setAutoplay(side: DeckSide, enabled: Boolean) {
        if (side == DeckSide.A) {
            autoplayA.value = enabled
            settingsStore.autoplayA = enabled
        } else {
            autoplayB.value = enabled
            settingsStore.autoplayB = enabled
        }
        if (!enabled) cancelCrossfade(side)
    }

    fun setCrossfadeSeconds(value: Float) {
        val clamped = value.coerceIn(0f, 12f)
        crossfadeSeconds.floatValue = clamped
        settingsStore.crossfadeSeconds = clamped
        if (clamped <= 0f) {
            cancelCrossfade(DeckSide.A)
            cancelCrossfade(DeckSide.B)
        }
    }

    fun setCrossfader(value: Float) {
        crossfader.floatValue = value
        DjEngine.setCrossfader(value)
        updateFxTempo()
    }

    fun setTheme(value: ScratchThemeName) {
        theme.value = value
        settingsStore.theme = value
    }

    fun toggleWaveformMode() {
        setWaveformMode(
            if (waveformMode.value == WaveformMode.Full) {
                WaveformMode.Scroll
            } else {
                WaveformMode.Full
            },
        )
    }

    fun setWaveformMode(value: WaveformMode) {
        waveformMode.value = value
        settingsStore.waveformMode = value
    }

    fun setWaveformWindowUnit(value: WaveformWindowUnit) {
        waveformWindowUnit.value = value
        settingsStore.waveformWindowUnit = value
    }

    fun setWaveformWindowSeconds(value: Float) {
        val clamped = value.coerceIn(
            WaveformWindowMath.MIN_SECONDS,
            WaveformWindowMath.MAX_SECONDS,
        )
        waveformWindowSeconds.floatValue = clamped
        settingsStore.waveformWindowSeconds = clamped
    }

    fun setWaveformWindowBeats(value: Float) {
        val clamped = value.coerceIn(
            WaveformWindowMath.MIN_BEATS,
            WaveformWindowMath.MAX_BEATS,
        )
        waveformWindowBeats.floatValue = clamped
        settingsStore.waveformWindowBeats = clamped
    }

    fun waveformWindowMs(side: DeckSide): Long {
        val state = deck(side).value
        val effectiveBpm = if (state.bpm > 0f) state.bpm * state.tempo else 0f
        return WaveformWindowMath.windowMs(
            unit = waveformWindowUnit.value,
            seconds = waveformWindowSeconds.floatValue,
            beats = waveformWindowBeats.floatValue,
            bpm = effectiveBpm,
        )
    }

    fun scratchBegin(side: DeckSide) {
        if (deck(side).value.track != null) {
            cancelCrossfade(side)
            DjEngine.scratchBegin(side.index)
        }
    }

    fun scratchRate(side: DeckSide, rate: Float) {
        DjEngine.setScratchRate(side.index, rate)
    }

    fun scratchEnd(side: DeckSide) {
        DjEngine.scratchEnd(side.index)
    }

    fun anyDeckPlaying(): Boolean =
        DjEngine.isPlaying(DeckSide.A.index) || DjEngine.isPlaying(DeckSide.B.index)

    fun pauseAllForAudioFocus() {
        DjEngine.setPlaying(DeckSide.A.index, false)
        DjEngine.setPlaying(DeckSide.B.index, false)
        deckA.value = deckA.value.copy(isPlaying = false)
        deckB.value = deckB.value.copy(isPlaying = false)
    }

    private fun playheadFraction(side: DeckSide): Float {
        val state = deck(side).value
        if (state.durationMs <= 0L) return 0f
        return (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    }

    fun setPlaying(side: DeckSide, playing: Boolean) {
        if (deck(side).value.track == null) return
        cancelCrossfade(side)
        DjEngine.setPlaying(side.index, playing)
        val holder = deck(side)
        holder.value = holder.value.copy(isPlaying = DjEngine.isPlaying(side.index))
        scheduleSessionSave()
    }

    fun setCuePoint(side: DeckSide) {
        val holder = deck(side)
        if (holder.value.track == null || holder.value.durationMs <= 0) return
        holder.value = holder.value.copy(cuePoint = playheadFraction(side))
        message.value = "Cue set on Deck ${side.letter}"
    }

    fun jumpToCuePoint(side: DeckSide) {
        val holder = deck(side)
        val state = holder.value
        val cue = state.cuePoint ?: return
        cancelCrossfade(side)
        DjEngine.seekFraction(side.index, cue)
        DjEngine.setPlaying(side.index, false)
        holder.value = state.copy(
            positionMs = (cue * state.durationMs).toLong(),
            isPlaying = false,
        )
    }

    fun clearCuePoint(side: DeckSide) {
        val holder = deck(side)
        holder.value = holder.value.copy(cuePoint = null)
    }

    fun hitHotCue(side: DeckSide, index: Int) {
        val holder = deck(side)
        val state = holder.value
        if (state.track == null || state.durationMs <= 0) return
        if (index !in state.hotCues.indices) return
        val stored = state.hotCues[index]
        if (stored == null) {
            val cues = state.hotCues.toMutableList()
            cues[index] = playheadFraction(side)
            holder.value = state.copy(hotCues = cues)
            message.value = "Hot cue ${index + 1} set on Deck ${side.letter}"
            return
        }
        cancelCrossfade(side)
        DjEngine.seekFraction(side.index, stored)
        DjEngine.setPlaying(side.index, true)
        holder.value = state.copy(
            positionMs = (stored * state.durationMs).toLong(),
            isPlaying = true,
        )
    }

    fun toggleSlipMode(side: DeckSide) {
        val holder = deck(side)
        val enabled = !holder.value.slipMode
        holder.value = holder.value.copy(slipMode = enabled)
        DjEngine.setSlipMode(side.index, enabled)
    }

    fun clearHotCue(side: DeckSide, index: Int) {
        val holder = deck(side)
        val state = holder.value
        if (index !in state.hotCues.indices) return
        val cues = state.hotCues.toMutableList()
        cues[index] = null
        holder.value = state.copy(hotCues = cues)
    }

    fun shutdown() {
        try {
            sessionStore.save(sessionSnapshot())
        } catch (_: Throwable) {
        }
        scope.cancel()
        DjEngine.shutdown()
        database.close()
        presetStore.close()
        queuePresetStore.close()
    }

    private sealed interface LoadResult {
        data object Success : LoadResult
        data class Failure(val message: String) : LoadResult
    }

    private companion object {
        const val WAVEFORM_BUCKETS = 8192
        const val LOOP_SECONDS = 4
        const val CROSSFADE_PREP_LEAD_MS = 5_000L
        const val CROSSFADE_START_SLOP_MS = 0L
    }

    fun fxRack(rack: FxRack): FxRackState =
        fxState.value.racks[rack] ?: FxRackState()

    fun setFxEnabled(rack: FxRack, chain: FxChain, effect: FxEffect, enabled: Boolean) {
        val current = fxRack(rack)
        val updated = current.withChain(chain, current.chain(chain).withEnabled(effect, enabled))
        fxState.value = fxState.value.copy(racks = fxState.value.racks + (rack to updated))
        if (!enabled && chain == FxChain.Punch && isFxPunchActive(rack, effect)) {
            setFxPunch(rack, effect, false)
        }
        DjEngine.setFxEnabled(rack.ordinal, chain.ordinal, effect.ordinal, enabled)
        persistFx()
    }

    fun setFxOrder(rack: FxRack, chain: FxChain, order: List<FxEffect>) {
        val current = fxRack(rack)
        val updated = current.withChain(chain, current.chain(chain).withOrder(order))
        fxState.value = fxState.value.copy(racks = fxState.value.racks + (rack to updated))
        DjEngine.setFxOrder(
            rack.ordinal,
            chain.ordinal,
            updated.chain(chain).order.map { it.ordinal }.toIntArray(),
        )
        persistFx()
    }

    fun setFxParam(
        rack: FxRack,
        chain: FxChain,
        effect: FxEffect,
        index: Int,
        value: Float,
    ) {
        val spec = effect.params.getOrNull(index) ?: return
        val clamped = value.coerceIn(spec.min, spec.max)
        val current = fxRack(rack)
        val updated = current.withChain(chain, current.chain(chain).withParam(effect, index, clamped))
        fxState.value = fxState.value.copy(racks = fxState.value.racks + (rack to updated))
        DjEngine.setFxParam(rack.ordinal, chain.ordinal, effect.ordinal, index, clamped)
        persistFx()
    }

    fun setFxSend(rack: FxRack, effect: FxEffect, value: Float) {
        val current = fxRack(rack)
        val updated = current.copy(
            punch = current.punch.withSend(effect, value),
        )
        fxState.value = fxState.value.copy(racks = fxState.value.racks + (rack to updated))
        DjEngine.setPunchSend(rack.ordinal, effect.ordinal, value.coerceIn(0f, 1f))
        persistFx()
    }

    fun setFxPadMode(mode: FxPadMode) {
        if (fxState.value.padMode == mode) return
        releaseAllFxPads()
        fxState.value = fxState.value.copy(padMode = mode)
        persistFx()
    }

    fun setFxPunch(rack: FxRack, effect: FxEffect, active: Boolean) {
        val node = fxRack(rack).punch.effect(effect)
        if (active && !node.enabled) return
        val key = rack to effect
        val current = fxPadActive.value
        if (active) {
            if (key !in current) fxPadActive.value = current + key
        } else {
            if (key in current) fxPadActive.value = current - key
        }
        DjEngine.setPunchActive(rack.ordinal, effect.ordinal, active)
    }

    fun isFxPunchActive(rack: FxRack, effect: FxEffect): Boolean =
        (rack to effect) in fxPadActive.value

    private fun releaseAllFxPads() {
        if (fxPadActive.value.isEmpty()) return
        fxPadActive.value.forEach { (rack, effect) ->
            DjEngine.setPunchActive(rack.ordinal, effect.ordinal, false)
        }
        fxPadActive.value = emptySet()
    }


    private fun updateFxTempo() {
        val a = deckA.value
        val b = deckB.value
        val aBpm = if (a.bpm > 0f) a.bpm * a.tempo else 0f
        val bBpm = if (b.bpm > 0f) b.bpm * b.tempo else 0f
        if (aBpm > 0f) DjEngine.setFxTempo(0, aBpm)
        if (bBpm > 0f) DjEngine.setFxTempo(1, bBpm)
        val masterBpm = if (crossfader.floatValue < 0.5f) {
            if (aBpm > 0f) aBpm else bBpm
        } else {
            if (bBpm > 0f) bBpm else aBpm
        }
        if (masterBpm > 0f) DjEngine.setFxTempo(2, masterBpm)
    }

    private fun persistFx() {
        settingsStore.fxPayload = FxPresetCodec.encode(fxState.value)
    }

    private fun applyFxState() {
        FxRack.entries.forEach { rack ->
            val rackState = fxRack(rack)
            FxChain.entries.forEach { chain ->
                val chainState = rackState.chain(chain)
                DjEngine.setFxOrder(
                    rack.ordinal,
                    chain.ordinal,
                    chainState.order.map { it.ordinal }.toIntArray(),
                )
                FxEffect.entries.forEach { effect ->
                    val node = chainState.effect(effect)
                    DjEngine.setFxEnabled(
                        rack.ordinal,
                        chain.ordinal,
                        effect.ordinal,
                        node.enabled,
                    )
                    node.params.forEachIndexed { index, value ->
                        DjEngine.setFxParam(
                            rack.ordinal,
                            chain.ordinal,
                            effect.ordinal,
                            index,
                            value,
                        )
                    }
                }
            }
            FxEffect.entries.forEach { effect ->
                DjEngine.setPunchSend(
                    rack.ordinal,
                    effect.ordinal,
                    rackState.punch.effect(effect).send,
                )
            }
        }
    }

}
