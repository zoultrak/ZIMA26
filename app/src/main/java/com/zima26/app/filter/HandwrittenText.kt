package com.zima26.app.filter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.zima26.app.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Paso 16: texto escrito a mano (por defecto "1943") en la esquina inferior izquierda
 * de la foto, con la fuente libre "Homemade Apple" (Google Fonts, Apache 2.0).
 *
 * El texto se dibuja aparte del filtro para poder cambiarlo ("Editar texto") sin
 * repetir todo el revelado.
 */
object HandwrittenText {

    @Volatile
    private var typeface: Typeface? = null

    private fun font(context: Context): Typeface =
        typeface ?: (ResourcesCompat.getFont(context, R.font.homemade_apple) ?: Typeface.SERIF)
            .also { typeface = it }

    /**
     * Devuelve una copia nueva de [base] con el texto dibujado (abajo a la izquierda) y,
     * opcionalmente, la fecha (abajo a la derecha) con la misma letra manuscrita.
     * @param maxSide si se indica, la copia se reduce para que su lado largo no lo supere
     *                (para mostrarla en pantalla sin cargar la foto completa).
     */
    fun render(
        context: Context,
        base: Bitmap,
        layout: PhotoLayout,
        text: String?,
        date: String? = null,
        maxSide: Int? = null
    ): Bitmap {
        val scale = if (maxSide != null && max(base.width, base.height) > maxSide) {
            maxSide.toFloat() / max(base.width, base.height)
        } else 1f

        val out = if (scale < 1f) {
            val w = (base.width * scale).roundToInt()
            val h = (base.height * scale).roundToInt()
            val scaled = Bitmap.createScaledBitmap(base, w, h, true)
            if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true).also { scaled.recycle() }
        } else {
            base.copy(Bitmap.Config.ARGB_8888, true)
        }

        val hasDate = !date.isNullOrBlank()
        if (!text.isNullOrBlank()) {
            // Si también va la fecha, el texto deja sitio a la derecha
            draw(context, out, text, alignRight = false, sizeFactor = 1f, maxFraction = if (hasDate) 0.55f else 0.85f)
        }
        if (hasDate) draw(context, out, date, alignRight = true, sizeFactor = 0.8f, maxFraction = 0.35f)
        return out
    }

    /**
     * Escribe [text] en una esquina inferior de la foto, en tono crema y por dentro del
     * borde quemado.
     */
    private fun draw(
        context: Context,
        bitmap: Bitmap,
        text: String,
        alignRight: Boolean,
        sizeFactor: Float,
        maxFraction: Float
    ) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(context)
        }

        paint.textSize = bitmap.width * 0.045f * sizeFactor
        paint.color = CREAM_COLOR
        val maxWidth = bitmap.width * maxFraction
        val width = paint.measureText(text)
        if (width > maxWidth) paint.textSize *= maxWidth / width
        val margin = bitmap.width * 0.06f
        val x = if (alignRight) bitmap.width - margin - paint.measureText(text) else margin
        val baseline = bitmap.height - bitmap.width * 0.065f

        canvas.save()
        // Ligera inclinación, como escrito deprisa a pluma.
        canvas.rotate(-2f, x, baseline)
        // Segunda pasada desplazada y tenue: simula la tinta que se corre un poco.
        val ink = paint.color
        paint.alpha = 70
        canvas.drawText(text, x + paint.textSize * 0.015f, baseline + paint.textSize * 0.01f, paint)
        paint.color = ink
        canvas.drawText(text, x, baseline, paint)
        canvas.restore()
    }

    private const val CREAM_COLOR = 0xD8F0E4CC.toInt()
}
