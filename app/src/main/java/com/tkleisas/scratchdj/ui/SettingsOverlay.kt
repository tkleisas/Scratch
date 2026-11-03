package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkleisas.scratchdj.BuildConfig
import com.tkleisas.scratchdj.DjController
import com.tkleisas.scratchdj.model.DeckSide
import com.tkleisas.scratchdj.ui.theme.DeckAccentA
import com.tkleisas.scratchdj.ui.theme.DeckAccentB
import com.tkleisas.scratchdj.ui.theme.ScratchBackground
import com.tkleisas.scratchdj.ui.theme.ScratchOutline
import com.tkleisas.scratchdj.ui.theme.ScratchSurfaceRaised

@Composable
fun SettingsOverlay(controller: DjController, onDismiss: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = ScratchBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            TouchBlocker()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .displayCutoutPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "SETTINGS",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.height(26.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("CLOSE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "AUTOPLAY",
                    color = DeckAccentA,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(6.dp))

                AutoplayRow(
                    controller = controller,
                    side = DeckSide.A,
                    accent = DeckAccentA,
                    enabled = controller.autoplayA.value,
                    onChange = { controller.setAutoplay(DeckSide.A, it) },
                )
                Spacer(Modifier.height(6.dp))
                AutoplayRow(
                    controller = controller,
                    side = DeckSide.B,
                    accent = DeckAccentB,
                    enabled = controller.autoplayB.value,
                    onChange = { controller.setAutoplay(DeckSide.B, it) },
                )

                Spacer(Modifier.height(18.dp))
                Text(
                    text = "TRANSITIONS",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ScratchSurfaceRaised, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Crossfade duration",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = String.format(
                                java.util.Locale.US,
                                "%.1f s",
                                controller.crossfadeSeconds.floatValue,
                            ),
                            color = DeckAccentA,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                    CompactSlider(
                        value = controller.crossfadeSeconds.floatValue,
                        onValueChange = { controller.setCrossfadeSeconds(it) },
                        accent = DeckAccentA,
                        valueRange = 0f..12f,
                        trackHeight = 26.dp,
                    )
                    Text(
                        text = "Stored now. True overlapping crossfade needs a second playback " +
                            "source per deck and is the next engine milestone.",
                        color = ScratchOutline,
                        fontSize = 9.sp,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Scratch v${BuildConfig.VERSION_NAME}",
                    color = ScratchOutline,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

@Composable
private fun AutoplayRow(
    controller: DjController,
    side: DeckSide,
    accent: Color,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ScratchSurfaceRaised, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(accent, CircleShape),
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
                text = "Autoplay Deck ${side.letter}",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Continue to the next queued track when this track ends",
                color = ScratchOutline,
                fontSize = 9.sp,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF0A0C0F),
                checkedTrackColor = accent,
            ),
        )
    }
}
