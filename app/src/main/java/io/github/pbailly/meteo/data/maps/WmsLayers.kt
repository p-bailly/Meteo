package io.github.pbailly.meteo.data.maps

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

// Couches WMS servies en tuiles (satellite, foudre) : sans réseau, reprises telles quelles par la webapp.

/**
 * Heures disponibles d'une couche WMS (dimension « time » de GetCapabilities) : liste d'heures,
 * intervalles « début/fin/pas » (ISO 8601) ou listes abrégées « a,b,…,y,z ».
 * Seules les heures comprises entre [from] et [to] sont gardées.
 */
object WmsTimes {
    private val DIMENSION = Regex("<Dimension[^>]*name=\"time\"[^>]*>([^<]*)</Dimension>", RegexOption.IGNORE_CASE)

    fun fromCapabilities(xml: String, from: Instant, to: Instant): List<Instant> =
        DIMENSION.find(xml)?.groupValues?.get(1)?.let { parse(it, from, to) } ?: emptyList()

    fun parse(text: String, from: Instant, to: Instant): List<Instant> {
        val out = sortedSetOf<Instant>()
        val items = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        var previous: Instant? = null
        var step: Duration? = null
        var pendingGap = false
        for (item in items) {
            if (item == "...") {
                pendingGap = true
                continue
            }
            val parts = item.split('/')
            if (parts.size == 3) {
                val start = Instant.parse(parts[0])
                val end = Instant.parse(parts[1])
                val period = Duration.parse(parts[2])
                expand(start, end, period, from, to, out)
                previous = end
                step = period
                continue
            }
            val time = Instant.parse(item)
            if (pendingGap && previous != null && step != null) expand(previous, time, step, from, to, out)
            if (!time.isBefore(from) && !time.isAfter(to)) out += time
            previous?.let { step = Duration.between(it, time).takeIf { d -> !d.isNegative && !d.isZero } ?: step }
            previous = time
            pendingGap = false
        }
        return out.toList()
    }

    /** Ajoute les heures start, start + period… jusqu'à end, sans parcourir des années d'archives. */
    private fun expand(start: Instant, end: Instant, period: Duration, from: Instant, to: Instant, out: MutableSet<Instant>) {
        if (period.isZero || period.isNegative || end.isBefore(from) || start.isAfter(to)) return
        val skip = if (start.isBefore(from)) Duration.between(start, from).toMillis() / period.toMillis() else 0
        var t = start.plusMillis(skip * period.toMillis())
        while (!t.isAfter(end) && !t.isAfter(to)) {
            if (!t.isBefore(from)) out += t
            t = t.plus(period)
        }
    }
}

/** Couche WMS servie en tuiles Web Mercator (EPSG:3857), utilisable directement par la carte. */
data class TileLayer(
    val baseUrl: String,
    val layer: String,
    val format: String,
    val transparent: Boolean,
    val capabilitiesUrl: String,
    val attribution: String,
    /** Fenêtre d'heures proposée dans la frise, avant maintenant. */
    val history: Duration,
) {
    /** Modèle d'URL de tuile pour MapLibre : la carte remplace {bbox-epsg-3857}. */
    fun tileUrl(time: Instant): String =
        "$baseUrl?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=$layer&STYLES=&CRS=EPSG:3857" +
            "&BBOX={bbox-epsg-3857}&WIDTH=512&HEIGHT=512&FORMAT=$format&TRANSPARENT=$transparent" +
            "&TIME=" + DateTimeFormatter.ISO_INSTANT.format(time)

    companion object {
        /** Meteosat de 3e génération (MTG), couleurs « GeoColour » : vraies couleurs le jour, nuages et lumières la nuit. */
        val SATELLITE = TileLayer(
            baseUrl = "https://view.eumetsat.int/geoserver/wms",
            layer = "mtg_fd:rgb_geocolour",
            format = "image/jpeg",
            transparent = false,
            capabilitiesUrl = "https://view.eumetsat.int/geoserver/mtg_fd/rgb_geocolour/ows?service=WMS&request=GetCapabilities&version=1.3.0",
            attribution = "EUMETSAT Meteosat",
            history = Duration.ofHours(2),
        )

        /** Imageur d'éclairs de Meteosat MTG : nombre d'éclairs en 5 minutes, sur une grille de 2 km. */
        val LIGHTNING = TileLayer(
            baseUrl = "https://view.eumetsat.int/geoserver/wms",
            layer = "mtg_fd:li_afa",
            format = "image/png",
            transparent = true,
            capabilitiesUrl = "https://view.eumetsat.int/geoserver/mtg_fd/li_afa/ows?service=WMS&request=GetCapabilities&version=1.3.0",
            attribution = "EUMETSAT Meteosat Lightning Imager",
            history = Duration.ofHours(1),
        )
    }
}
