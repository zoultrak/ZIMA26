package com.zima26.app.filter

import org.opencv.core.Mat
import org.opencv.core.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Desgaste físico de la copia, sobre la imagen final (CV_8UC3 RGB, in situ):
 * manchas de agua/café, pliegues de foto doblada, esquina doblada y cinta adhesiva.
 *
 * Cada efecto solo recorre la zona que afecta (un recuadro o una franja), así que el
 * coste es pequeño aunque la foto sea grande.
 */
object PhysicalWear {

    private val OUTSIDE = doubleArrayOf(12.0, 10.0, 9.0)
    /** Dorso de la foto: papel envejecido. */
    private val PAPER_BACK = doubleArrayOf(198.0, 182.0, 150.0)

    // ------------------------------------------------------------------ manchas

    /** 1-2 manchas de café o agua: cerco marrón irregular y un leve tinte en el interior. */
    fun applyStains(photo: Mat, rnd: Random) {
        val cols = photo.cols()
        val rows = photo.rows()
        val minDim = min(cols, rows)
        repeat(1 + rnd.nextInt(2)) {
            val r = minDim * rnd.nextDouble(0.08, 0.18)
            val cx = cols * rnd.nextDouble(0.08, 0.92)
            val cy = rows * rnd.nextDouble(0.08, 0.92)
            // Contorno irregular: suma de ondas con fases aleatorias
            val harmonics = List(5) { k -> Triple(k + 2, rnd.nextDouble(0.0, 2 * PI), 0.10 / (k + 1)) }
            val ringPhase = rnd.nextDouble(0.0, 2 * PI)
            val strength = rnd.nextDouble(0.75, 1.0)
            val ringWidth = 0.045

            withRegion(photo, Rect(
                (cx - r * 1.4).toInt(), (cy - r * 1.4).toInt(), (r * 2.8).toInt(), (r * 2.8).toInt()
            )) { x, y, px, i ->
                val dx = x - cx
                val dy = y - cy
                val theta = atan2(dy, dx)
                var edge = 1.0
                for ((k, ph, a) in harmonics) edge += a * sin(k * theta + ph)
                val s = sqrt(dx * dx + dy * dy) / (r * edge)
                if (s > 1.05) return@withRegion
                // El cerco no es igual de intenso en todo el contorno
                val partial = 0.55 + 0.45 * sin(theta * 2 + ringPhase)
                val ring = exp(-((s - 0.985) / ringWidth).let { it * it }) * partial
                // Segundo cerco más tenue, como las marcas de secado
                val tide = 0.4 * exp(-((s - 0.86) / (ringWidth * 0.7)).let { it * it }) * partial
                val interior = if (s < 1.0) 0.22 * (1 - s * s * 0.4) else 0.0
                val absorb = strength * (0.9 * ring + 0.5 * tide + interior)
                // El café absorbe más el azul: deja un tono marrón
                tint(px, i, absorb, 0.45, 0.62, 0.85)
            }
        }
    }

    // ------------------------------------------------------------------ pliegues

    /**
     * Pliegues de foto doblada: una arista clara y una sombra a cada lado, una leve curvatura
     * y grietas blancas donde la emulsión se ha cuarteado.
     */
    fun applyFolds(photo: Mat, rnd: Random, escala: Double) {
        val p = rnd.nextDouble()
        val vertical = p < 0.8      // 60 % ambos, 20 % solo vertical, 20 % solo horizontal
        val horizontal = p > 0.2
        if (vertical) fold(photo, rnd, escala, isVertical = true)
        if (horizontal) fold(photo, rnd, escala, isVertical = false)
    }

    private fun fold(photo: Mat, rnd: Random, escala: Double, isVertical: Boolean) {
        val cols = photo.cols()
        val rows = photo.rows()
        val across = if (isVertical) cols else rows   // dirección perpendicular al pliegue
        val along = if (isVertical) rows else cols
        val pos = across * rnd.nextDouble(0.42, 0.58)
        val w = 2.5 * escala                             // anchura de la arista
        val wobble = valueNoise1D(along, 120.0 * escala, rnd)
        val cracks = valueNoise1D(along, 5.0 * escala, rnd)
        val crackThreshold = rnd.nextDouble(0.70, 0.80)
        val band = (40 * w).toInt()
        val lightSide = if (rnd.nextBoolean()) 1.0 else -1.0

        val rect = if (isVertical) Rect(pos.toInt() - band, 0, 2 * band, rows)
        else Rect(0, pos.toInt() - band, cols, 2 * band)
        withRegion(photo, rect) { x, y, px, i ->
            val a = if (isVertical) y.toInt() else x.toInt()
            val c = if (isVertical) x else y
            val dx = (c - (pos + (wobble[a] - 0.5) * 6 * escala)) * lightSide
            // Arista: brillo a un lado, sombra al otro, y curvatura suave alrededor
            val light = exp(-((dx - w) / w).let { it * it })
            val dark = exp(-((dx + w) / w).let { it * it })
            val bend = exp(-(dx / (18 * w)).let { it * it })
            var factor = 1 + 0.22 * light - 0.30 * dark - 0.06 * bend * (if (dx < 0) 1.0 else -0.4)
            factor = max(0.0, factor)
            for (k in 0..2) px[i + k] = clamp((px[i + k].toInt() and 0xFF) * factor)
            // Grietas: la emulsión saltada deja ver el papel blanco
            if (cracks[a] > crackThreshold && abs(dx) < w * (0.4 + 2.0 * (cracks[a] - crackThreshold))) {
                for (k in 0..2) px[i + k] = clamp(PAPER_BACK[k] + 12)
            }
        }
    }

