package io.github.pbailly.meteo.data.radar

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/** Une prévision PIAF disponible : identifiant WCS et heure de calcul (réseau). */
data class PiafRun(val coverageId: String, val runTime: Instant)

/**
 * Prévision immédiate des précipitations de Météo-France (PIAF, API « PrevisionImmediatePrecipitations »,
 * gratuite) : fusion de l'extrapolation radar et du modèle AROME-PI, 1 km, toutes les 5 min, jusqu'à +3 h.
 *
 * Les images sont demandées au service WCS en GeoTIFF sur une zone autour du lieu (pour limiter
 * le volume téléchargé), puis colorées comme le radar et enregistrées en PNG Web Mercator.
 */
class MeteoFrancePiaf(
    private val dir: File,
    private val http: (url: String, apiKey: String, accept: String) -> ByteArray = ::meteoFranceGet,
) {
    private val mutex = Mutex()

    /**
     * Images prévues après [after] (en général la dernière observation radar), toutes les [STEP_MINUTES],
     * autour de ([lat], [lon]). Réutilise celles déjà calculées pour le même réseau et la même zone.
     */
    suspend fun forecast(apiKey: String, lat: Double, lon: Double, after: Instant): List<RadarFrame> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val caps = http("$WCS/GetCapabilities?service=WCS&version=2.0.1&language=eng", apiKey, "application/xml")
            val run = latestRun(caps.decodeToString()) ?: error("aucune prévision PIAF annoncée")
            val area = Area.around(lat, lon)
            val folder = File(dir, "v2_${run.runTime.epochSecond}_${area.key}")
            if (!folder.isDirectory) {
                dir.listFiles()?.forEach { it.deleteRecursively() } // anciens réseaux ou autre zone
                folder.mkdirs()
            }
            val start = maxOf(after, run.runTime).truncatedTo(ChronoUnit.MINUTES)
            val times = validTimes(run.runTime, start)
            var lookup: MercatorLookup? = null
            // Unité et valeurs manquantes annoncées par le service (DescribeCoverage), lues une fois par réseau.
            val description by lazy {
                runCatching {
                    val url = "$WCS/DescribeCoverage?service=WCS&version=2.0.1&coverageid=" + URLEncoder.encode(run.coverageId, "UTF-8")
                    CoverageDescription.parse(http(url, apiKey, "application/xml").decodeToString())
                }.getOrNull()
            }
            for (time in times) {
                val file = File(folder, "${time.epochSecond}.png")
                if (file.exists()) continue
                val url = "$WCS/GetCoverage?service=WCS&version=2.0.1" +
                    "&coverageid=" + URLEncoder.encode(run.coverageId, "UTF-8") +
                    "&subset=" + URLEncoder.encode("time(${ISO.format(time)})", "UTF-8") +
                    "&subset=" + URLEncoder.encode("lat(${area.south},${area.north})", "UTF-8") +
                    "&subset=" + URLEncoder.encode("long(${area.west},${area.east})", "UTF-8") +
                    "&format=image/tiff"
                val grid = toGrid(GeoTiff.read(http(url, apiKey, "image/tiff")), run.coverageId, area, description)
                val table = lookup?.takeIf { it.gridWidth == grid.width && it.gridHeight == grid.height }
                    ?: MercatorLookup.forGrid(MeteoFranceRadar.BOUNDS, MeteoFranceRadar.IMAGE_WIDTH, grid).also { lookup = it }
                val bitmap = Bitmap.createBitmap(table.render(grid.mmPerHour), table.width, table.height, Bitmap.Config.ARGB_8888)
                val tmp = File(folder, "${time.epochSecond}.tmp")
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                tmp.renameTo(file)
            }
            times.map { RadarFrame(it, File(folder, "${it.epochSecond}.png")) }.filter { it.file.exists() }
        }
    }

    /** Zone demandée : ± 2° de latitude et ± 3° de longitude autour du lieu, dans le domaine PIAF. */
    data class Area(val south: Double, val north: Double, val west: Double, val east: Double) {
        val key: String get() = String.format(Locale.ROOT, "%.1f_%.1f", south, west)

        companion object {
            fun around(lat: Double, lon: Double): Area {
                // Arrondi au demi-degré : la même zone (et le même cache) tant qu'on reste dans le coin.
                val cLat = Math.round(lat * 2) / 2.0
                val cLon = Math.round(lon * 2) / 2.0
                return Area(
                    south = (cLat - 2.0).coerceIn(DOMAIN.south, DOMAIN.north - 4.0),
                    north = (cLat + 2.0).coerceIn(DOMAIN.south + 4.0, DOMAIN.north),
                    west = (cLon - 3.0).coerceIn(DOMAIN.west, DOMAIN.east - 6.0),
                    east = (cLon + 3.0).coerceIn(DOMAIN.west + 6.0, DOMAIN.east),
                )
            }
        }
    }

    companion object {
        private const val WCS = "https://api.meteofrance.fr/pro/piaf/1.0/wcs/MF-NWP-HIGHRES-PIAF-001-FRANCE-WCS"
        private val ISO = DateTimeFormatter.ISO_INSTANT

        /** France métropolitaine (domaine prudent : la grille PIAF couvre au moins cette zone). */
        private val DOMAIN = Area(south = 41.0, north = 51.5, west = -5.5, east = 10.0)

        const val STEP_MINUTES = 15L
        private const val HORIZON_MINUTES = 180L

        private val COVERAGE = Regex("<(?:[A-Za-z0-9]+:)?CoverageId>\\s*([^<\\s]+)\\s*</")
        private val RUN = Regex("___(\\d{4}-\\d{2}-\\d{2}T\\d{2})\\.(\\d{2})\\.(\\d{2})Z")

        /**
         * Dernier réseau annoncé dans la réponse GetCapabilities. Les identifiants WCS de Météo-France
         * finissent par l'heure du réseau : `PARAMETRE__NIVEAU___2026-10-19T17.10.00Z[_PT5M]`.
         */
        fun latestRun(capabilities: String): PiafRun? {
            val runs = COVERAGE.findAll(capabilities).mapNotNull { match ->
                val id = match.groupValues[1]
                val t = RUN.find(id) ?: return@mapNotNull null
                val time = runCatching {
                    Instant.parse("${t.groupValues[1]}:${t.groupValues[2]}:${t.groupValues[3]}Z")
                }.getOrNull() ?: return@mapNotNull null
                PiafRun(id, time)
            }.toList()
            // On préfère le paramètre de précipitations s'il y en a plusieurs.
            val precipitation = runs.filter { run -> listOf("PRECIP", "RR5", "RAIN").any { it in run.coverageId.uppercase() } }
            return precipitation.ifEmpty { runs }.maxWithOrNull(compareBy({ it.runTime }, { it.coverageId.length }))
        }

        /** Heures prévues : après [start], au pas de [STEP_MINUTES], jusqu'à 3 h après le réseau. */
        fun validTimes(runTime: Instant, start: Instant): List<Instant> {
            val end = runTime.plus(Duration.ofMinutes(HORIZON_MINUTES))
            val first = start.plus(Duration.ofMinutes(STEP_MINUTES))
            return generateSequence(first) { it.plus(Duration.ofMinutes(STEP_MINUTES)) }
                .takeWhile { !it.isAfter(end) }
                .toList()
        }

        /**
         * Grille en mm/h. L'unité vient de la description du service quand elle est connue (cumul en kg/m² ou mm sur
         * la période de l'identifiant, flux en kg m⁻² s⁻¹, intensité en mm/h, réflectivité en dBZ) ; sinon de
         * l'identifiant (« RATE » : flux, sinon cumul sur 5 minutes). Si le résultat est invraisemblable (pluie
         * médiane au-delà de 25 mm/h, ce qui colorerait toute la zone en « grêle »), l'échelle est corrigée.
         */
        fun toGrid(image: GeoTiffImage, coverageId: String, area: Area, description: CoverageDescription? = null): RadarGrid {
            val noData = buildSet {
                image.noData?.let { add(it) }
                description?.nilValues?.let { addAll(it) }
            }
            val raw = FloatArray(image.values.size) { i ->
                val v = image.values[i]
                if (v.isNaN() || v.toDouble() in noData || v > 1e6f) Float.NaN else v
            }
            dropFillValue(raw)
            val unit = Conversion.of(description?.unit, coverageId)
            val mmPerHour = when (unit) {
                Conversion.Reflectivity -> FloatArray(raw.size) { i ->
                    val v = raw[i]
                    if (v.isNaN()) Float.NaN else if (v < 7f) 0f else RainClass.dbzToMmPerHour(v.toDouble()).toFloat()
                }
                is Conversion.Factor -> {
                    val factor = plausibleFactor(raw, unit.factor)
                    FloatArray(raw.size) { i ->
                        val v = raw[i]
                        if (v.isNaN()) Float.NaN else if (v <= 0f) 0f else v * factor
                    }
                }
            }
            // Calage du GeoTIFF, ou à défaut la zone demandée.
            val originX = image.originX ?: area.west
            val originY = image.originY ?: area.north
            val sx = image.scaleX ?: ((area.east - area.west) / image.width)
            val sy = image.scaleY ?: ((area.north - area.south) / image.height)
            val projection = GridProjection { lat, lon, out ->
                out[0] = (lon - originX) / sx - 0.5
                out[1] = (originY - lat) / sy - 0.5
            }
            return RadarGrid(image.width, image.height, mmPerHour, null, projection)
        }

        /**
         * Une même grande valeur (99 ou plus : 999, 9999, 65535…) sur plus de la moitié de la zone : c'est une valeur de
         * remplissage, pas de la pluie.
         */
        private fun dropFillValue(raw: FloatArray) {
            val counts = HashMap<Float, Int>()
            var valid = 0
            for (v in raw) {
                if (v.isNaN()) continue
                valid++
                if (v > 0f) counts[v] = (counts[v] ?: 0) + 1
            }
            val (value, count) = counts.maxByOrNull { it.value } ?: return
            if (count * 2 > valid && value >= 99f) for (i in raw.indices) if (raw[i] == value) raw[i] = Float.NaN
        }

        /** Facteurs d'échelle possibles, du plus grand au plus petit (flux par seconde, cumuls, mm/h, centièmes…). */
        private val FACTORS = floatArrayOf(3600f, 60f, 12f, 4f, 1f, 0.1f, 0.01f)

        /**
         * [factor], sauf si la pluie médiane dépasserait 25 mm/h : les intensités usuelles sont de l'ordre de 0,5 à
         * 5 mm/h, et une médiane aussi forte trahit une erreur d'unité. On prend alors le facteur plus petit qui donne
         * la médiane la plus proche de 1,5 mm/h.
         */
        fun plausibleFactor(raw: FloatArray, factor: Float): Float {
            val wet = raw.filter { !it.isNaN() && it > 0f }
            if (wet.size < 20) return factor
            val median = wet.sorted()[wet.size / 2]
            if (median * factor <= 25f) return factor
            return FACTORS.filter { it < factor }.minByOrNull { abs(ln(median * it / 1.5f)) } ?: factor
        }
    }
}

