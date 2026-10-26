package io.github.pbailly.meteo.data

import java.net.HttpURLConnection
import java.net.URL

// Téléchargements du téléphone (la webapp a les siens, par fetch : web/…/data/HttpGet.kt).

/** Une requête à Open-Meteo et aux autres services, réessayée sur une erreur réseau passagère. */
internal fun defaultHttpGet(url: String): String = retryOnNetworkError { httpGetOnce(url) }

/**
 * Pour OpenStreetMap (Overpass) : une requête sur les environs d'un lieu prend souvent 15 à 25 s, au-delà des 20 s
 * accordées à Open-Meteo. Un serveur qui ne répond pas en 30 s n'est pas réessayé : on passe au suivant.
 */
fun slowHttpGet(url: String): String = httpGetOnce(url, readTimeoutMs = 30_000)

private fun httpGetOnce(url: String, readTimeoutMs: Int = 20_000): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        // Un serveur surchargé ne répond parfois même pas à la connexion : mieux vaut réessayer tôt qu'attendre.
        connection.connectTimeout = 8_000
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Meteo-perso/1.0 (Android; github.com/p-bailly/Meteo)")
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code in 502..504) throw ServerBusyException(code)
        if (code !in 200..299) {
            // Open-Meteo renvoie {"error":true,"reason":"..."} : on laisse le parseur en tirer le message.
            if (body.contains("\"reason\"")) return body
            throw HttpStatusException(code)
        }
        return body
    } finally {
        connection.disconnect()
    }
}
