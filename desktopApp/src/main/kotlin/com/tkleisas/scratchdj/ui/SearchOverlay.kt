package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkleisas.scratchdj.DjController
import com.tkleisas.scratchdj.data.Track
import com.tkleisas.scratchdj.model.DeckSide
import com.tkleisas.scratchdj.ui.theme.DeckAccentA
import com.tkleisas.scratchdj.ui.theme.DeckAccentB
import com.tkleisas.scratchdj.ui.theme.ScratchBackground
import com.tkleisas.scratchdj.ui.theme.ScratchOutline
import com.tkleisas.scratchdj.ui.theme.ScratchSurface
import com.tkleisas.scratchdj.ui.theme.ScratchSurfaceRaised
import java.util.Locale
import kotlinx.coroutines.delay

private const val PAGE_SIZE = 100
private const val PREFETCH_DISTANCE = 20

@Composable
fun SearchOverlay(controller: DjController, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var lastAction by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val latestQuery by rememberUpdatedState(query)
    val latestResults by rememberUpdatedState(results)
    val latestLoading by rememberUpdatedState(searching || loadingMore)
    val latestEndReached by rememberUpdatedState(endReached)

    suspend fun loadPage(offset: Int, replace: Boolean) {
        val requestQuery = latestQuery
        if (replace) {
            searching = true
            loadingMore = false
            results = emptyList()
            total = 0
            endReached = false
            if (listState.firstVisibleItemIndex != 0) {
                listState.scrollToItem(0)
            }
        } else {
            if (loadingMore || endReached) return
            loadingMore = true
        }
        try {
            val page = controller.searchPage(requestQuery, offset, PAGE_SIZE)
            if (latestQuery != requestQuery) return
            results = if (replace) page.tracks else results + page.tracks
            total = page.total
            endReached = !page.hasMore
        } finally {
            if (latestQuery == requestQuery) {
                searching = false
                loadingMore = false
            }
        }
    }

    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(120)
        loadPage(0, true)
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { lastIndex ->
            if (!latestLoading &&
                !latestEndReached &&
                latestResults.isNotEmpty() &&
                lastIndex >= latestResults.lastIndex - PREFETCH_DISTANCE
            ) {
                loadPage(latestResults.size, false)
            }
        }
    }

    LaunchedEffect(lastAction) {
        if (lastAction != null) {
            delay(1400)
            lastAction = null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = ScratchBackground,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        TouchBlocker()
        Column(
            modifier = Modifier
                .fillMaxSize()
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "SEARCH LIBRARY",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = "Add to either deck queue",
                    color = ScratchOutline,
                    fontSize = 9.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = lastAction ?: "${total} tracks",
                    color = if (lastAction != null) DeckAccentB else ScratchOutline,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(26.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("CLOSE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        text = "Search title, artist or album",
                        color = ScratchOutline,
                        fontSize = 11.sp,
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { focusManager.clearFocus() },
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ScratchSurfaceRaised,
                    unfocusedContainerColor = ScratchSurfaceRaised,
                    focusedBorderColor = DeckAccentA,
                    unfocusedBorderColor = ScratchOutline.copy(alpha = 0.4f),
                ),
            )

            Box(modifier = Modifier.fillMaxWidth().height(12.dp)) {
                if (searching) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = DeckAccentA,
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(results, key = { it.id }) { track ->
                    TrackResultRow(
                        track = track,
                        onAddToDeck = { side ->
                            controller.enqueue(track, side)
                            lastAction = "Added to Deck ${side.letter}"
                        },
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                }
                if (loadingMore) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                color = DeckAccentA,
                            )
                        }
                    }
                }
                if (!searching && results.isEmpty()) {
                    item {
                        Text(
                            text = "No tracks found.",
                            color = ScratchOutline,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                    }
                }
            }

            Text(
                text = "Indexed locally in SQLite. WAV, AIFF, MP3, FLAC and OGG.",
                color = ScratchOutline,
                fontSize = 8.sp,
            )
        }
        }
    }
}

@Composable
private fun TrackResultRow(track: Track, onAddToDeck: (DeckSide) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AddButton(side = DeckSide.A, onAddToDeck = onAddToDeck)
        AddButton(side = DeckSide.B, onAddToDeck = onAddToDeck)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                color = ScratchOutline,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.bpm > 0f) {
            Text(
                text = String.format(Locale.US, "%.1f BPM", track.bpm),
                color = DeckAccentA.copy(alpha = 0.8f),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = track.extension.uppercase(),
            color = ScratchOutline,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = formatTime(track.durationMs),
            color = ScratchOutline,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(38.dp),
        )
    }
}

@Composable
private fun AddButton(side: DeckSide, onAddToDeck: (DeckSide) -> Unit) {
    val accent = if (side == DeckSide.A) DeckAccentA else DeckAccentB
    FilledTonalButton(
        onClick = { onAddToDeck(side) },
        modifier = Modifier.height(30.dp).width(40.dp),
        contentPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = accent.copy(alpha = 0.16f),
            contentColor = accent,
        ),
    ) {
        Text(
            text = side.letter,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
        )
    }
}
