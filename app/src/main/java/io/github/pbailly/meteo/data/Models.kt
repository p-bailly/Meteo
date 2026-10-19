package io.github.pbailly.meteo.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** Un lieu (résultat de recherche, favori ou position GPS). */
data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val admin1: String? = null,
    val country: String? = null,
    val elevation: Double? = null,
) {
    /** Identifiant stable, arrondi à ~10 m, utilisé pour les favoris et le cache. */
    val id: String get() = String.format(Locale.ROOT, "%.4f_%.4f", latitude, longitude)

    val subtitle: String get() = listOfNotNull(admin1, country).distinct().joinToString(", ")
}

/** Modèles proposés dans l'onglet MultiModèle. L'ordre fixe la couleur de chaque modèle. */
enum class WeatherModel(val apiId: String, val label: String, val detail: String) {
    METEO_FRANCE("meteofrance_seamless", "Météo-France", "AROME + ARPEGE"),
    ICON("icon_seamless", "DWD ICON", "ICON-D2 + EU + Global"),
    ECMWF_IFS("ecmwf_ifs025", "ECMWF IFS", "Centre européen"),
    ECMWF_AIFS("ecmwf_aifs025_single", "ECMWF AIFS", "Modèle IA du centre européen"),
    GFS("gfs_seamless", "NOAA GFS", "Modèle américain"),
    UKMO("ukmo_seamless", "UK Met Office", "Modèle britannique"),
    METEOSWISS("meteoswiss_icon_seamless", "MeteoSwiss", "ICON-CH1/CH2 (Alpes)"),
    GEM("gem_seamless", "GEM", "Modèle canadien"),
}

data class CurrentConditions(
    val time: LocalDateTime,
    val temperature: Double,
    val apparentTemperature: Double,
    val humidity: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val precipitation: Double,
    val windSpeed: Double,
    val windDirection: Double,
    val windGusts: Double,
    val pressure: Double,
)

/** Séries horaires, en colonnes. Une valeur absente vaut NaN (ou -1 pour les codes météo). */
class HourlySeries(
    val time: List<LocalDateTime>,
    val temperature: DoubleArray,
    val apparentTemperature: DoubleArray,
    val humidity: DoubleArray,
    val precipitation: DoubleArray,
    val snowfall: DoubleArray,
    val precipitationProbability: DoubleArray,
    val weatherCode: IntArray,
    val cloudCoverLow: DoubleArray,
    val cloudCoverMid: DoubleArray,
    val cloudCoverHigh: DoubleArray,
    val windSpeed: DoubleArray,
    val windDirection: DoubleArray,
    val windGusts: DoubleArray,
    val pressure: DoubleArray,
    val isDay: BooleanArray,
    val uvIndex: DoubleArray,
) {
    val size: Int get() = time.size
}

class DailySeries(
    val date: List<LocalDate>,
    val weatherCode: IntArray,
    val temperatureMax: DoubleArray,
    val temperatureMin: DoubleArray,
    val precipitationSum: DoubleArray,
    val precipitationProbabilityMax: DoubleArray,
    val windSpeedMax: DoubleArray,
    val windGustsMax: DoubleArray,
    val windDirectionDominant: DoubleArray,
    /** Durée d'ensoleillement, en secondes. */
    val sunshineDuration: DoubleArray,
    val uvIndexMax: DoubleArray,
    val sunrise: List<LocalDateTime?>,
    val sunset: List<LocalDateTime?>,
) {
    val size: Int get() = date.size
}

class Forecast(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double,
    val timezone: String,
    val utcOffsetSeconds: Int,
    val current: CurrentConditions?,
    val hourly: HourlySeries,
    val daily: DailySeries,
)

class ModelSeries(
    val temperature: DoubleArray,
    val precipitation: DoubleArray,
    val windSpeed: DoubleArray,
) {
    /** Faux quand le modèle ne couvre pas ce lieu (ex. AROME hors de France). */
    val hasData: Boolean get() = temperature.any { !it.isNaN() }
}

class MultiModelForecast(
    val time: List<LocalDateTime>,
    val utcOffsetSeconds: Int,
    val series: Map<WeatherModel, ModelSeries>,
)
