package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    bipolar: Boolean = false,
    trackHeight: Dp = 22.dp,
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .pointerInput(valueRange, bipolar) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun update(positionX: Float) {
                        val fraction = (positionX / size.width).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + fraction * span)
                    }
                    update(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        update(change.position.x)
                        change.consume()
                    }
                }
            },
    ) {
        val centerY = size.height / 2f
        val trackStart = 6.dp.toPx()
        val trackEnd = size.width - 6.dp.toPx()
        val trackWidth = (trackEnd - trackStart).coerceAtLeast(1f)
        val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
        val thumbX = trackStart + fraction * trackWidth
        val centerX = trackStart + trackWidth / 2f

        drawLine(
            color = Color.White.copy(alpha = 0.14f),
            start = Offset(trackStart, centerY),
            end = Offset(trackEnd, centerY),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )

        if (bipolar) {
            drawLine(
                color = accent.copy(alpha = 0.85f),
                start = Offset(centerX, centerY),
                end = Offset(thumbX, centerY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(centerX, centerY - 4.dp.toPx()),
                end = Offset(centerX, centerY + 4.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        } else {
            drawLine(
                color = accent.copy(alpha = 0.85f),
                start = Offset(trackStart, centerY),
                end = Offset(thumbX, centerY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        drawCircle(
            color = accent,
            radius = 6.dp.toPx(),
            center = Offset(thumbX, centerY),
        )
    }
}
