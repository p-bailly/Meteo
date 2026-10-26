package io.github.pbailly.meteo.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Délais avant de redemander en parallèle une requête restée sans réponse. Open-Meteo répond d'habitude en moins d'une
 * seconde et demie, mais, surchargé, laisse sans réponse environ une requête sur trois (la même redemandée aussitôt
 * passe le plus souvent) : attendre la fin du délai de lecture (20 s) avant de réessayer faisait démarrer l'appli en
 * 20 à 60 s.
 */
val HEDGE_DELAYS_MS = listOf(3_000L, 5_000L)

/**
 * Lance [attempt] ; s'il n'a pas répondu après [hedgeDelaysMs][0], en lance un deuxième en parallèle, puis un
 * troisième après [hedgeDelaysMs][1], et garde la première réponse (les autres sont annulés). Une requête coupée en
 * route est relancée tout de suite (une demi-seconde après), un serveur surchargé deux secondes après ; une autre
 * réponse d'erreur du serveur (clé refusée, lieu introuvable) arrête tout. Sans réponse de personne, la dernière erreur.
 */
suspend fun <T> hedged(hedgeDelaysMs: List<Long> = HEDGE_DELAYS_MS, attempt: suspend () -> T): T = coroutineScope {
    val maxAttempts = hedgeDelaysMs.size + 1
    val results = Channel<Result<T>>(Channel.UNLIMITED)
    val jobs = mutableListOf<Job>()
    fun start() {
        jobs += launch {
            val result = try {
                Result.success(attempt())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            results.send(result)
        }
    }
    try {
        start()
        var running = 1
        var last: Exception? = null
        while (true) {
            // Encore une tentative possible : on n'attend la réponse que jusqu'au délai suivant.
            val result = if (jobs.size < maxAttempts) {
                withTimeoutOrNull(hedgeDelaysMs[jobs.size - 1]) { results.receive() }
            } else {
                results.receive()
            }
            if (result == null) {
                start()
                running++
                continue
            }
            running--
            result.onSuccess { return@coroutineScope it }
            val e = result.exceptionOrNull() as Exception
            if (e is HttpStatusException && e !is ServerBusyException) throw e
            if (e !is IOException) throw e
            last = e
            if (jobs.size < maxAttempts) {
                // Personne d'autre en route : on relance après une courte pause.
                if (running == 0) {
                    delay(if (e is ServerBusyException) 2_000L else 500L)
                    start()
                    running++
                }
            } else if (running == 0) {
                throw last
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    } finally {
        jobs.forEach { it.cancel() }
    }
}
