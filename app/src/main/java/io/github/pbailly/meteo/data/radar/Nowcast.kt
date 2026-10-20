package io.github.pbailly.meteo.data.radar

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Champ de déplacement des pluies, estimé sur une grille de blocs.
 * [u] et [v] sont en pixels d'image par minute (x vers l'est, y vers le sud).
 */
class MotionField(
    val width: Int,
    val height: Int,
    /** Position (en pixels) du centre du premier bloc, et écart entre centres de blocs. */
    private val originPx: Float,
    private val spacingPx: Float,
    private val cols: Int,
    private val rows: Int,
    val u: FloatArray,
    val v: FloatArray,
) {
    /** Vitesse au pixel (x, y), interpolée entre les centres de blocs. */
    fun at(x: Float, y: Float, out: FloatArray) {
        val gx = ((x - originPx) / spacingPx).coerceIn(0f, (cols - 1).toFloat())
        val gy = ((y - originPx) / spacingPx).coerceIn(0f, (rows - 1).toFloat())
        val c0 = floor(gx).toInt().coerceAtMost(cols - 2).coerceAtLeast(0)
        val r0 = floor(gy).toInt().coerceAtMost(rows - 2).coerceAtLeast(0)
        val c1 = (c0 + 1).coerceAtMost(cols - 1)
        val r1 = (r0 + 1).coerceAtMost(rows - 1)
        val fx = gx - c0
        val fy = gy - r0
        fun lerp(a: FloatArray): Float {
            val top = a[r0 * cols + c0] * (1 - fx) + a[r0 * cols + c1] * fx
            val bottom = a[r1 * cols + c0] * (1 - fx) + a[r1 * cols + c1] * fx
            return top * (1 - fy) + bottom * fy
        }
        out[0] = lerp(u)
        out[1] = lerp(v)
    }

    /** Vitesse moyenne, en pixels par minute (utile pour les tests et l'affichage). */
    fun meanSpeed(): Pair<Float, Float> = u.average().toFloat() to v.average().toFloat()

    companion object {
        /**
         * Aucun déplacement : quand il n'y a qu'une image radar, ou trop peu de pluie pour mesurer son mouvement, la
         * prévision montre la pluie telle qu'elle est (et, s'il ne pleut nulle part, une carte sèche).
         */
        fun still(width: Int, height: Int) = MotionField(width, height, 0f, 1f, 1, 1, floatArrayOf(0f), floatArrayOf(0f))
    }
}

/**
 * Prévision immédiate par extrapolation (méthode TREC : suivi des échos radar par corrélation).
 *
 * On compare deux images radar récentes bloc par bloc pour trouver où chaque zone de pluie s'est déplacée,
 * puis on prolonge ce mouvement. Fiable sur une heure environ : les averses ne naissent ni ne meurent.
 */
object Nowcast {
    /** Réduction de l'image pour l'estimation du mouvement (1 case = 4 × 4 pixels). */
    private const val SCALE = 4
    /** Taille d'un bloc et écart entre blocs, en cases réduites. */
    private const val BLOCK = 16
    private const val STRIDE = 12
    /** Recherche du déplacement jusqu'à ± SEARCH cases réduites (± 32 pixels, environ 45 km). */
    private const val SEARCH = 8
    /** Part minimale de cases pluvieuses pour qu'un bloc serve à estimer le mouvement. */
    private const val MIN_RAIN = 0.08

    private val classOf: Map<Int, Int> = RainClass.entries.associate { it.argb to it.ordinal + 1 }

