package com.tkleisas.scratchdj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkleisas.scratchdj.DjController
import com.tkleisas.scratchdj.model.FxChain
import com.tkleisas.scratchdj.model.FxEffect
import com.tkleisas.scratchdj.model.FxPadMode
import com.tkleisas.scratchdj.model.FxParamSpec
import com.tkleisas.scratchdj.model.FxRack
import com.tkleisas.scratchdj.model.FxValueFormat
import com.tkleisas.scratchdj.ui.theme.DeckAccentA
import com.tkleisas.scratchdj.ui.theme.DeckAccentB
import com.tkleisas.scratchdj.ui.theme.MasterAccent
import com.tkleisas.scratchdj.ui.theme.ScratchBackground
import com.tkleisas.scratchdj.ui.theme.ScratchOutline
import com.tkleisas.scratchdj.ui.theme.ScratchSurface
import com.tkleisas.scratchdj.ui.theme.ScratchSurfaceRaised
import kotlin.math.roundToInt

fun rackAccent(rack: FxRack): Color = when (rack) {
    FxRack.A -> DeckAccentA
    FxRack.B -> DeckAccentB
    FxRack.Master -> MasterAccent
}

@Composable
fun FxOverlay(controller: DjController, rack: FxRack, onDismiss: () -> Unit) {
    val accent = rackAccent(rack)
    val rackState = controller.fxRack(rack)
    val padMode = controller.fxState.value.padMode
    var chain by remember(rack) { mutableStateOf(FxChain.Insert) }
    var expanded by remember(rack, chain) { mutableStateOf<FxEffect?>(FxEffect.Filter) }
    val chainState = rackState.chain(chain)

    Surface(modifier = Modifier.fillMaxSize(), color = ScratchBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            TouchBlocker()
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
                            text = if (rack == FxRack.Master) "M" else rack.label,
                            color = Color(0xFF0A0C0F),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                    Text(
                        text = "FX · ${rack.label.uppercase()}",
                        color = accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    FxHeaderButton(
                        label = padMode.label.uppercase(),
                        active = padMode == FxPadMode.Latch,
                        accent = accent,
                        onClick = {
                            controller.setFxPadMode(
                                if (padMode == FxPadMode.Hold) FxPadMode.Latch else FxPadMode.Hold,
                            )
                        },
                    )
                    FxHeaderButton(
                        label = "CLOSE",
                        active = false,
                        accent = accent,
                        onClick = onDismiss,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FxChainTab(
                        label = "INSERT",
                        selected = chain == FxChain.Insert,
                        accent = accent,
                        onClick = { chain = FxChain.Insert },
                    )
                    FxChainTab(
                        label = "PUNCH",
                        selected = chain == FxChain.Punch,
                        accent = accent,
                        onClick = { chain = FxChain.Punch },
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (chain == FxChain.Insert) {
                            "Always-on serial chain · reorder with ↑ ↓"
                        } else {
                            "Pads trigger parallel sends · ${padMode.label.lowercase()}"
                        },
                        color = ScratchOutline,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(top = 6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(chainState.order, key = { it.name }) { effect ->
                        val node = chainState.effect(effect)
                        val isExpanded = expanded == effect
                        FxEffectCard(
                            effect = effect,
                            chain = chain,
                            enabled = node.enabled,
                            send = node.send,
                            params = node.params,
                            expanded = isExpanded,
                            accent = accent,
                            canMoveUp = chainState.order.indexOf(effect) > 0,
                            canMoveDown = chainState.order.indexOf(effect) < chainState.order.lastIndex,
                            onToggleExpand = {
                                expanded = if (isExpanded) null else effect
                            },
                            onEnabled = {
                                controller.setFxEnabled(rack, chain, effect, !node.enabled)
                            },
                            onMove = { delta ->
                                val order = chainState.order.toMutableList()
                                val index = order.indexOf(effect)
                                val target = index + delta
                                if (index >= 0 && target in order.indices) {
                                    val moved = order.removeAt(index)
                                    order.add(target, moved)
                                    controller.setFxOrder(rack, chain, order)
                                }
                            },
                            onSend = { controller.setFxSend(rack, effect, it) },
                            onParam = { index, value ->
                                controller.setFxParam(rack, chain, effect, index, value)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FxEffectCard(
    effect: FxEffect,
    chain: FxChain,
    enabled: Boolean,
    send: Float,
    params: List<Float>,
    expanded: Boolean,
    accent: Color,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleExpand: () -> Unit,
    onEnabled: () -> Unit,
    onMove: (Int) -> Unit,
    onSend: (Float) -> Unit,
    onParam: (Int, Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(ScratchSurface)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FxSmallButton("↑", enabled = canMoveUp, accent = accent) { onMove(-1) }
            FxSmallButton("↓", enabled = canMoveDown, accent = accent) { onMove(1) }
            FxSmallButton(
                if (enabled) "ON" else "OFF",
                enabled = true,
                accent = accent,
                active = enabled,
            ) { onEnabled() }
            Text(
                text = effect.label,
                color = if (enabled) accent else ScratchOutline,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).clickable { onToggleExpand() },
            )
            if (chain == FxChain.Punch) {
                Text(
                    text = "SEND ${(send * 100f).roundToInt()}%",
                    color = ScratchOutline,
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = if (expanded) "−" else "+",
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .width(20.dp)
                    .clickable { onToggleExpand() },
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (chain == FxChain.Punch) {
                    FxSendRow(send, accent, onSend)
                }
                effect.params.forEachIndexed { index, spec ->
                    if (chain == FxChain.Punch && index == effect.mixParamIndex) {
                        return@forEachIndexed
                    }
                    FxParamRow(
                        spec = spec,
                        value = params.getOrNull(index) ?: spec.default,
                        accent = accent,
                        onChange = { onParam(index, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FxSendRow(send: Float, accent: Color, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Send", color = ScratchOutline, fontSize = 8.sp)
            Text(
                text = "${(send * 100f).roundToInt()}%",
                color = accent,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        CompactSlider(
            value = send,
            onValueChange = onChange,
            accent = accent,
            trackHeight = 16.dp,
        )
    }
}

@Composable
private fun FxParamRow(
    spec: FxParamSpec,
    value: Float,
    accent: Color,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(spec.label, color = ScratchOutline, fontSize = 8.sp)
            Text(
                text = spec.format(value),
                color = accent,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        when (spec.format) {
            FxValueFormat.Toggle -> FxChoiceRow(
                options = listOf("OFF", "ON"),
                selected = if (value >= 0.5f) 1 else 0,
                accent = accent,
                onSelect = { onChange(if (it == 1) 1f else 0f) },
            )
            FxValueFormat.Steps -> FxChoiceRow(
                options = spec.steps.orEmpty(),
                selected = value.roundToInt().coerceIn(0, (spec.steps?.lastIndex ?: 0).coerceAtLeast(0)),
                accent = accent,
                onSelect = { onChange(it.toFloat()) },
            )
            else -> CompactSlider(
                value = spec.normalized(value),
                onValueChange = { normalized ->
                    val next = spec.valueAt(normalized)
                    onChange(
                        if (spec.format == FxValueFormat.Bits) {
                            next.roundToInt().toFloat()
                        } else {
                            next
                        },
                    )
                },
                accent = accent,
                trackHeight = 16.dp,
            )
        }
    }
}

@Composable
private fun FxChoiceRow(
    options: List<String>,
    selected: Int,
    accent: Color,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { index, option ->
            Box(
                modifier = Modifier
                    .height(17.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (index == selected) accent.copy(alpha = 0.9f)
                        else ScratchSurfaceRaised,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    color = if (index == selected) Color(0xFF0A0C0F) else ScratchOutline,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun FxChainTab(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) accent.copy(alpha = 0.9f) else ScratchSurfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF0A0C0F) else ScratchOutline,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FxSmallButton(
    label: String,
    enabled: Boolean,
    accent: Color,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(
                when {
                    active -> accent.copy(alpha = 0.9f)
                    enabled -> ScratchSurfaceRaised
                    else -> ScratchSurfaceRaised.copy(alpha = 0.4f)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                active -> Color(0xFF0A0C0F)
                enabled -> ScratchOutline
                else -> ScratchOutline.copy(alpha = 0.35f)
            },
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FxHeaderButton(
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(22.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
    ) {
        Text(
            text = label,
            color = if (active) accent else ScratchOutline,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
