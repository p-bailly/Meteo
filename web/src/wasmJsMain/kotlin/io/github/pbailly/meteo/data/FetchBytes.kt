package io.github.pbailly.meteo.data

import kotlinx.coroutines.await
import kotlin.js.Promise

// Téléchargement binaire du navigateur (fetch), pour les couches de la carte et Météo-France.

private fun fetchBinary(url: String, headersJson: String, timeoutMs: Int): Promise<JsAny?> = js(
    """(async () => {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeoutMs);
        try {
            const response = await fetch(url, { signal: controller.signal, headers: JSON.parse(headersJson) });
            const bytes = new Uint8Array(await response.arrayBuffer());
            let body = "";
            for (let i = 0; i < bytes.length; i += 8192) body += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192));
            return { status: response.status, body };
        } catch (e) {
            return { status: e && e.name === 'AbortError' ? -2 : -1, body: String(e) };
        } finally {
            clearTimeout(timer);
        }
    })()"""
)

private fun replyStatus(reply: JsAny): Int = js("reply.status")
private fun replyBody(reply: JsAny): String = js("reply.body")

/** Réponse d'un serveur : son code HTTP et son corps. */
class BinaryReply(val status: Int, val bytes: ByteArray)

/**
 * GET avec [headers] ; une coupure réseau ou un délai dépassé devient une IOException, une réponse d'erreur est
 * rendue telle quelle (à l'appelant de l'interpréter).
 */
suspend fun fetchBytes(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30_000): BinaryReply {
    val json = headers.entries.joinToString(",", "{", "}") { (k, v) -> "\"${k.jsonEscaped()}\":\"${v.jsonEscaped()}\"" }
    val reply = fetchBinary(url, json, timeoutMs).await<JsAny?>() ?: throw java.io.IOException("Réponse vide")
    return when (val status = replyStatus(reply)) {
        -2 -> throw java.net.SocketTimeoutException(replyBody(reply))
        -1 -> throw java.net.ConnectException(replyBody(reply))
        else -> BinaryReply(status, replyBody(reply).let { s -> ByteArray(s.length) { s[it].code.toByte() } })
    }
}

private fun String.jsonEscaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")
