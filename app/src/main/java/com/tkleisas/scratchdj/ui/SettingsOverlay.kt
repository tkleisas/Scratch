package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkleisas.scratchdj.BuildConfig
import com.tkleisas.scratchdj.DjController
import com.tkleisas.scratchdj.model.CrossfadeWaveformStyle
import com.tkleisas.scratchdj.model.DeckSide
import com.tkleisas.scratchdj.model.WaveformMode
import com.tkleisas.scratchdj.model.WaveformWindowMath
import com.tkleisas.scratchdj.model.WaveformWindowUnit
import com.tkleisas.scratchdj.ui.theme.ScratchThemeName
import com.tkleisas.scratchdj.ui.theme.paletteFor
import com.tkleisas.scratchdj.ui.theme.DeckAccentA
import com.tkleisas.scratchdj.ui.theme.DeckAccentB
import com.tkleisas.scratchdj.ui.theme.ScratchBackground
import com.tkleisas.scratchdj.ui.theme.ScratchOnAccent
import com.tkleisas.scratchdj.ui.theme.ScratchOutline
import com.tkleisas.scratchdj.ui.theme.ScratchSurfaceRaised
import kotlin.math.roundToInt

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
                    text = "APPEARANCE",
                    color = DeckAccentA,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(6.dp))
                ThemeSettings(controller)

                Spacer(Modifier.height(18.dp))
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
                    text = "WAVEFORM",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(6.dp))
                WaveformSettings(controller)

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
                            text = "Auto-crossfade duration",
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
                        text = "0 s advances at the end. Above 0 s, the next queued track " +
                            "overlaps and fades in before the current track ends. Autoplay " +
                            "must be on for that deck; NEXT/PREV cancels a transition.",
                        color = ScratchOutline,
                        fontSize = 9.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Crossfade waveform",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CrossfadeStyleChip(
                            label = "SWEEP",
                            selected = controller.crossfadeWaveformStyle.value ==
                                CrossfadeWaveformStyle.Sweep,
                            accent = DeckAccentA,
                            onClick = {
                                controller.setCrossfadeWaveformStyle(
                                    CrossfadeWaveformStyle.Sweep,
                                )
                            },
                        )
                        CrossfadeStyleChip(
                            label = "CENTERED",
                            selected = controller.crossfadeWaveformStyle.value ==
                                CrossfadeWaveformStyle.Centered,
                            accent = DeckAccentA,
                            onClick = {
                                controller.setCrossfadeWaveformStyle(
                                    CrossfadeWaveformStyle.Centered,
                                )
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (controller.crossfadeWaveformStyle.value ==
                            CrossfadeWaveformStyle.Centered
                        ) {
                            "Both waveforms scroll beneath one fixed playhead while they fade."
                        } else {
                            "One playhead sweeps across the outgoing tail and incoming head."
                        },
                        color = ScratchOutline,
                        fontSize = 9.sp,
                    )
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    text = "ABLETON LINK",
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
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ableton Link",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Sync tempo and phase with other Link apps on your local network",
                                color = ScratchOutline,
                                fontSize = 9.sp,
                            )
                        }
                        Switch(
                            checked = controller.linkEnabled.value,
                            onCheckedChange = { controller.setLinkEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ScratchOnAccent,
                                checkedTrackColor = DeckAccentA,
                            ),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (controller.linkEnabled.value) {
                        Text(
                            text = String.format(
                                java.util.Locale.US,
                                "%d peers · %.1f BPM · %.0f%% phase",
                                controller.linkPeers.intValue,
                                controller.linkTempo.floatValue,
                                controller.linkPhase.floatValue * 100f,
                            ),
                            color = DeckAccentA,
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                        Text(
                            text = "SYNC on a deck aligns it to the Link session. Start/stop " +
                                "transport sync is not enabled.",
                            color = ScratchOutline,
                            fontSize = 9.sp,
                        )
                    } else {
                        Text(
                            text = "Off by default. Link uses local-network discovery only; " +
                                "Scratch does not send your music or library off the device.",
                            color = ScratchOutline,
                            fontSize = 9.sp,
                        )
                    }
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
private fun CrossfadeStyleChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(24.dp)
            .background(
                if (selected) accent.copy(alpha = 0.2f) else ScratchSurfaceRaised,
                RoundedCornerShape(5.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) accent else ScratchOutline,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun WaveformSettings(controller: DjController) {
    val scrolling = controller.waveformMode.value == WaveformMode.Scroll
    val beats = controller.waveformWindowUnit.value == WaveformWindowUnit.Beats
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ScratchSurfaceRaised, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Scrolling display",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Keep the playhead centered in a zoomed window",
                    color = ScratchOutline,
                    fontSize = 9.sp,
                )
            }
            Switch(
                checked = scrolling,
                onCheckedChange = {
                    controller.setWaveformMode(
                        if (it) WaveformMode.Scroll else WaveformMode.Full,
                    )
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ScratchOnAccent,
                    checkedTrackColor = DeckAccentA,
                ),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "BPM-synced window",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Size the window in beats instead of seconds",
                    color = ScratchOutline,
                    fontSize = 9.sp,
                )
            }
            Switch(
                checked = beats,
                onCheckedChange = {
                    controller.setWaveformWindowUnit(
                        if (it) WaveformWindowUnit.Beats else WaveformWindowUnit.Seconds,
                    )
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ScratchOnAccent,
                    checkedTrackColor = DeckAccentA,
                ),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Window size",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (beats) {
                    String.format(
                        java.util.Locale.US,
                        "%.0f beats",
                        controller.waveformWindowBeats.floatValue,
                    )
                } else {
                    String.format(
                        java.util.Locale.US,
                        "%.0f s",
                        controller.waveformWindowSeconds.floatValue,
                    )
                },
                color = DeckAccentA,
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        CompactSlider(
            value = if (beats) controller.waveformWindowBeats.floatValue
                else controller.waveformWindowSeconds.floatValue,
            onValueChange = {
                if (beats) {
                    controller.setWaveformWindowBeats(it.roundToInt().toFloat())
                } else {
                    controller.setWaveformWindowSeconds(it.roundToInt().toFloat())
                }
            },
            accent = DeckAccentA,
            valueRange = if (beats) {
                WaveformWindowMath.MIN_BEATS..WaveformWindowMath.MAX_BEATS
            } else {
                WaveformWindowMath.MIN_SECONDS..WaveformWindowMath.MAX_SECONDS
            },
            trackHeight = 26.dp,
        )
        Text(
            text = if (beats) {
                "At 120 BPM this is about " +
                    String.format(
                        java.util.Locale.US,
                        "%.0f s",
                        controller.waveformWindowBeats.floatValue * 60f / 120f,
                    ) + ". Each deck follows its effective BPM."
            } else {
                "The scrolling window shows this many seconds around the playhead."
            },
            color = ScratchOutline,
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun ThemeSettings(controller: DjController) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ScratchThemeName.entries.forEach { theme ->
            val palette = paletteFor(theme)
            val selected = controller.theme.value == theme
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) palette.accentA.copy(alpha = 0.14f)
                        else ScratchSurfaceRaised,
                    )
                    .clickable { controller.setTheme(theme) }
                    .padding(horizontal = 5.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(palette.accentA, CircleShape),
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(palette.accentB, CircleShape),
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = theme.label,
                    color = if (selected) palette.accentA
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
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
