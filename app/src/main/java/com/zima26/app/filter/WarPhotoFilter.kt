package com.zima26.app.filter

import android.graphics.Bitmap
import com.zima26.app.data.FilterSettings
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Tamaño de la copia final, necesario para colocar el texto manuscrito. */
data class PhotoLayout(val width: Int, val height: Int)

/** Resultado del filtro: la copia (sin texto) y su geometría. */
class FilteredPhoto(val bitmap: Bitmap, val layout: PhotoLayout)

/**
 * Filtro completo de "fotografía antigua de guerra".
 *
 * Trabaja casi todo el tiempo sobre un único canal en coma flotante (0..1) para
 * ahorrar memoria: una foto de 12 MP ocupa ~48 MB por mapa float.
 * Todas las Mat intermedias se liberan en cuanto dejan de usarse.
 *
 * Los parámetros espaciales del enunciado (radios, sigmas, grosor de rayones, tamaño
 * de motas) están pensados para una foto de ~2400 px de lado; se multiplican por
 * [escala] para que el aspecto sea el mismo a cualquier resolución.
 */
class WarPhotoFilter {

    /**
     * Procesa la imagen. Toma posesión de [src] (CV_8UC3 RGB) y la libera.
     * @param seed semilla aleatoria: rayones, polvo, grano y manchas cambian en cada foto.
     * @param onProgress avance de 0 a 1 para el indicador "Revelando...".
     */
    fun process(
        src: Mat,
        settings: FilterSettings,
        seed: Long,
        onProgress: (Float) -> Unit = {}
    ): FilteredPhoto {
        val rnd = Random(seed)
        // OpenCV usa su propio generador (por hilo) para randn/randu.
        Core.setRNGSeed((seed xor (seed ushr 32)).toInt())

        val wear = settings.wear.factor.toDouble()
        val cols = src.cols()
        val rows = src.rows()
        val escala = max(0.5, max(cols, rows) / REFERENCE_SIZE)

        // ---------------------------------------------------------------- 1. Limpieza
        // Filtro bilateral suave: quita el ruido digital sin perder bordes.
        val clean = Mat()
        Imgproc.bilateralFilter(src, clean, 5, 20.0, 4.0)
        src.release()
        onProgress(0.10f)

        // ------------------------------------------- 2 + 3. Balance de blancos + exposición
        // Ambos pasos son funciones por canal, así que se combinan en una única LUT de 3 canales.
        val mean = Core.mean(clean).`val`
        val avgGray = (mean[0] + mean[1] + mean[2]) / 3.0
        val lutData = ByteArray(256 * 3)
        for (c in 0..2) {
            val factor = 0.72 + 0.28 * avgGray / max(mean[c], 1.0)
            for (v in 0..255) {
                var x = (v / 255.0 * factor).coerceIn(0.0, 1.0)
                x = ((x - 0.05) / 0.95).coerceIn(0.0, 1.0)
                x = 1.0 - (1.0 - x).pow(1.2)
                lutData[v * 3 + c] = (x * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
            }
        }
        Mat(1, 256, CvType.CV_8UC3).also { lut ->
            lut.put(0, 0, lutData)
            Core.LUT(clean, lut, clean)
            lut.release()
        }
        onProgress(0.18f)

        // ------------------------------------------------- 4. Contraste local (CLAHE)
        // Solo sobre la luminancia (canal L de Lab) para no alterar los colores.
        val lab = Mat()
        Imgproc.cvtColor(clean, lab, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>(3)
        Core.split(lab, channels)
        Imgproc.createCLAHE(2.2, Size(8.0, 8.0)).apply(channels[0], channels[0])
        Core.merge(channels, lab)
        channels.forEach { it.release() }
        Imgproc.cvtColor(lab, clean, Imgproc.COLOR_Lab2RGB)
        lab.release()
        onProgress(0.30f)

        // Estilos con color: se guarda la crominancia (a media resolución, basta y ahorra
        // memoria). Al final se combina con la luminancia procesada, que lleva el grano,
        // la viñeta, los rayones y el polvo.
        val style = settings.tone
        val chroma: Mat? = if (style.usesColor) {
            val small = Mat()
            Imgproc.resize(clean, small, Size(cols / 2.0, rows / 2.0), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, small, Imgproc.COLOR_RGB2YCrCb)
            small
        } else null

        // ------------------------------------ 6. Blanco y negro ortocromático (y 5. nitidez)
        // gris = 0.25 R + 0.45 G + 0.30 B: el rojo sale oscuro como en la película de los 40.
        // Los estilos en color usan la luminancia estándar.
        val weights = if (style.usesColor) floatArrayOf(0.299f, 0.587f, 0.114f) else floatArrayOf(0.25f, 0.45f, 0.30f)
        val gray8 = Mat()
        Mat(1, 3, CvType.CV_32F).also { k ->
            k.put(0, 0, weights)
            Core.transform(clean, gray8, k)
            k.release()
        }
        clean.release()
        val g = Mat()
        gray8.convertTo(g, CvType.CV_32F, 1.0 / 255.0)
        gray8.release()

        // 5. Nitidez: la máscara de enfoque y la conversión a gris son operaciones lineales,
        // así que aplicarla sobre el gris da el mismo resultado que sobre RGB pero es 3 veces
        // más rápido. Dos pasos: radio 4 (cantidad 0.6) y radio 1 (cantidad 0.4).
        unsharp(g, 4.0 * escala, 0.6)
        unsharp(g, 1.0 * escala, 0.4)
        // Desenfoque gaussiano ligero propio de la óptica antigua.
        Imgproc.GaussianBlur(g, g, Size(0.0, 0.0), 0.8 * escala)
        clamp01(g)
        onProgress(0.42f)

        // Máscara de brillos fuertes (antes de la curva, que apaga los blancos).
        val bright = Mat()
        Imgproc.threshold(g, bright, BRIGHT_THRESHOLD, 1.0, Imgproc.THRESH_BINARY)

        // ------------------------------------------------------------- 7. Curva
        // g = clamp((g - 0.06) / 0.86); g = 0.6 smoothstep(g) + 0.4 g; g = 0.07 + 0.83 g
        g.convertTo(g, -1, 1.0 / 0.86, -0.06 / 0.86)
        clamp01(g)
        Mat().also { smooth ->
            g.convertTo(smooth, -1, -2.0, 3.0)      // 3 - 2g
            Core.multiply(smooth, g, smooth)          // g (3 - 2g)
            Core.multiply(smooth, g, smooth)          // g² (3 - 2g)
            Core.addWeighted(smooth, 0.6, g, 0.4, 0.0, g)
            smooth.release()
        }
        g.convertTo(g, -1, 0.83, 0.07)
        onProgress(0.50f)

        // ------------------------------------------------------ 8. Brillos con halo
        applyGlow(g, bright, escala)
        bright.release()
        onProgress(0.56f)

        // ------------------------------------------------------ 9. Grano de película
        addGrain(g, wear, escala)
        onProgress(0.66f)

        // -------------------------- 10 + 11. Exposición irregular + viñeta (un solo mapa)
        WearEffects.applyExposureAndVignette(g, rnd, wear)
        onProgress(0.72f)

        // -------------------------------------------------------------- 12. Rayones
        WearEffects.applyScratches(g, rnd, escala, wear)
        onProgress(0.78f)

        // ---------------------------------------------------------------- 13. Polvo
        WearEffects.applyDust(g, rnd, escala, wear)
        Imgproc.GaussianBlur(g, g, Size(0.0, 0.0), 0.6 * escala)
        onProgress(0.83f)

        // ------------------------------------------------------- 14. Virado según el estilo
        val out8 = Mat()
        g.convertTo(out8, CvType.CV_8U, 255.0)      // satura a 0..255
        g.release()
        val photo = if (chroma != null) {
            StyleToning.colorize(out8, chroma, style)
        } else {
            Mat().also {
                Imgproc.cvtColor(out8, it, Imgproc.COLOR_GRAY2RGB)
                StyleToning.toneMono(it, style)
            }
        }
        out8.release()
        chroma?.release()
        onProgress(0.86f)

        // ------------------------------------------- Desgaste físico sobre la superficie
        if (settings.stains) PhysicalWear.applyStains(photo, rnd)
        if (settings.folds) PhysicalWear.applyFolds(photo, rnd, escala)
        onProgress(0.90f)

        // ---------------------------------------------------- 15. Borde quemado y rasgado
        if (settings.showBorder) BurntEdge.apply(photo, rnd, escala)
        // La esquina doblada y la cinta van encima de todo (también del borde quemado)
        val foldedCorner = if (settings.dogEar) PhysicalWear.applyDogEar(photo, rnd, escala) else null
        if (settings.tape) PhysicalWear.applyTape(photo, rnd, escala, foldedCorner)
        onProgress(0.95f)

        // Mat -> Bitmap. El texto (paso 16) se dibuja aparte con Canvas de Android,
        // así se puede editar sin repetir todo el revelado.
        val bitmap = Bitmap.createBitmap(photo.cols(), photo.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(photo, bitmap)
        val layout = PhotoLayout(photo.cols(), photo.rows())
        photo.release()
        onProgress(1f)
        return FilteredPhoto(bitmap, layout)
    }

    /** Máscara de enfoque in situ: g = g + amount * (g - blur(g)). */
    private fun unsharp(g: Mat, sigma: Double, amount: Double) {
        val blur = Mat()
        Imgproc.GaussianBlur(g, blur, Size(0.0, 0.0), sigma)
        Core.addWeighted(g, 1.0 + amount, blur, -amount, 0.0, g)
        blur.release()
    }

    /**
     * Paso 8: conserva los puntos muy brillantes (p. ej. ojos con reflejo del flash) y
     * les añade un halo suave. Los desenfoques grandes se hacen a 1/4 de resolución
     * porque un sigma de ~35 px a resolución completa sería muy lento.
     */
    private fun applyGlow(g: Mat, bright: Mat, escala: Double) {
        if (Core.countNonZero(bright) == 0) return
        val small = Mat()
        Imgproc.resize(bright, small, Size(g.cols() / 4.0, g.rows() / 4.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val near = Mat()
        val far = Mat()
        Imgproc.GaussianBlur(small, near, Size(0.0, 0.0), max(1.0, 6.0 * escala / 4.0))
        Imgproc.GaussianBlur(small, far, Size(0.0, 0.0), max(2.0, 20.0 * escala / 4.0))
        Core.addWeighted(near, 0.6, far, 0.5, 0.0, small)
        near.release(); far.release()
        val glow = Mat()
        Imgproc.resize(small, glow, g.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()

        // Mezcla tipo "trama" (screen): g = g + glow * (1 - g) * 0.9
        val tmp = Mat()
        Core.multiply(glow, g, tmp)
        Core.addWeighted(glow, 0.9, tmp, -0.9, 0.0, tmp)
        Core.add(g, tmp, g)
        tmp.release(); glow.release()

        // Los puntos de brillo extremo se quedan casi en blanco puro.
        val keep = Mat()
        bright.convertTo(keep, -1, 0.97)
        Core.max(g, keep, g)
        keep.release()
    }

    /**
     * Paso 9: grano en dos escalas, más fuerte en las sombras.
     * ruido = N(σ1.2)·0.06 + N·0.025 ;  g += ruido · (0.6 + 0.4 (1 - g))
     */
    private fun addGrain(g: Mat, wear: Double, escala: Double) {
        val coarse = Mat(g.size(), CvType.CV_32F)
        Core.randn(coarse, 0.0, 1.0)
        Imgproc.GaussianBlur(coarse, coarse, Size(0.0, 0.0), 1.2 * escala)
        // Tras desenfocar, la desviación típica baja: se renormaliza a 1.
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(coarse, mean, std)
        val sd = std.toArray().firstOrNull()?.takeIf { it > 1e-6 } ?: 1.0
        mean.release(); std.release()

        val fine = Mat(g.size(), CvType.CV_32F)
        Core.randn(fine, 0.0, 1.0)
        Core.addWeighted(coarse, 0.06 * wear / sd, fine, 0.025 * wear, 0.0, coarse)

        // Reutiliza 'fine' como peso: 0.6 + 0.4 (1 - g) = 1 - 0.4 g
        g.convertTo(fine, -1, -0.4, 1.0)
        Core.multiply(coarse, fine, coarse)
        Core.add(g, coarse, g)
        coarse.release(); fine.release()
    }

    companion object {
        /** Lado largo de referencia para el que están pensados los parámetros espaciales. */
        const val REFERENCE_SIZE = 2400.0

        /** Umbral (en 0..1) a partir del cual un punto se considera "brillo fuerte". */
        private const val BRIGHT_THRESHOLD = 0.96

        /** Recorta una Mat float al rango 0..1 in situ. */
        fun clamp01(m: Mat) {
            Imgproc.threshold(m, m, 1.0, 1.0, Imgproc.THRESH_TRUNC)
            Imgproc.threshold(m, m, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        }

        /** Número aleatorio con distribución normal estándar (Box-Muller). */
        fun Random.gaussian(): Double {
            val u1 = nextDouble().coerceAtLeast(1e-12)
            val u2 = nextDouble()
            return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        }
    }
}
