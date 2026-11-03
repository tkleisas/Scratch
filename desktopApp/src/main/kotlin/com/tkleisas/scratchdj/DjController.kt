package com.tkleisas.scratchdj

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.tkleisas.scratchdj.audio.DjEngine
import com.tkleisas.scratchdj.audio.SyncMath
import com.tkleisas.scratchdj.data.AudioFormats
import com.tkleisas.scratchdj.data.DeckQueue
import com.tkleisas.scratchdj.data.QueuePresetData
import com.tkleisas.scratchdj.data.QueuePresetSummary
import com.tkleisas.scratchdj.data.Track
import com.tkleisas.scratchdj.desktop.DesktopPcmCache
import com.tkleisas.scratchdj.desktop.DesktopPresetStore
import com.tkleisas.scratchdj.model.DeckSide
import com.tkleisas.scratchdj.model.DeckUiState
import com.tkleisas.scratchdj.model.EqPreset
import com.tkleisas.scratchdj.model.EqPresetCodec
import com.tkleisas.scratchdj.model.EqNode
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
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences
import kotlin.math.max
import kotlin.math.min

class DjController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = Preferences.userNodeForPackage(DjController::class.java)
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
    private var generationA = 0
    private var generationB = 0

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
    val eqPresetNames = mutableStateOf(eqPresets.keys.sorted())
    val queuePresetSummaries = mutableStateOf(
        queuePresets.values.map { it.summary }.sortedBy { it.name },
    )
    val autoplayA = mutableStateOf(preferences.getBoolean("autoplay_a", true))
    val autoplayB = mutableStateOf(preferences.getBoolean("autoplay_b", true))
    val crossfadeSeconds = mutableFloatStateOf(preferences.getFloat("crossfade_seconds", 0f))

    init {
        if (!DjEngine.start()) {
            message.value = DjEngine.lastError().ifBlank { "Audio engine failed to start" }
        }
        DjEngine.setCrossfader(crossfader.value)
        scope.launch { pollEngine() }
    }

    fun syncLibrary() {
        if (isSyncing.value) return
        scope.launch {
            isSyncing.value = true
            try {
                val found = withContext(Dispatchers.IO) { scanLibrary() }
                library = found
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

    suspend fun search(term: String): List<Track> {
        if (term.isBlank()) return library.take(500)
        val needle = term.trim().lowercase(Locale.US)
        return library.filter {
            it.title.lowercase(Locale.US).contains(needle) ||
                it.artist.lowercase(Locale.US).contains(needle) ||
                it.album.lowercase(Locale.US).contains(needle)
        }.take(500)
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
        rawQueue(side).remove(trackId)
        publishQueue(side)
    }

    fun playFromQueue(trackId: Long, side: DeckSide) {
        val queue = rawQueue(side)
        val track = queue.select(trackId) ?: return
        publishQueue(side)
        loadTrack(track, side, autoPlay = true)
    }

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
    ) {
        rawQueue(side).select(track.id)
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
                    val peaks = DjEngine.waveform(side.index, 1024)
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
                    if (autoPlay) DjEngine.setPlaying(side.index, true)
                    analyzeBpm(side, track, duration, generation)
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
            }
        }
    }

    private fun publishQueue(side: DeckSide) {
        val queue = rawQueue(side)
        if (side == DeckSide.A) queueAState.value = queue.snapshot()
        else queueBState.value = queue.snapshot()
    }

    private suspend fun pollEngine() {
        while (scope.isActive) {
            updateDeckState(DeckSide.A)
            updateDeckState(DeckSide.B)

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
        DjEngine.togglePlaying(side.index)
        val holder = deck(side)
        holder.value = holder.value.copy(isPlaying = DjEngine.isPlaying(side.index))
    }

    fun seekFraction(side: DeckSide, fraction: Float) {
        if (deck(side).value.track == null) return
        DjEngine.seekFraction(side.index, fraction)
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

    fun toggleLoop(side: DeckSide) {
        val holder = deck(side)
        val state = holder.value
        if (state.track == null) return
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
    }

    fun setCrossfadeSeconds(value: Float) {
        val clamped = value.coerceIn(0f, 12f)
        crossfadeSeconds.floatValue = clamped
        preferences.putFloat("crossfade_seconds", clamped)
    }

    fun setCrossfader(value: Float) {
        crossfader.floatValue = value
        DjEngine.setCrossfader(value)
    }

    fun scratchBegin(side: DeckSide) {
        if (deck(side).value.track != null) DjEngine.scratchBegin(side.index)
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
    }

    private sealed interface LoadResult {
        data object Success : LoadResult
        data class Failure(val message: String) : LoadResult
    }

    private companion object {
        const val LOOP_SECONDS = 4
    }
}
