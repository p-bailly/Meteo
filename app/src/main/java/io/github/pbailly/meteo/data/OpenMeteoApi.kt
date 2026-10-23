package io.github.pbailly.meteo.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Construction des URL et lecture des réponses de l'API Open-Meteo (https://open-meteo.com).
 * Données sous licence CC BY 4.0.
 */
object OpenMeteoApi {
    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
    private const val ELEVATION_URL = "https://api.open-meteo.com/v1/elevation"
    private const val ENSEMBLE_URL = "https://ensemble-api.open-meteo.com/v1/ensemble"

    /** Niveaux de pression demandés : de 925 hPa (vers 750 m) à 500 hPa (vers 5 500 m, au-dessus du mont Blanc). */
    val PRESSURE_LEVELS = listOf(925, 850, 700, 600, 500)

    /** Jours demandés pour les favoris : de quoi annoncer la neige ou le verglas de la semaine. */
    const val SUMMARY_DAYS = 8

    /** L'API d'altitude accepte au plus 100 points par appel. */
    const val ELEVATION_MAX_POINTS = 100

    const val FORECAST_DAYS = 14
    /** 14 jours : la prévisibilité de chaque jour se calcule sur l'accord entre modèles. */
    const val MULTI_MODEL_DAYS = 14

    private val HOURLY = listOf(
        "temperature_2m", "apparent_temperature", "relative_humidity_2m",
        "precipitation", "snowfall", "precipitation_probability", "weather_code",
        "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high",
        "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m", "pressure_msl", "is_day", "uv_index",
        // Neige au sol (pour le dessin d'Aujourd'hui).
        "snow_depth",
        // Altitude : iso 0 °C, puis température, altitude réelle et vent de chaque niveau de pression.
        "freezing_level_height",
    ) + PRESSURE_LEVELS.flatMap { p ->
        listOf("temperature_${p}hPa", "geopotential_height_${p}hPa", "wind_speed_${p}hPa", "wind_direction_${p}hPa")
    }
    private val DAILY = listOf(
        "weather_code", "temperature_2m_max", "temperature_2m_min",
        "precipitation_sum", "precipitation_probability_max",
        "wind_speed_10m_max", "wind_gusts_10m_max", "wind_direction_10m_dominant",
        "sunshine_duration", "uv_index_max", "sunrise", "sunset",
    )
    private val CURRENT = listOf(
        "temperature_2m", "apparent_temperature", "relative_humidity_2m", "weather_code", "is_day",
        "precipitation", "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m", "pressure_msl",
    )
    private val MULTI_MODEL_HOURLY = listOf("temperature_2m", "precipitation", "wind_speed_10m")

