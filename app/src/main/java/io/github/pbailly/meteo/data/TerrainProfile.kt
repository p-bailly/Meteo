package io.github.pbailly.meteo.data

import kotlin.math.abs

/** Un sommet nommé d'OpenStreetMap. */
data class Peak(val name: String, val latitude: Double, val longitude: Double, val elevation: Double)

/** Repère posé sur la coupe : un sommet (nommé ou non) à l'échantillon [index]. */
data class ProfileLabel(val index: Int, val name: String?, val elevation: Double)

/**
 * Coupe du relief, de la vallée vers les sommets : [elevations] échantillonne en ligne droite le terrain d'un
 * peu avant le lieu jusqu'un peu après le plus haut sommet des environs.
 */
class TerrainProfile(
    val elevations: DoubleArray,
    val lengthKm: Double,
    val placeIndex: Int,
    /** Le plus haut sommet en premier. */
    val labels: List<ProfileLabel>,
    /** Sommets nommés proches de la coupe (pour nommer une altitude, « 2 000 m · Plan Praz »). */
    val peaks: List<Peak>,
) {
    val summit: ProfileLabel? get() = labels.firstOrNull()

    /** Nom d'un sommet de la coupe proche de [altitude] (à [tolerance] m près), ou null. */
    fun nameNear(altitude: Double, tolerance: Double = 200.0): String? =
        peaks.filter { abs(it.elevation - altitude) <= tolerance }.minByOrNull { abs(it.elevation - altitude) }?.name
}
