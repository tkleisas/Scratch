package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkleisas.scratchdj.DjController
import com.tkleisas.scratchdj.audio.DjEngine
import com.tkleisas.scratchdj.model.DeckSide
import com.tkleisas.scratchdj.model.EqNode
import com.tkleisas.scratchdj.model.EqNodes
import com.tkleisas.scratchdj.ui.theme.ScratchBackground
import com.tkleisas.scratchdj.ui.theme.ScratchOutline
import com.tkleisas.scratchdj.ui.theme.ScratchSurfaceRaised
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PRE_COLOR = 0xFF4DD0E1
private const val EQ_CURVE_COLOR = 0xFFF2F4F7
private const val RESPONSE_POINTS = 160
private const val SPECTRUM_BANDS = 48
private const val MAX_EQ_DB = 18f

@Composable
fun EqOverlay(controller: DjController, side: DeckSide, onDismiss: () -> Unit) {
    val state = controller.deck(side).value
    val accent = deckAccent(side)
    var selectedNode by remember { mutableIntStateOf(3) }
    var spectra by remember(side) { mutableStateOf(FloatArray(SPECTRUM_BANDS * 2)) }

    val curve = remember(state.eqNodes) {
        DjEngine.eqResponse(side.index, RESPONSE_POINTS)
    }

    LaunchedEffect(side) {
        while (isActive) {
            spectra = DjEngine.eqSpectra(side.index, SPECTRUM_BANDS)
            delay(33)
        }
    }

    val selected = state.eqNodes.getOrNull(selectedNode) ?: state.eqNodes.first()

    Surface(modifier = Modifier.fillMaxSize(), color = ScratchBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .displayCutoutPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier.size(20.dp).background(accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = side.letter,
                        color = Color(0xFF0A0C0F),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
                Text(
                    text = "DECK ${side.letter} GRAPHIC EQ",
                    color = accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.weight(1f))
                LegendDot(Color(PRE_COLOR), "PRE")
                LegendDot(accent, "POST")
                LegendDot(Color(EQ_CURVE_COLOR), "CURVE")
                TextButton(
                    onClick = { controller.flattenEq(side) },
                    modifier = Modifier.height(26.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("FLAT", color = ScratchOutline, fontSize = 9.sp)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(26.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("CLOSE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(4.dp))
            EqGraph(
                nodes = state.eqNodes,
                curve = curve,
                spectra = spectra,
                selected = selectedNode,
                accent = accent,
                onSelect = { selectedNode = it },
                onNodeChange = { index, node ->
                    controller.setEqNode(side, index, node)
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.width(210.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = EqNodes.typeLabel(selectedNode),
                            color = accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatHz(EqNodes.normToHz(selected.frequency)),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = String.format(Locale.US, "%+.1f dB", selected.gainDb),
                            color = accent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        text = "Drag nodes to shape the curve; tap a node to select it.",
                        color = ScratchOutline,
                        fontSize = 8.sp,
                    )
                }

                if (!EqNodes.isShelf(selectedNode)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Q", color = ScratchOutline, fontSize = 8.sp)
                            Text(
                                text = String.format(Locale.US, "%.2f", selected.q),
                                color = accent,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        CompactSlider(
                            value = selected.q,
                            onValueChange = { q ->
                                val live = controller.deck(side).value.eqNodes[selectedNode]
                                controller.setEqNode(side, selectedNode, live.copy(q = q))
                            },
                            accent = accent,
                            valueRange = 0.3f..4f,
                            trackHeight = 26.dp,
                        )
                    }
                } else {
                    Text(
                        text = "Shelf bands use a fixed Q.",
                        color = ScratchOutline,
                        fontSize = 8.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(3.dp))
        Text(label, color = ScratchOutline, fontSize = 7.sp)
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun EqGraph(
    nodes: List<EqNode>,
    curve: FloatArray,
    spectra: FloatArray,
    selected: Int,
    accent: Color,
    onSelect: (Int) -> Unit,
    onNodeChange: (Int, EqNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val gridFrequencies = remember {
        floatArrayOf(30f, 60f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
    }
    val spectrumColor = Color(PRE_COLOR)
    val curveColor = Color(EQ_CURVE_COLOR)
    val latestNodes by rememberUpdatedState(nodes)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnNodeChange by rememberUpdatedState(onNodeChange)

    Box(
        modifier = modifier
            .background(ScratchSurfaceRaised, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                val leftPadding = 30.dp.toPx()
                val rightPadding = 8.dp.toPx()
                val topPadding = 6.dp.toPx()
                val bottomPadding = 20.dp.toPx()
                val hitRadius = 24.dp.toPx()

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val plotWidth = (size.width - leftPadding - rightPadding).coerceAtLeast(1f)
                    val plotHeight =
                        (size.height - topPadding - bottomPadding).coerceAtLeast(1f)

                    fun nodePosition(node: EqNode): Offset {
                        val x = leftPadding + node.frequency * plotWidth
                        val y = topPadding +
                            ((MAX_EQ_DB - node.gainDb.coerceIn(-MAX_EQ_DB, MAX_EQ_DB)) /
                                (2f * MAX_EQ_DB)) * plotHeight
                        return Offset(x, y)
                    }

                    var dragging = -1
                    var bestDistance = hitRadius
                    latestNodes.forEachIndexed { index, node ->
                        val distance = (nodePosition(node) - down.position).getDistance()
                        if (distance <= bestDistance) {
                            bestDistance = distance
                            dragging = index
                        }
                    }
                    if (dragging < 0) {
                        return@awaitEachGesture
                    }
                    latestOnSelect(dragging)
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        val frequency = ((change.position.x - leftPadding) / plotWidth)
                            .coerceIn(0f, 1f)
                        val gain = (MAX_EQ_DB -
                            ((change.position.y - topPadding) / plotHeight) * (2f * MAX_EQ_DB))
                            .coerceIn(-MAX_EQ_DB, MAX_EQ_DB)
                        val node = latestNodes[dragging]
                        latestOnNodeChange(
                            dragging,
                            node.copy(frequency = frequency, gainDb = gain),
                        )
                        change.consume()
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = 30.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 6.dp.toPx()
            val bottom = size.height - 20.dp.toPx()
            val plotWidth = (right - left).coerceAtLeast(1f)
            val plotHeight = (bottom - top).coerceAtLeast(1f)

            fun xForNormalized(value: Float) = left + value.coerceIn(0f, 1f) * plotWidth
            fun yForDb(value: Float) =
                top + ((MAX_EQ_DB - value.coerceIn(-MAX_EQ_DB, MAX_EQ_DB)) /
                    (2f * MAX_EQ_DB)) * plotHeight

            for (db in listOf(-18f, -12f, -6f, 0f, 6f, 12f, 18f)) {
                val y = yForDb(db)
                drawLine(
                    color = Color.White.copy(alpha = if (db == 0f) 0.18f else 0.07f),
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = if (db == 0f) 1.2.dp.toPx() else 0.7.dp.toPx(),
                )
                if (db == -12f || db == 0f || db == 12f) {
                    val label = if (db > 0f) "+${db.roundToInt()}" else db.roundToInt().toString()
                    drawText(
                        textMeasurer = textMeasurer,
                        text = label,
                        topLeft = Offset(2.dp.toPx(), y - 5.dp.toPx()),
                        style = TextStyle(
                            color = ScratchOutline,
                            fontSize = 7.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }

            for (frequency in gridFrequencies) {
                val x = xForNormalized(EqNodes.hzToNorm(frequency))
                drawLine(
                    color = Color.White.copy(alpha = 0.07f),
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    strokeWidth = 0.7.dp.toPx(),
                )
            }
            for (frequency in listOf(100f, 1_000f, 10_000f)) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = formatHz(frequency),
                    topLeft = Offset(
                        xForNormalized(EqNodes.hzToNorm(frequency)) - 10.dp.toPx(),
                        bottom + 2.dp.toPx(),
                    ),
                    style = TextStyle(
                        color = ScratchOutline,
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }

            val preCount = spectra.size / 2
            if (preCount > 0) {
                val prePath = Path()
                val postPath = Path()
                prePath.moveTo(left, bottom)
                postPath.moveTo(left, bottom)
                for (index in 0 until preCount) {
                    val x = left + index.toFloat() / (preCount - 1).coerceAtLeast(1) * plotWidth
                    val pre = spectra[index].coerceIn(0f, 1f)
                    val post = spectra[preCount + index].coerceIn(0f, 1f)
                    prePath.lineTo(x, bottom - pre * plotHeight)
                    postPath.lineTo(x, bottom - post * plotHeight)
                }
                prePath.lineTo(right, bottom)
                postPath.lineTo(right, bottom)
                prePath.close()
                postPath.close()

                drawPath(prePath, spectrumColor.copy(alpha = 0.10f))
                drawPath(
                    prePath,
                    spectrumColor.copy(alpha = 0.55f),
                    style = Stroke(width = 0.8.dp.toPx(), cap = StrokeCap.Round),
                )
                drawPath(postPath, accent.copy(alpha = 0.14f))
                drawPath(
                    postPath,
                    accent.copy(alpha = 0.75f),
                    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            if (curve.size > 1) {
                val path = Path()
                curve.forEachIndexed { index, db ->
                    val x = left + index.toFloat() / (curve.size - 1) * plotWidth
                    val y = yForDb(db)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path,
                    curveColor.copy(alpha = 0.95f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            nodes.forEachIndexed { index, node ->
                val center = Offset(
                    xForNormalized(node.frequency),
                    yForDb(node.gainDb),
                )
                val selectedNode = index == selected
                drawCircle(
                    color = accent,
                    radius = (if (selectedNode) 8.dp else 6.dp).toPx(),
                    center = center,
                )
                drawCircle(
                    color = if (selectedNode) Color.White else Color.Black.copy(alpha = 0.7f),
                    radius = (if (selectedNode) 8.dp else 6.dp).toPx(),
                    center = center,
                    style = Stroke(width = if (selectedNode) 2.dp.toPx() else 1.dp.toPx()),
                )
                val labelLayout = textMeasurer.measure(
                    text = index.toString(),
                    style = TextStyle(
                        color = Color(0xFF0A0C0F),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = index.toString(),
                    topLeft = Offset(
                        center.x - labelLayout.size.width / 2f,
                        center.y - labelLayout.size.height / 2f,
                    ),
                    style = TextStyle(
                        color = Color(0xFF0A0C0F),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}

private fun formatHz(hz: Float): String = when {
    hz < 1_000f -> "${hz.roundToInt()} Hz"
    hz < 10_000f -> String.format(Locale.US, "%.1f kHz", hz / 1_000f)
    else -> String.format(Locale.US, "%.0f kHz", hz / 1_000f)
}
