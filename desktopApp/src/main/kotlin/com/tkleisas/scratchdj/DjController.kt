package com.tkleisas.scratchdj

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.tkleisas.scratchdj.audio.DjEngine
import com.tkleisas.scratchdj.audio.BeatSyncMath
import com.tkleisas.scratchdj.audio.SyncMath
import com.tkleisas.scratchdj.data.AudioFormats
import com.tkleisas.scratchdj.data.DeckSession
import com.tkleisas.scratchdj.data.DeckQueue
import com.tkleisas.scratchdj.data.SessionSnapshot
import com.tkleisas.scratchdj.data.QueuePresetData
import com.tkleisas.scratchdj.data.QueuePresetSummary
import com.tkleisas.scratchdj.data.SearchPage
import com.tkleisas.scratchdj.data.Track
import com.tkleisas.scratchdj.desktop.DesktopPcmCache
import com.tkleisas.scratchdj.desktop.DesktopSessionStore
import com.tkleisas.scratchdj.desktop.DesktopPresetStore
import com.tkleisas.scratchdj.model.DeckSide
import com.tkleisas.scratchdj.model.DeckUiState
import com.tkleisas.scratchdj.model.EqPreset
import com.tkleisas.scratchdj.model.EqPresetCodec
import com.tkleisas.scratchdj.model.EqNode
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
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences
import kotlin.math.max
import kotlin.math.min

