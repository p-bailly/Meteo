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
    /** Département (en France ; sinon la subdivision équivalente). Vide quand on l'a cherché sans le trouver. */
    val admin2: String? = null,
) {
    /** Identifiant stable, arrondi à ~10 m, utilisé pour les favoris et le cache. */
    val id: String get() = String.format(Locale.ROOT, "%.4f_%.4f", latitude, longitude)

    /** Le département, ou à défaut la région : « Drôme », « Haute-Savoie ». */
    val department: String? get() = admin2?.takeIf { it.isNotBlank() } ?: admin1?.takeIf { it.isNotBlank() }

    /** Sous le nom du lieu, dans Aujourd'hui : le département (ou la région), à défaut le pays. */
    val departmentOrCountry: String? get() = department ?: country?.takeIf { it.isNotBlank() }

    val subtitle: String get() = listOfNotNull(department, country).distinct().joinToString(", ")
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
    /** Altitude de l'isotherme 0 °C, en mètres (NaN si le modèle ne la fournit pas). */
    val freezingLevel: DoubleArray,
    /** Épaisseur de neige au sol, en mètres (NaN si le modèle ne la fournit pas). */
    val snowDepth: DoubleArray = DoubleArray(0),
    /** Niveaux de pression, du plus bas (925 hPa, vers 750 m) au plus haut (500 hPa, vers 5 500 m). */
    val levels: List<PressureLevel> = emptyList(),
) {
    val size: Int get() = time.size
}

/** Un niveau de pression du modèle : son altitude réelle (géopotentiel, en m), sa température et son vent. */
class PressureLevel(
    val hPa: Int,
    val temperature: DoubleArray,
    val height: DoubleArray,
    val windSpeed: DoubleArray,
    val windDirection: DoubleArray,
)

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

/**
 * Prévision d'ensemble du centre européen (ECMWF IFS ENS) : 51 scénarios du même modèle, aux conditions de départ
 * légèrement différentes. Leur dispersion mesure l'incertitude de la prévision.
 */
class EnsembleForecast(
    val time: List<LocalDateTime>,
    /** Une série horaire par scénario. */
    val temperature: List<DoubleArray>,
    val precipitation: List<DoubleArray>,
)