    /** Liste des lieux : de quoi écrire la température, le ressenti, la neige et le verglas à venir. */
    private val SUMMARY_CURRENT = listOf(
        "temperature_2m", "apparent_temperature", "weather_code", "is_day", "wind_speed_10m", "wind_direction_10m",
    )
    /** Heures : pour le pictogramme représentatif de chaque jour. */
    private val SUMMARY_HOURLY = listOf("precipitation", "weather_code", "is_day")
    /** Jours : pour la liste des lieux et leur comparaison. */
    private val SUMMARY_DAILY = listOf(
        "weather_code", "temperature_2m_max", "temperature_2m_min", "precipitation_sum", "wind_speed_10m_max",
        "wind_direction_10m_dominant", "sunshine_duration",
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun forecastUrl(place: Place): String = buildUrl(
        FORECAST_URL,
        "latitude" to coord(place.latitude),
        "longitude" to coord(place.longitude),
        "hourly" to HOURLY.joinToString(","),
        "daily" to DAILY.joinToString(","),
        "current" to CURRENT.joinToString(","),
        "timezone" to "auto",
        "forecast_days" to FORECAST_DAYS.toString(),
        "wind_speed_unit" to "kmh",
    )

    fun multiModelUrl(place: Place, models: List<WeatherModel>): String = buildUrl(
        FORECAST_URL,
        "latitude" to coord(place.latitude),
        "longitude" to coord(place.longitude),
        "hourly" to MULTI_MODEL_HOURLY.joinToString(","),
        "models" to models.joinToString(",") { it.apiId },
        "timezone" to "auto",
        "forecast_days" to MULTI_MODEL_DAYS.toString(),
        "wind_speed_unit" to "kmh",
    )

    /** Un seul appel pour tous les lieux de la liste : Open-Meteo accepte des coordonnées séparées par des virgules. */
    fun summaryUrl(places: List<Place>): String = buildUrl(
        FORECAST_URL,
        "latitude" to places.joinToString(",") { coord(it.latitude) },
        "longitude" to places.joinToString(",") { coord(it.longitude) },
        "current" to SUMMARY_CURRENT.joinToString(","),
        "hourly" to SUMMARY_HOURLY.joinToString(","),
        "daily" to SUMMARY_DAILY.joinToString(","),
        "timezone" to "auto",
        "forecast_days" to SUMMARY_DAYS.toString(),
        "wind_speed_unit" to "kmh",
    )

    /** Altitudes du modèle numérique de terrain Copernicus (90 m) pour une liste de points (≤ 100). */
    fun elevationUrl(points: List<Pair<Double, Double>>): String = buildUrl(
        ELEVATION_URL,
        "latitude" to points.joinToString(",") { coord(it.first) },
        "longitude" to points.joinToString(",") { coord(it.second) },
    )

    fun parseElevation(body: String): DoubleArray {
        val root = parseRoot(body)
        val values = root["elevation"] as? JsonArray ?: throw OpenMeteoException("Réponse incomplète : « elevation » manquant")
        return DoubleArray(values.size) { values[it].jsonPrimitive.doubleOrNull.validElevation() ?: Double.NaN }
    }

    fun searchUrl(query: String): String = buildUrl(
        GEOCODING_URL,
        "name" to query.trim(),
        "count" to "20",
        "language" to "fr",
        "format" to "json",
    )

    /**
     * Les lieux trouvés, les plus importants d'abord : ceux dont le nom est exactement celui cherché (sans tenir compte
     * des accents ni des majuscules), puis ceux qui commencent par lui ; à égalité, une capitale ou un chef-lieu avant
     * un village, les plus peuplés avant les autres, et la France un peu avant le reste du monde.
     */
    fun parseSearch(body: String, query: String = ""): List<Place> {
        val root = parseRoot(body)
        val results = root["results"] as? JsonArray ?: return emptyList()
        val wanted = searchKey(query)
        return results.mapIndexedNotNull { index, element ->
            val o = element.jsonObject
            val lat = o.double("latitude") ?: return@mapIndexedNotNull null
            val lon = o.double("longitude") ?: return@mapIndexedNotNull null
            val place = Place(
                name = o.string("name") ?: return@mapIndexedNotNull null,
                latitude = lat,
                longitude = lon,
                admin1 = o.string("admin1"),
                admin2 = o.string("admin2"),
                country = o.string("country"),
                elevation = o.double("elevation").validElevation(),
            )
            val name = searchKey(place.name)
            val match = when {
                wanted.isEmpty() -> 0
                name == wanted -> 3
                name.startsWith(wanted) -> 2
                wanted in name -> 1
                else -> 0
            }
            val importance = when (o.string("feature_code")) {
                "PPLC" -> 4.0
                "PPLA" -> 3.0
                "PPLA2" -> 2.0
                "PPLA3" -> 1.2
                "PPLA4" -> 0.6
                else -> 0.0
            }
            val people = o.double("population") ?: 0.0
            val france = if (o.string("country_code") == "FR") 1.0 else 0.0
            // Le rang donné par Open-Meteo départage le reste.
            Triple(place, match, importance + kotlin.math.log10(people + 1) + france - index * 0.01)
        }.sortedWith(compareByDescending<Triple<Place, Int, Double>> { it.second }.thenByDescending { it.third }).map { it.first }
    }

    /** Le nom tel qu'on le tape : sans accents, en minuscules, les tirets et apostrophes comme des espaces. */
    fun searchKey(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(java.util.Locale.ROOT).replace(Regex("[-'’]"), " ").replace(Regex("\\s+"), " ").trim()

    fun parseForecast(body: String): Forecast = forecastOf(parseRoot(body))

    /** Réponse à plusieurs lieux : un tableau, dans l'ordre des coordonnées (un objet seul s'il n'y en a qu'un). */
    fun parseForecasts(body: String): List<Forecast> {
        val root = json.parseToJsonElement(body)
        if (root is JsonArray) return root.map { forecastOf(checked(it.jsonObject)) }
        return listOf(forecastOf(checked(root.jsonObject)))
    }

    private fun forecastOf(root: JsonObject): Forecast {
        val h = root.obj("hourly")
        val hourTimes = h.strings("time").map { LocalDateTime.parse(it) }
        val n = hourTimes.size
        val hourly = HourlySeries(
            time = hourTimes,
            temperature = h.doubles("temperature_2m", n),
            apparentTemperature = h.doubles("apparent_temperature", n),
            humidity = h.doubles("relative_humidity_2m", n),
            precipitation = h.doubles("precipitation", n),
            snowfall = h.doubles("snowfall", n),
            precipitationProbability = h.doubles("precipitation_probability", n),
            weatherCode = h.ints("weather_code", n),
            cloudCoverLow = h.doubles("cloud_cover_low", n),
            cloudCoverMid = h.doubles("cloud_cover_mid", n),
            cloudCoverHigh = h.doubles("cloud_cover_high", n),
            windSpeed = h.doubles("wind_speed_10m", n),
            windDirection = h.doubles("wind_direction_10m", n),
            windGusts = h.doubles("wind_gusts_10m", n),
            pressure = h.doubles("pressure_msl", n),
            isDay = h.ints("is_day", n).let { codes -> BooleanArray(n) { codes[it] != 0 } },
            uvIndex = h.doubles("uv_index", n),
            freezingLevel = h.doubles("freezing_level_height", n),
            snowDepth = h.doubles("snow_depth", n),
            levels = PRESSURE_LEVELS.filter { h.containsKey("temperature_${it}hPa") }.map { p ->
                PressureLevel(
                    hPa = p,
                    temperature = h.doubles("temperature_${p}hPa", n),
                    height = h.doubles("geopotential_height_${p}hPa", n),
                    windSpeed = h.doubles("wind_speed_${p}hPa", n),
                    windDirection = h.doubles("wind_direction_${p}hPa", n),
                )
            },
        )

        val d = root.obj("daily")
        val days = d.strings("time").map { LocalDate.parse(it) }
        val m = days.size
        val daily = DailySeries(
            date = days,
            weatherCode = d.ints("weather_code", m),
            temperatureMax = d.doubles("temperature_2m_max", m),
            temperatureMin = d.doubles("temperature_2m_min", m),
            precipitationSum = d.doubles("precipitation_sum", m),
            precipitationProbabilityMax = d.doubles("precipitation_probability_max", m),
            windSpeedMax = d.doubles("wind_speed_10m_max", m),
            windGustsMax = d.doubles("wind_gusts_10m_max", m),
            windDirectionDominant = d.doubles("wind_direction_10m_dominant", m),
            sunshineDuration = d.doubles("sunshine_duration", m),
            uvIndexMax = d.doubles("uv_index_max", m),
            sunrise = d.optionalStrings("sunrise", m).map { s -> s?.let { LocalDateTime.parse(it) } },
            sunset = d.optionalStrings("sunset", m).map { s -> s?.let { LocalDateTime.parse(it) } },
        )

        val current = (root["current"] as? JsonObject)?.let { c ->
            CurrentConditions(
                time = LocalDateTime.parse(c.string("time") ?: return@let null),
                temperature = c.double("temperature_2m") ?: Double.NaN,
                apparentTemperature = c.double("apparent_temperature") ?: Double.NaN,
                humidity = c.double("relative_humidity_2m") ?: Double.NaN,
                weatherCode = c.double("weather_code")?.toInt() ?: -1,
                isDay = (c.double("is_day") ?: 1.0) != 0.0,
                precipitation = c.double("precipitation") ?: Double.NaN,
                windSpeed = c.double("wind_speed_10m") ?: Double.NaN,
                windDirection = c.double("wind_direction_10m") ?: Double.NaN,
                windGusts = c.double("wind_gusts_10m") ?: Double.NaN,
                pressure = c.double("pressure_msl") ?: Double.NaN,
            )
        }

        return Forecast(
            latitude = root.double("latitude") ?: Double.NaN,
            longitude = root.double("longitude") ?: Double.NaN,
            elevation = root.double("elevation").validElevation() ?: Double.NaN,
            timezone = root.string("timezone") ?: "GMT",
            utcOffsetSeconds = root.double("utc_offset_seconds")?.toInt() ?: 0,
            current = current,
            hourly = hourly,
            daily = daily,
        )
    }

    /**
     * Les cinq prévisions d'ensemble mondiales qui vont au moins à 7 jours : ECMWF IFS et AIFS (51 scénarios chacune,
     * 15 jours), NOAA GEFS (31, 10 jours), DWD ICON (40, 7 jours et demi), Canada GEM (21, 16 jours).
     */
    private val ENSEMBLE_MODELS = listOf("ecmwf_ifs025", "ecmwf_aifs025", "ncep_gefs025", "icon_seamless", "gem_global")

    /** Prévisions d'ensemble : température, pluie, nébulosité et vent horaires sur 15 jours. */
    fun ensembleUrl(place: Place): String = buildUrl(
        ENSEMBLE_URL,
        "latitude" to coord(place.latitude),
        "longitude" to coord(place.longitude),
        "hourly" to "temperature_2m,precipitation,cloud_cover,wind_speed_10m",
        "models" to ENSEMBLE_MODELS.joinToString(","),
        "forecast_days" to "15",
        "timezone" to "auto",
    )

    /** « temperature_2m_member01_ncep_gefs025 » : variable, numéro du scénario (aucun pour le contrôle), modèle. */
    private val ENSEMBLE_KEY = Regex("(temperature_2m|precipitation|cloud_cover|wind_speed_10m)(?:_member(\\d+))?(?:_(.+))?")

    /** Les scénarios de chaque modèle : « temperature_2m_<modèle> » (le contrôle), « temperature_2m_member01_<modèle> »… */
    fun parseEnsemble(body: String): EnsembleForecast {
        val root = parseRoot(body)
        val h = root.obj("hourly")
        val times = h.strings("time").map { LocalDateTime.parse(it) }
        val n = times.size
        // Modèle, puis numéro du scénario → variable → série.
        val scenarios = sortedMapOf<String, MutableMap<String, DoubleArray>>()
        for (key in h.keys) {
            val match = ENSEMBLE_KEY.matchEntire(key) ?: continue
            val (variable, member, model) = match.destructured
            val id = model + "#" + (member.toIntOrNull() ?: 0).toString().padStart(3, '0')
            scenarios.getOrPut(id) { mutableMapOf() }[variable] = h.doubles(key, n)
        }
        val complete = scenarios.filter { "temperature_2m" in it.value && "precipitation" in it.value }
        if (complete.isEmpty()) throw OpenMeteoException("Réponse incomplète : scénarios manquants")
        fun series(variable: String) = complete.values.map { it[variable] ?: DoubleArray(n) { Double.NaN } }
        return EnsembleForecast(
            time = times,
            temperature = series("temperature_2m"),
            precipitation = series("precipitation"),
            model = complete.keys.map { it.substringBefore('#') },
            cloudCover = series("cloud_cover"),
            windSpeed = series("wind_speed_10m"),
        )
    }

    /**
     * Avec plusieurs modèles, Open-Meteo suffixe chaque variable par l'identifiant du modèle :
     * `temperature_2m_icon_seamless`, `temperature_2m_gfs_seamless`, etc.
     */
    fun parseMultiModel(body: String, models: List<WeatherModel>): MultiModelForecast {
        val root = parseRoot(body)
        val h = root.obj("hourly")
        val times = h.strings("time").map { LocalDateTime.parse(it) }
        val n = times.size
        val series = models.associateWith { model ->
            ModelSeries(
                temperature = h.doubles("temperature_2m_${model.apiId}", n),
                precipitation = h.doubles("precipitation_${model.apiId}", n),
                windSpeed = h.doubles("wind_speed_10m_${model.apiId}", n),
            )
        }
        return MultiModelForecast(
            time = times,
            utcOffsetSeconds = root.double("utc_offset_seconds")?.toInt() ?: 0,
            series = series,
        )
    }

    private fun parseRoot(body: String): JsonObject = checked(json.parseToJsonElement(body).jsonObject)

    private fun checked(root: JsonObject): JsonObject {
        if (root["error"]?.jsonPrimitive?.booleanOrNull == true) {
            throw OpenMeteoException(root.string("reason") ?: "Erreur Open-Meteo")
        }
        return root
    }

    private fun buildUrl(base: String, vararg params: Pair<String, String>): String =
        base + "?" + params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

    private fun coord(value: Double) = String.format(Locale.ROOT, "%.4f", value)

    private fun JsonObject.obj(key: String): JsonObject =
        this[key] as? JsonObject ?: throw OpenMeteoException("Réponse incomplète : « $key » manquant")

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.strings(key: String): List<String> =
        this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    private fun JsonObject.optionalStrings(key: String, size: Int): List<String?> {
        val arr = this[key] as? JsonArray ?: return List(size) { null }
        return List(size) { i -> arr.getOrNull(i)?.jsonPrimitive?.contentOrNull }
    }

    private fun JsonObject.doubles(key: String, size: Int): DoubleArray {
        val arr = this[key] as? JsonArray ?: return DoubleArray(size) { Double.NaN }
        return DoubleArray(size) { i -> arr.getOrNull(i)?.jsonPrimitive?.doubleOrNull ?: Double.NaN }
    }

    private fun JsonObject.ints(key: String, size: Int): IntArray {
        val arr = this[key] as? JsonArray ?: return IntArray(size) { -1 }
        return IntArray(size) { i ->
            val p = arr.getOrNull(i)?.jsonPrimitive
            p?.intOrNull ?: p?.doubleOrNull?.toInt() ?: -1
        }
    }
}

class OpenMeteoException(message: String) : Exception(message)

/** Altitude plausible, ou null : Open-Meteo renvoie -9999 quand il n'en connaît pas (Tromsø, par exemple). */
fun Double?.validElevation(): Double? = this?.takeIf { !it.isNaN() && it > -1000 }
