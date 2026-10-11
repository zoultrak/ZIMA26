package com.zima26.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zima26.app.camera.ProCapabilities
import com.zima26.app.data.ProItem
import com.zima26.app.data.ProSettings
import com.zima26.app.data.WhiteBalance
import com.zima26.app.ui.theme.Brass
import com.zima26.app.ui.theme.Carbon
import com.zima26.app.ui.theme.CarbonLight
import com.zima26.app.ui.theme.Paper
import com.zima26.app.ui.theme.PaperDim
import com.zima26.app.ui.theme.Sepia
import com.zima26.app.ui.theme.SepiaDark
import java.util.Locale
import kotlin.math.roundToInt

/** ¿Este control se puede usar con la cámara actual y los ajustes actuales? */
private fun ProItem.enabled(pro: ProSettings, caps: ProCapabilities?): Boolean = when (this) {
    ProItem.WB -> caps != null
    ProItem.FOCUS -> caps?.manualFocus == true
    ProItem.SHUTTER, ProItem.ISO -> caps?.manualExposure == true
    // Con velocidad o ISO manual, la compensación no tiene efecto (como en la cámara de Xiaomi)
    ProItem.EV -> caps != null && !pro.manualExposure
}

private fun ProItem.value(pro: ProSettings, caps: ProCapabilities?): String = when (this) {
    ProItem.WB -> pro.wb.short
    ProItem.FOCUS -> if (pro.focus == null) "A" else "M"
    ProItem.SHUTTER -> pro.shutterNs?.let { ProSettings.formatShutter(it) } ?: "Auto"
    ProItem.ISO -> pro.iso?.toString() ?: "Auto"
    ProItem.EV -> formatEv(pro.ev, caps?.evStep ?: 1f)
}

private fun formatEv(index: Int, step: Float): String {
    val ev = index * step
    return if (index == 0) "0" else String.format(Locale.US, "%+.1f", ev)
}

/**
 * Barra Pro: WB · F · S · ISO · EV. Tocar un control abre su panel; los valores
 * manuales se muestran en latón y los automáticos en papel.
 */
@Composable
fun ProBar(
    pro: ProSettings,
    caps: ProCapabilities?,
    selected: ProItem?,
    onSelect: (ProItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ProItem.entries.forEach { item ->
            val enabled = item.enabled(pro, caps)
            val manual = when (item) {
                ProItem.WB -> pro.wb != WhiteBalance.AUTO
                ProItem.FOCUS -> pro.focus != null
                ProItem.SHUTTER -> pro.shutterNs != null
                ProItem.ISO -> pro.iso != null
                ProItem.EV -> pro.ev != 0
            }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (item == selected) CarbonLight else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(item) }
                    .alpha(if (enabled) 1f else 0.35f)
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic),
                    color = if (item == selected) Sepia else Paper
                )
                Text(
                    item.value(pro, caps),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (manual) Brass else PaperDim
                )
            }
        }
    }
}

/** Panel del control seleccionado, superpuesto en la parte baja del visor. */
@Composable
fun ProPanel(
    item: ProItem,
    pro: ProSettings,
    caps: ProCapabilities,
    onChange: ((ProSettings) -> ProSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(10.dp)
            .background(Color(0xD91B1A18), RoundedCornerShape(6.dp))
            .border(1.dp, SepiaDark, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        when (item) {
            ProItem.WB -> {
                PanelTitle("Balance de blancos", pro.wb.label)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WhiteBalance.entries.forEach { wb ->
                        Chip(wb.label, wb == pro.wb) { onChange { it.copy(wb = wb) } }
                    }
                }
            }

            ProItem.FOCUS -> {
                PanelTitle("Enfoque", if (pro.focus == null) "Auto" else "Manual")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip("Auto", pro.focus == null) { onChange { it.copy(focus = null) } }
                    Spacer(Modifier.width(10.dp))
                    Text("∞", color = PaperDim)
                    VintageSlider(
                        value = pro.focus ?: 0f,
                        onValueChange = { v -> onChange { it.copy(focus = v) } },
                        range = 0f..1f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text("Cerca", color = PaperDim, fontSize = 12.sp)
                }
            }

            ProItem.SHUTTER -> {
                val range = caps.exposureRange!!
                val steps = ProSettings.SHUTTER_STEPS.filter { it in range.lower..range.upper }
                StepPanel(
                    title = "Velocidad",
                    steps = steps,
                    current = pro.shutterNs,
                    label = { ProSettings.formatShutter(it) },
                    onSet = { v -> onChange { it.copy(shutterNs = v) } }
                )
            }

            ProItem.ISO -> {
                val range = caps.isoRange!!
                val steps = (ProSettings.ISO_STEPS.filter { it in range.lower..range.upper } + range.upper).distinct()
                StepPanel(
                    title = "ISO",
                    steps = steps,
                    current = pro.iso,
                    label = { it.toString() },
                    onSet = { v -> onChange { it.copy(iso = v) } }
                )
            }

            ProItem.EV -> {
                PanelTitle("Compensación de exposición", formatEv(pro.ev, caps.evStep))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip("0", pro.ev == 0) { onChange { it.copy(ev = 0) } }
                    VintageSlider(
                        value = pro.ev.toFloat(),
                        onValueChange = { v -> onChange { it.copy(ev = v.roundToInt()) } },
                        range = caps.evRange.lower.toFloat()..caps.evRange.upper.toFloat(),
                        steps = (caps.evRange.upper - caps.evRange.lower - 1).coerceAtLeast(0),
                        modifier = Modifier.weight(1f).padding(start = 10.dp)
                    )
                }
            }
        }
    }
}

/** Panel con "Auto" y un deslizador por pasos fijos (velocidad, ISO). */
@Composable
private fun <T> StepPanel(
    title: String,
    steps: List<T>,
    current: T?,
    label: (T) -> String,
    onSet: (T?) -> Unit
) {
    PanelTitle(title, current?.let(label) ?: "Auto")
    if (steps.isEmpty()) return
    val index = current?.let { steps.indexOf(it) }?.takeIf { it >= 0 } ?: (steps.size / 2)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Chip("Auto", current == null) { onSet(null) }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            VintageSlider(
                value = index.toFloat(),
                onValueChange = { v -> onSet(steps[v.roundToInt().coerceIn(0, steps.lastIndex)]) },
                range = 0f..steps.lastIndex.toFloat(),
                steps = (steps.size - 2).coerceAtLeast(0)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label(steps.first()), color = PaperDim, fontSize = 11.sp)
                Text(label(steps.last()), color = PaperDim, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PanelTitle(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Sepia, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = Brass)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Paper else CarbonLight)
            .border(1.dp, if (selected) Sepia else SepiaDark, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Carbon else Paper,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun VintageSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0
) {
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        modifier = modifier.height(32.dp),
        colors = SliderDefaults.colors(
            thumbColor = Brass,
            activeTrackColor = Sepia,
            inactiveTrackColor = SepiaDark,
            activeTickColor = Carbon,
            inactiveTickColor = Carbon
        )
    )
}
