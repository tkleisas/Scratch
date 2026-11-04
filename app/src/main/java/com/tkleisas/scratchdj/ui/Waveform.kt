package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Waveform(
    peaks: FloatArray,
    positionMs: Long,
    durationMs: Long,
    accent: Color,
    loopIn: Float? = null,
    loopOut: Float? = null,
    loopEnabled: Boolean = false,
    cuePoint: Float? = null,
    modifier: Modifier = Modifier,
    onSeek: (Float) -> Unit,
) {
    val path = remember(peaks) {
        if (peaks.isEmpty()) {
            null
        } else {
            val loudest = peaks.maxOrNull()?.coerceAtLeast(0.0001f) ?: 1f
            Path().apply {
                moveTo(0f, 0.5f)
                peaks.forEachIndexed { index, peak ->
                    lineTo(
                        index / (peaks.size - 1f).coerceAtLeast(1f),
                        0.5f - (peak / loudest) * 0.46f,
                    )
                }
                for (index in peaks.indices.reversed()) {
                    lineTo(
                        index / (peaks.size - 1f).coerceAtLeast(1f),
                        0.5f + (peaks[index] / loudest) * 0.46f,
                    )
                }
                close()
            }
        }
    }
    val progress = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(durationMs) {
                    if (durationMs > 0) {
                        detectTapGestures { offset ->
                            onSeek((offset.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                },
        ) {
            if (path == null) {
                drawLine(
                    color = accent.copy(alpha = 0.18f),
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 1.dp.toPx(),
                )
                return@Canvas
            }

            withTransform({
                scale(size.width, size.height, pivot = Offset.Zero)
            }) {
                drawPath(path, accent.copy(alpha = 0.22f))
            }

            val inPoint = loopIn
            val outPoint = loopOut
            if (inPoint != null && outPoint != null && outPoint > inPoint) {
                val startX = inPoint.coerceIn(0f, 1f) * size.width
                val endX = outPoint.coerceIn(0f, 1f) * size.width
                val width = (endX - startX).coerceAtLeast(2.dp.toPx())
                drawRect(
                    color = accent.copy(alpha = if (loopEnabled) 0.20f else 0.08f),
                    topLeft = Offset(startX, 0f),
                    size = Size(width, size.height),
                )
                drawLine(
                    color = accent.copy(alpha = if (loopEnabled) 0.95f else 0.45f),
                    start = Offset(startX, 0f),
                    end = Offset(startX, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawLine(
                    color = accent.copy(alpha = if (loopEnabled) 0.95f else 0.45f),
                    start = Offset(startX + width, 0f),
                    end = Offset(startX + width, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            } else if (inPoint != null) {
                val inX = inPoint.coerceIn(0f, 1f) * size.width
                drawLine(
                    color = accent.copy(alpha = 0.9f),
                    start = Offset(inX, 0f),
                    end = Offset(inX, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }

            if (cuePoint != null) {
                val cueX = cuePoint.coerceIn(0f, 1f) * size.width
                drawLine(
                    color = Color.White.copy(alpha = 0.7f),
                    start = Offset(cueX, 0f),
                    end = Offset(cueX, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawCircle(
                    color = accent,
                    radius = 2.dp.toPx(),
                    center = Offset(cueX, 2.dp.toPx()),
                )
            }

            val playhead = size.width * progress
            clipRect(right = playhead) {
                withTransform({
                    scale(size.width, size.height, pivot = Offset.Zero)
                }) {
                    drawPath(path, accent.copy(alpha = 0.95f))
                }
            }

            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(playhead, 0f),
                end = Offset(playhead, size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }

        if (path == null) {
            Text(
                text = "LOAD A TRACK",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
        }
    }
}
