package com.zima26.app.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zima26.app.data.FilterSettings
import com.zima26.app.data.FlashSetting
import com.zima26.app.data.PhotoTone
import com.zima26.app.data.ProItem
import com.zima26.app.data.ProSettings
import com.zima26.app.data.SettingsRepository
import com.zima26.app.data.WearLevel
import com.zima26.app.filter.HandwrittenText
import com.zima26.app.filter.PhotoLayout
import com.zima26.app.filter.WarPhotoFilter
import com.zima26.app.io.ImageLoader
import com.zima26.app.io.PhotoSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Pantallas de la app. */
enum class Screen { CAMERA, RESULT, SETTINGS, ALBUM }

/** Foto revelada en memoria. */
class DevelopedPhoto(
    /** Copia a resolución completa, sin texto (el texto se añade al guardar/compartir). */
    val base: Bitmap,
    val layout: PhotoLayout,
    /** Archivo original de la cámara (null si se importó de la galería). */
    val originalFile: File?,
    /** De dónde salió la foto: permite revelar "otra versión". */
    val sourceUri: Uri,
    val exifDate: String?,
    /** Fecha del revelado ("11/10/2026") para escribirla a mano si se activa. */
    val dateLabel: String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())
)

data class UiState(
    val screen: Screen = Screen.CAMERA,
    val processing: Boolean = false,
    val progress: Float = 0f,
    /** Versión reducida con el texto, para mostrar en pantalla. */
    val preview: Bitmap? = null,
    /** Cambia con cada foto nueva para relanzar la animación de revelado. */
    val photoId: Long = 0,
    val busySaving: Boolean = false,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val flash: FlashSetting = FlashSetting.AUTO,
    /** Ajustes manuales del modo Pro (no se guardan: al reabrir la app vuelven a Auto). */
    val pro: ProSettings = ProSettings(),
    /** Control Pro abierto en el panel, o null si está cerrado. */
    val proItem: ProItem? = null,
    /** Foto original (sin filtro) en tamaño de pantalla, para comparar antes/después. */
    val originalPreview: Bitmap? = null,
    /** Texto del revelado en lote ("Revelando 2 de 7"), o null si es una sola foto. */
    val batchLabel: String? = null,
    /** Temporizador del disparo: 0 (desactivado), 3 o 10 segundos. */
    val timer: Int = 0
)

/** Eventos de un solo uso para la interfaz. */
sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Share(val intent: Intent) : UiEvent
}

