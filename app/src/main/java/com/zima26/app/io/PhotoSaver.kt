package com.zima26.app.io

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guarda las fotos en Pictures/ZIMA26 y prepara archivos para compartir.
 *
 * - Android 10+ : MediaStore con RELATIVE_PATH, sin ningún permiso de almacenamiento.
 * - Android 8-9 : carpeta pública Pictures/ZIMA26 (requiere WRITE_EXTERNAL_STORAGE,
 *                 declarado con maxSdkVersion=28 en el manifiesto).
 */
object PhotoSaver {

    const val ALBUM = "ZIMA26"
    private const val JPEG_QUALITY = 95
    private val EXIF_FORMAT get() = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    /** Nombre "zima26_AAAAMMDD_HHMMSS.jpg". */
    fun newFileName(suffix: String = ""): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "zima26_$stamp$suffix.jpg"
    }

    /**
     * Comprime [bitmap] a JPEG (calidad 95), escribe la fecha EXIF y lo guarda en la galería.
     * @param exifDate fecha original de la toma; si es null se usa la fecha actual.
     */
    fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String, exifDate: String?): Uri {
        val temp = File(context.cacheDir, "guardar_tmp.jpg")
        try {
            writeJpegWithExif(bitmap, temp, exifDate)
            return publish(context, temp, fileName, exifDate)
        } finally {
            temp.delete()
        }
    }

    /** Copia tal cual un JPEG existente (la foto original sin filtro, con su EXIF). */
    fun saveOriginal(context: Context, source: File, fileName: String, exifDate: String?): Uri =
        publish(context, source, fileName, exifDate)

    /**
     * Escribe la foto final en la caché y devuelve un Uri de FileProvider para
     * compartirla con Intent.ACTION_SEND.
     */
    fun prepareShareFile(context: Context, bitmap: Bitmap, exifDate: String?): Uri {
        val dir = File(context.cacheDir, "compartir").apply { mkdirs() }
        // Limpiar lo compartido anteriormente
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, newFileName())
        writeJpegWithExif(bitmap, file, exifDate)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Copia el APK instalado a la caché y devuelve un Uri de FileProvider para compartirlo. */
    fun prepareAppShareFile(context: Context): Uri {
        val dir = File(context.cacheDir, "compartir").apply { mkdirs() }
        val apk = File(dir, "ZIMA26.apk")
        File(context.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    }

    private fun writeJpegWithExif(bitmap: Bitmap, file: File, exifDate: String?) {
        file.outputStream().buffered().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "Error al comprimir JPEG" }
        }
        val date = exifDate ?: EXIF_FORMAT.format(Date())
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
            setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date)
            setAttribute(ExifInterface.TAG_DATETIME, EXIF_FORMAT.format(Date()))
            setAttribute(ExifInterface.TAG_SOFTWARE, "ZIMA26")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            saveAttributes()
        }
    }

    /** Publica un archivo JPEG en la galería, según la versión de Android. */
    private fun publish(context: Context, source: File, fileName: String, exifDate: String?): Uri {
        val takenMillis = exifDate?.let { runCatching { EXIF_FORMAT.parse(it)?.time }.getOrNull() }
            ?: System.currentTimeMillis()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.DATE_TAKEN, takenMillis)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: error("No se pudo crear la entrada en MediaStore")
            try {
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    ?: error("No se pudo abrir el archivo de destino")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
            dir.mkdirs()
            val dest = File(dir, fileName)
            source.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null)
            Uri.fromFile(dest)
        }
    }
}
