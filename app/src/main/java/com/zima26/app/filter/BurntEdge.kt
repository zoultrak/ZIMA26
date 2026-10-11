package com.zima26.app.filter

import org.opencv.core.Mat
import org.opencv.core.Rect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Paso 15: borde de la foto quemado y rasgado (sin marco de papel).
 *
 * Sobre la propia foto se dibuja una línea de rotura irregular. Fuera de ella queda fondo
 * negro; dentro, la quemadura (carbón -> marrón -> papel tostado) avanza de forma
 * irregular: en unas zonas se mete en la foto y en otras apenas hay quemado.
 * La forma de la rotura es aleatoria en cada foto.
 *
 * Solo se recorren cuatro franjas estrechas junto a los bordes: el resto de la foto
 * no se toca.
 */
object BurntEdge {

    // Colores de la quemadura (de menos a más quemado)
    /** Papel tostado: la foto se multiplica por este tinte marrón. */
    private val TOAST = doubleArrayOf(0.80, 0.70, 0.58)
    private val BROWN = doubleArrayOf(62.0, 36.0, 18.0)
    private val CHAR = doubleArrayOf(20.0, 14.0, 10.0)
    /** Fuera de la rotura: negro carbón (se funde con el fondo oscuro de la galería). */
    private val OUTSIDE = doubleArrayOf(12.0, 10.0, 9.0)
    /** Rescoldo marrón muy oscuro justo fuera de la rotura, que se degrada a negro. */
    private val EMBER = doubleArrayOf(30.0, 19.0, 11.0)

    /** Escala de la rotura: 6 % del ancho. */
    private const val TEAR_FRACTION = 0.06
    /** Franja procesada: 10 % del ancho (la quemadura puede avanzar bastante en algunas zonas). */
    private const val BAND_FRACTION = 0.10

    /** Aplica el borde quemado in situ sobre [photo] (CV_8UC3 RGB). */
    fun apply(photo: Mat, rnd: Random, escala: Double) {
        val cols = photo.cols()
        val rows = photo.rows()
        val band = (cols * BAND_FRACTION).roundToInt().coerceIn(4, minOf(cols, rows) / 3)
        val m = cols * TEAR_FRACTION

        // Cuánto entra la rotura desde cada lado (mordiscos amplios + ondulación media).
        // Abajo entra algo menos para no tapar el texto manuscrito.
        val offTop = tearProfile(cols, m, rnd, 1.0)
        val offBottom = tearProfile(cols, m, rnd, 0.75)
        val offLeft = tearProfile(rows, m, rnd, 1.0)
        val offRight = tearProfile(rows, m, rnd, 1.0)
        // Dentado fino de fibras arrancadas: solo afecta al corte y al filo negro,
        // no al degradado (si no, aparecerían rayas perpendiculares al borde).
        val jag = 2.5 * escala
        val spacing = max(2.0, 3.0 * escala)
        val jagTop = valueNoise1D(cols, spacing, rnd)
        val jagBottom = valueNoise1D(cols, spacing, rnd)
        val jagLeft = valueNoise1D(rows, spacing, rnd)
        val jagRight = valueNoise1D(rows, spacing, rnd)

        // Ruido 2D que decide cuánto avanza la quemadura en cada zona: en unas partes se
        // mete en la foto como una mancha y en otras apenas hay quemado.
        val reachBig = Noise2D(cols, rows, m * 1.3, rnd)
        val reachMid = Noise2D(cols, rows, m * 0.4, rnd)
        // Moteado fino del carbonizado (que no sea un degradado liso)
        val mottle = Noise2D(cols, rows, max(3.0, m * 0.07), rnd)

        val lineWidth = max(1.5, 0.03 * m)  // filo negro justo en la rotura
        val aa = max(0.7, 0.6 * escala)     // antialiasing del corte
        val fade = 0.12 * m                 // degradado exterior hacia el negro

        // Cuatro franjas que no se solapan
        val strips = listOf(
            Rect(0, 0, cols, band),
            Rect(0, rows - band, cols, band),
            Rect(0, band, band, rows - 2 * band),
            Rect(cols - band, band, band, rows - 2 * band)
        )
        for (r in strips) {
            if (r.width <= 0 || r.height <= 0) continue
            val roi = photo.submat(r)
            val part = roi.clone()
            val px = ByteArray(r.width * r.height * 3)
            part.get(0, 0, px)

            for (yy in 0 until r.height) {
                val y = r.y + yy
                for (xx in 0 until r.width) {
                    val x = r.x + xx
                    // ds = distancia (positiva = dentro) a la rotura suave del lado más cercano
                    var ds = y - offTop[x]
                    var jg = jagTop[x]
                    var depth = 1.0
                    val dB = (rows - 1 - y) - offBottom[x]
                    if (dB < ds) { ds = dB; jg = jagBottom[x]; depth = 0.75 }
                    val dL = x - offLeft[y]
                    if (dL < ds) { ds = dL; jg = jagLeft[y]; depth = 1.0 }
                    val dR = (cols - 1 - x) - offRight[y]
                    if (dR < ds) { ds = dR; jg = jagRight[y]; depth = 1.0 }
                    val d = ds - jag * jg

                    // Alcance local de la quemadura (irregular)
                    val fx = x.toDouble()
                    val fy = y.toDouble()
                    val n = 0.65 * reachBig.at(fx, fy) + 0.35 * reachMid.at(fx, fy)
                    val q = smoothstep(0.28, 0.85, n)
                    val reach = depth * m * (0.05 + 0.8 * q)
                    // "Calor": 1 en la rotura, 0 donde ya no llega la quemadura
                    var heat = 1.0 - smoothstep(0.0, reach, ds)
                    heat *= 0.8 + 0.4 * mottle.at(fx, fy)
                    heat = heat.coerceIn(0.0, 1.0)
                    val toast = smoothstep(0.0, 0.35, heat)
                    val brown = smoothstep(0.2, 0.7, heat)
                    val charred = max(smoothstep(0.55, 0.95, heat), 1.0 - smoothstep(-lineWidth * 0.5, lineWidth * 1.5, d))

                    val alpha = smoothstep(-2 * aa, 2 * aa, d)
                    // Fuera: rescoldo oscuro que se degrada a negro; usa la distancia suave y
                    // la distancia al borde de la imagen para que sea continuo.
                    val edgeDist = minOf(minOf(x, cols - 1 - x), minOf(y, rows - 1 - y)).toDouble()
                    val g = smoothstep(-fade, 0.0, ds) * smoothstep(0.0, fade * 0.6, edgeDist)
                    val glow = g * g * (0.4 + 0.6 * q)

                    val i = (yy * r.width + xx) * 3
                    for (c in 0..2) {
                        var v = (px[i + c].toInt() and 0xFF).toDouble()
                        v *= 1.0 + (TOAST[c] - 1.0) * toast
                        v += (BROWN[c] - v) * brown
                        v += (CHAR[c] - v) * charred
                        val outside = OUTSIDE[c] + (EMBER[c] - OUTSIDE[c]) * glow
                        v = outside * (1.0 - alpha) + v * alpha
                        px[i + c] = clampByte(v)
                    }
                }
            }
            part.put(0, 0, px)
            part.copyTo(roi)
            part.release()
            roi.release()
        }
    }