/**
 * ViewModel principal (MVVM): mantiene el estado de las pantallas, lanza el revelado en
 * segundo plano (Dispatchers.Default) y gestiona guardar/compartir.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)
    private val filter = WarPhotoFilter()
    private val photoMutex = Mutex()

    val settings: StateFlow<FilterSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, FilterSettings())

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var developed: DevelopedPhoto? = null

    // ------------------------------------------------------------ navegación

    fun openSettings() = _ui.update { it.copy(screen = Screen.SETTINGS) }
    fun openAlbum() = _ui.update { it.copy(screen = Screen.ALBUM, proItem = null) }

    // ------------------------------------------------------------ disparo

    private val _shutter = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Peticiones de disparo (botón en pantalla o botones de volumen). */
    val shutterRequests = _shutter.asSharedFlow()

    fun requestShutter() {
        val ui = _ui.value
        if (ui.screen == Screen.CAMERA && !ui.processing) _shutter.tryEmit(Unit)
    }

    fun cycleTimer() = _ui.update {
        it.copy(timer = when (it.timer) { 0 -> 3; 3 -> 10; else -> 0 })
    }

    fun back() {
        val target = if (_ui.value.screen == Screen.SETTINGS && developed != null) Screen.RESULT else Screen.CAMERA
        if (target == Screen.CAMERA) discardPhoto()
        _ui.update { it.copy(screen = target) }
    }

    /** "Repetir": descarta la foto y vuelve a la cámara. */
    fun retake() {
        discardPhoto()
        _ui.update { it.copy(screen = Screen.CAMERA) }
    }

    // ------------------------------------------------------------ cámara

    fun toggleLens() = _ui.update {
        it.copy(
            // Cada cámara tiene otros límites: los ajustes Pro vuelven a automático
            pro = ProSettings(),
            proItem = null,
            lensFacing = if (it.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
        )
    }

    fun cycleFlash() = _ui.update {
        val next = when (it.flash) {
            FlashSetting.AUTO -> FlashSetting.ON
            FlashSetting.ON -> FlashSetting.OFF
            FlashSetting.OFF -> FlashSetting.AUTO
        }
        it.copy(flash = next)
    }

    /** Abre o cierra el panel de un control Pro. */
    fun selectProItem(item: ProItem) = _ui.update {
        it.copy(proItem = if (it.proItem == item) null else item)
    }

    fun updatePro(transform: (ProSettings) -> ProSettings) = _ui.update { it.copy(pro = transform(it.pro)) }

    fun onCaptureError(e: Exception) {
        Log.e(TAG, "Error de captura", e)
        message("No se pudo tomar la foto: ${e.message}")
    }

    /** Foto recién tomada con la cámara. */
    fun onPhotoCaptured(file: File) = develop(Uri.fromFile(file), file)

    /** Foto elegida en la galería. */
    fun onPhotoImported(uri: Uri) = develop(uri, null)

    // ------------------------------------------------------------ revelado

    /** "Otra versión": vuelve a revelar la misma foto con otra quemadura, rayones y polvo. */
    fun anotherVersion() {
        val photo = developed ?: return
        develop(photo.sourceUri, photo.originalFile)
    }

    private fun develop(uri: Uri, originalFile: File?) {
        if (_ui.value.processing) return
        _ui.update { it.copy(processing = true, progress = 0f, batchLabel = null) }
        viewModelScope.launch {
            try {
                val current = settings.value
                val (photo, preview, original) = withContext(Dispatchers.Default) {
                    photoMutex.withLock {
                        val start = System.currentTimeMillis()
                        val loaded = ImageLoader.load(getApplication(), uri)
                        val original = ImageLoader.previewBitmap(loaded.rgb, PREVIEW_MAX_SIDE)
                        _ui.update { it.copy(progress = 0.05f) }
                        val result = filter.process(loaded.rgb, current, Random.nextLong()) { p ->
                            _ui.update { it.copy(progress = 0.05f + 0.9f * p) }
                        }
                        Log.i(TAG, "Revelado en ${System.currentTimeMillis() - start} ms " +
                            "(${result.layout.width}x${result.layout.height})")
                        val dev = DevelopedPhoto(result.bitmap, result.layout, originalFile, uri, loaded.exifDate)
                        Triple(dev, renderPreview(dev, current), original)
                    }
                }
                replacePhoto(photo)
                _ui.update {
                    it.copy(
                        processing = false, progress = 1f, preview = preview, originalPreview = original,
                        photoId = System.nanoTime(), screen = Screen.RESULT
                    )
                }
            } catch (t: Throwable) {
                // Incluye OutOfMemoryError: mejor avisar que cerrar la app.
                Log.e(TAG, "Error al revelar", t)
                _ui.update { it.copy(processing = false) }
                message("No se pudo revelar la foto")
            }
        }
    }

    /**
     * Revela varias fotos de la galería seguidas y las guarda directamente en
     * Pictures/ZIMA26 (con el texto y la fecha según los ajustes).
     */
    fun developBatch(uris: List<Uri>) {
        if (uris.isEmpty() || _ui.value.processing) return
        if (uris.size == 1) return onPhotoImported(uris.first())
        _ui.update { it.copy(processing = true, progress = 0f) }
        viewModelScope.launch {
            var saved = 0
            val s = settings.value
            withContext(Dispatchers.Default) {
                uris.forEachIndexed { index, uri ->
                    _ui.update { it.copy(batchLabel = "Foto ${index + 1} de ${uris.size}", progress = index.toFloat() / uris.size) }
                    try {
                        photoMutex.withLock {
                            val loaded = ImageLoader.load(getApplication(), uri)
                            val result = filter.process(loaded.rgb, s, Random.nextLong()) { p ->
                                _ui.update { it.copy(progress = (index + p) / uris.size) }
                            }
                            val dev = DevelopedPhoto(result.bitmap, result.layout, null, uri, loaded.exifDate)
                            val finalBmp = HandwrittenText.render(getApplication(), dev.base, dev.layout, textFor(s), dateFor(dev, s))
                            dev.base.recycle()
                            try {
                                // Un sufijo evita nombres repetidos dentro del mismo segundo
                                PhotoSaver.saveBitmap(getApplication(), finalBmp, PhotoSaver.newFileName("_${index + 1}"), loaded.exifDate)
                                saved++
                            } finally {
                                finalBmp.recycle()
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error al revelar en lote: $uri", t)
                    }
                }
            }
            _ui.update { it.copy(processing = false, batchLabel = null) }
            message("$saved de ${uris.size} fotos guardadas en Pictures/${PhotoSaver.ALBUM}")
        }
    }

    private fun renderPreview(photo: DevelopedPhoto, s: FilterSettings): Bitmap =
        HandwrittenText.render(getApplication(), photo.base, photo.layout, textFor(s), dateFor(photo, s), PREVIEW_MAX_SIDE)

    private fun textFor(s: FilterSettings): String? = s.borderText.takeIf { s.showText }

    private fun dateFor(photo: DevelopedPhoto, s: FilterSettings): String? = photo.dateLabel.takeIf { s.showDate }

    /** Vuelve a dibujar la vista previa (p. ej. tras editar el texto). */
    private fun refreshPreview() {
        viewModelScope.launch {
            val newPreview = withContext(Dispatchers.Default) {
                photoMutex.withLock {
                    val photo = developed ?: return@withContext null
                    renderPreview(photo, repo.settings.first())
                }
            } ?: return@launch
            // La vista previa anterior la libera el recolector de basura: Compose podría
            // estar dibujándola todavía, así que no se llama a recycle().
            _ui.update { it.copy(preview = newPreview) }
        }
    }

    private fun replacePhoto(photo: DevelopedPhoto) {
        val old = developed
        developed = photo
        old?.let { recycleLater(it) }
    }

    private fun discardPhoto() {
        val photo = developed ?: return
        developed = null
        _ui.update { it.copy(preview = null, originalPreview = null) }
        recycleLater(photo)
    }

    /** Libera la copia a resolución completa (decenas de MB) cuando nadie la está usando. */
    private fun recycleLater(photo: DevelopedPhoto) {
        viewModelScope.launch(Dispatchers.Default) {
            photoMutex.withLock { photo.base.recycle() }
        }
    }

    // ------------------------------------------------------------ guardar / compartir

    fun save() {
        val photo = developed ?: return
        if (_ui.value.busySaving) return
        _ui.update { it.copy(busySaving = true) }
        viewModelScope.launch {
            try {
                val s = settings.value
                withContext(Dispatchers.IO) {
                    photoMutex.withLock {
                        val name = PhotoSaver.newFileName()
                        val finalBmp = HandwrittenText.render(getApplication(), photo.base, photo.layout, textFor(s), dateFor(photo, s))
                        try {
                            PhotoSaver.saveBitmap(getApplication(), finalBmp, name, photo.exifDate)
                        } finally {
                            finalBmp.recycle()
                        }
                        if (s.saveOriginal && photo.originalFile?.exists() == true) {
                            PhotoSaver.saveOriginal(
                                getApplication(), photo.originalFile,
                                name.replace(".jpg", "_original.jpg"), photo.exifDate
                            )
                        }
                    }
                }
                message("Guardada en Pictures/${PhotoSaver.ALBUM}")
            } catch (t: Throwable) {
                Log.e(TAG, "Error al guardar", t)
                message("No se pudo guardar la foto")
            } finally {
                _ui.update { it.copy(busySaving = false) }
            }
        }
    }

    fun share() {
        val photo = developed ?: return
        if (_ui.value.busySaving) return
        _ui.update { it.copy(busySaving = true) }
        viewModelScope.launch {
            try {
                val s = settings.value
                val uri = withContext(Dispatchers.IO) {
                    photoMutex.withLock {
                        val finalBmp = HandwrittenText.render(getApplication(), photo.base, photo.layout, textFor(s), dateFor(photo, s))
                        try {
                            PhotoSaver.prepareShareFile(getApplication(), finalBmp, photo.exifDate)
                        } finally {
                            finalBmp.recycle()
                        }
                    }
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                _events.send(UiEvent.Share(Intent.createChooser(send, "Compartir foto ZIMA26")))
            } catch (t: Throwable) {
                Log.e(TAG, "Error al compartir", t)
                message("No se pudo compartir la foto")
            } finally {
                _ui.update { it.copy(busySaving = false) }
            }
        }
    }

    /**
     * Comparte el propio APK de ZIMA26 para que otros puedan instalarla
     * (por WhatsApp, Telegram, Bluetooth...).
     */
    fun shareApp() {
        if (_ui.value.busySaving) return
        _ui.update { it.copy(busySaving = true) }
        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { PhotoSaver.prepareAppShareFile(getApplication()) }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "ZIMA26: cámara de fotos antiguas. Instala el archivo ZIMA26.apk")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                _events.send(UiEvent.Share(Intent.createChooser(send, "Compartir ZIMA26")))
            } catch (t: Throwable) {
                Log.e(TAG, "Error al compartir la app", t)
                message("No se pudo compartir la app")
            } finally {
                _ui.update { it.copy(busySaving = false) }
            }
        }
    }

    // ------------------------------------------------------------ ajustes

    /** "Editar texto" desde la pantalla de resultado: actualiza el ajuste y la vista. */
    fun editText(text: String) {
        viewModelScope.launch {
            repo.setBorderText(text)
            if (text.isNotBlank()) repo.setShowText(true)
            refreshPreview()
        }
    }

    fun setBorderText(text: String) = viewModelScope.launch { repo.setBorderText(text); refreshPreview() }
    fun setShowText(show: Boolean) = viewModelScope.launch { repo.setShowText(show); refreshPreview() }
    fun setShowDate(show: Boolean) = viewModelScope.launch { repo.setShowDate(show); refreshPreview() }
    fun setWear(wear: WearLevel) = viewModelScope.launch { repo.setWear(wear) }
    fun setTone(tone: PhotoTone) = viewModelScope.launch { repo.setTone(tone) }
    fun setShowBorder(show: Boolean) = viewModelScope.launch { repo.setShowBorder(show) }
    fun setSaveOriginal(save: Boolean) = viewModelScope.launch { repo.setSaveOriginal(save) }
    fun setFolds(on: Boolean) = viewModelScope.launch { repo.setFolds(on) }
    fun setStains(on: Boolean) = viewModelScope.launch { repo.setStains(on) }
    fun setDogEar(on: Boolean) = viewModelScope.launch { repo.setDogEar(on) }
    fun setTape(on: Boolean) = viewModelScope.launch { repo.setTape(on) }

    private fun message(text: String) {
        _events.trySend(UiEvent.Message(text))
    }

    override fun onCleared() {
        developed?.base?.recycle()
        developed = null
    }

    companion object {
        private const val TAG = "MainViewModel"
        /** Lado largo máximo de la imagen mostrada en pantalla. */
        private const val PREVIEW_MAX_SIDE = 2048
    }
}
