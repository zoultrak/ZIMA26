package com.zima26.app.filter

import com.zima26.app.filter.WarPhotoFilter.Companion.gaussian
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Efectos de desgaste sobre la imagen en gris (Mat CV_32F, rango 0..1):
 * exposición irregular, viñeta, rayones y polvo. Todo es aleatorio según el [Random]
 * que se recibe, de modo que cada foto es única.
 */
object WearEffects {

    /**
     * Pasos 10 y 11 combinados en un único mapa multiplicativo de baja resolución:
     * - Exposición irregular: cuadrícula 12x6 de ruido escalada con bicúbica (intensidad 0.05).
     * - Viñeta: g *= clamp(1 - 0.55 d^2.2, 0.2, 1), con d = distancia elíptica normalizada.
     * Ambos varían muy despacio, así que se calculan a 1/8 de resolución y se amplían.
     */
    fun applyExposureAndVignette(g: Mat, rnd: Random, wear: Double) {
        val cols = g.cols()
        val rows = g.rows()
        val lowW = max(64, cols / 8)
        val lowH = max(48, rows / 8)

        // Cuadrícula de exposición: 12 celdas en el lado largo y 6 en el corto.
        val gridW = if (cols >= rows) 12 else 6
        val gridH = if (cols >= rows) 6 else 12
        val intensity = 0.05 * wear
        val grid = Mat(gridH, gridW, CvType.CV_32F)
        val gridData = FloatArray(gridW * gridH) { (1.0 + intensity * (rnd.nextDouble() * 2 - 1)).toFloat() }
        grid.put(0, 0, gridData)
        val low = Mat()
        Imgproc.resize(grid, low, Size(lowW.toDouble(), lowH.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
        grid.release()

        // Multiplicar por la viñeta elíptica.
        val data = FloatArray(lowW * lowH)
        low.get(0, 0, data)
        for (y in 0 until lowH) {
            val dy = (y + 0.5) / lowH * 2.0 - 1.0
            for (x in 0 until lowW) {
                val dx = (x + 0.5) / lowW * 2.0 - 1.0
                val d = sqrt(dx * dx + dy * dy)
                val v = (1.0 - 0.55 * d.pow(2.2)).coerceIn(0.2, 1.0)
                data[y * lowW + x] *= v.toFloat()
            }
        }
        low.put(0, 0, data)

        val map = Mat()
        Imgproc.resize(low, map, g.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        low.release()
        Core.multiply(g, map, g)
        map.release()
    }

    /**
     * Paso 12: rayones claros de la película.
     * - ~6 líneas verticales finas, largas y casi rectas (opacidad 0.35-0.7), a veces cortadas.
     * - ~25 rayones cortos en direcciones aleatorias.
     * Se dibujan con antialiasing en una máscara de 8 bits y se aplican con max(g, rayón·0.8).
     */
    fun applyScratches(g: Mat, rnd: Random, escala: Double, wear: Double) {
        val cols = g.cols()
        val rows = g.rows()
        val mask = Mat.zeros(g.size(), CvType.CV_8U)

        // Rayones verticales largos
        val longCount = max(1, (6 * wear).roundToInt())
        repeat(longCount) {
            val opacity = rnd.nextDouble(0.35, 0.7)
            val thickness = max(1, (escala * rnd.nextDouble(0.6, 1.4)).roundToInt())
            val x0 = rnd.nextDouble(0.04, 0.96) * cols
            val y0 = rnd.nextDouble(0.0, 0.25) * rows
            val y1 = rnd.nextDouble(0.7, 1.0) * rows
            val segments = 30
            val wobbleAmp = rnd.nextDouble(0.5, 2.5) * escala
            val wobbleFreq = rnd.nextDouble(1.0, 3.0)
            var drift = 0.0
            var current = ArrayList<Point>()
            for (i in 0..segments) {
                val t = i.toDouble() / segments
                drift += rnd.gaussian() * 0.6 * escala
                val x = x0 + drift + sin(t * PI * wobbleFreq) * wobbleAmp
                current.add(Point(x, y0 + (y1 - y0) * t))
                // De vez en cuando el rayón se interrumpe
                if (rnd.nextDouble() < 0.06 && current.size > 1) {
                    drawPolyline(mask, current, opacity, thickness)
                    current = ArrayList()
                }
            }
            if (current.size > 1) drawPolyline(mask, current, opacity, thickness)
        }

        // Rayones cortos en direcciones aleatorias, ligeramente curvos
        val shortCount = (25 * wear).roundToInt()
        repeat(shortCount) {
            val cx = rnd.nextDouble() * cols
            val cy = rnd.nextDouble() * rows
            val len = rnd.nextDouble(10.0, 70.0) * escala
            val angle = rnd.nextDouble() * PI
            val bend = rnd.nextDouble(-0.15, 0.15) * len
            val dx = cos(angle) * len / 2
            val dy = sin(angle) * len / 2
            val pts = listOf(
                Point(cx - dx, cy - dy),
                Point(cx - sin(angle) * bend, cy + cos(angle) * bend),
                Point(cx + dx, cy + dy)
            )
            drawPolyline(mask, pts, rnd.nextDouble(0.3, 0.7), max(1, (0.8 * escala).roundToInt()))
        }

        if (escala > 1.2) {
            Imgproc.GaussianBlur(mask, mask, Size(0.0, 0.0), 0.4 * escala)
        }
        val maskF = Mat()
        mask.convertTo(maskF, CvType.CV_32F, 0.8 / 255.0)
        mask.release()
        Core.max(g, maskF, g)
        maskF.release()
    }

    private fun drawPolyline(mask: Mat, pts: List<Point>, opacity: Double, thickness: Int) {
        val poly = MatOfPoint(*pts.toTypedArray())
        Imgproc.polylines(mask, listOf(poly), false, Scalar(opacity * 255.0), thickness, Imgproc.LINE_AA)
        poly.release()
    }

    /**
     * Paso 13: ~350 motas de polvo de 1 a 3 px (a escala de referencia).
     * El 70 % son claras (0.75-1.0) y el 30 % oscuras (0-0.12).
     * El desenfoque final (sigma 0.6) lo aplica el filtro principal.
     */
    fun applyDust(g: Mat, rnd: Random, escala: Double, wear: Double) {
        val cols = g.cols()
        val rows = g.rows()
        val count = (350 * wear).roundToInt()
        repeat(count) {
            val light = rnd.nextDouble() < 0.7
            val value = if (light) rnd.nextDouble(0.75, 1.0) else rnd.nextDouble(0.0, 0.12)
            val center = Point(rnd.nextDouble() * cols, rnd.nextDouble() * rows)
            // Diámetro 1-3 px -> semiejes de la elipse (motas algo irregulares)
            val diameter = rnd.nextDouble(1.0, 3.0) * escala
            val ax = max(1.0, diameter / 2 * rnd.nextDouble(0.7, 1.3))
            val ay = max(1.0, diameter / 2 * rnd.nextDouble(0.7, 1.3))
            if (diameter < 1.6) {
                Imgproc.circle(g, center, 0, Scalar(value), -1, Imgproc.LINE_8)
            } else {
                Imgproc.ellipse(
                    g, center, Size(ax, ay), rnd.nextDouble(0.0, 180.0), 0.0, 360.0,
                    Scalar(value), -1, Imgproc.LINE_8
                )
            }
        }
    }
}
