package com.tkleisas.scratchdj.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkleisas.scratchdj.DjController
import com.tkleisas.scratchdj.model.DeckSide
import com.tkleisas.scratchdj.ui.theme.DeckAccentA
import com.tkleisas.scratchdj.ui.theme.DeckAccentB
import com.tkleisas.scratchdj.ui.theme.ScratchOutline
import com.tkleisas.scratchdj.ui.theme.ScratchSurface

@Composable
fun DjScreen(controller: DjController) {
    var searchOpen by remember { mutableStateOf(false) }
    var queueSide by remember { mutableStateOf<DeckSide?>(null) }
    var eqSide by remember { mutableStateOf<DeckSide?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val message = controller.message.value

    BackHandler(enabled = searchOpen || queueSide != null || eqSide != null) {
        searchOpen = false
        queueSide = null
        eqSide = null
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        controller.message.value = null
        snackbarHostState.showSnackbar(text)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .displayCutoutPadding()
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TopBar(
                    controller = controller,
                    onSearch = {
                        queueSide = null
                        eqSide = null
                        searchOpen = true
                    },
                )
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DeckPanel(
                        controller,
                        DeckSide.A,
                        Modifier.weight(1f).fillMaxHeight(),
                        onOpenEq = {
                            searchOpen = false
                            queueSide = null
                            eqSide = DeckSide.A
                        },
                    )
                    MixerStrip(controller, Modifier.width(136.dp).fillMaxHeight())
                    DeckPanel(
                        controller,
                        DeckSide.B,
                        Modifier.weight(1f).fillMaxHeight(),
                        onOpenEq = {
                            searchOpen = false
                            queueSide = null
                            eqSide = DeckSide.B
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(23.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QueueBarButton(
                        controller = controller,
                        side = DeckSide.A,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            searchOpen = false
                            eqSide = null
                            queueSide = DeckSide.A
                        },
                    )
                    QueueBarButton(
                        controller = controller,
                        side = DeckSide.B,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            searchOpen = false
                            eqSide = null
                            queueSide = DeckSide.B
                        },
                    )
                }
            }
        }

        if (searchOpen) {
            SearchOverlay(controller, onDismiss = { searchOpen = false })
        }
        queueSide?.let { side ->
            QueueOverlay(controller, side, onDismiss = { queueSide = null })
        }
        eqSide?.let { side ->
            EqOverlay(controller, side, onDismiss = { eqSide = null })
        }
    }
}

@Composable
private fun TopBar(controller: DjController, onSearch: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(29.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(DeckAccentA, CircleShape),
        )
        Text(
            text = "SCRATCH",
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
        )
        Text(
            text = "${controller.libraryCount.intValue} TRACKS",
            color = ScratchOutline,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (controller.isSyncing.value) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = DeckAccentA,
            )
        }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(
            onClick = onSearch,
            modifier = Modifier.height(27.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text("SEARCH + QUEUE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(
            onClick = { controller.syncLibrary() },
            modifier = Modifier.height(27.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text("RESCAN", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QueueBarButton(
    controller: DjController,
    side: DeckSide,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = if (side == DeckSide.A) DeckAccentA else DeckAccentB
    val count = controller.queue(side).value.size
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            containerColor = ScratchSurface,
            contentColor = accent,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Text(
            text = "DECK ${side.letter} QUEUE",
            color = accent,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$count",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.weight(1f))
        Text("OPEN", color = ScratchOutline, fontSize = 8.sp)
    }
}