    // ------------------------------------------------------------------ esquina doblada

    /**
     * Una esquina doblada hacia delante: el triángulo de la esquina desaparece (fondo negro)
     * y encima de la foto aparece la solapa con el dorso de papel, con su sombra.
     * @return la esquina usada (0 sup-izq, 1 sup-der, 2 inf-izq, 3 inf-der).
     */
    fun applyDogEar(photo: Mat, rnd: Random, escala: Double): Int {
        val cols = photo.cols()
        val rows = photo.rows()
        val minDim = min(cols, rows)
        val a = minDim * rnd.nextDouble(0.10, 0.17)      // corte sobre el lado horizontal
        val b = a * rnd.nextDouble(0.7, 1.3)              // corte sobre el lado vertical
        val corner = rnd.nextInt(4)                       // 0 sup-izq, 1 sup-der, 2 inf-izq, 3 inf-der
        val flipX = corner == 1 || corner == 3
        val flipY = corner == 2 || corner == 3

        // Recta del pliegue en coordenadas locales (esquina en el origen): n·p = d
        val nl = sqrt(1 / (a * a) + 1 / (b * b))
        val nx = (1 / a) / nl
        val ny = (1 / b) / nl
        val d = 1 / nl
        val reach = (2 * d + 0.08 * a)
        val size = (max(a, b).coerceAtLeast(reach) * 1.15).toInt() + 2
        val shadow = 0.05 * a
        val aa = max(0.8, 0.7 * escala)

        val rect = Rect(if (flipX) cols - size else 0, if (flipY) rows - size else 0, size, size)
        withRegion(photo, rect) { x, y, px, i ->
            val u = if (flipX) cols - 1 - x else x
            val v = if (flipY) rows - 1 - y else y
            val sd = nx * u + ny * v - d                     // < 0: lado de la esquina
            if (sd < 0) {
                // La esquina original ya no está
                for (k in 0..2) px[i + k] = clamp(OUTSIDE[k])
                return@withRegion
            }
            // Punto reflejado: si cae dentro del triángulo de la esquina, es la solapa
            val qu = u - 2 * sd * nx
            val qv = v - 2 * sd * ny
            val inside = min(qu, qv)                         // > 0 dentro de la solapa
            val flapAlpha = smoothstep(-aa, aa, inside)
            // Sombra que proyecta la solapa sobre la foto
            val shade = if (inside < 0) 0.45 * exp(inside / shadow) else 0.45
            for (k in 0..2) {
                val photoV = (px[i + k].toInt() and 0xFF) * (1 - shade)
                // Dorso de papel: más oscuro junto al pliegue y hacia la punta
                val crease = 1 - 0.20 * exp(-(sd / (3 * escala + 1)).let { it * it })
                val gradient = 1 - 0.12 * (sd / d).coerceIn(0.0, 1.0)
                val back = PAPER_BACK[k] * crease * gradient
                px[i + k] = clamp(back * flapAlpha + photoV * (1 - flapAlpha))
            }
        }
        return corner
    }

    // ------------------------------------------------------------------ cinta adhesiva

