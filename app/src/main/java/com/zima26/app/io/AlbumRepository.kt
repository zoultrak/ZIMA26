package com.zima26.app.io

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.exifinterface.media.ExifInterface

/** Una foto del álbum (guardada por ZIMA26 en Pictures/ZIMA26). */
data class AlbumPhoto(val uri: Uri, val name: String, val dateTaken: Long)

/**
 * Lee las fotos reveladas de Pictures/ZIMA26 mediante MediaStore.
 * En Android 10+ una app puede ver sin permisos las imágenes que ella misma guardó.
 */
object AlbumRepository {

    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Fotos del álbum, de la más reciente a la más antigua. */
    fun load(context: Context): List<AlbumPhoto> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN
        )
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to arrayOf("%Pictures/${PhotoSaver.ALBUM}%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/Pictures/${PhotoSaver.ALBUM}/%")
        }
        val photos = ArrayList<AlbumPhoto>()
        context.contentResolver.query(
            collection, projection, selection, args, "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                photos += AlbumPhoto(
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    name = c.getString(nameCol) ?: "",
                    dateTaken = c.getLong(dateCol)
                )
            }
        }
        return photos
    }

    /** Miniatura (con caché) para la cuadrícula del álbum. */
    fun thumbnail(context: Context, uri: Uri, size: Int): Bitmap? {
        thumbCache.get(uri.toString())?.let { return it }
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(size, size), null)
            } else {
                decodeSampled(context, uri, size)
            }
        }.getOrNull() ?: return null
        thumbCache.put(uri.toString(), bmp)
        return bmp
    }

    /** Foto a tamaño de pantalla (lado largo cerca de [maxSide]), girada según su EXIF. */
    fun screenImage(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        decodeSampled(context, uri, maxSide)
    }.getOrNull()

    /** Borra una foto. Devuelve false si el sistema no lo permite (p. ej. tras reinstalar la app). */
    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        thumbCache.remove(uri.toString())
        context.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    private fun decodeSampled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        // Las fotos de ZIMA26 se guardan derechas, pero se respeta el EXIF por si acaso
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bmp
        }
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }
}
