package com.zima26.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zima26.app.R
import com.zima26.app.data.PhotoTone
import com.zima26.app.data.WearLevel
import com.zima26.app.ui.components.RoundIconButton
import com.zima26.app.ui.theme.Carbon
import com.zima26.app.ui.theme.CarbonLight
import com.zima26.app.ui.theme.HandwritingStyle
import com.zima26.app.ui.theme.Paper
import com.zima26.app.ui.theme.PaperDim
import com.zima26.app.ui.theme.Sepia
import com.zima26.app.ui.theme.SepiaDark
import com.zima26.app.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla de ajustes: texto del borde, desgaste, tono, borde de papel y copia original.
 */
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val today = remember { SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date()) }
    // Estado local del campo para que escribir sea fluido aunque DataStore sea asíncrono
    var text by remember { mutableStateOf(s.borderText) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Carbon)
            .safeDrawingPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconButton(R.drawable.ic_back, "Volver", viewModel::back, size = 44.dp)
            Text(
                "AJUSTES",
                style = MaterialTheme.typography.titleLarge,
                color = Paper,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Section("Texto del borde")
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.take(40)
                    viewModel.setBorderText(text)
                },
                singleLine = true,
                enabled = s.showText,
                textStyle = HandwritingStyle.copy(color = Paper),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Sepia, cursorColor = Sepia),
                modifier = Modifier.fillMaxWidth()
            )
            SwitchRow("Mostrar texto escrito a mano", s.showText, viewModel::setShowText)
            SwitchRow("Escribir la fecha actual", s.showDate, viewModel::setShowDate)
            // Muestra de cómo queda la fecha, con la misma letra manuscrita
            Text(
                today,
                style = HandwritingStyle.copy(color = if (s.showDate) Paper else PaperDim.copy(alpha = 0.5f)),
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )

            Section("Intensidad del desgaste")
            Choice(
                options = WearLevel.entries.map { it.label },
                selected = s.wear.ordinal,
                onSelect = { viewModel.setWear(WearLevel.entries[it]) }
            )
            Hint("Escala el grano, los rayones, el polvo y las manchas.")

            Section("Estilo de época")
            // Cuadrícula de 2 columnas con los 7 estilos
            PhotoTone.entries.chunked(2).forEach { row ->
                Choice(
                    options = row.map { it.label },
                    selected = row.indexOf(s.tone),
                    onSelect = { viewModel.setTone(row[it]) },
                    slots = 2
                )
                Spacer(Modifier.height(8.dp))
            }

            Section("Desgaste físico")
            SwitchRow("Pliegues (foto doblada)", s.folds, viewModel::setFolds)
            SwitchRow("Manchas de agua o café", s.stains, viewModel::setStains)
            SwitchRow("Esquina doblada", s.dogEar, viewModel::setDogEar)
            SwitchRow("Cinta adhesiva", s.tape, viewModel::setTape)

            Section("Copia")
            SwitchRow("Borde quemado y rasgado", s.showBorder, viewModel::setShowBorder)
            SwitchRow("Guardar también la foto original sin filtro", s.saveOriginal, viewModel::setSaveOriginal)

            Section("ZIMA26")
            Button(
                onClick = viewModel::shareApp,
                enabled = !ui.busySaving,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Sepia, contentColor = Carbon),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("COMPARTIR LA APP")
            }
            Hint("Envía el instalador de ZIMA26 por WhatsApp, Telegram, Bluetooth...")

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = SepiaDark)
            Text(
                "ZIMA26 · Fuentes: Special Elite y Homemade Apple (Apache 2.0)",
                style = MaterialTheme.typography.bodySmall,
                color = PaperDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = Sepia,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp)
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = PaperDim, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Paper, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Carbon,
                checkedTrackColor = Sepia,
                uncheckedThumbColor = PaperDim,
                uncheckedTrackColor = CarbonLight,
                uncheckedBorderColor = SepiaDark
            )
        )
    }
}

/** Selector segmentado con estilo de etiqueta de papel. */
@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit, slots: Int = options.size) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSel) Carbon else Paper,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isSel) Paper else CarbonLight)
                    .border(1.5.dp, if (isSel) Sepia else SepiaDark, RoundedCornerShape(4.dp))
                    .clickable { onSelect(i) }
                    .padding(vertical = 12.dp)
            )
        }
        // Huecos vacíos para que todas las filas tengan las mismas columnas
        repeat(slots - options.size) { Spacer(Modifier.weight(1f)) }
    }
}
