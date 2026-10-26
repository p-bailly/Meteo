package io.github.pbailly.meteo.data

import java.io.IOException

// Comme app/…/data/Http.kt, pour le navigateur.

/** Réponse d'erreur d'un serveur (clé refusée, service indisponible…) : la redemander ne sert à rien. */
open class HttpStatusException(val code: Int, message: String = "Serveur indisponible (erreur $code), réessaie plus tard") :
    IOException(message)

/** Serveur surchargé (502, 503, 504) : le redemander un peu plus tard réussit souvent. */
class ServerBusyException(code: Int) : HttpStatusException(code, "Serveur météo surchargé, réessaie dans un instant")

/** Message à montrer pour une erreur réseau. */
fun IOException.networkMessage(): String = when (this) {
    is HttpStatusException -> message ?: "Serveur indisponible"
    is java.net.UnknownHostException, is java.net.ConnectException -> "Pas de connexion internet"
    is java.net.SocketTimeoutException -> "Le serveur ne répond pas, touche ↻ pour réessayer"
    else -> "Connexion interrompue, touche ↻ pour réessayer"
}
