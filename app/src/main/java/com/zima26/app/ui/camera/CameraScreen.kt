package com.zima26.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zima26.app.R
import com.zima26.app.camera.CameraController
import com.zima26.app.camera.gl.VintagePreviewView
import com.zima26.app.data.FlashSetting
import com.zima26.app.ui.components.RoundIconButton
import com.zima26.app.ui.components.ShutterButton
import com.zima26.app.ui.theme.Brass
import com.zima26.app.ui.theme.Carbon
import com.zima26.app.ui.theme.Paper
import com.zima26.app.ui.theme.PaperDim
import com.zima26.app.ui.theme.Sepia
import com.zima26.app.ui.theme.SepiaDark
import com.zima26.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Pantalla de cámara: vista previa con filtro en vivo, disparo, galería y ajustes.
 */
@Composable
fun CameraScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    // Selector de fotos del sistema (no necesita permisos). Una foto se abre en el
    // resultado; varias se revelan en lote y se guardan directamente.
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        viewModel.developBatch(uris)
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val controller = remember { CameraController(context.applicationContext) }
    val scope = rememberCoroutineScope()
    // Destello blanco al disparar
    val flashAlpha = remember { Animatable(0f) }
    val cameraInfo by controller.info.collectAsStateWithLifecycle()
    // Cuenta atrás del temporizador (null = sin cuenta atrás en curso)
    var countdown by remember { mutableStateOf<Int?>(null) }

    // Todas las peticiones de disparo (botón y teclas de volumen) pasan por aquí
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        viewModel.shutterRequests.collect {
            if (countdown != null) return@collect
            val seconds = viewModel.ui.value.timer
            for (n in seconds downTo 1) {
                countdown = n
                delay(1000)
            }
            countdown = null
            scope.launch {
                flashAlpha.snapTo(0.9f)
                flashAlpha.animateTo(0f, tween(350))
            }
            controller.takePicture(viewModel::onPhotoCaptured, viewModel::onCaptureError)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Carbon)
            .safeDrawingPadding()
    ) {
        // ---- Barra superior: flash, título y ajustes
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconButton(
                icon = when (ui.flash) {
                    FlashSetting.AUTO -> R.drawable.ic_flash_auto
                    FlashSetting.ON -> R.drawable.ic_flash_on
                    FlashSetting.OFF -> R.drawable.ic_flash_off
                },
                contentDescription = "Flash: ${ui.flash.label}",
                onClick = viewModel::cycleFlash,
                size = 44.dp
            )
            Spacer(Modifier.width(8.dp))
            Box {
                RoundIconButton(R.drawable.ic_timer, "Temporizador", viewModel::cycleTimer, size = 44.dp)
                if (ui.timer > 0) {
                    Text(
                        "${ui.timer}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = Carbon,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(Brass, RoundedCornerShape(3.dp))
                            .padding(horizontal = 3.dp)
                    )
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ZIMA26", style = MaterialTheme.typography.titleLarge, color = Paper)
                Text("· cámara antigua ·", style = MaterialTheme.typography.labelMedium, color = Sepia)
            }
            RoundIconButton(R.drawable.ic_album, "Álbum", viewModel::openAlbum, size = 44.dp)
            Spacer(Modifier.width(8.dp))
            RoundIconButton(R.drawable.ic_settings, "Ajustes", viewModel::openSettings, size = 44.dp)
        }

        // ---- Visor
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (hasPermission) {
                Viewfinder(viewModel, controller, flashAlpha.value, settings.tone.ordinal, countdown)
                // Panel del control Pro abierto, superpuesto en la parte baja del visor
                val caps = cameraInfo.pro
                val item = ui.proItem
                if (item != null && caps != null) {
                    ProPanel(item, ui.pro, caps, viewModel::updatePro, Modifier.align(Alignment.BottomCenter))
                }
            } else {
                PermissionCard { permissionLauncher.launch(Manifest.permission.CAMERA) }
            }
        }

        // ---- Barra Pro: WB · F · S · ISO · EV
        if (hasPermission) {
            ProBar(ui.pro, cameraInfo.pro, ui.proItem, viewModel::selectProItem, Modifier.padding(top = 6.dp))
        }

        // ---- Barra inferior: galería, disparo, cambiar cámara
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RoundIconButton(R.drawable.ic_gallery, "Revelar fotos de la galería", {
                    pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, enabled = !ui.processing)
                Text("REVELAR", style = MaterialTheme.typography.labelSmall, color = PaperDim, modifier = Modifier.padding(top = 4.dp))
            }
            ShutterButton(
                onClick = viewModel::requestShutter,
                enabled = hasPermission && !ui.processing && countdown == null
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RoundIconButton(R.drawable.ic_switch_camera, "Cambiar cámara", viewModel::toggleLens, enabled = hasPermission)
                Text("GIRAR", style = MaterialTheme.typography.labelSmall, color = PaperDim, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * Visor: vista previa OpenGL con el filtro en vivo, gestos de enfoque/zoom e indicadores.
 */
@Composable
private fun Viewfinder(viewModel: MainViewModel, controller: CameraController, flashAlpha: Float, style: Int, countdown: Int?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val previewView = remember { VintagePreviewView(context) }
    val cameraInfo by controller.info.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Punto de enfoque que se muestra brevemente al tocar
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val focusAlpha = remember { Animatable(0f) }

    previewView.renderer.style = style

    // (Re)enlazar al cambiar de cámara o al entrar/salir de ajustes manuales:
    // las extensiones Noche/HDR no admiten control manual, así que se desactivan.
    val manual = ui.pro.anyManual
    LaunchedEffect(ui.lensFacing, manual) {
        controller.bind(lifecycleOwner, ui.lensFacing, ui.flash, previewView.renderer, allowExtensions = !manual)
        // El valor más reciente (pudo cambiar mientras se enlazaba la cámara)
        controller.applyPro(viewModel.ui.value.pro)
    }
    LaunchedEffect(ui.pro) { controller.applyPro(ui.pro) }
    LaunchedEffect(ui.flash) { controller.setFlash(ui.flash) }

    DisposableEffect(lifecycleOwner) {
        previewView.renderer.onSurfaceLost = { controller.refreshSurface() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> previewView.onResume()
                Lifecycle.Event.ON_PAUSE -> previewView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            previewView.onPause()
            controller.unbind()
        }
    }

    // El visor tiene la misma proporción 3:4 que la foto, sin recortes.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .border(2.dp, SepiaDark)
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Gestos: tocar para enfocar y pellizcar para zoom
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        controller.focus(pos.x, pos.y, size.width.toFloat(), size.height.toFloat())
                        focusPoint = pos
                        scope.launch {
                            focusAlpha.snapTo(1f)
                            focusAlpha.animateTo(0f, tween(1200))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) controller.zoomBy(zoom)
                    }
                }
        )

        // Marcas de esquina del visor, como en un telémetro antiguo
        ViewfinderCorners()

        // Anillo de enfoque
        focusPoint?.let { p ->
            val ring = 64.dp
            val half = with(density) { (ring / 2).toPx() }
            Box(
                Modifier
                    .offset { IntOffset((p.x - half).roundToInt(), (p.y - half).roundToInt()) }
                    .size(ring)
                    .alpha(focusAlpha.value)
                    .border(2.dp, Brass, CircleShape)
            )
        }

        // Indicadores: zoom y extensión activa
        Row(
            Modifier.align(Alignment.BottomCenter).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Tag(String.format(java.util.Locale.US, "%.1fx", cameraInfo.zoomRatio))
            cameraInfo.extension?.let { Tag("Modo $it") }
        }

        // Cuenta atrás del temporizador
        countdown?.let {
            Text(
                "$it",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
                color = Paper.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Box(Modifier.fillMaxSize().alpha(flashAlpha).background(Color.White))
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Paper,
        modifier = Modifier
            .background(Color(0x99000000), RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun ViewfinderCorners() {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(14.dp)) {
        val len = size.minDimension * 0.08f
        val w = 2.dp.toPx()
        val c = Paper.copy(alpha = 0.7f)
        val (W, H) = size.width to size.height
        // Cuatro esquinas en "L"
        listOf(
            Offset(0f, 0f) to Offset(1f, 1f),
            Offset(W, 0f) to Offset(-1f, 1f),
            Offset(0f, H) to Offset(1f, -1f),
            Offset(W, H) to Offset(-1f, -1f)
        ).forEach { (o, d) ->
            drawLine(c, o, Offset(o.x + d.x * len, o.y), w)
            drawLine(c, o, Offset(o.x, o.y + d.y * len), w)
        }
        // Cruz central
        val cx = W / 2
        val cy = H / 2
        val s = len * 0.3f
        drawLine(c, Offset(cx - s, cy), Offset(cx + s, cy), w / 2)
        drawLine(c, Offset(cx, cy - s), Offset(cx, cy + s), w / 2)
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Se necesita permiso para usar la cámara",
            style = MaterialTheme.typography.titleMedium,
            color = Paper,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "También puedes importar una foto de la galería con el botón de la izquierda.",
            style = MaterialTheme.typography.bodyMedium,
            color = PaperDim,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRequest,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Sepia, contentColor = Carbon)
        ) { Text("CONCEDER PERMISO") }
        Spacer(Modifier.width(1.dp))
    }
}
