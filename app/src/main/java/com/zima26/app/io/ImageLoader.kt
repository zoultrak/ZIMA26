package com.zima26.app.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * Imagen ya decodificada, girada según su EXIF y lista para el filtro.
 * @param rgb Mat CV_8UC3 en orden RGB. Quien la recibe es responsable de liberarla.
 * @param exifDate fecha de captura original ("yyyy:MM:dd HH:mm:ss") si existía.
 */
class LoadedImage(val rgb: Mat, val exifDate: String?)

/**
 * Decodifica fotos (de la cámara o de la galería) de forma segura en memoria.
 */
object ImageLoader {

    /**
     * Máximo de píxeles con los que trabaja el filtro. 13 MP deja pasar sin cambios las
     * fotos típicas de 12 MP; las de 48-200 MP se reducen (primero con inSampleSize
     * al decodificar y luego con INTER_AREA) para no agotar la memoria y cumplir
     * el objetivo de < 5 s en un teléfono de gama media.
     */
    const val MAX_PIXELS = 13_000_000L

    fun load(context: Context, uri: Uri): LoadedImage {
        val resolver = context.contentResolver

        // 1) Solo leer dimensiones, sin reservar memoria para los píxeles.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        require(w > 0 && h > 0) { "No se pudo leer la imagen" }

        // 2) Elegir inSampleSize (potencia de 2) que deje la imagen todavía >= MAX_PIXELS,
        //    para luego afinar con un redimensionado de calidad.
        var sample = 1
        while ((w / (sample * 2)).toLong() * (h / (sample * 2)) >= MAX_PIXELS) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("No se pudo decodificar la imagen")

        // 3) Bitmap -> Mat RGB y liberar el Bitmap enseguida.
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        bitmap.recycle()
        var rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()

        // 4) Ajuste fino al límite de píxeles.
        val pixels = rgb.cols().toLong() * rgb.rows()
        if (pixels > MAX_PIXELS) {
            val f = sqrt(MAX_PIXELS.toDouble() / pixels)
            val resized = Mat()
            Imgproc.resize(rgb, resized, Size(rgb.cols() * f, rgb.rows() * f), 0.0, 0.0, Imgproc.INTER_AREA)
            rgb.release()
            rgb = resized
        }

        // 5) Leer EXIF: orientación y fecha.
        var orientation = ExifInterface.ORIENTATION_NORMAL
        var date: String? = null
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
                date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            }
        }

        rgb = applyOrientation(rgb, orientation)
        return LoadedImage(rgb, date)
    }

    /** Copia reducida (lado largo <= [maxSide]) de una Mat RGB, como Bitmap para la pantalla. */
    fun previewBitmap(rgb: Mat, maxSide: Int): Bitmap {
        val f = minOf(1.0, maxSide.toDouble() / maxOf(rgb.cols(), rgb.rows()))
        val small = Mat()
        Imgproc.resize(rgb, small, Size(rgb.cols() * f, rgb.rows() * f), 0.0, 0.0, Imgproc.INTER_AREA)
        val bmp = Bitmap.createBitmap(small.cols(), small.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(small, bmp)
        small.release()
        return bmp
    }

    /** Gira/voltea la Mat según la etiqueta EXIF para que quede derecha. */
    private fun applyOrientation(src: Mat, orientation: Int): Mat {
        fun rotate(m: Mat, code: Int): Mat = Mat().also { Core.rotate(m, it, code); m.release() }
        fun flipH(m: Mat): Mat = Mat().also { Core.flip(m, it, 1); m.release() }

        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotate(src, Core.ROTATE_90_CLOCKWISE)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotate(src, Core.ROTATE_180)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotate(src, Core.ROTATE_90_COUNTERCLOCKWISE)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flipH(src)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Mat().also { Core.flip(src, it, 0); src.release() }
            ExifInterface.ORIENTATION_TRANSPOSE -> flipH(rotate(src, Core.ROTATE_90_CLOCKWISE))
            ExifInterface.ORIENTATION_TRANSVERSE -> flipH(rotate(src, Core.ROTATE_90_COUNTERCLOCKWISE))
            else -> src
        }
    }
}
