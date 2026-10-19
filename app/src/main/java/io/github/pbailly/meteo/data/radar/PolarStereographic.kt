package io.github.pbailly.meteo.data.radar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Projection stéréographique polaire nord sur l'ellipsoïde WGS84 (formules de Snyder),
 * celle des mosaïques radar Météo-France : `+proj=stere +lat_0=90 +lon_0=0 +lat_ts=45`.
 */
class PolarStereographic(
    private val lon0: Double,
    latTs: Double,
    private val falseEasting: Double,
    private val falseNorthing: Double,
) {
    private val tc = t(Math.toRadians(latTs))
    private val mc = m(Math.toRadians(latTs))

    /** Coordonnées projetées (x, y) en mètres, écrites dans out[0] et out[1]. */
    fun forward(lat: Double, lon: Double, out: DoubleArray) {
        val rho = A * mc * t(Math.toRadians(lat)) / tc
        val dLon = Math.toRadians(lon - lon0)
        out[0] = falseEasting + rho * sin(dLon)
        out[1] = falseNorthing - rho * cos(dLon)
    }

    private fun t(phi: Double): Double {
        val es = E * sin(phi)
        return tan(PI / 4 - phi / 2) / ((1 - es) / (1 + es)).pow(E / 2)
    }

    private fun m(phi: Double): Double = cos(phi) / sqrt(1 - E * E * sin(phi) * sin(phi))

    companion object {
        private const val A = 6378137.0
        private const val F = 1 / 298.257223563
        private val E = sqrt(2 * F - F * F)

        /** Lit une définition PROJ.4 du type `+proj=stere +lat_0=90 +lon_0=0 +lat_ts=45 +x_0=… +y_0=…`. */
        fun fromProjDef(projdef: String): PolarStereographic {
            val params = projdef.split(' ', '\t')
                .filter { it.startsWith("+") && '=' in it }
                .associate { it.removePrefix("+").substringBefore('=') to it.substringAfter('=') }
            require(params["proj"] == "stere" && params["lat_0"]?.toDoubleOrNull() == 90.0) {
                "Projection radar inattendue : $projdef"
            }
            return PolarStereographic(
                lon0 = params["lon_0"]?.toDoubleOrNull() ?: 0.0,
                latTs = params["lat_ts"]?.toDoubleOrNull() ?: 90.0,
                falseEasting = params["x_0"]?.toDoubleOrNull() ?: 0.0,
                falseNorthing = params["y_0"]?.toDoubleOrNull() ?: 0.0,
            )
        }
    }
}
