package com.tkleisas.scratchdj.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.tkleisas.scratchdj.DjController
import com.tkleisas.scratchdj.ui.DjScreen
import com.tkleisas.scratchdj.ui.theme.ScratchTheme

fun main() = application {
    val controller = remember { DjController() }
    LaunchedEffect(Unit) { controller.syncLibrary() }

    Window(
        onCloseRequest = {
            controller.shutdown()
            exitApplication()
        },
        title = "Scratch ${AppInfo.VERSION}",
        icon = painterResource("scratch.png"),
        state = rememberWindowState(width = 1_600.dp, height = 720.dp),
    ) {
        ScratchTheme(theme = controller.theme.value) {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                DjScreen(controller)
            }
        }
    }
}
