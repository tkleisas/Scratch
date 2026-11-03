package com.tkleisas.scratchdj.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.tkleisas.scratchdj.audio.ScratchMath
import com.tkleisas.scratchdj.data.AudioFormats
import com.tkleisas.scratchdj.model.EqNodes

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Scratch Desktop",
        state = rememberWindowState(width = 1_100.dp, height = 680.dp),
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                DesktopScaffold()
            }
        }
    }
}

@Composable
private fun DesktopScaffold() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "SCRATCH",
            fontSize = 52.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 10.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Compose Multiplatform desktop scaffold",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = "${AudioFormats.supportedExtensions.size} supported formats · " +
                "${ScratchMath.RECORD_RPM} RPM platter · ${EqNodes.COUNT}-node EQ",
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Next: shared deck UI, JACK/PipeWire output, FFmpeg decoding.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