    /**
     * Estime le mouvement entre [older] et [newer] (pixels ARGB de même taille), séparés de [minutes].
     * Renvoie null s'il n'y a pas assez de pluie pour le mesurer.
     */
    fun estimateMotion(older: IntArray, newer: IntArray, width: Int, height: Int, minutes: Double): MotionField? {
        require(older.size == width * height && newer.size == width * height)
        val w = width / SCALE
        val h = height / SCALE
        val a = downsample(older, width, w, h)
        val b = downsample(newer, width, w, h)

        val cols = maxOf(1, (w - BLOCK) / STRIDE + 1)
        val rows = maxOf(1, (h - BLOCK) / STRIDE + 1)
        val du = FloatArray(cols * rows) { Float.NaN }
        val dv = FloatArray(cols * rows) { Float.NaN }
        val costs = Array(2 * SEARCH + 1) { FloatArray(2 * SEARCH + 1) }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x0 = c * STRIDE
                val y0 = r * STRIDE
                if (rainFraction(b, w, x0, y0) < MIN_RAIN) continue
                var best = Float.MAX_VALUE
                var bx = 0
                var by = 0
                for (sy in -SEARCH..SEARCH) {
                    for (sx in -SEARCH..SEARCH) {
                        val cost = blockCost(a, b, w, h, x0, y0, sx, sy)
                        costs[sy + SEARCH][sx + SEARCH] = cost
                        if (cost < best || (cost == best && abs(sx) + abs(sy) < abs(bx) + abs(by))) {
                            best = cost
                            bx = sx
                            by = sy
                        }
                    }
                }
                // Affinage sous le pixel par une parabole autour du minimum.
                val fx = bx + subPixel(costs, bx, by, horizontal = true)
                val fy = by + subPixel(costs, bx, by, horizontal = false)
                du[r * cols + c] = (fx * SCALE / minutes).toFloat()
                dv[r * cols + c] = (fy * SCALE / minutes).toFloat()
            }
        }
        if (du.none { !it.isNaN() }) return null
        removeOutliers(du, dv, cols, rows)
        fillGaps(du, dv, cols, rows)
        return MotionField(
            width, height,
            originPx = BLOCK / 2f * SCALE,
            spacingPx = (STRIDE * SCALE).toFloat(),
            cols = cols, rows = rows, u = du, v = dv,
        )
    }

    /**
     * Image prévue [minutes] après [latest] : chaque pixel reprend la couleur de l'endroit
     * d'où la pluie arrive (advection semi-lagrangienne).
     */
    fun extrapolate(latest: IntArray, motion: MotionField, minutes: Double): IntArray {
        val w = motion.width
        val h = motion.height
        val out = IntArray(w * h)
        val vel = FloatArray(2)
        val t = minutes.toFloat()
        for (y in 0 until h) {
            for (x in 0 until w) {
                motion.at(x + 0.5f, y + 0.5f, vel)
                val sx = (x - vel[0] * t).roundToInt()
                val sy = (y - vel[1] * t).roundToInt()
                if (sx in 0 until w && sy in 0 until h) out[y * w + x] = latest[sy * w + sx]
            }
        }
        return out
    }

    /** Intensité moyenne (0 à 6, selon la classe de la légende) par case de SCALE × SCALE pixels. */
    private fun downsample(argb: IntArray, width: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (dy in 0 until SCALE) {
                    val row = (y * SCALE + dy) * width
                    for (dx in 0 until SCALE) sum += classOf[argb[row + x * SCALE + dx]] ?: 0
                }
                out[y * w + x] = sum.toFloat() / (SCALE * SCALE)
            }
        }
        return out
    }

    private fun rainFraction(grid: FloatArray, w: Int, x0: Int, y0: Int): Double {
        var rainy = 0
        for (y in y0 until y0 + BLOCK) for (x in x0 until x0 + BLOCK) if (grid[y * w + x] > 0f) rainy++
        return rainy.toDouble() / (BLOCK * BLOCK)
    }

    /** Écart moyen entre le bloc récent et le bloc ancien décalé de (-sx, -sy). */
    private fun blockCost(a: FloatArray, b: FloatArray, w: Int, h: Int, x0: Int, y0: Int, sx: Int, sy: Int): Float {
        var sum = 0f
        for (y in y0 until y0 + BLOCK) {
            val oy = y - sy
            for (x in x0 until x0 + BLOCK) {
                val ox = x - sx
                val old = if (ox in 0 until w && oy in 0 until h) a[oy * w + ox] else 0f
                sum += abs(b[y * w + x] - old)
            }
        }
        return sum
    }

    private fun subPixel(costs: Array<FloatArray>, bx: Int, by: Int, horizontal: Boolean): Double {
        val i = by + SEARCH
        val j = bx + SEARCH
        val (left, right) = if (horizontal) {
            if (j == 0 || j == 2 * SEARCH) return 0.0
            costs[i][j - 1] to costs[i][j + 1]
        } else {
            if (i == 0 || i == 2 * SEARCH) return 0.0
            costs[i - 1][j] to costs[i + 1][j]
        }
        val mid = costs[i][j]
        val denominator = left - 2 * mid + right
        return if (denominator > 1e-6f) ((left - right) / (2 * denominator)).toDouble().coerceIn(-0.5, 0.5) else 0.0
    }

    /** Remplace un vecteur très différent de ses voisins par la médiane de ceux-ci. */
    private fun removeOutliers(u: FloatArray, v: FloatArray, cols: Int, rows: Int) {
        val u0 = u.copyOf()
        val v0 = v.copyOf()
        for (r in 0 until rows) for (c in 0 until cols) {
            val k = r * cols + c
            if (u0[k].isNaN()) continue
            val nu = mutableListOf<Float>()
            val nv = mutableListOf<Float>()
            for (dr in -2..2) for (dc in -2..2) {
                val rr = r + dr
                val cc = c + dc
                if ((dr == 0 && dc == 0) || rr !in 0 until rows || cc !in 0 until cols) continue
                val kk = rr * cols + cc
                if (!u0[kk].isNaN()) { nu += u0[kk]; nv += v0[kk] }
            }
            if (nu.size < 3) continue
            val mu = nu.sorted()[nu.size / 2]
            val mv = nv.sorted()[nv.size / 2]
            val spread = maxOf(0.5f, hypot(mu, mv) * 0.5f)
            if (hypot(u0[k] - mu, v0[k] - mv) > spread) {
                u[k] = mu
                v[k] = mv
            }
        }
    }

    /** Complète les blocs sans pluie par une moyenne des vecteurs mesurés, pondérée par la distance. */
    private fun fillGaps(u: FloatArray, v: FloatArray, cols: Int, rows: Int) {
        val known = u.indices.filter { !u[it].isNaN() }
        for (k in u.indices) {
            if (!u[k].isNaN()) continue
            val r = k / cols
            val c = k % cols
            var su = 0.0
            var sv = 0.0
            var sw = 0.0
            for (kk in known) {
                val d2 = ((kk / cols - r) * (kk / cols - r) + (kk % cols - c) * (kk % cols - c)).toDouble()
                val weight = 1.0 / d2
                su += weight * u[kk]
                sv += weight * v[kk]
                sw += weight
            }
            u[k] = (su / sw).toFloat()
            v[k] = (sv / sw).toFloat()
        }
    }
}
