package com.zima26.app.filter

import com.zima26.app.data.PhotoTone
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.pow

/**
 * Paso 14: virado final según el estilo de época.
 *
 * - Monocromos (sepia, ByN, daguerrotipo, cianotipia): una LUT de 3 canales sobre el gris.
 * - Con color (Polaroid, Kodachrome, postal): la luminancia procesada (con grano, viñeta,
 *   rayones y polvo) se combina con la crominancia de la foto original en YCrCb, con la
 *   saturación y el tinte propios de cada película.
 */
object StyleToning {

    /** Vira in situ una imagen gris en RGB (CV_8UC3 con los 3 canales iguales). */
    fun toneMono(rgb: Mat, style: PhotoTone) {
        if (style == PhotoTone.BLANCO_NEGRO) return
        val data = ByteArray(256 * 3)
        for (v in 0..255) {
            val g = v / 255.0
            val (r, gg, b) = when (style) {
                // Sepia: R = g + 0.04, G = 0.93 g + 0.03, B = 0.80 g + 0.02, por (1.05, 0.95, 0.82)
                PhotoTone.SEPIA -> Triple((g + 0.04) * 1.05, (g * 0.93 + 0.03) * 0.95, (g * 0.80 + 0.02) * 0.82)
                // Daguerrotipo: plata fría, con un leve reflejo azulado de espejo
                PhotoTone.DAGUERROTIPO -> Triple(0.03 + 0.88 * g, 0.04 + 0.91 * g, 0.07 + 0.95 * g)
                // Cianotipia: del azul de Prusia profundo al blanco azulado del papel
                PhotoTone.CIANOTIPIA -> {
                    val t = g.pow(0.9)
                    Triple(0.04 + (0.86 - 0.04) * t, 0.13 + (0.93 - 0.13) * t, 0.30 + (0.96 - 0.30) * t)
                }
                else -> Triple(g, g, g)
            }
            data[v * 3] = to8(r)
            data[v * 3 + 1] = to8(gg)
            data[v * 3 + 2] = to8(b)
        }
        val lut = Mat(1, 256, CvType.CV_8UC3)
        lut.put(0, 0, data)
        Core.LUT(rgb, lut, rgb)
        lut.release()
    }

    /**
     * Devuelve una imagen RGB nueva combinando [luma] (CV_8UC1, tamaño final) con la
     * crominancia de [chromaYCrCb] (CV_8UC3 en YCrCb, a cualquier resolución).
     */
    fun colorize(luma: Mat, chromaYCrCb: Mat, style: PhotoTone): Mat {
        // saturación, desplazamiento de Cr (rojo) y de Cb (azul; negativo = más amarillo)
        val (sat, offCr, offCb) = when (style) {
            PhotoTone.POLAROID -> Triple(0.75, 3.0, -12.0)
            PhotoTone.KODACHROME -> Triple(1.35, 6.0, -6.0)
            else -> Triple(0.45, 8.0, -10.0)   // postal coloreada a mano
        }

        // Curva de luminancia propia de cada película
        val y = Mat()
        val lutData = ByteArray(256)
        for (v in 0..255) {
            val g = v / 255.0
            val s = g * g * (3 - 2 * g)
            val out = when (style) {
                PhotoTone.POLAROID -> 0.10 + 0.80 * (0.5 * s + 0.5 * g)   // negros levantados, blancos suaves
                PhotoTone.KODACHROME -> 0.7 * s + 0.3 * g                  // más contraste
                else -> g
            }
            lutData[v] = to8(out)
        }
        Mat(1, 256, CvType.CV_8U).also { lut ->
            lut.put(0, 0, lutData)
            Core.LUT(luma, lut, y)
            lut.release()
        }

        val ch = ArrayList<Mat>(3)
        Core.split(chromaYCrCb, ch)
        ch[0].release()
        val cr = Mat()
        val cb = Mat()
        // c' = 128 + (c - 128)·sat + desplazamiento
        ch[1].convertTo(ch[1], -1, sat, 128.0 * (1 - sat) + offCr)
        ch[2].convertTo(ch[2], -1, sat, 128.0 * (1 - sat) + offCb)
        Imgproc.resize(ch[1], cr, luma.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Imgproc.resize(ch[2], cb, luma.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        ch[1].release(); ch[2].release()

        val ycc = Mat()
        Core.merge(listOf(y, cr, cb), ycc)
        y.release(); cr.release(); cb.release()
        val rgb = Mat()
        Imgproc.cvtColor(ycc, rgb, Imgproc.COLOR_YCrCb2RGB)
        ycc.release()
        return rgb
    }

    private fun to8(x: Double): Byte = (x * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
}
