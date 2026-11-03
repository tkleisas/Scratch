package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkleisas.scratchdj.DjController
import com.tkleisas.scratchdj.model.FxRack
import kotlin.math.roundToInt
import com.tkleisas.scratchdj.ui.theme.DeckAccentA
import com.tkleisas.scratchdj.ui.theme.DeckAccentB
import com.tkleisas.scratchdj.ui.theme.MasterAccent
import com.tkleisas.scratchdj.ui.theme.ScratchOutline
import com.tkleisas.scratchdj.ui.theme.ScratchSurface

@Composable
fun MixerStrip(
    controller: DjController,
    modifier: Modifier = Modifier,
    onOpenFx: () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ScratchSurface),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MIX",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onOpenFx,
                    modifier = Modifier.height(18.dp),
                    contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "FX",
                        color = MasterAccent,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                VolumeRow(
                    label = "A",
                    value = controller.deckA.value.volume,
                    accent = DeckAccentA,
                    onValueChange = { controller.setVolume(com.tkleisas.scratchdj.model.DeckSide.A, it) },
                )
                VolumeRow(
                    label = "B",
                    value = controller.deckB.value.volume,
                    accent = DeckAccentB,
                    onValueChange = { controller.setVolume(com.tkleisas.scratchdj.model.DeckSide.B, it) },
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "CROSSFADER",
                    color = ScratchOutline,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(2.dp))
                CompactSlider(
                    value = controller.crossfader.floatValue,
                    onValueChange = { controller.setCrossfader(it) },
                    accent = DeckAccentA,
                    modifier = Modifier.fillMaxWidth(),
                    trackHeight = 18.dp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "A",
                        color = DeckAccentA,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = "B",
                        color = DeckAccentB,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }

            PunchPadGrid(
                controller = controller,
                rack = FxRack.Master,
                accent = MasterAccent,
                modifier = Modifier.fillMaxWidth(),
            )

            SpectrumView(
                bands = controller.spectrum.value,
                modifier = Modifier.fillMaxWidth().height(24.dp),
            )
        }
    }
}

@Composable
private fun VolumeRow(
    label: String,
    value: Float,
    accent: Color,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "VOL $label",
            color = accent,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(26.dp),
        )
        CompactSlider(
            value = value,
            onValueChange = onValueChange,
            accent = accent,
            modifier = Modifier.weight(1f),
            valueRange = 0f..1.5f,
            trackHeight = 16.dp,
        )
        Text(
            text = "${(value * 100f).roundToInt()}%",
            color = ScratchOutline,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.width(28.dp),
        )
    }
}

@Composable
private fun SpectrumView(bands: FloatArray, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (bands.isEmpty()) return@Canvas
        val gap = 1.dp.toPx()
        val barWidth = ((size.width - gap * (bands.size - 1)) / bands.size)
            .coerceAtLeast(1f)
        for (index in bands.indices) {
            val level = bands[index].coerceIn(0f, 1f)
            val barHeight = (level * size.height).coerceAtLeast(1f)
            val x = index * (barWidth + gap)
            val color = lerp(
                DeckAccentA,
                DeckAccentB,
                index.toFloat() / (bands.size - 1).coerceAtLeast(1),
            )
            drawRoundRect(
                color = color.copy(alpha = 0.85f),
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