    /**
     * Dos trozos de cinta adhesiva amarillenta en esquinas opuestas, con los extremos rasgados.
     * @param avoidCorner esquina que no debe llevar cinta (la que está doblada).
     */
    fun applyTape(photo: Mat, rnd: Random, escala: Double, avoidCorner: Int? = null) {
        val cols = photo.cols()
        val rows = photo.rows()
        val minDim = min(cols, rows)
        val diagonal = when (avoidCorner) {
            0, 3 -> false
            1, 2 -> true
            else -> rnd.nextBoolean()
        }
        val corners = if (diagonal) listOf(0, 3) else listOf(1, 2)
        for (corner in corners) {
            val length = minDim * rnd.nextDouble(0.20, 0.26)
            val width = minDim * rnd.nextDouble(0.06, 0.08)
            val inset = minDim * 0.10   // fuera de la zona quemada del borde
            val cx = if (corner == 1 || corner == 3) cols - inset else inset
            val cy = if (corner == 2 || corner == 3) rows - inset else inset
            // Orientada a ~45° respecto a la esquina
            val baseAngle = if (corner == 0 || corner == 3) -PI / 4 else PI / 4
            val angle = baseAngle + rnd.nextDouble(-0.2, 0.2)
            val ca = cos(angle)
            val sa = sin(angle)
            val jagA = valueNoise1D(200, 2.0, rnd)
            val jagB = valueNoise1D(200, 2.0, rnd)
            val haze = valueNoise1D(400, 8.0, rnd)
            val aa = max(0.8, 0.7 * escala)
            val half = length / 2 + width

            withRegion(photo, Rect((cx - half).toInt(), (cy - half).toInt(), (2 * half).toInt(), (2 * half).toInt())) { x, y, px, i ->
                val dx = x - cx
                val dy = y - cy
                val s = dx * ca + dy * sa                    // a lo largo de la cinta
                val t = -dx * sa + dy * ca                   // a lo ancho
                if (abs(t) > width / 2 + aa) return@withRegion
                val ti = ((t / width + 0.5) * 199).roundToInt().coerceIn(0, 199)
                // Extremos rasgados en zigzag
                val endA = length / 2 + (jagA[ti] - 0.5) * width * 0.25
                val endB = length / 2 + (jagB[ti] - 0.5) * width * 0.25
                val alongIn = if (s >= 0) endA - s else endB + s
                val acrossIn = width / 2 - abs(t)
                val alpha = smoothstep(-aa, aa, min(alongIn, acrossIn))
                if (alpha <= 0) return@withRegion
                // Cola más oscura en los bordes y velo lechoso irregular
                val glue = 1 - 0.10 * exp(-(acrossIn / (2 * escala + 1)).let { it * it })
                val hi = ((s / length + 0.5) * 399).roundToInt().coerceIn(0, 399)
                val milk = 34 + 22 * haze[hi]
                val tintRgb = doubleArrayOf(1.0, 0.90, 0.62)
                for (k in 0..2) {
                    val v = (px[i + k].toInt() and 0xFF).toDouble()
                    val taped = (v * tintRgb[k] + milk * tintRgb[k]) * glue
                    px[i + k] = clamp(taped * alpha * 0.9 + v * (1 - alpha * 0.9))
                }
            }
        }
    }

    // ------------------------------------------------------------------ utilidades

    /**
     * Recorre los píxeles de [rect] (recortado a la imagen) y vuelve a escribirlos.
     * [block] recibe las coordenadas absolutas, el array de bytes RGB y el índice del píxel.
     */
    private inline fun withRegion(photo: Mat, rect: Rect, block: (Double, Double, ByteArray, Int) -> Unit) {
        val x0 = max(0, rect.x)
        val y0 = max(0, rect.y)
        val x1 = min(photo.cols(), rect.x + rect.width)
        val y1 = min(photo.rows(), rect.y + rect.height)
        if (x1 <= x0 || y1 <= y0) return
        val r = Rect(x0, y0, x1 - x0, y1 - y0)
        val roi = photo.submat(r)
        val part = roi.clone()
        val px = ByteArray(r.width * r.height * 3)
        part.get(0, 0, px)
        for (yy in 0 until r.height) {
            for (xx in 0 until r.width) {
                block((x0 + xx).toDouble(), (y0 + yy).toDouble(), px, (yy * r.width + xx) * 3)
            }
        }
        part.put(0, 0, px)
        part.copyTo(roi)
        part.release()
        roi.release()
    }

    /** Oscurece un píxel con absorción distinta por canal (tinte marrón). */
    private fun tint(px: ByteArray, i: Int, absorb: Double, kr: Double, kg: Double, kb: Double) {
        val k = doubleArrayOf(kr, kg, kb)
        for (c in 0..2) px[i + c] = clamp((px[i + c].toInt() and 0xFF) * (1 - absorb * k[c]).coerceAtLeast(0.0))
    }

    private fun valueNoise1D(n: Int, spacing: Double, rnd: Random): DoubleArray {
        val knots = DoubleArray((n / spacing).toInt() + 3) { rnd.nextDouble() }
        return DoubleArray(n) { i ->
            val t = i / spacing
            val k = t.toInt()
            val w = (1 - cos((t - k) * PI)) / 2
            knots[k] * (1 - w) + knots[k + 1] * w
        }
    }

    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun clamp(v: Double): Byte = (v + 0.5).toInt().coerceIn(0, 255).toByte()
}
