package io.github.pbailly.meteo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * Une donnée chargée, avec l'heure du téléchargement et un indicateur « hors ligne ». [downloaded] : elle vient d'être
 * téléchargée (et non relue dans le cache).
 */
data class Loaded<T>(val value: T, val fetchedAt: Long, val stale: Boolean, val downloaded: Boolean = false)

/**
 * Accès à Open-Meteo avec un cache disque : on ne rappelle pas l'API tant que les données
 * ont moins de [FORECAST_MAX_AGE_MS], et on réaffiche le dernier résultat quand il n'y a pas de réseau.
 */
class WeatherRepository(
    private val cacheDir: File,
    /**
     * Pour l'historique depuis 1940 (5 s de calcul chez Open-Meteo) : une seule demande, sans la doubler ; sinon
     * [httpGet]. Avant lui : [httpGet] reste le dernier paramètre, celui qu'on passe en lambda.
     */
    private val slowHttpGet: (suspend (String) -> String)? = null,
    val httpGet: suspend (String) -> String = ::defaultHttpGet,
) {
    suspend fun forecast(place: Place, force: Boolean): Loaded<Forecast> =
        cached("forecast_${place.id}", OpenMeteoApi.forecastUrl(place), FORECAST_MAX_AGE_MS, force) {
            OpenMeteoApi.parseForecast(it)
        }

    /** Les niveaux de pression (pour « Neige & altitude »), après la prévision : leur calcul est lent chez Open-Meteo. */
    suspend fun levels(place: Place, force: Boolean): Loaded<PressureLevels> =
        cached("levels_${place.id}", OpenMeteoApi.levelsUrl(place), FORECAST_MAX_AGE_MS, force) {
            OpenMeteoApi.parseLevels(it)
        }

    suspend fun multiModel(place: Place, force: Boolean): Loaded<MultiModelForecast> {
        val models = WeatherModel.entries
        return cached("multimodel_${place.id}", OpenMeteoApi.multiModelUrl(place, models), MULTI_MODEL_MAX_AGE_MS, force) {
            OpenMeteoApi.parseMultiModel(it, models)
        }
    }

    suspend fun ensemble(place: Place, force: Boolean): Loaded<EnsembleForecast> =
        cached("ensembles_${place.id}", OpenMeteoApi.ensembleUrl(place), ENSEMBLE_MAX_AGE_MS, force) {
            OpenMeteoApi.parseEnsemble(it)
        }

    /**
     * Le temps passé du lieu, de 1940 à hier. La demande depuis 1940 pèse lourd dans le quota d'Open-Meteo (plus de
     * 30 000 jours : 2 000 à 4 500 des 10 000 appels gratuits par jour) : les années finies ne sont demandées qu'une
     * fois et gardées, puis seulement ce qui manque depuis, toutes les 6 heures. Sans ce complément (réseau absent),
     * les années finies suffisent.
     */
    suspend fun history(place: Place, today: LocalDate, force: Boolean): Loaded<DailyHistory> {
        val yesterday = today.minusDays(1)
        suspend fun fetchPast(refetch: Boolean) = cached(
            "history_past_${place.id}",
            OpenMeteoApi.archiveUrl(place, OpenMeteoApi.ARCHIVE_START, LocalDate.of(today.year - 1, 12, 31)),
            HISTORY_PAST_MAX_AGE_MS,
            force = refetch,
            get = slowHttpGet ?: httpGet,
        ) { OpenMeteoApi.parseHistory(it) }
        var past = fetchPast(refetch = false)
        // Au bout de trois ans, le complément devient lourd lui aussi : on redemande tout, une fois.
        if (past.value.end.year < today.year - 3) past = fetchPast(refetch = true)
        val from = past.value.end.plusDays(1)
        val recent = if (from.isAfter(yesterday)) null else try {
            cached(
                "history_recent_${place.id}",
                OpenMeteoApi.archiveUrl(place, from, yesterday),
                HISTORY_RECENT_MAX_AGE_MS,
                force,
            ) { OpenMeteoApi.parseHistory(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val merged = past.value.followedBy(recent?.value)
        return Loaded(merged, recent?.fetchedAt ?: past.fetchedAt, stale = recent?.stale ?: (from <= yesterday), downloaded = recent?.downloaded == true)
    }

    /** Vrai si les années finies du lieu sont déjà gardées : afficher son historique ne coûte presque rien. */
    suspend fun hasHistory(place: Place): Boolean = withContext(Dispatchers.IO) { File(cacheDir, "history_past_${place.id}.json").exists() }

    /**
     * La dernière prévision gardée du lieu quand elle n'est plus fraîche (moins d'un jour) : elle s'affiche tout de
     * suite, le temps que la nouvelle arrive. Null si elle est fraîche ([forecast] la rend alors sans attendre).
     */
    suspend fun lastForecast(place: Place): Loaded<Forecast>? =
        last("forecast_${place.id}", FORECAST_MAX_AGE_MS) { OpenMeteoApi.parseForecast(it) }

    suspend fun lastMultiModel(place: Place): Loaded<MultiModelForecast>? =
        last("multimodel_${place.id}", MULTI_MODEL_MAX_AGE_MS) { OpenMeteoApi.parseMultiModel(it, WeatherModel.entries) }

    private suspend fun <T> last(key: String, maxAgeMs: Long, parse: (String) -> T): Loaded<T>? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$key.json")
        val age = System.currentTimeMillis() - file.lastModified()
        if (!file.exists() || age < maxAgeMs || age > LAST_MAX_AGE_MS) return@withContext null
        runCatching { parse(file.readText()) }.getOrNull()?.let { Loaded(it, file.lastModified(), stale = true) }
    }

    /** Prévision courte de tous les lieux de la liste, en un seul appel ; dans l'ordre de [places]. */
    suspend fun summaries(places: List<Place>, force: Boolean): Loaded<List<Forecast>> {
        val key = "summary_" + Integer.toHexString(places.joinToString("|") { it.id }.hashCode())
        return cached(key, OpenMeteoApi.summaryUrl(places), FORECAST_MAX_AGE_MS, force) { body ->
            OpenMeteoApi.parseForecasts(body).also {
                if (it.size != places.size) throw OpenMeteoException("Réponse incomplète pour la liste des lieux")
            }
        }
    }

    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        OpenMeteoApi.parseSearch(httpGet(OpenMeteoApi.searchUrl(query)), query)
    }

    /** Altitude d'un point (modèle de terrain Copernicus à 90 m) ; null si l'API n'en donne pas. */
    suspend fun elevation(latitude: Double, longitude: Double): Double? = withContext(Dispatchers.IO) {
        OpenMeteoApi.parseElevation(httpGet(OpenMeteoApi.elevationUrl(listOf(latitude to longitude)))).firstOrNull()?.takeIf { !it.isNaN() }
    }

    private suspend fun <T> cached(
        key: String,
        url: String,
        maxAgeMs: Long,
        force: Boolean,
        get: suspend (String) -> String = httpGet,
        parse: (String) -> T,
    ): Loaded<T> = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$key.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < maxAgeMs
        if (fresh && !force) {
            val cachedValue = runCatching { parse(file.readText()) }.getOrNull()
            if (cachedValue != null) return@withContext Loaded(cachedValue, file.lastModified(), stale = false)
        }
        try {
            val body = get(url)
            // On lit la réponse avant de l'écrire : une réponse invalide n'écrase jamais un bon cache.
            val value = parse(body)
            cacheDir.mkdirs()
            file.writeText(body)
            Loaded(value, file.lastModified(), stale = false, downloaded = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fallback = if (file.exists()) runCatching { parse(file.readText()) }.getOrNull() else null
            fallback?.let { Loaded(it, file.lastModified(), stale = true) } ?: throw e
        }
    }

    companion object {
        const val FORECAST_MAX_AGE_MS = 30 * 60 * 1000L
        const val MULTI_MODEL_MAX_AGE_MS = 60 * 60 * 1000L
        /** L'ensemble est recalculé toutes les 6 heures (et pèse plus lourd dans le quota d'Open-Meteo). */
        const val ENSEMBLE_MAX_AGE_MS = 3 * 60 * 60 * 1000L
        /** Au-delà d'un jour, une prévision gardée ne s'affiche plus en attendant la nouvelle. */
        const val LAST_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        /** Les années finies ne changent plus : gardées (voir [history] pour le moment où on les redemande). */
        const val HISTORY_PAST_MAX_AGE_MS = Long.MAX_VALUE / 2
        const val HISTORY_RECENT_MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
