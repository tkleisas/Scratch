package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkleisas.scratchdj.ui.theme.DeckAccentA
import com.tkleisas.scratchdj.ui.theme.ScratchBackground
import com.tkleisas.scratchdj.ui.theme.ScratchBackgroundAlt
import com.tkleisas.scratchdj.ui.theme.ScratchOnAccent
import com.tkleisas.scratchdj.ui.theme.ScratchOutline

@Composable
fun PermissionScreen(
    showSettings: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(ScratchBackground, ScratchBackgroundAlt),
                ),
            )
            .padding(32.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 620.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "SCRATCH",
                color = DeckAccentA,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 8.sp,
            )
            Text(
                text = "VINYL-FIRST DJ DECKS",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Scratch indexes your local audio files so you can search them and load them onto either deck.",
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Audio never leaves your device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeckAccentA,
                    contentColor = ScratchOnAccent,
                ),
            ) {
                Text("ALLOW LIBRARY ACCESS", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            if (showSettings) {
                TextButton(onClick = onOpenSettings) {
                    Text("OPEN SYSTEM SETTINGS", color = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = "WAV, AIFF, MP3, FLAC and OGG are supported. Long tracks are decoded once to a local cache, then memory-mapped for instant scratching.",
                color = ScratchOutline,
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
            )
        }
    }
}
