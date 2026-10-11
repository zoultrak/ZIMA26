package com.zima26.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zima26.app.data.FlashSetting
import com.zima26.app.data.ProSettings
import com.zima26.app.data.WhiteBalance
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Límites del sensor para el modo Pro (null si la cámara no permite control manual). */
data class ProCapabilities(
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    /** Distancia mínima de enfoque en dioptrías (0 = foco fijo). */
    val minFocusDiopters: Float,
    val evRange: Range<Int>,
    /** Valor en EV de cada paso del índice de compensación. */
    val evStep: Float
) {
    val manualExposure: Boolean get() = isoRange != null && exposureRange != null
    val manualFocus: Boolean get() = minFocusDiopters > 0f
}

/** Información de la cámara que la interfaz necesita mostrar. */
data class CameraInfoState(
    val zoomRatio: Float = 1f,
    val extension: String? = null,
    val hasFrontAndBack: Boolean = true,
    val pro: ProCapabilities? = null
)

/**
 * Envoltorio de CameraX: enlaza la vista previa (renderizada con OpenGL) y la captura
 * en máxima calidad, gestiona extensiones Night/HDR, flash, enfoque, zoom y el modo Pro
 * (balance de blancos, enfoque, velocidad, ISO y EV manuales mediante Camera2 interop).
 */
@SuppressLint("UnsafeOptInUsageError")
class CameraController(private val context: Context) {

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null

