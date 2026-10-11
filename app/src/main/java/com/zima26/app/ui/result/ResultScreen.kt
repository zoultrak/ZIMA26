package com.zima26.app.ui.result

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zima26.app.R
import com.zima26.app.ui.components.RoundIconButton
import com.zima26.app.ui.components.VintageActionButton
import com.zima26.app.ui.theme.Carbon
import com.zima26.app.ui.theme.CarbonLight
import com.zima26.app.ui.theme.HandwritingStyle
import com.zima26.app.ui.theme.Paper
import com.zima26.app.ui.theme.Sepia
import com.zima26.app.viewmodel.MainViewModel

/**
 * Pantalla de resultado: la foto revelada con su animación y las acciones
 * Guardar, Compartir, Repetir y Editar texto.
 */
@Composable
fun ResultScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var split by remember { mutableFloatStateOf(0.5f) }

    // Android 9 o anterior: hace falta permiso de escritura para guardar en Pictures/.
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.save()
    }
    val onSave = {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else viewModel.save()
    }

    // Animación de revelado: la imagen aparece desde un blanco lechoso, como en la cubeta.
    val develop = remember(ui.photoId) { Animatable(0f) }
    LaunchedEffect(ui.photoId) {
        develop.animateTo(1f, tween(durationMillis = 3200, easing = FastOutSlowInEasing))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Carbon)
            .safeDrawingPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconButton(R.drawable.ic_back, "Volver", viewModel::retake, size = 44.dp)
            Text(
                "COPIA REVELADA",
                style = MaterialTheme.typography.titleMedium,
                color = Paper,
                modifier = Modifier.weight(1f).padding(start = 16.dp)
            )
        }

        Box(
            Modifier.fillMaxWidth().weight(1f).padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            ui.preview?.let { bmp ->
                val p = develop.value
                // De blanco (p=0) a la imagen real (p=1): out = in·k + 255·(1-k)
                val k = p * p
                val offset = 255f * (1f - k) * 0.92f
                val matrix = ColorMatrix(
                    floatArrayOf(
                        k, 0f, 0f, 0f, offset,
                        0f, k, 0f, 0f, offset,
                        0f, 0f, k, 0f, offset,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                val image = remember(bmp) { bmp.asImageBitmap() }
                val original = ui.originalPreview
                val originalImage = remember(original) { original?.asImageBitmap() }
                Box(
                    Modifier
                        .graphicsLayer {
                            alpha = 0.35f + 0.65f * p.coerceAtMost(1f)
                            rotationZ = if (comparing) 0f else -1.2f
                        }
                        .shadow(10.dp, RoundedCornerShape(2.dp))
                ) {
                    Image(
                        bitmap = image,
                        contentDescription = "Foto revelada",
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.colorMatrix(matrix)
                    )
                    // Antes / después: a la izquierda de la línea se ve la foto original
                    if (comparing && originalImage != null) {
                        Image(
                            bitmap = originalImage,
                            contentDescription = "Foto original",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .matchParentSize()
                                .drawWithContent {
                                    clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
                                }
                        )
                        Box(
                            Modifier
                                .matchParentSize()
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures { change, _ ->
                                        split = (change.position.x / size.width).coerceIn(0f, 1f)
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures { pos -> split = (pos.x / size.width).coerceIn(0f, 1f) }
                                }
                                .drawBehind {
                                    val x = size.width * split
                                    drawLine(Paper, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
                                    drawCircle(Paper, 14.dp.toPx(), Offset(x, size.height / 2))
                                    drawCircle(Carbon, 11.dp.toPx(), Offset(x, size.height / 2))
                                }
                        )
                        Text("ANTES", style = MaterialTheme.typography.labelMedium, color = Paper,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                                .background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                        Text("DESPUÉS", style = MaterialTheme.typography.labelMedium, color = Paper,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                                .background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        }

        // Acciones secundarias: otra versión aleatoria y comparar con el original
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallAction(R.drawable.ic_dice, "Otra versión", Modifier.weight(1f), enabled = !ui.busySaving && !ui.processing) {
                comparing = false
                viewModel.anotherVersion()
            }
            SmallAction(R.drawable.ic_compare, if (comparing) "Ocultar original" else "Comparar", Modifier.weight(1f),
                enabled = ui.originalPreview != null, selected = comparing) {
                comparing = !comparing
                split = 0.5f
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val enabled = !ui.busySaving
            VintageActionButton(R.drawable.ic_save, "Guardar", onSave, Modifier.weight(1f), enabled)
            VintageActionButton(R.drawable.ic_share, "Compartir", viewModel::share, Modifier.weight(1f), enabled)
            VintageActionButton(R.drawable.ic_retake, "Repetir", viewModel::retake, Modifier.weight(1f), enabled)
            VintageActionButton(R.drawable.ic_edit, "Texto", { editing = true }, Modifier.weight(1f), enabled)
        }
    }

    if (editing) {
        EditTextDialog(
            initial = settings.borderText,
            onDismiss = { editing = false },
            onConfirm = {
                viewModel.editText(it)
                editing = false
            }
        )
    }
}

/** Botón secundario estrecho con icono y texto. */
@Composable
private fun SmallAction(
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Sepia else CarbonLight)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(icon), null, tint = if (selected) Carbon else Paper, modifier = Modifier.size(18.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Carbon else Paper,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** Diálogo para cambiar el texto manuscrito del borde. */
@Composable
private fun EditTextDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarbonLight,
        shape = RoundedCornerShape(6.dp),
        title = { Text("Editar texto", color = Paper) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(40) },
                    singleLine = true,
                    textStyle = HandwritingStyle.copy(color = Paper),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Sepia,
                        cursorColor = Sepia
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Se escribirá a mano en la esquina del borde.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text("ACEPTAR", color = Sepia) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR", color = Paper) } }
    )
}
