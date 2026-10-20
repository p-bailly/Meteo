package io.github.pbailly.meteo.data

import kotlin.math.abs

/** Un sommet nommé d'OpenStreetMap. */
data class Peak(val name: String, val latitude: Double, val longitude: Double, val elevation: Double)

/** Le relief vu dans une autre direction ([bearing]) : [elevations] du lieu jusqu'à [lengthKm]. */
class Backdrop(val elevations: DoubleArray, val lengthKm: Double, val bearing: Double)

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
    /** Direction de la coupe, du lieu vers le sommet, en degrés depuis le nord (90 : vers l'est). */
    val bearing: Double? = null,
    /** Une autre chaîne, dans une autre direction : dessinée pâle, au loin, derrière le relief. */
    val backdrop: Backdrop? = null,
    /** Les deux bouts de la coupe (latitude, longitude) : pour retrouver où est chaque point, et nommer ses sommets. */
    val start: Pair<Double, Double>? = null,
    val end: Pair<Double, Double>? = null,
) {
    val summit: ProfileLabel? get() = labels.firstOrNull()

    /** Nom d'un sommet de la coupe proche de [altitude] (à [tolerance] m près), ou null. */
    fun nameNear(altitude: Double, tolerance: Double = 200.0): String? =
        peaks.filter { abs(it.elevation - altitude) <= tolerance }.minByOrNull { abs(it.elevation - altitude) }?.name

    fun with(backdrop: Backdrop?) = TerrainProfile(elevations, lengthKm, placeIndex, labels, peaks, bearing, backdrop, start, end)

    fun withLabels(labels: List<ProfileLabel>) = TerrainProfile(elevations, lengthKm, placeIndex, labels, peaks, bearing, backdrop, start, end)

    /** Au moins un sommet de la coupe a un nom. */
    val named: Boolean get() = labels.any { it.name != null }

    /** Où est l'échantillon [index] de la coupe (latitude, longitude), si l'on connaît ses deux bouts. */
    fun pointAt(index: Int): Pair<Double, Double>? {
        val a = start ?: return null
        val b = end ?: return null
        val f = index.toDouble() / (elevations.size - 1).coerceAtLeast(1)
        return (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
    }
}