    // Últimos valores de exposición calculados por el modo automático. Sirven para que,
    // al fijar solo la velocidad o solo el ISO, el otro se ajuste y la foto no salga
    // ni más clara ni más oscura (prioridad a velocidad / a ISO).
    @Volatile private var autoIso = 100
    @Volatile private var autoExposureNs = 16_666_666L
    @Volatile private var manualExposureActive = false

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (manualExposureActive) return
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { autoIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { autoExposureNs = it }
        }
    }

    private val _info = MutableStateFlow(CameraInfoState())
    val info: StateFlow<CameraInfoState> = _info.asStateFlow()

    /**
     * La actividad está fijada en vertical, así que la rotación de la foto se calcula
     * con el sensor de orientación para que el EXIF sea correcto al girar el teléfono.
     */
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val rotation = when (orientation) {
                in 45..134 -> Surface.ROTATION_270
                in 135..224 -> Surface.ROTATION_180
                in 225..314 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            imageCapture?.targetRotation = rotation
        }
    }

    /**
     * Enlaza la cámara al ciclo de vida. Intenta, por orden:
     * extensión + alta resolución -> extensión -> alta resolución -> configuración básica.
     */
    suspend fun bind(
        owner: LifecycleOwner,
        lensFacing: Int,
        flash: FlashSetting,
        surfaceProvider: Preview.SurfaceProvider,
        allowExtensions: Boolean
    ) {
        val provider = provider ?: ProcessCameraProvider.getInstance(context).await().also { provider = it }
        val ext = extensions ?: runCatching {
            ExtensionsManager.getInstanceAsync(context, provider).await()
        }.onFailure { Log.w(TAG, "Extensiones no disponibles", it) }.getOrNull().also { extensions = it }

        val back = CameraSelector.DEFAULT_BACK_CAMERA
        val front = CameraSelector.DEFAULT_FRONT_CAMERA
        val hasBack = provider.hasCamera(back)
        val hasFront = provider.hasCamera(front)
        val baseSelector = when {
            lensFacing == CameraSelector.LENS_FACING_FRONT && hasFront -> front
            hasBack -> back
            else -> front
        }

        // Extensión Night (o HDR) si el dispositivo la ofrece: mejora mucho la poca luz.
        var extSelector: CameraSelector? = null
        var extName: String? = null
        if (ext != null && allowExtensions) {
            for ((mode, name) in listOf(ExtensionMode.NIGHT to "Noche", ExtensionMode.HDR to "HDR")) {
                if (ext.isExtensionAvailable(baseSelector, mode)) {
                    extSelector = ext.getExtensionEnabledCameraSelector(baseSelector, mode)
                    extName = name
                    break
                }
            }
        }

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
        // Lee de cada fotograma la exposición que elige el modo automático
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(resultCallback)
        val previewUseCase = previewBuilder.build()
        previewUseCase.setSurfaceProvider(surfaceProvider)

        provider.unbindAll()
        val attempts = buildList {
            if (extSelector != null) {
                add(Triple(extSelector, true, extName))
                add(Triple(extSelector, false, extName))
            }
            add(Triple(baseSelector, true, null))
            add(Triple(baseSelector, false, null))
        }
        var bound: Camera? = null
        var usedExt: String? = null
        for ((selector, highRes, name) in attempts) {
            val capture = buildImageCapture(flash, highRes)
            try {
                bound = provider.bindToLifecycle(owner, selector, previewUseCase, capture)
                imageCapture = capture
                usedExt = name
                break
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo enlazar (ext=$name, altaRes=$highRes)", e)
                provider.unbindAll()
            }
        }

        camera = bound
        preview = previewUseCase
        this.surfaceProvider = surfaceProvider
        _info.value = CameraInfoState(
            zoomRatio = bound?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f,
            extension = usedExt,
            hasFrontAndBack = hasBack && hasFront,
            pro = bound?.let { readCapabilities(it) }
        )
        orientationListener.enable()
    }

    /**
     * Captura en máxima calidad: CAPTURE_MODE_MAXIMIZE_QUALITY, JPEG calidad 100,
     * relación 4:3 y la resolución más alta disponible del sensor.
     */
    private fun buildImageCapture(flash: FlashSetting, highRes: Boolean): ImageCapture {
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .apply {
                // Permite usar los modos de 48/50/200 MP aunque bajen la velocidad de captura.
                if (highRes) setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            }
            .build()
        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(selector)
            .setJpegQuality(100)
            .setFlashMode(flash.toCameraX())
            .build()
    }

    private fun readCapabilities(cam: Camera): ProCapabilities {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val exposure = cam.cameraInfo.exposureState
        return ProCapabilities(
            isoRange = if (manualSensor) info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) else null,
            exposureRange = if (manualSensor) info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null,
            minFocusDiopters = if (manualSensor) info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f else 0f,
            evRange = exposure.exposureCompensationRange,
            evStep = exposure.exposureCompensationStep.toFloat()
        )
    }

    /**
     * Aplica los ajustes del modo Pro. Si solo se fija la velocidad o solo el ISO, el otro
     * valor se calcula a partir de la última exposición automática para mantener el brillo.
     */
    fun applyPro(p: ProSettings) {
        val cam = camera ?: return
        val caps = _info.value.pro ?: return
        val builder = CaptureRequestOptions.Builder()

        if (p.wb != WhiteBalance.AUTO) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, p.wb.awbMode)
        }
        if (p.focus != null && caps.manualFocus) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, p.focus * caps.minFocusDiopters)
        }

        val manualExposure = p.manualExposure && caps.manualExposure
        if (manualExposure) {
            val isoRange = caps.isoRange!!
            val expRange = caps.exposureRange!!
            // Producto ISO·tiempo que daba la exposición correcta en automático
            val target = autoIso.toDouble() * autoExposureNs
            val exposureNs = (p.shutterNs ?: (target / (p.iso ?: autoIso)).toLong())
                .coerceIn(expRange.lower, minOf(expRange.upper, 1_000_000_000L))
            val iso = (p.iso ?: (target / exposureNs).toInt()).coerceIn(isoRange.lower, isoRange.upper)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposureNs, 33_333_333L))
        }
        manualExposureActive = manualExposure

        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(builder.build())
        // La compensación de exposición solo tiene sentido con exposición automática
        val ev = if (manualExposure) 0 else p.ev.coerceIn(caps.evRange.lower, caps.evRange.upper)
        cam.cameraControl.setExposureCompensationIndex(ev)
    }

    /** Vuelve a pedir una superficie de vista previa (si el contexto GL se recreó). */
    fun refreshSurface() {
        val p = preview ?: return
        surfaceProvider?.let { p.setSurfaceProvider(it) }
    }

    fun setFlash(flash: FlashSetting) {
        imageCapture?.flashMode = flash.toCameraX()
    }

    /** Toma la foto y la guarda (JPEG con EXIF de orientación y fecha) en la caché. */
    fun takePicture(onSaved: (File) -> Unit, onError: (Exception) -> Unit) {
        val capture = imageCapture ?: return onError(IllegalStateException("La cámara no está lista"))
        val dir = File(context.cacheDir, "capturas").apply { mkdirs() }
        // Solo se conserva la última captura
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "captura_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved(file)
                override fun onError(exception: ImageCaptureException) = onError(exception)
            }
        )
    }

    /**
     * Tocar para enfocar. [x], [y] son coordenadas dentro de la vista previa de tamaño
     * [width] x [height] (la vista muestra el encuadre completo 3:4, sin recortes).
     */
    fun focus(x: Float, y: Float, width: Float, height: Float) {
        val cam = camera ?: return
        val display = ContextCompat.getDisplayOrDefault(context)
        val factory = DisplayOrientedMeteringPointFactory(display, cam.cameraInfo, width, height)
        val action = FocusMeteringAction.Builder(
            factory.createPoint(x, y),
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    /** Pellizcar para hacer zoom: multiplica el zoom actual por [scale]. */
    fun zoomBy(scale: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        val ratio = (state.zoomRatio * scale).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        cam.cameraControl.setZoomRatio(ratio)
        _info.value = _info.value.copy(zoomRatio = ratio)
    }

    fun unbind() {
        orientationListener.disable()
        provider?.unbindAll()
        camera = null
        imageCapture = null
        preview = null
    }

    private fun FlashSetting.toCameraX(): Int = when (this) {
        FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
        FlashSetting.OFF -> ImageCapture.FLASH_MODE_OFF
    }

    companion object {
        private const val TAG = "CameraController"
    }
}

/** Espera un ListenableFuture sin dependencias extra. */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: Exception) {
            cont.resumeWithException(e.cause ?: e)
        }
    }, Runnable::run)
}
