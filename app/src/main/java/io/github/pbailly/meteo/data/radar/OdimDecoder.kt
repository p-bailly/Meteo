package io.github.pbailly.meteo.data.radar

import io.jhdf.HdfFile
import io.jhdf.api.Node
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Une mosaïque radar décodée, ramenée à des intensités en mm/h.
 * Les cases sont rangées ligne par ligne, la ligne 0 au nord ; NaN = hors couverture.
 */
class RadarGrid(
    val width: Int,
    val height: Int,
    val mmPerHour: FloatArray,
    /** Fin de la période de cumul (UTC), si le fichier l'indique. */
    val time: Instant?,
    val projection: GridProjection,
)

/**
 * Lecture des fichiers ODIM_H5 de la lame d'eau radar Météo-France (`T_IPRN20_*.h5`, maille 500 m) :
 * cumul de précipitations sur 5 minutes, en centièmes de mm, sur une grille stéréographique polaire.
 */
object OdimDecoder {
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /**
     * @param factor regroupe les cases par blocs de factor × factor en gardant le maximum,
     *   pour alléger la mémoire (2 → maille de 1 km) sans perdre les averses localisées.
     */
    fun decode(bytes: ByteArray, factor: Int = 1): RadarGrid = HdfFile.fromBytes(bytes).use { hdf ->
        val where = hdf.getByPath("/where")
        val width = where.number("xsize").toInt()
        val height = where.number("ysize").toInt()
        val xScale = where.number("xscale")
        val yScale = where.number("yscale")
        val projection = PolarStereographic.fromProjDef(where.string("projdef"))

        val what = hdf.getByPath("/dataset1/data1/what")
        val quantity = what.stringOrNull("quantity")
        require(quantity == null || quantity == "ACRR") { "Produit radar inattendu : $quantity" }
        val gain = what.numberOrNull("gain") ?: 0.01
        val offset = what.numberOrNull("offset") ?: 0.0
        val noData = what.numberOrNull("nodata")?.toInt() ?: 65535
        val undetect = what.numberOrNull("undetect")?.toInt() ?: 65534

        val raw = hdf.getDatasetByPath("/dataset1/data1/data").dataFlat
        val outW = width / factor
        val outH = height / factor
        val mmPerHour = FloatArray(outW * outH) { Float.NaN }
        for (row in 0 until outH * factor) {
            for (col in 0 until outW * factor) {
                val code = valueAt(raw, row * width + col)
                val mmh = when (code) {
                    noData -> continue
                    undetect -> 0f
                    else -> ((code * gain + offset) * 12.0).toFloat() // cumul 5 min → mm/h
                }
                val k = (row / factor) * outW + col / factor
                val current = mmPerHour[k]
                if (current.isNaN() || mmh > current) mmPerHour[k] = mmh
            }
        }

        // Coin bas-gauche de la grille dans la projection, pour passer de (x, y) à (colonne, ligne).
        val corner = DoubleArray(2)
        projection.forward(where.number("LL_lat"), where.number("LL_lon"), corner)
        val x0 = corner[0]
        val yTop = corner[1] + height * yScale
        val cellX = xScale * factor
        val cellY = yScale * factor
        val gridProjection = GridProjection { lat, lon, out ->
            projection.forward(lat, lon, out)
            val x = out[0]
            val y = out[1]
            out[0] = (x - x0) / cellX - 0.5
            out[1] = (yTop - y) / cellY - 0.5
        }

        val dataset = hdf.getByPath("/dataset1/what")
        val time = runCatching {
            LocalDateTime.parse(dataset.string("enddate") + dataset.string("endtime"), DATE_TIME).toInstant(ZoneOffset.UTC)
        }.getOrNull()

        RadarGrid(outW, outH, mmPerHour, time, gridProjection)
    }

    private fun valueAt(raw: Any, i: Int): Int = when (raw) {
        is IntArray -> raw[i]
        is ShortArray -> raw[i].toInt() and 0xFFFF
        is LongArray -> raw[i].toInt()
        is ByteArray -> raw[i].toInt() and 0xFF
        else -> error("Type de données radar inattendu : ${raw.javaClass.simpleName}")
    }

    private fun Node.attribute(name: String): Any? = getAttribute(name)?.data?.let { data ->
        when (data) {
            is Array<*> -> data.firstOrNull()
            is DoubleArray -> data.firstOrNull()
            is LongArray -> data.firstOrNull()
            is IntArray -> data.firstOrNull()
            else -> data
        }
    }

    private fun Node.numberOrNull(name: String): Double? = when (val v = attribute(name)) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    private fun Node.number(name: String): Double = numberOrNull(name) ?: error("Attribut radar manquant : $name")

    private fun Node.stringOrNull(name: String): String? = attribute(name)?.toString()?.trim()?.trimEnd('\u0000')

    private fun Node.string(name: String): String = stringOrNull(name) ?: error("Attribut radar manquant : $name")
}