    /** Ruido 2D suave en 0..1: cuadrícula aleatoria con interpolación coseno. */
    private class Noise2D(w: Int, h: Int, private val cell: Double, rnd: Random) {
        private val gw = (w / cell).toInt() + 3
        private val gh = (h / cell).toInt() + 3
        private val grid = DoubleArray(gw * gh) { rnd.nextDouble() }

        fun at(x: Double, y: Double): Double {
            val tx = x / cell
            val ty = y / cell
            val ix = tx.toInt()
            val iy = ty.toInt()
            val wx = (1 - cos((tx - ix) * PI)) / 2
            val wy = (1 - cos((ty - iy) * PI)) / 2
            val a = grid[iy * gw + ix] * (1 - wx) + grid[iy * gw + ix + 1] * wx
            val b = grid[(iy + 1) * gw + ix] * (1 - wx) + grid[(iy + 1) * gw + ix + 1] * wx
            return a * (1 - wy) + b * wy
        }
    }

    /** Perfil de rotura: ondas amplias (mordiscos grandes) y ondulación media. */
    private fun tearProfile(n: Int, m: Double, rnd: Random, depth: Double): DoubleArray {
        val coarse = valueNoise1D(n, m * 1.1, rnd)
        val medium = valueNoise1D(n, m * 0.22, rnd)
        return DoubleArray(n) { i ->
            val c = coarse[i]
            depth * m * (0.14 + 0.26 * c * c + 0.06 * medium[i])
        }
    }

    /** Ruido 1D suave en 0..1 con nodos cada [spacing] píxeles e interpolación coseno. */
    private fun valueNoise1D(n: Int, spacing: Double, rnd: Random): DoubleArray {
        val knots = DoubleArray((n / spacing).toInt() + 3) { rnd.nextDouble() }
        return DoubleArray(n) { i ->
            val t = i / spacing
            val k = t.toInt()
            val frac = t - k
            val w = (1 - cos(frac * PI)) / 2
            knots[k] * (1 - w) + knots[k + 1] * w
        }
    }

    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun clampByte(v: Double): Byte = (v + 0.5).toInt().coerceIn(0, 255).toByte()
}