/** Unité des valeurs d'une couverture WCS et valeurs « sans donnée », lues dans DescribeCoverage. */
class CoverageDescription(val unit: String?, val nilValues: List<Double>) {
    companion object {
        private val UOM = Regex("<(?:[A-Za-z0-9]+:)?uom[^>]*\\scode=\"([^\"]+)\"")
        private val NIL = Regex("<(?:[A-Za-z0-9]+:)?nilValue[^>]*>\\s*([^<\\s]+)\\s*</")

        fun parse(xml: String): CoverageDescription = CoverageDescription(
            unit = UOM.find(xml)?.groupValues?.get(1),
            nilValues = NIL.findAll(xml).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList(),
        )
    }
}

/** Conversion des valeurs PIAF en mm/h. */
sealed interface Conversion {
    /** Multiplier par [factor]. */
    class Factor(val factor: Float) : Conversion
    /** Réflectivité radar (dBZ), relation de Marshall-Palmer. */
    object Reflectivity : Conversion

    companion object {
        private val PERIOD = Regex("_PT(\\d+)([MH])$")

        fun of(unit: String?, coverageId: String): Conversion {
            val id = coverageId.uppercase()
            val u = unit?.lowercase()?.replace(Regex("[\\s.*^]"), "")
            // Période de cumul de l'identifiant (« _PT5M », « _PT1H ») : 5 minutes par défaut.
            val minutes = PERIOD.find(id)?.let { m -> m.groupValues[1].toInt() * if (m.groupValues[2] == "H") 60 else 1 } ?: 5
            val accumulation = Factor(60f / minutes.coerceAtLeast(1))
            return when {
                u != null && "dbz" in u -> Reflectivity
                u != null && ("s-1" in u || "/s" in u) -> Factor(3600f)
                u != null && ("h-1" in u || "/h" in u) -> Factor(1f)
                u != null && ("min-1" in u || "/min" in u) -> Factor(60f)
                u != null && (u == "mm" || u == "kgm-2" || u == "kg/m2" || u == "kgm2") -> accumulation
                u == "m" -> Factor(accumulation.factor * 1000f)
                "REFLECTIVITY" in id || "DBZ" in id -> Reflectivity
                "RATE" in id -> Factor(3600f)
                else -> accumulation
            }
        }
    }
}
