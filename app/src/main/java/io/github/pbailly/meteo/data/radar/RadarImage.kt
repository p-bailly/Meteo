package io.github.pbailly.meteo.data.radar

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/** Rectangle géographique, en degrés. */
data class GeoBounds(val north: Double, val south: Double, val west: Double, val east: Double)

/** Classes de précipitations de la légende, avec les couleurs de Meteoblue. */
enum class RainClass(val label: String, val fromMmPerHour: Double, val argb: Int) {
    DRIZZLE("Bruine", 0.1, 0xFF7AE1E8.toInt()),
    LIGHT("Légère", 0.6, 0xFF00C8D8.toInt()),
    MODERATE("Modéré", 2.7, 0xFF2D7BEA.toInt()),
    HEAVY("Fort", 11.5, 0xFFBE46EB.toInt()),
    VERY_HEAVY("Très fort", 48.0, 0xFFDE5E47.toInt()),
    HAIL("Grêle", 100.0, 0xFFE4D541.toInt()),
    ;

    companion object {
        /** Couleur ARGB d'une intensité en mm/h ; 0 (transparent) sous le seuil de la bruine. */
        fun argbFor(mmPerHour: Float): Int {
            if (mmPerHour.isNaN() || mmPerHour < DRIZZLE.fromMmPerHour) return 0
            var color = DRIZZLE.argb
            for (c in entries) if (mmPerHour >= c.fromMmPerHour) color = c.argb
            return color
        }

        /** Conversion réflectivité → intensité (relation de Marshall-Palmer, Z = 200 R^1,6). */
        fun dbzToMmPerHour(dbz: Double): Double = (10.0.pow(dbz / 10.0) / 200.0).pow(1.0 / 1.6)
    }
}

/** Position (lat, lon) → coordonnées fractionnaires (colonne, ligne) dans une grille radar. */
fun interface GridProjection {
    /** Écrit la colonne dans out[0] et la ligne dans out[1]. */
    fun toGrid(lat: Double, lon: Double, out: DoubleArray)
}

/**
 * Table de correspondance pixel Web Mercator → case de la grille radar, calculée une fois
 * puis réutilisée pour chaque image de l'animation.
 */
class MercatorLookup(
    val bounds: GeoBounds,
    val width: Int,
    val height: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    projection: GridProjection,
) {
    /** Indice dans la grille (ligne × largeur + colonne), ou -1 hors de la zone couverte. */
    val index = IntArray(width * height)

    init {
        val yNorth = mercatorY(bounds.north)
        val ySouth = mercatorY(bounds.south)
        val out = DoubleArray(2)
        for (py in 0 until height) {
            val lat = latitudeOf(yNorth + (ySouth - yNorth) * (py + 0.5) / height)
            for (px in 0 until width) {
                val lon = bounds.west + (bounds.east - bounds.west) * (px + 0.5) / width
                projection.toGrid(lat, lon, out)
                val col = out[0].roundToInt()
                val row = out[1].roundToInt()
                index[py * width + px] =
                    if (col in 0 until gridWidth && row in 0 until gridHeight) row * gridWidth + col else -1
            }
        }
    }

    /** Pixels ARGB de l'image Web Mercator, à partir des intensités (mm/h) de la grille. */
    fun render(mmPerHour: FloatArray): IntArray = IntArray(index.size) { i ->
        val k = index[i]
        if (k < 0) 0 else RainClass.argbFor(mmPerHour[k])
    }

    companion object {
        private fun mercatorY(lat: Double): Double = ln(tan(PI / 4 + Math.toRadians(lat) / 2))
        private fun latitudeOf(y: Double): Double = Math.toDegrees(atan(sinh(y)))

        /** Table pour une image de [width] pixels de large ; la hauteur suit les proportions Web Mercator. */
        fun forGrid(bounds: GeoBounds, width: Int, grid: RadarGrid): MercatorLookup {
            val spanX = Math.toRadians(bounds.east - bounds.west)
            val spanY = mercatorY(bounds.north) - mercatorY(bounds.south)
            val height = (width * spanY / spanX).roundToInt()
            return MercatorLookup(bounds, width, height, grid.width, grid.height, grid.projection)
        }
    }
}