class DjController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = Preferences.userNodeForPackage(DjController::class.java)
    private val sessionStore = DesktopSessionStore()
    private val sessionSaveMutex = Mutex()
    private var sessionSaveJob: Job? = null
    private var lastSessionSaveAt = 0L
    private var restoredSession: SessionSnapshot? = null
    val scanDirectories = mutableStateOf(loadScanDirectories())

    private val queueA = DeckQueue()
    private val queueB = DeckQueue()
    private val historyA = ArrayDeque<Track>()
    private val historyB = ArrayDeque<Track>()
    private val presetStore = DesktopPresetStore()
    private val storedPresets = presetStore.load()
    private val eqPresets = storedPresets.eqPresets.toMutableMap()
    private val queuePresets = storedPresets.queuePresets.toMutableMap()

    private var library: List<Track> = emptyList()
    private var loadJobA: Job? = null
    private var loadJobB: Job? = null
    private var crossfadePrepA: CrossfadePrep? = null
    private var crossfadePrepB: CrossfadePrep? = null
    private var crossfadePreparedA: Long? = null
    private var crossfadePreparedB: Long? = null
    private var crossfadeNextA: Track? = null
    private var crossfadeNextB: Track? = null
    private val crossfadeGhostA = mutableStateOf<CrossfadeGhost?>(null)
    private val crossfadeGhostB = mutableStateOf<CrossfadeGhost?>(null)
    private var generationA = 0
    private var generationB = 0

    val deckA = mutableStateOf(DeckUiState())
    val deckB = mutableStateOf(DeckUiState())
    val queueAState = mutableStateOf<List<Track>>(emptyList())
    val queueBState = mutableStateOf<List<Track>>(emptyList())
    private val nextTrackA = mutableStateOf<Track?>(null)
    private val nextTrackB = mutableStateOf<Track?>(null)
    val crossfader = mutableFloatStateOf(0.5f)
    val libraryCount = mutableIntStateOf(0)
    val isSyncing = mutableStateOf(false)
    val message = mutableStateOf<String?>(null)
    val latencyMs = mutableIntStateOf(0)
    val bufferFrames = mutableIntStateOf(0)
    val sampleRate = mutableIntStateOf(48000)
    val spectrum = mutableStateOf(FloatArray(32))
    val eqPresetNames = mutableStateOf(eqPresets.keys.sorted())
    val queuePresetSummaries = mutableStateOf(
        queuePresets.values.map { it.summary }.sortedBy { it.name },
    )
    val autoplayA = mutableStateOf(preferences.getBoolean("autoplay_a", true))
    val autoplayB = mutableStateOf(preferences.getBoolean("autoplay_b", true))
    val crossfadeSeconds = mutableFloatStateOf(preferences.getFloat("crossfade_seconds", 0f))
    val theme = mutableStateOf(
        runCatching {
            ScratchThemeName.valueOf(
                preferences.get("theme", ScratchThemeName.Classic.name),
            )
        }.getOrDefault(ScratchThemeName.Classic),
    )
    val waveformMode = mutableStateOf(
        runCatching {
            WaveformMode.valueOf(
                preferences.get("waveform_mode", WaveformMode.Full.name),
            )
        }.getOrDefault(WaveformMode.Full),
    )
    val waveformWindowUnit = mutableStateOf(
        runCatching {
            WaveformWindowUnit.valueOf(
                preferences.get("waveform_window_unit", WaveformWindowUnit.Seconds.name),
            )
        }.getOrDefault(WaveformWindowUnit.Seconds),
    )
    val waveformWindowSeconds = mutableFloatStateOf(
        preferences.getFloat("waveform_window_seconds", WaveformWindowMath.DEFAULT_SECONDS),
    )
    val waveformWindowBeats = mutableFloatStateOf(
        preferences.getFloat("waveform_window_beats", WaveformWindowMath.DEFAULT_BEATS),
    )
    val fxState = mutableStateOf(FxPresetCodec.decode(preferences.get("fx_payload", "")))
    val fxPadActive = mutableStateOf<Set<Pair<FxRack, FxEffect>>>(emptySet())

    init {
        restoreSessionQueues()
        if (!DjEngine.start()) {
            message.value = DjEngine.lastError().ifBlank { "Audio engine failed to start" }
        }
        DjEngine.setCrossfader(crossfader.value)
        applyFxState()
        updateFxTempo()
        restoreLastTracks()
        scope.launch { pollEngine() }
    }

    fun syncLibrary() {
        if (isSyncing.value) return
        scope.launch {
            isSyncing.value = true
            try {
                val found = withContext(Dispatchers.IO) { scanLibrary() }
                library = found
                searchCacheTerm = null
                searchCache = emptyList()
                libraryCount.intValue = found.size
                if (found.isEmpty()) {
                    message.value = "No supported audio files found in Music"
                }
            } catch (error: Throwable) {
                message.value = "Library scan failed: ${error.message}"
            } finally {
                isSyncing.value = false
            }
        }
    }

    private fun loadScanDirectories(): List<String> {
        val stored = preferences.get("scan_directories", "")
        val parsed = stored.split('\n').filter { it.isNotBlank() }
        return if (parsed.isEmpty()) {
            listOf(File(System.getProperty("user.home"), "Music").absolutePath)
        } else {
            parsed
        }
    }

    private fun scanLibrary(): List<Track> {
        val roots = scanDirectories.value
            .map(::File)
            .filter { it.isDirectory }
        if (roots.isEmpty()) return emptyList()

        val seen = HashSet<String>()
        val found = ArrayList<Track>()
        for (root in roots) {
            try {
                root.walkTopDown()
                    .filter { it.isFile && AudioFormats.isSupported(it.name) }
                    .forEach { file ->
                        if (!seen.add(file.absolutePath)) return@forEach
                        found.add(
                            Track(
                                id = file.absolutePath.hashCode().toLong() and 0x7FFFFFFF,
                                title = file.nameWithoutExtension,
                                artist = file.parentFile?.name ?: "Unknown artist",
                                album = file.parentFile?.name ?: "",
                                displayName = file.name,
                                extension = AudioFormats.extensionOf(file.name) ?: "",
                                durationMs = 0L,
                                uri = file.absolutePath,
                            ),
                        )
                    }
            } catch (_: Throwable) {
            }
        }
        return found.sortedBy { it.title.lowercase(Locale.US) }
    }

    fun addScanDirectory(path: String) {
        val directory = File(path).absolutePath
        if (scanDirectories.value.any { it == directory }) return
        updateScanDirectories(scanDirectories.value + directory)
    }

    fun removeScanDirectory(path: String) {
        updateScanDirectories(scanDirectories.value.filterNot { it == path })
    }

    private fun updateScanDirectories(directories: List<String>) {
        scanDirectories.value = directories
        preferences.put("scan_directories", directories.joinToString("\n"))
        syncLibrary()
    }

    private var searchCacheTerm: String? = null
    private var searchCache: List<Track> = emptyList()

    suspend fun searchPage(term: String, offset: Int, limit: Int): SearchPage =
        withContext(Dispatchers.Default) {
            val cacheKey = term.trim()
            val filtered = if (searchCacheTerm == cacheKey) {
                searchCache
            } else {
                val needle = cacheKey.lowercase(Locale.US)
                val matches = if (needle.isBlank()) {
                    library
                } else {
                    library.filter {
                        it.title.lowercase(Locale.US).contains(needle) ||
                            it.artist.lowercase(Locale.US).contains(needle) ||
                            it.album.lowercase(Locale.US).contains(needle)
                    }
                }
                searchCacheTerm = cacheKey
                searchCache = matches
                matches
            }
            SearchPage(
                tracks = filtered.drop(offset).take(limit),
                total = filtered.size,
                offset = offset,
            )
        }

    fun deck(side: DeckSide) = if (side == DeckSide.A) deckA else deckB
    fun queue(side: DeckSide) = if (side == DeckSide.A) queueAState else queueBState

    private fun rawQueue(side: DeckSide) = if (side == DeckSide.A) queueA else queueB
    private fun history(side: DeckSide) = if (side == DeckSide.A) historyA else historyB
    private fun loadJob(side: DeckSide) = if (side == DeckSide.A) loadJobA else loadJobB
    private fun setLoadJob(side: DeckSide, job: Job?) {
        if (side == DeckSide.A) loadJobA = job else loadJobB = job
    }
    private fun currentGeneration(side: DeckSide) =
        if (side == DeckSide.A) generationA else generationB

    fun enqueue(track: Track, side: DeckSide) {
        val queue = rawQueue(side)
        queue.enqueue(track)
        publishQueue(side)
        val state = deck(side).value
        if (state.track == null && !state.isLoading) {
            queue.select(track.id)
            loadTrack(track, side)
        }
    }

    fun removeFromQueue(trackId: Long, side: DeckSide) {
        cancelCrossfade(side)
        rawQueue(side).remove(trackId)
        publishQueue(side)
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
        if (selectQueue) {
            rawQueue(side).select(track.id)
            publishQueue(side)
        }
        cancelCrossfade(side)
        val holder = deck(side)
        val current = holder.value.track
        val pushedHistory = recordHistory && current != null && current.id != track.id
        if (pushedHistory) {
            val history = history(side)
            history.addLast(current)
            while (history.size > 20) history.removeFirst()
        }

        val generation = if (side == DeckSide.A) ++generationA else ++generationB
        loadJob(side)?.cancel()
        holder.value = holder.value.copy(
            isLoading = true,
            loadingTitle = track.title,
            loadProgress = 0f,
        )

        val job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val rate = DjEngine.sampleRate()
                    val cache = DesktopPcmCache.ensureDecoded(File(track.uri), rate)
                    if (DjEngine.loadPcmFile(side.index, cache.absolutePath, rate)) {
                        LoadResult.Success
                    } else {
                        LoadResult.Failure(
                            DjEngine.lastError().ifBlank { "Could not load ${track.title}" },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    LoadResult.Failure(error.message ?: "Could not load ${track.title}")
                }
            }

            if (generation != currentGeneration(side)) return@launch
            when (result) {
                LoadResult.Success -> {
                    val peaks = DjEngine.waveform(side.index, 8192)
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
                        beatOffsetMs = null,
                        loopIn = null,
                        loopOut = null,
                        loopEnabled = false,
                    )
                    if (autoPlay) DjEngine.setPlaying(side.index, true)
                    analyzeBpm(side, track, duration, generation)
                    scheduleSessionSave()
                }

                is LoadResult.Failure -> {
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

    private fun analyzeBpm(side: DeckSide, track: Track, durationMs: Long, generation: Int) {
        scope.launch(Dispatchers.IO) {
            val bpm = DjEngine.analyzeBpm(side.index, durationMs)
            if (bpm <= 0f) return@launch
            withContext(Dispatchers.Main) {
                val holder = deck(side)
                if (generation == currentGeneration(side) && holder.value.track?.id == track.id) {
                    holder.value = holder.value.copy(bpm = bpm)
                }
                updateFxTempo()
            }
        }
    }

    private fun publishQueue(side: DeckSide) {
        val queue = rawQueue(side)
        if (side == DeckSide.A) {
            queueAState.value = queue.snapshot()
            nextTrackA.value = queue.peekNext()
        } else {
            queueBState.value = queue.snapshot()
            nextTrackB.value = queue.peekNext()
        }
        scheduleSessionSave()
    }

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
                            peaks = DjEngine.crossfadeWaveform(side.index, 8192),
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
                val rate = DjEngine.sampleRate()
                val cache = DesktopPcmCache.ensureDecoded(File(next.uri), rate)
                DjEngine.loadCrossfadePcmFile(side.index, cache.absolutePath, rate)
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

        val peaks = DjEngine.waveform(side.index, 8192)
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
            beatOffsetMs = null,
            loopIn = null,
            loopOut = null,
            loopEnabled = false,
            cuePoint = null,
            hotCues = List(4) { null },
        )
        reapplyDeckSettings(side, holder.value)
        if (next.bpm <= 0f) analyzeBpm(side, next, duration, currentGeneration(side))
        updateFxTempo()
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

    private fun restoreLastTracks() {
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

    fun nextTrack(side: DeckSide): Track? =
        if (side == DeckSide.A) nextTrackA.value else nextTrackB.value

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
        holder.value = current.copy(
            positionMs = DjEngine.positionMs(side.index),
            durationMs = DjEngine.durationMs(side.index).takeIf { it > 0 } ?: current.durationMs,
            isPlaying = DjEngine.isPlaying(side.index),
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

    fun setEqNode(side: DeckSide, index: Int, node: EqNode) {
        val holder = deck(side)
        val nodes = holder.value.eqNodes.toMutableList()
        if (index !in nodes.indices) return
        nodes[index] = node
        holder.value = holder.value.copy(eqNodes = nodes)
        DjEngine.setEqNode(side.index, index, node.frequency, node.gainDb, node.q)
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

    fun syncToOtherDeck(side: DeckSide) {
        val state = deck(side).value
        val other = deck(side.other()).value
        if (state.track == null || other.track == null) {
            message.value = "Load a track on both decks to sync"
            return
        }
        if (state.bpm > 0f && other.bpm > 0f &&
            state.beatOffsetMs != null && other.beatOffsetMs != null
        ) {
            applySync(side)
            return
        }

        message.value = "Analyzing BPM and beat grid…"
        scope.launch {
            var thisBpm = state.bpm
            var otherBpm = other.bpm
            var thisOffset = state.beatOffsetMs
            var otherOffset = other.beatOffsetMs
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
            if (thisOffset == null && thisBpm > 0f) {
                thisOffset = withContext(Dispatchers.IO) {
                    DjEngine.analyzeBeatOffset(side.index, state.durationMs, thisBpm)
                }
            }
            if (otherOffset == null && otherBpm > 0f) {
                otherOffset = withContext(Dispatchers.IO) {
                    DjEngine.analyzeBeatOffset(
                        side.other().index,
                        other.durationMs,
                        otherBpm,
                    )
                }
            }

            val holder = deck(side)
            if (holder.value.track?.id == state.track?.id) {
                holder.value = holder.value.copy(
                    bpm = thisBpm,
                    beatOffsetMs = thisOffset,
                )
            }
            val otherHolder = deck(side.other())
            if (otherHolder.value.track?.id == other.track?.id) {
                otherHolder.value = otherHolder.value.copy(
                    bpm = otherBpm,
                    beatOffsetMs = otherOffset,
                )
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

        val thisOffset = state.beatOffsetMs
        val otherOffset = other.beatOffsetMs
        if (thisOffset != null && otherOffset != null &&
            state.bpm > 0f && other.bpm > 0f && state.durationMs > 1L
        ) {
            val otherPosition = DjEngine.positionMs(side.other().index)
            val targetPhase = BeatSyncMath.phase(otherPosition, otherOffset, other.bpm)
            val currentPosition = DjEngine.positionMs(side.index)
            val aligned = BeatSyncMath.alignedPositionMs(
                positionMs = currentPosition,
                offsetMs = thisOffset,
                bpm = state.bpm,
                targetPhase = targetPhase,
                durationMs = state.durationMs,
            )
            if (aligned != null && aligned != currentPosition) {
                DjEngine.seekFraction(
                    side.index,
                    aligned.toFloat() / state.durationMs.toFloat(),
                )
                deck(side).value = deck(side).value.copy(positionMs = aligned)
            }
            message.value = "Deck ${side.letter} beat-synced to " +
                String.format(java.util.Locale.US, "%.1f", targetBpm) + " BPM"
        } else {
            message.value = "Deck ${side.letter} synced to " +
                String.format(java.util.Locale.US, "%.1f", targetBpm) + " BPM"
        }
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
        if (end - start < 0.01f) start = max(0f, end - LOOP_SECONDS * 1000f / duration)
        return start to end
    }

    private fun applyLoop(side: DeckSide, startFraction: Float, endFraction: Float, enabled: Boolean) {
        var start = startFraction.coerceIn(0f, 1f)
        var end = endFraction.coerceIn(0f, 1f)
        if (end <= start) end = min(1f, start + 0.01f)
        if (end <= start) start = max(0f, end - 0.01f)
        DjEngine.setLoopPoints(side.index, start, end)
        DjEngine.setLoopEnabled(side.index, enabled)
        val holder = deck(side)
        holder.value = holder.value.copy(loopIn = start, loopOut = end, loopEnabled = enabled)
    }

    fun saveEqPreset(side: DeckSide, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            message.value = "Preset name is required"
            return
        }
        val state = deck(side).value
        eqPresets[trimmed] = EqPresetCodec.encode(state.eqNodes, state.eqBypassed)
        eqPresetNames.value = eqPresets.keys.sorted()
        persistPresets()
        message.value = "Saved preset “$trimmed”"
    }

    fun loadEqPreset(side: DeckSide, name: String) {
        val preset = eqPresets[name]?.let(EqPresetCodec::decode)
        if (preset == null) {
            message.value = "Could not load preset “$name”"
            return
        }
        applyEqPreset(side, preset)
        message.value = "Loaded preset “$name”"
    }

    fun deleteEqPreset(name: String) {
        eqPresets.remove(name)
        eqPresetNames.value = eqPresets.keys.sorted()
        persistPresets()
    }

    private fun applyEqPreset(side: DeckSide, preset: EqPreset) {
        val holder = deck(side)
        holder.value = holder.value.copy(eqNodes = preset.nodes, eqBypassed = preset.bypassed)
        preset.nodes.forEachIndexed { index, node ->
            DjEngine.setEqNode(side.index, index, node.frequency, node.gainDb, node.q)
        }
        DjEngine.setEqBypassed(side.index, preset.bypassed)
    }

    fun saveQueuePreset(name: String, presetScope: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            message.value = "Preset name is required"
            return
        }
        val normalized = presetScope.uppercase()
        val deckA = if (normalized == "A" || normalized == "ALL") queueA.snapshot() else emptyList()
        val deckB = if (normalized == "B" || normalized == "ALL") queueB.snapshot() else emptyList()
        if (deckA.isEmpty() && deckB.isEmpty()) {
            message.value = "Queue is empty"
            return
        }
        val summary = QueuePresetSummary(trimmed, normalized)
        queuePresets[trimmed] = QueuePresetData(summary, deckA, deckB)
        queuePresetSummaries.value = queuePresets.values.map { it.summary }.sortedBy { it.name }
        persistPresets()
        message.value = "Saved queue preset “$trimmed”"
    }

    fun loadQueuePreset(name: String) {
        val data = queuePresets[name]
        if (data == null) {
            message.value = "Could not load queue preset “$name”"
            return
        }
        applyQueuePreset(data)
        message.value = "Loaded queue preset “$name”"
    }

    fun deleteQueuePreset(name: String) {
        queuePresets.remove(name)
        queuePresetSummaries.value = queuePresets.values.map { it.summary }.sortedBy { it.name }
        persistPresets()
    }

    private fun persistPresets() {
        try {
            presetStore.save(DesktopPresetStore.Snapshot(eqPresets, queuePresets))
        } catch (error: Throwable) {
            message.value = "Could not save presets: ${error.message}"
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
            if (current != null && tracks.any { it.id == current.id }) queue.select(current.id)
            publishQueue(side)
            if (deck(side).value.track == null && tracks.isNotEmpty()) {
                val first = queue.next() ?: tracks.first()
                loadTrack(first, side, autoPlay = false, recordHistory = false)
            }
        }
    }

    fun setAutoplay(side: DeckSide, enabled: Boolean) {
        if (side == DeckSide.A) {
            autoplayA.value = enabled
            preferences.putBoolean("autoplay_a", enabled)
        } else {
            autoplayB.value = enabled
            preferences.putBoolean("autoplay_b", enabled)
        }
        if (!enabled) cancelCrossfade(side)
    }

    fun setCrossfadeSeconds(value: Float) {
        val clamped = value.coerceIn(0f, 12f)
        crossfadeSeconds.floatValue = clamped
        preferences.putFloat("crossfade_seconds", clamped)
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
        preferences.put("theme", value.name)
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
        preferences.put("waveform_mode", value.name)
    }

    fun setWaveformWindowUnit(value: WaveformWindowUnit) {
        waveformWindowUnit.value = value
        preferences.put("waveform_window_unit", value.name)
    }

    fun setWaveformWindowSeconds(value: Float) {
        val clamped = value.coerceIn(
            WaveformWindowMath.MIN_SECONDS,
            WaveformWindowMath.MAX_SECONDS,
        )
        waveformWindowSeconds.floatValue = clamped
        preferences.putFloat("waveform_window_seconds", clamped)
    }

    fun setWaveformWindowBeats(value: Float) {
        val clamped = value.coerceIn(
            WaveformWindowMath.MIN_BEATS,
            WaveformWindowMath.MAX_BEATS,
        )
        waveformWindowBeats.floatValue = clamped
        preferences.putFloat("waveform_window_beats", clamped)
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
    }

    private sealed interface LoadResult {
        data object Success : LoadResult
        data class Failure(val message: String) : LoadResult
    }

    private companion object {
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
        val updated = current.copy(punch = current.punch.withSend(effect, value))
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
        preferences.put("fx_payload", FxPresetCodec.encode(fxState.value))
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
