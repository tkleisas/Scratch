package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

@Composable
fun QueueOverlay(
    controller: DjController,
    side: DeckSide,
    onDismiss: () -> Unit,
) {
    val queue = controller.queue(side).value
    val accent = deckAccent(side)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = ScratchBackground,
    ) {
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
                Spacer(Modifier.weight(1f))
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
    }
}

@Composable
private fun QueueRow(
    index: Int,
    track: Track,
    accent: Color,
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
