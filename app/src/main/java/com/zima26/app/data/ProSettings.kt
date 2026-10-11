package com.zima26.app.data

import android.hardware.camera2.CameraMetadata
import java.util.Locale

/** Balance de blancos del modo Pro. */
enum class WhiteBalance(val label: String, val short: String, val awbMode: Int) {
    AUTO("Auto", "A", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Sol", "SOL", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Nublado", "NUB", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    INCANDESCENT("Bombilla", "BOM", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluorescente", "FLU", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT)
}

/** Controles de la barra Pro. */
enum class ProItem(val label: String) { WB("WB"), FOCUS("F"), SHUTTER("S"), ISO("ISO"), EV("EV") }

/**
 * Ajustes manuales de la cámara (modo Pro). null = automático.
 * @param focus 0 = infinito, 1 = lo más cerca posible.
 * @param ev índice de compensación de exposición (solo con exposición automática).
 */
data class ProSettings(
    val wb: WhiteBalance = WhiteBalance.AUTO,
    val focus: Float? = null,
    val shutterNs: Long? = null,
    val iso: Int? = null,
    val ev: Int = 0
) {
    /** La exposición es manual si se fija la velocidad o el ISO. */
    val manualExposure: Boolean get() = shutterNs != null || iso != null

    /** Cualquier ajuste manual desactiva las extensiones Noche/HDR (no los admiten). */
    val anyManual: Boolean get() = wb != WhiteBalance.AUTO || focus != null || manualExposure

    companion object {
        /** Velocidades típicas de obturación, de la más rápida a la más lenta (ns). */
        val SHUTTER_STEPS: List<Long> = listOf(
            8000, 4000, 2000, 1000, 640, 500, 250, 125, 60, 30, 15, 8, 4, 2
        ).map { 1_000_000_000L / it } + listOf(1_000_000_000L)

        val ISO_STEPS = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)

        /** "1/640", "1/2" o "1\"". */
        fun formatShutter(ns: Long): String =
            if (ns >= 1_000_000_000L) String.format(Locale.US, "%.0f\"", ns / 1e9)
            else "1/" + Math.round(1e9 / ns)
    }
}
