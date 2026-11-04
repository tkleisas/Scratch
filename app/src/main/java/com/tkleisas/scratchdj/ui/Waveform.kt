package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkleisas.scratchdj.model.WaveformMode
import com.tkleisas.scratchdj.model.WaveformWindowMath
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

@Composable
fun Waveform(
    peaks: FloatArray,
    positionMs: Long,
    durationMs: Long,
    accent: Color,
    mode: WaveformMode,
    windowMs: Long,
    loopIn: Float? = null,
    loopOut: Float? = null,
    loopEnabled: Boolean = false,
    cuePoint: Float? = null,
    ghostPeaks: FloatArray? = null,
    ghostPositionMs: Long = 0L,
    ghostDurationMs: Long = 0L,
    crossfadeProgress: Float? = null,
    crossfadeWindowMs: Long = 0L,
    modifier: Modifier = Modifier,
    onSeek: (Float) -> Unit,
    onToggleMode: () -> Unit,
) {
    val loudest = remember(peaks) {
        peaks.maxOrNull()?.coerceAtLeast(0.0001f) ?: 1f
    }
    val fullPath = remember(peaks, loudest) { buildNormalizedPath(peaks, loudest) }
    val progress = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val windowFraction = if (mode == WaveformMode.Full) {
        1f
    } else {
        WaveformWindowMath.windowFraction(windowMs, durationMs)
    }
    val viewport = WaveformWindowMath.viewport(progress, windowFraction)
    val ghostLoudest = remember(ghostPeaks) {
        ghostPeaks?.maxOrNull()?.coerceAtLeast(0.0001f) ?: 1f
    }
    val ghostFullPath = remember(ghostPeaks, ghostLoudest) {
        ghostPeaks?.let { buildNormalizedPath(it, ghostLoudest) }
    }
    val crossfading = ghostPeaks != null &&
        ghostFullPath != null &&
        crossfadeProgress != null
    val crossfade = (crossfadeProgress ?: 0f).coerceIn(0f, 1f)
    val ghostProgress = if (ghostDurationMs > 0L) {
        (ghostPositionMs.toFloat() / ghostDurationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val outgoingFadeStart = if (durationMs > 0L && crossfadeWindowMs > 0L) {
        1f - min(1f, crossfadeWindowMs.toFloat() / durationMs)
    } else {
        0f
    }
    val outgoingFadeFraction = if (durationMs > 0L && crossfadeWindowMs > 0L) {
        min(1f, crossfadeWindowMs.toFloat() / durationMs)
    } else {
        1f
    }
    val incomingFadeFraction = if (ghostDurationMs > 0L && crossfadeWindowMs > 0L) {
        min(1f, crossfadeWindowMs.toFloat() / ghostDurationMs)
    } else {
        1f
    }
    val currentMode by rememberUpdatedState(mode)
    val currentViewport by rememberUpdatedState(viewport)
    val currentOnSeek by rememberUpdatedState(onSeek)

    Column(modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(durationMs, crossfading) {
                        if (durationMs > 0 && !crossfading) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val gestureViewport = currentViewport
                                val gestureMode = currentMode
                                fun seek(xPosition: Float) {
                                    val x = (xPosition / size.width).coerceIn(0f, 1f)
                                    val fraction = if (gestureMode == WaveformMode.Full) {
                                        x
                                    } else {
                                        gestureViewport.startFraction +
                                            x * gestureViewport.fraction
                                    }
                                    currentOnSeek(fraction.coerceIn(0f, 1f))
                                }
                                seek(down.position.x)
                                down.consume()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    if (!change.pressed) break
                                    seek(change.position.x)
                                    change.consume()
                                }
                            }
                        }
                    },
            ) {
                val playhead = size.width * if (mode == WaveformMode.Full) progress
                    else viewport.playheadFraction

                if (fullPath == null) {
                    drawLine(
                        color = accent.copy(alpha = 0.18f),
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 1.dp.toPx(),
                    )
                    return@Canvas
                }

                val ghostData = ghostPeaks
                if (crossfading && ghostData != null) {
                    val fullView = mode == WaveformMode.Full
                    val outgoingPath = buildVisiblePath(
                        peaks = peaks,
                        loudest = loudest,
                        startFraction = if (fullView) 0f else outgoingFadeStart,
                        fraction = if (fullView) 1f else outgoingFadeFraction,
                        width = size.width,
                        height = size.height,
                    )
                    val incomingPath = buildVisiblePath(
                        peaks = ghostData,
                        loudest = ghostLoudest,
                        startFraction = 0f,
                        fraction = if (fullView) 1f else incomingFadeFraction,
                        width = size.width,
                        height = size.height,
                    )

                    drawPath(
                        incomingPath,
                        accent.copy(alpha = crossfade * 0.92f),
                    )
                    drawPath(
                        outgoingPath,
                        accent.copy(alpha = (1f - crossfade) * 0.92f),
                    )

                    val outgoingOutline = accent.copy(
                        alpha = 0.20f + (1f - crossfade) * 0.65f,
                    )
                    drawPath(
                        outgoingPath,
                        outgoingOutline,
                        style = Stroke(width = 1.dp.toPx()),
                    )

                    val dashed = PathEffect.dashPathEffect(
                        floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                    )
                    drawPath(
                        incomingPath,
                        Color.White.copy(alpha = 0.18f + (1f - crossfade) * 0.55f),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = dashed),
                    )

                    if (fullView) {
                        drawLine(
                            color = Color.White.copy(alpha = 0.35f),
                            start = Offset(progress * size.width, 0f),
                            end = Offset(progress * size.width, size.height),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                        drawLine(
                            color = accent.copy(alpha = 0.75f),
                            start = Offset(ghostProgress * size.width, 0f),
                            end = Offset(ghostProgress * size.width, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    } else {
                        val sweep = crossfade * size.width
                        drawLine(
                            color = Color.White.copy(alpha = 0.55f),
                            start = Offset(sweep, 0f),
                            end = Offset(sweep, size.height),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }
                    return@Canvas
                }

                val scrolling = mode == WaveformMode.Scroll && windowFraction < 1f
                if (scrolling) {
                    val visiblePath = buildVisiblePath(
                        peaks = peaks,
                        loudest = loudest,
                        startFraction = viewport.startFraction,
                        fraction = viewport.fraction,
                        width = size.width,
                        height = size.height,
                    )
                    drawPath(visiblePath, accent.copy(alpha = 0.22f))
                    clipRect(right = playhead) {
                        drawPath(visiblePath, accent.copy(alpha = 0.95f))
                    }
                } else {
                    withTransform({
                        scale(size.width, size.height, pivot = Offset.Zero)
                    }) {
                        drawPath(fullPath, accent.copy(alpha = 0.22f))
                    }
                    clipRect(right = playhead) {
                        withTransform({
                            scale(size.width, size.height, pivot = Offset.Zero)
                        }) {
                            drawPath(fullPath, accent.copy(alpha = 0.95f))
                        }
                    }
                }

                val startX = rawMarkerX(loopIn, viewport, mode, size.width)
                val endX = rawMarkerX(loopOut, viewport, mode, size.width)
                if (loopIn != null && loopOut != null && loopOut > loopIn &&
                    startX != null && endX != null
                ) {
                    val left = startX.coerceIn(0f, size.width)
                    val right = endX.coerceIn(0f, size.width)
                    if (right > left) {
                        drawRect(
                            color = accent.copy(alpha = if (loopEnabled) 0.20f else 0.08f),
                            topLeft = Offset(left, 0f),
                            size = Size(right - left, size.height),
                        )
                    }
                    val lineColor = accent.copy(alpha = if (loopEnabled) 0.95f else 0.45f)
                    if (startX in 0f..size.width) {
                        drawLine(
                            lineColor,
                            Offset(startX, 0f),
                            Offset(startX, size.height),
                            1.5.dp.toPx(),
                        )
                    }
                    if (endX in 0f..size.width) {
                        drawLine(
                            lineColor,
                            Offset(endX, 0f),
                            Offset(endX, size.height),
                            1.5.dp.toPx(),
                        )
                    }
                } else if (loopIn != null) {
                    markerX(loopIn, viewport, mode, size.width)?.let { inX ->
                        drawLine(
                            color = accent.copy(alpha = 0.9f),
                            start = Offset(inX, 0f),
                            end = Offset(inX, size.height),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }
                }

                markerX(cuePoint, viewport, mode, size.width)?.let { cueX ->
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

                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(playhead, 0f),
                    end = Offset(playhead, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }

            ModePill(
                mode = mode,
                accent = accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                onClick = onToggleMode,
            )

            if (fullPath == null) {
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

        if (mode == WaveformMode.Scroll) {
            MiniMap(
                fullPath = fullPath,
                progress = progress,
                windowStart = if (crossfading) outgoingFadeStart else viewport.startFraction,
                windowFraction = if (crossfading) outgoingFadeFraction else viewport.fraction,
                accent = accent,
                durationMs = durationMs,
                onSeek = onSeek,
            )
        }
    }
}

@Composable
private fun ModePill(
    mode: WaveformMode,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xCC14171D))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (mode == WaveformMode.Full) "FULL" else "SCROLL",
            color = accent,
            fontSize = 5.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun MiniMap(
    fullPath: Path?,
    progress: Float,
    windowStart: Float,
    windowFraction: Float,
    accent: Color,
    durationMs: Long,
    onSeek: (Float) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .pointerInput(durationMs) {
                if (durationMs > 0) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        onSeek((down.position.x / size.width).coerceIn(0f, 1f))
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!change.pressed) break
                            onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                            change.consume()
                        }
                    }
                }
            },
    ) {
        drawRect(Color.Black.copy(alpha = 0.22f))
        if (fullPath == null) return@Canvas
        withTransform({
            scale(size.width, size.height, pivot = Offset.Zero)
        }) {
            drawPath(fullPath, accent.copy(alpha = 0.30f))
        }
        val left = windowStart * size.width
        val right = (windowStart + windowFraction).coerceAtMost(1f) * size.width
        drawRect(
            color = accent.copy(alpha = 0.12f),
            topLeft = Offset(left, 0f),
            size = Size((right - left).coerceAtLeast(1f), size.height),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.6f),
            start = Offset(progress * size.width, 0f),
            end = Offset(progress * size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun markerX(
    fraction: Float?,
    viewport: com.tkleisas.scratchdj.model.WaveformWindow,
    mode: WaveformMode,
    width: Float,
): Float? = rawMarkerX(fraction, viewport, mode, width)?.takeIf { it in 0f..width }

private fun rawMarkerX(
    fraction: Float?,
    viewport: com.tkleisas.scratchdj.model.WaveformWindow,
    mode: WaveformMode,
    width: Float,
): Float? {
    if (fraction == null) return null
    return if (mode == WaveformMode.Full) {
        fraction * width
    } else {
        ((fraction - viewport.startFraction) / viewport.fraction) * width
    }
}

private fun buildNormalizedPath(peaks: FloatArray, loudest: Float): Path? {
    if (peaks.size < 2) return null
    val buckets = peaks.size.coerceAtMost(2048)
    val last = buckets - 1
    val path = Path()
    path.moveTo(0f, 0.5f)
    for (bucket in 0 until buckets) {
        val begin = peaks.size * bucket / buckets
        val end = (peaks.size * (bucket + 1) / buckets).coerceAtLeast(begin + 1)
        var peak = 0f
        for (index in begin until end.coerceAtMost(peaks.size)) {
            if (peaks[index] > peak) peak = peaks[index]
        }
        path.lineTo(
            bucket.toFloat() / last,
            0.5f - (peak / loudest) * 0.46f,
        )
    }
    for (bucket in last downTo 0) {
        val begin = peaks.size * bucket / buckets
        val end = (peaks.size * (bucket + 1) / buckets).coerceAtLeast(begin + 1)
        var peak = 0f
        for (index in begin until end.coerceAtMost(peaks.size)) {
            if (peaks[index] > peak) peak = peaks[index]
        }
        path.lineTo(
            bucket.toFloat() / last,
            0.5f + (peak / loudest) * 0.46f,
        )
    }
    path.close()
    return path
}

private fun buildVisiblePath(
    peaks: FloatArray,
    loudest: Float,
    startFraction: Float,
    fraction: Float,
    width: Float,
    height: Float,
): Path {
    val path = Path()
    val last = peaks.size - 1
    val firstIndex = floor(startFraction * last).toInt().coerceIn(0, last)
    val lastIndex = ceil((startFraction + fraction) * last).toInt().coerceIn(firstIndex, last)
    for (index in firstIndex..lastIndex) {
        val x = ((index.toFloat() / last - startFraction) / fraction) * width
        val y = height * (0.5f - (peaks[index] / loudest) * 0.46f)
        if (index == firstIndex) path.moveTo(x, y) else path.lineTo(x, y)
    }
    for (index in lastIndex downTo firstIndex) {
        val x = ((index.toFloat() / last - startFraction) / fraction) * width
        val y = height * (0.5f + (peaks[index] / loudest) * 0.46f)
        path.lineTo(x, y)
    }
    path.close()
    return path
}
