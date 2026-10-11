package com.zima26.app.ui.album

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zima26.app.R
import com.zima26.app.io.AlbumPhoto
import com.zima26.app.io.AlbumRepository
import com.zima26.app.ui.components.RoundIconButton
import com.zima26.app.ui.components.VintageActionButton
import com.zima26.app.ui.theme.CarbonLight
import com.zima26.app.ui.theme.Paper
import com.zima26.app.ui.theme.PaperDim
import com.zima26.app.ui.theme.Sepia
import com.zima26.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Negro de las hojas de álbum antiguas. */
private val AlbumPage = Color(0xFF121010)

/**
 * Álbum ZIMA26: las fotos reveladas como en un álbum antiguo de hojas negras con
 * esquineros. Al tocar una se abre a pantalla completa, con compartir y borrar.
 */
@Composable
fun AlbumScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf<List<AlbumPhoto>?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(reloadKey) {
        photos = withContext(Dispatchers.IO) { runCatching { AlbumRepository.load(context) }.getOrDefault(emptyList()) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(AlbumPage)
            .safeDrawingPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconButton(R.drawable.ic_back, "Volver", viewModel::back, size = 44.dp)
            Column(Modifier.padding(start = 16.dp)) {
                Text("ÁLBUM", style = MaterialTheme.typography.titleLarge, color = Paper)
                photos?.let {
                    Text("${it.size} fotos", style = MaterialTheme.typography.labelMedium, color = Sepia)
                }
            }
        }

        val list = photos
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Sepia)
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Aún no hay fotos en el álbum.\nLas que guardes aparecerán aquí.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = PaperDim,
                    textAlign = TextAlign.Center
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(18.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(list, key = { _, p -> p.uri.toString() }) { index, photo ->
                    AlbumThumb(photo, tilt = if (index % 3 == 0) -2f else if (index % 3 == 1) 1.5f else -0.6f) {
                        viewerIndex = index
                    }
                }
            }
        }
    }

    // Visor a pantalla completa
    val list = photos
    val start = viewerIndex
    if (list != null && start != null && start < list.size) {
        PhotoViewer(
            photos = list,
            startIndex = start,
            onClose = { viewerIndex = null },
            onShare = { photo ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, photo.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Compartir foto ZIMA26"))
            },
            onDelete = { photo ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { AlbumRepository.delete(context, photo.uri) }
                    if (!ok) {
                        android.widget.Toast.makeText(
                            context, "No se pudo borrar (bórrala desde la galería)", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    viewerIndex = null
                    reloadKey++
                }
            }
        )
    }
}

/** Miniatura pegada en la hoja con cuatro esquineros. */
@Composable
private fun AlbumThumb(photo: AlbumPhoto, tilt: Float, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, photo.uri) {
        value = withContext(Dispatchers.IO) { AlbumRepository.thumbnail(context, photo.uri, 480)?.asImageBitmap() }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .rotate(tilt)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = photo.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.shadow(6.dp)
                )
            }
            PhotoCorners()
        }
        Text(
            SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(photo.dateTaken)),
            style = MaterialTheme.typography.labelSmall,
            color = PaperDim,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Esquineros de papel negro, como los de los álbumes antiguos. */
@Composable
private fun PhotoCorners() {
    Canvas(Modifier.fillMaxSize()) {
        val s = size.minDimension * 0.16f
        val color = Color(0xFF2B2622)
        val edge = Color(0xFF4A4038)
        fun corner(o: Offset, dx: Float, dy: Float) {
            val p = Path().apply {
                moveTo(o.x, o.y)
                lineTo(o.x + dx * s, o.y)
                lineTo(o.x, o.y + dy * s)
                close()
            }
            drawPath(p, color)
            drawLine(edge, Offset(o.x + dx * s, o.y), Offset(o.x, o.y + dy * s), 1.5f)
        }
        corner(Offset(0f, 0f), 1f, 1f)
        corner(Offset(size.width, 0f), -1f, 1f)
        corner(Offset(0f, size.height), 1f, -1f)
        corner(Offset(size.width, size.height), -1f, -1f)
    }
}

@Composable
private fun PhotoViewer(
    photos: List<AlbumPhoto>,
    startIndex: Int,
    onClose: () -> Unit,
    onShare: (AlbumPhoto) -> Unit,
    onDelete: (AlbumPhoto) -> Unit
) {
    val pager = rememberPagerState(initialPage = startIndex) { photos.size }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(enabled = true, onClick = {})   // no dejar pasar toques al álbum
            .safeDrawingPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconButton(R.drawable.ic_back, "Cerrar", onClose, size = 44.dp)
            Text(
                "${pager.currentPage + 1} / ${photos.size}",
                style = MaterialTheme.typography.titleMedium,
                color = Paper,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
            val context = LocalContext.current
            val photo = photos[page]
            val image by produceState<ImageBitmap?>(null, photo.uri) {
                value = withContext(Dispatchers.IO) { AlbumRepository.screenImage(context, photo.uri, 2048)?.asImageBitmap() }
            }
            Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                image?.let {
                    Image(it, contentDescription = photo.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } ?: CircularProgressIndicator(color = Sepia)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            VintageActionButton(R.drawable.ic_share, "Compartir", { onShare(photos[pager.currentPage]) }, Modifier.weight(1f))
            VintageActionButton(R.drawable.ic_delete, "Borrar", { confirmDelete = true }, Modifier.weight(1f))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = CarbonLight,
            shape = RoundedCornerShape(6.dp),
            title = { Text("¿Borrar esta foto?", color = Paper) },
            text = { Text("Se borrará también de la galería del teléfono.", color = PaperDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(photos[pager.currentPage])
                }) { Text("BORRAR", color = Sepia) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCELAR", color = Paper) } }
        )
    }
}
