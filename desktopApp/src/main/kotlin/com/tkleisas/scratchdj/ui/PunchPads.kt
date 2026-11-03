package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkleisas.scratchdj.DjController
import com.tkleisas.scratchdj.model.FxEffect
import com.tkleisas.scratchdj.model.FxPadMode
import com.tkleisas.scratchdj.model.FxRack
import com.tkleisas.scratchdj.ui.theme.ScratchOutline
import com.tkleisas.scratchdj.ui.theme.ScratchSurfaceRaised

@Composable
fun PunchPadGrid(
    controller: DjController,
    rack: FxRack,
    accent: Color,
    onOpenFx: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val rackState = controller.fxRack(rack)
    val mode = controller.fxState.value.padMode
    val order = rackState.punch.order
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "PUNCH",
                color = ScratchOutline,
                fontSize = 6.5.sp,
                letterSpacing = 1.sp,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = mode.label.uppercase(),
                    color = accent.copy(alpha = 0.7f),
                    fontSize = 6.sp,
                    fontFamily = FontFamily.Monospace,
                )
                if (onOpenFx != null) {
                    Text(
                        text = "FX",
                        color = accent,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onOpenFx),
                    )
                }
            }
        }
        Spacer(Modifier.height(1.dp))
        order.chunked(4).forEach { rowEffects ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rowEffects.forEach { effect ->
                    val node = rackState.punch.effect(effect)
                    PunchPad(
                        controller = controller,
                        rack = rack,
                        effect = effect,
                        enabled = node.enabled,
                        mode = mode,
                        accent = accent,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(4 - rowEffects.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PunchPad(
    controller: DjController,
    rack: FxRack,
    effect: FxEffect,
    enabled: Boolean,
    mode: FxPadMode,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val active = controller.isFxPunchActive(rack, effect)
    val gesture = if (!enabled) {
        Modifier
    } else if (mode == FxPadMode.Hold) {
        Modifier.pointerInput(rack, effect, enabled) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                controller.setFxPunch(rack, effect, true)
                waitForUpOrCancellation()
                controller.setFxPunch(rack, effect, false)
            }
        }
    } else {
        Modifier.clickable { controller.setFxPunch(rack, effect, !active) }
    }

    Box(
        modifier = modifier
            .height(16.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(
                if (active) accent.copy(alpha = 0.9f)
                else ScratchSurfaceRaised.copy(alpha = if (enabled) 1f else 0.35f),
            )
            .then(gesture)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = effect.shortLabel,
            color = when {
                active -> Color(0xFF0A0C0F)
                enabled -> ScratchOutline
                else -> ScratchOutline.copy(alpha = 0.35f)
            },
            fontSize = 6.5.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
