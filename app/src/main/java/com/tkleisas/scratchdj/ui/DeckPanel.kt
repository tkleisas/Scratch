package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkleisas.scratchdj.DjController
import com.tkleisas.scratchdj.audio.ScratchMath
import com.tkleisas.scratchdj.model.DeckSide
import com.tkleisas.scratchdj.model.DeckUiState
import com.tkleisas.scratchdj.ui.theme.DeckAccentA
import com.tkleisas.scratchdj.ui.theme.DeckAccentB
import com.tkleisas.scratchdj.ui.theme.ScratchOutline
import com.tkleisas.scratchdj.ui.theme.ScratchSurface
import com.tkleisas.scratchdj.ui.theme.ScratchSurfaceRaised
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

fun deckAccent(side: DeckSide): Color = if (side == DeckSide.A) DeckAccentA else DeckAccentB

@Composable
fun DeckPanel(
    controller: DjController,
    side: DeckSide,
    modifier: Modifier = Modifier,
    onOpenEq: () -> Unit,
) {
    val state = controller.deck(side).value
    val accent = deckAccent(side)
    val queued = controller.queue(side).value.size

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ScratchSurface),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = side.letter,
                        color = Color(0xFF0A0C0F),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.track?.title ?: "No track loaded",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = if (state.isLoading) {
                        val percent = (state.loadProgress * 100f).roundToInt()
                        val suffix = if (percent > 0) " · $percent%" else "…"
                        "Loading ${state.loadingTitle ?: "track"}$suffix"
                    } else {
                        state.track?.artist ?: "Search and add to queue"
                    }
                    Text(
                        text = subtitle,
                        color = if (state.isLoading) accent else ScratchOutline,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val eqActive = state.eqNodes.any { it.gainDb != 0f }
                TextButton(
                    onClick = onOpenEq,
                    modifier = Modifier.height(20.dp),
                    contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "EQ",
                        color = if (eqActive) accent else ScratchOutline,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                val effectiveBpm = state.bpm * state.tempo
                Text(
                    text = if (effectiveBpm > 0f) {
                        String.format(Locale.US, "%.1f BPM", effectiveBpm)
                    } else {
                        "— BPM"
                    },
                    color = if (state.bpm > 0f) accent.copy(alpha = 0.9f) else ScratchOutline,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "$queued QUEUED",
                    color = accent.copy(alpha = 0.8f),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.height(2.dp))
            Waveform(
                peaks = state.peaks,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                accent = accent,
                loopIn = state.loopIn,
                loopOut = state.loopOut,
                loopEnabled = state.loopEnabled,
                modifier = Modifier.fillMaxWidth().height(28.dp),
                onSeek = { controller.seekFraction(side, it) },
            )

            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Platter(state, side, accent, controller)
                    if (state.isLoading) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (state.loadProgress > 0f) {
                                CircularProgressIndicator(
                                    progress = { state.loadProgress },
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.dp,
                                    color = accent,
                                )
                                Text(
                                    text = "${(state.loadProgress * 100f).roundToInt()}%",
                                    color = accent,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = accent,
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1.08f).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    TransportRow(controller, side, state, accent, queued)

                    FilterControl(
                        mode = state.filterMode,
                        cutoff = state.filterCutoff,
                        accent = accent,
                        onModeChange = { controller.setFilterMode(side, it) },
                        onCutoffChange = { controller.setFilterCutoff(side, it) },
                    )

                    TempoControl(
                        value = state.tempo,
                        keyLock = state.keyLock,
                        canSync = state.bpm > 0f &&
                            controller.deck(side.other()).value.bpm > 0f,
                        accent = accent,
                        onValueChange = { controller.setTempo(side, it) },
                        onToggleKeyLock = { controller.toggleKeyLock(side) },
                        onSync = { controller.syncToOtherDeck(side) },
                    )

                    LoopControl(
                        state = state,
                        accent = accent,
                        onToggle = { controller.toggleLoop(side) },
                        onSetIn = { controller.setLoopIn(side) },
                        onSetOut = { controller.setLoopOut(side) },
                        onClear = { controller.clearLoop(side) },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatTime(state.positionMs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = formatTime(state.durationMs),
                            color = ScratchOutline,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportRow(
    controller: DjController,
    side: DeckSide,
    state: DeckUiState,
    accent: Color,
    queued: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SmallTransportButton(
            label = "PREV",
            enabled = state.track != null,
            accent = accent,
            modifier = Modifier.weight(1f),
            onClick = { controller.previousTrack(side) },
        )
        Button(
            onClick = { controller.togglePlay(side) },
            enabled = state.track != null,
            modifier = Modifier.weight(1.35f).height(25.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = Color(0xFF0A0C0F),
                disabledContainerColor = ScratchSurfaceRaised,
                disabledContentColor = ScratchOutline,
            ),
        ) {
            Text(
                text = if (state.isPlaying) "PAUSE" else "PLAY",
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
        }
        SmallTransportButton(
            label = "NEXT",
            enabled = queued > 0,
            accent = accent,
            modifier = Modifier.weight(1f),
            onClick = { controller.skipToNext(side) },
        )
    }
}

@Composable
private fun SmallTransportButton(
    label: String,
    enabled: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(25.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = ScratchSurfaceRaised,
            contentColor = accent,
            disabledContainerColor = ScratchSurfaceRaised,
            disabledContentColor = ScratchOutline,
        ),
    ) {
        Text(label, fontSize = 7.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FilterControl(
    mode: Int,
    cutoff: Float,
    accent: Color,
    onModeChange: (Int) -> Unit,
    onCutoffChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "FILTER",
                color = ScratchOutline,
                fontSize = 8.sp,
                letterSpacing = 1.sp,
            )
            Text(
                text = if (mode == 0) "OFF" else formatCutoff(cutoff),
                color = if (mode == 0) ScratchOutline else accent,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            FilterModeChip("LP", 1, mode, accent, onModeChange, Modifier.weight(1f))
            FilterModeChip("OFF", 0, mode, accent, onModeChange, Modifier.weight(1f))
            FilterModeChip("HP", 2, mode, accent, onModeChange, Modifier.weight(1f))
        }
        CompactSlider(
            value = cutoff,
            onValueChange = onCutoffChange,
            accent = if (mode == 0) ScratchOutline else accent,
            valueRange = 0f..1f,
        )
    }
}

@Composable
private fun FilterModeChip(
    label: String,
    value: Int,
    selected: Int,
    accent: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = selected == value
    Box(
        modifier = modifier
            .height(17.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (active) accent.copy(alpha = 0.9f) else ScratchSurfaceRaised,
            )
            .clickable { onSelect(value) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) Color(0xFF0A0C0F) else ScratchOutline,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TempoControl(
    value: Float,
    keyLock: Boolean,
    canSync: Boolean,
    accent: Color,
    onValueChange: (Float) -> Unit,
    onToggleKeyLock: () -> Unit,
    onSync: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "TEMPO",
                    color = ScratchOutline,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = String.format(Locale.US, "%+.1f%%", (value - 1f) * 100f),
                    color = accent,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onSync,
                    enabled = canSync,
                    modifier = Modifier.height(18.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "SYNC",
                        color = if (canSync) accent else ScratchOutline,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                TextButton(
                    onClick = onToggleKeyLock,
                    modifier = Modifier.height(18.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "KEY",
                        color = if (keyLock) accent else ScratchOutline,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        CompactSlider(
            value = value,
            onValueChange = onValueChange,
            accent = accent,
            valueRange = 0.5f..2f,
            bipolar = true,
        )
    }
}

@Composable
private fun LoopControl(
    state: DeckUiState,
    accent: Color,
    onToggle: () -> Unit,
    onSetIn: () -> Unit,
    onSetOut: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        LoopChip("IN", active = false, accent = accent, modifier = Modifier.weight(1f), onClick = onSetIn)
        LoopChip("OUT", active = false, accent = accent, modifier = Modifier.weight(1f), onClick = onSetOut)
        LoopChip(
            "LOOP",
            active = state.loopEnabled,
            accent = accent,
            modifier = Modifier.weight(1.2f),
            onClick = onToggle,
        )
        LoopChip("CLR", active = false, accent = accent, modifier = Modifier.weight(1f), onClick = onClear)
    }
}

@Composable
private fun LoopChip(
    label: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(17.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) accent.copy(alpha = 0.9f) else ScratchSurfaceRaised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) Color(0xFF0A0C0F) else ScratchOutline,
            fontSize = 6.5.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatCutoff(normalized: Float): String {
    val hz = (20.0 * 1000.0.pow(normalized.coerceIn(0f, 1f).toDouble())).roundToInt()
    return if (hz < 1000) {
        "$hz Hz"
    } else {
        String.format(Locale.US, "%.1f kHz", hz / 1000f)
    }
}

@Composable
private fun Platter(
    state: DeckUiState,
    side: DeckSide,
    accent: Color,
    controller: DjController,
) {
    var scratching by remember { mutableStateOf(false) }
    val enabled = state.track != null
    val rotation = ((state.positionMs / 1000.0) * 200.0 % 360.0).toFloat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(side, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    var lastAngle = atan2(
                        down.position.y - center.y,
                        down.position.x - center.x,
                    ).toFloat()
                    var lastTime = down.uptimeMillis
                    scratching = true
                    controller.scratchBegin(side)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break

                            val angle = atan2(
                                change.position.y - center.y,
                                change.position.x - center.x,
                            ).toFloat()
                            var delta = angle - lastAngle
                            if (delta > PI.toFloat()) delta -= (2 * PI).toFloat()
                            if (delta < -PI.toFloat()) delta += (2 * PI).toFloat()

                            val elapsed = change.uptimeMillis - lastTime
                            controller.scratchRate(
                                side,
                                ScratchMath.rateFor(delta, elapsed.coerceAtLeast(1L)),
                            )

                            lastAngle = angle
                            lastTime = change.uptimeMillis
                            change.consume()
                        }
                    } finally {
                        scratching = false
                        controller.scratchEnd(side)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sidePx = min(size.width, size.height)
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = sidePx / 2f - 2.dp.toPx()

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF30363F), Color(0xFF0A0C0F)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            drawCircle(
                color = Color.Black,
                radius = radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            for (groove in 1..10) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.045f),
                    radius = radius * groove / 11f,
                    center = center,
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }
            rotate(rotation, pivot = center) {
                drawLine(
                    color = accent,
                    start = Offset(center.x, center.y - radius * 0.34f),
                    end = Offset(center.x, center.y - radius * 0.93f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = accent,
                    radius = 2.5.dp.toPx(),
                    center = Offset(center.x, center.y + radius * 0.76f),
                )
            }
            drawCircle(
                color = ScratchSurfaceRaised,
                radius = radius * 0.34f,
                center = center,
            )
            drawCircle(
                color = accent.copy(alpha = 0.8f),
                radius = radius * 0.34f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            if (scratching) {
                drawCircle(
                    color = accent.copy(alpha = 0.75f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = side.letter,
                color = accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = if (state.isPlaying) "33⅓" else "JOG",
                color = ScratchOutline,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
