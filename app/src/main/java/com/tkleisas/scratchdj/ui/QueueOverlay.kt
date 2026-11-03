package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkleisas.scratchdj.DjController
import com.tkleisas.scratchdj.data.Track
import com.tkleisas.scratchdj.model.DeckSide
import com.tkleisas.scratchdj.ui.theme.ScratchBackground
import com.tkleisas.scratchdj.ui.theme.ScratchOutline
import com.tkleisas.scratchdj.ui.theme.ScratchSurfaceRaised
import kotlinx.coroutines.delay

@Composable
fun QueueOverlay(
    controller: DjController,
    side: DeckSide,
    onDismiss: () -> Unit,
) {
    val queue = controller.queue(side).value
    val accent = deckAccent(side)
    val currentId = controller.deck(side).value.track?.id
    var showSave by remember { mutableStateOf(false) }
    var showLoad by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var saveScope by remember { mutableStateOf(side.letter) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(status) {
        if (status != null) {
            delay(1800)
            status = null
        }
    }

    val canSave = when (saveScope) {
        "A" -> controller.queue(DeckSide.A).value.isNotEmpty()
        "B" -> controller.queue(DeckSide.B).value.isNotEmpty()
        else -> controller.queue(DeckSide.A).value.isNotEmpty() ||
            controller.queue(DeckSide.B).value.isNotEmpty()
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
                .displayCutoutPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(30.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = side.letter,
                        color = Color(0xFF0A0C0F),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
                Text(
                    text = "DECK ${side.letter} QUEUE",
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = "${queue.size} tracks",
                    color = ScratchOutline,
                    fontSize = 9.sp,
                )
                if (status != null) {
                    Text(
                        text = status.orEmpty(),
                        color = accent,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        presetName = ""
                        saveScope = side.letter
                        showSave = true
                    },
                    modifier = Modifier.height(26.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("SAVE", color = ScratchOutline, fontSize = 9.sp)
                }
                TextButton(
                    onClick = { showLoad = true },
                    modifier = Modifier.height(26.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("LOAD", color = ScratchOutline, fontSize = 9.sp)
                }
                if (queue.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            queue.forEach { controller.removeFromQueue(it.id, side) }
                        },
                        modifier = Modifier.height(26.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("CLEAR", color = ScratchOutline, fontSize = 9.sp)
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(26.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("CLOSE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Queue is empty. Use SEARCH + QUEUE to add tracks.",
                        color = ScratchOutline,
                        fontSize = 11.sp,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(queue) { index, track ->
                        QueueRow(
                            index = index,
                            track = track,
                            accent = accent,
                            isCurrent = track.id == currentId,
                            onPlay = {
                                controller.playFromQueue(track.id, side)
                                onDismiss()
                            },
                            onRemove = { controller.removeFromQueue(track.id, side) },
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                    }
                }
            }
        }

        if (showSave) {
            QueueSavePanel(
                name = presetName,
                onNameChange = { presetName = it },
                scope = saveScope,
                onScopeChange = { saveScope = it },
                canSave = canSave,
                accent = accent,
                onSave = {
                    val trimmed = presetName.trim()
                    if (trimmed.isNotEmpty()) {
                        controller.saveQueuePreset(trimmed, saveScope)
                        status = "Saved queue preset “$trimmed”"
                        showSave = false
                    }
                },
                onDismiss = { showSave = false },
            )
        }
        if (showLoad) {
            QueueLoadPanel(
                summaries = controller.queuePresetSummaries.value,
                accent = accent,
                onLoad = { name ->
                    controller.loadQueuePreset(name)
                    status = "Loaded queue preset “$name”"
                    showLoad = false
                },
                onDelete = { controller.deleteQueuePreset(it) },
                onDismiss = { showLoad = false },
            )
        }
        }
    }
}

@Composable
private fun QueueScopeChip(
    label: String,
    value: String,
    selected: String,
    accent: Color,
    onClick: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .height(24.dp)
            .background(
                if (selected == value) accent.copy(alpha = 0.2f) else ScratchSurfaceRaised,
                RoundedCornerShape(4.dp),
            )
            .clickable { onClick(value) }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected == value) accent else ScratchOutline,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun QueueSavePanel(
    name: String,
    onNameChange: (String) -> Unit,
    scope: String,
    onScopeChange: (String) -> Unit,
    canSave: Boolean,
    accent: Color,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        TouchBlocker()
        Surface(
            modifier = Modifier.width(460.dp),
            shape = RoundedCornerShape(14.dp),
            color = ScratchSurfaceRaised,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "SAVE QUEUE PRESET",
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text("Preset name", color = ScratchOutline, fontSize = 11.sp)
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = ScratchOutline.copy(alpha = 0.4f),
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "SAVE",
                        color = ScratchOutline,
                        fontSize = 8.sp,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    QueueScopeChip("DECK A", "A", scope, accent, onScopeChange)
                    QueueScopeChip("DECK B", "B", scope, accent, onScopeChange)
                    QueueScopeChip("A + B", "ALL", scope, accent, onScopeChange)
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("CANCEL", color = ScratchOutline, fontSize = 9.sp)
                    }
                    Button(
                        onClick = onSave,
                        enabled = name.isNotBlank() && canSave,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = Color(0xFF0A0C0F),
                        ),
                    ) {
                        Text("SAVE", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueLoadPanel(
    summaries: List<com.tkleisas.scratchdj.data.QueuePresetSummary>,
    accent: Color,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        TouchBlocker()
        Surface(
            modifier = Modifier.width(500.dp),
            shape = RoundedCornerShape(14.dp),
            color = ScratchSurfaceRaised,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "LOAD QUEUE PRESET",
                        color = accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("CLOSE", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (summaries.isEmpty()) {
                    Text(
                        text = "No queue presets saved yet.",
                        color = ScratchOutline,
                        fontSize = 10.sp,
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(summaries.size) { index ->
                            val summary = summaries[index]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = summary.name,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = when (summary.scope) {
                                        "A" -> "DECK A"
                                        "B" -> "DECK B"
                                        else -> "A + B"
                                    },
                                    color = ScratchOutline,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    onClick = { onLoad(summary.name) },
                                    modifier = Modifier.height(26.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text("LOAD", color = accent, fontSize = 9.sp)
                                }
                                TextButton(
                                    onClick = { onDelete(summary.name) },
                                    modifier = Modifier.height(26.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text("DELETE", color = ScratchOutline, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    track: Track,
    accent: Color,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = (index + 1).toString().padStart(2, '0'),
            color = ScratchOutline,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                color = ScratchOutline,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            modifier = Modifier.width(42.dp),
        )
        FilledTonalButton(
            onClick = onPlay,
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = accent.copy(alpha = 0.16f),
                contentColor = accent,
            ),
        ) {
            Text("PLAY", fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(
            onClick = onRemove,
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text("REMOVE", color = ScratchOutline, fontSize = 9.sp)
        }
    }
}
