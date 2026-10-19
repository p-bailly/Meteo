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

    const val FORECAST_DAYS = 14
    /** 14 jours : la prévisibilité de chaque jour se calcule sur l'accord entre modèles. */
    const val MULTI_MODEL_DAYS = 14

    private val HOURLY = listOf(
        "temperature_2m", "apparent_temperature", "relative_humidity_2m",
        "precipitation", "snowfall", "precipitation_probability", "weather_code",
        "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high",
        "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m", "pressure_msl", "is_day", "uv_index",
        // Bloc altitude : iso 0 °C et niveaux 850 hPa (≈ 1 500 m) et 700 hPa (≈ 3 000 m).
        "freezing_level_height", "temperature_850hPa", "geopotential_height_850hPa",
        "temperature_700hPa", "geopotential_height_700hPa",
    )
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

    fun searchUrl(query: String): String = buildUrl(
        GEOCODING_URL,
        "name" to query.trim(),
        "count" to "20",
        "language" to "fr",
        "format" to "json",
    )

    fun parseSearch(body: String): List<Place> {
        val root = parseRoot(body)
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val o = element.jsonObject
            val lat = o.double("latitude") ?: return@mapNotNull null
            val lon = o.double("longitude") ?: return@mapNotNull null
            Place(
                name = o.string("name") ?: return@mapNotNull null,
                latitude = lat,
                longitude = lon,
                admin1 = o.string("admin1"),
                country = o.string("country"),
                elevation = o.double("elevation"),
            )
        }
    }

    fun parseForecast(body: String): Forecast {
        val root = parseRoot(body)
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
            temperature850 = h.doubles("temperature_850hPa", n),
            height850 = h.doubles("geopotential_height_850hPa", n),
            temperature700 = h.doubles("temperature_700hPa", n),
            height700 = h.doubles("geopotential_height_700hPa", n),
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
            elevation = root.double("elevation") ?: Double.NaN,
            timezone = root.string("timezone") ?: "GMT",
            utcOffsetSeconds = root.double("utc_offset_seconds")?.toInt() ?: 0,
            current = current,
            hourly = hourly,
            daily = daily,
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

    private fun parseRoot(body: String): JsonObject {
        val root = json.parseToJsonElement(body).jsonObject
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
