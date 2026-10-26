package io.github.pbailly.meteo.data

import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.js.Promise

// Téléchargements du navigateur (fetch), à la place de ceux du téléphone (app/…/data/HttpGet.kt).

/**
 * Le corps de la réponse et son code ; -1 sans réseau (ou serveur qui refuse la page), -2 au-delà de [timeoutMs].
 */
private fun fetchText(url: String, timeoutMs: Int): Promise<JsAny?> = js(
    """(async () => {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeoutMs);
        try {
            const response = await fetch(url, { signal: controller.signal, headers: { 'Accept': 'application/json' } });
            return { status: response.status, body: await response.text() };
        } catch (e) {
            return { status: e && e.name === 'AbortError' ? -2 : -1, body: String(e) };
        } finally {
            clearTimeout(timer);
        }
    })()"""
)

private fun statusOf(reply: JsAny): Int = js("reply.status")
private fun bodyOf(reply: JsAny): String = js("reply.body")

private suspend fun httpGetOnce(url: String, timeoutMs: Int = 20_000): String {
    val reply = fetchText(url, timeoutMs).await<JsAny?>() ?: throw IOException("Réponse vide")
    val code = statusOf(reply)
    val body = bodyOf(reply)
    when {
        code == -2 -> throw java.net.SocketTimeoutException(body)
        code == -1 -> throw java.net.ConnectException(body)
        code in 502..504 -> throw ServerBusyException(code)
        code !in 200..299 -> {
            // Open-Meteo renvoie {"error":true,"reason":"..."} : on laisse le parseur en tirer le message.
            if (body.contains("\"reason\"")) return body
            throw HttpStatusException(code)
        }
    }
    return body
}

/** Réessaie deux fois une requête coupée en route ou refusée par un serveur surchargé (comme Http.kt sur Android). */
internal suspend fun defaultHttpGet(url: String): String {
    var last: IOException? = null
    for (k in 0 until 3) {
        try {
            return httpGetOnce(url)
        } catch (e: ServerBusyException) {
            last = e
            if (k < 2) delay(2_000L * (k + 1))
        } catch (e: HttpStatusException) {
            throw e
        } catch (e: IOException) {
            last = e
            if (k < 2) delay(500L * (k + 1))
        }
    }
    throw last!!
}

/** Pour OpenStreetMap (Overpass), lent : 30 s, sans nouvel essai. */
suspend fun slowHttpGet(url: String): String = httpGetOnce(url, timeoutMs = 30_000)
