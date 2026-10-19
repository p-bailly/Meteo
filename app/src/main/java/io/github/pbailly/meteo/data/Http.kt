package io.github.pbailly.meteo.data

import java.io.IOException

/** Réponse d'erreur d'un serveur (clé refusée, service indisponible…) : la redemander ne sert à rien. */
open class HttpStatusException(val code: Int, message: String = "Serveur indisponible (erreur $code), réessaie plus tard") :
    IOException(message)

/**
 * Réessaie deux fois une requête coupée en route (connexion interrompue, erreur TLS « BAD_RECORD_MAC » d'un réseau
 * mobile instable…), mais pas une réponse d'erreur du serveur.
 */
internal inline fun <T> retryOnNetworkError(attempts: Int = 3, block: () -> T): T {
    var last: IOException? = null
    for (k in 0 until attempts) {
        try {
            return block()
        } catch (e: HttpStatusException) {
            throw e
        } catch (e: IOException) {
            last = e
            if (k < attempts - 1) Thread.sleep(500L * (k + 1))
        }
    }
    throw last!!
}

/** Message à montrer pour une erreur réseau, sans le jargon de la bibliothèque TLS. */
fun IOException.networkMessage(): String = when (this) {
    is HttpStatusException -> message ?: "Serveur indisponible"
    is java.net.UnknownHostException, is java.net.ConnectException -> "Pas de connexion internet"
    is java.net.SocketTimeoutException -> "Le serveur ne répond pas, touche ↻ pour réessayer"
    else -> "Connexion interrompue, touche ↻ pour réessayer"
}
