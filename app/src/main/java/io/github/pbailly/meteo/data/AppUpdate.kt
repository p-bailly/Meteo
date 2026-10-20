package io.github.pbailly.meteo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** La dernière version publiée par GitHub Actions : son commit et l'adresse de l'APK. */
data class Release(val commit: String, val publishedAt: Instant?, val apkUrl: String, val size: Long)

/**
 * Mise à jour de l'appli depuis les Réglages : la dernière release du dépôt (« apk-<numéro> ») porte le commit qui l'a produite ;
 * s'il diffère de celui de l'appli installée, on télécharge son APK (signé avec la même clé, il s'installe par-dessus).
 */
object AppUpdate {
    private const val LATEST = "https://api.github.com/repos/p-bailly/Meteo/releases/latest"

    fun parse(body: String): Release {
        val o = Json.parseToJsonElement(body).jsonObject
        val asset = (o["assets"] as? JsonArray)?.map { it.jsonObject }?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "meteo.apk" }
            ?: throw OpenMeteoException("Pas d'APK dans la dernière version publiée")
        return Release(
            commit = o["target_commitish"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            publishedAt = o["published_at"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() },
            apkUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: throw OpenMeteoException("Adresse de l'APK absente"),
            size = asset["size"]?.jsonPrimitive?.longOrNull ?: -1,
        )
    }

    /** Vrai si [release] vient d'un autre commit que l'appli installée (ou si celle-ci n'a pas été compilée par GitHub). */
    fun isNewer(release: Release, installedCommit: String): Boolean =
        installedCommit.isBlank() || release.commit.isBlank() || !release.commit.startsWith(installedCommit) && !installedCommit.startsWith(release.commit)

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        retryOnNetworkError {
            val connection = URL(LATEST).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                val code = connection.responseCode
                if (code !in 200..299) throw HttpStatusException(code)
                parse(connection.inputStream.use { it.readBytes() }.decodeToString())
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Télécharge l'APK dans [dir] ; [onProgress] reçoit la part téléchargée, de 0 à 1. */
    suspend fun download(release: Release, dir: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val file = File(dir, "meteo.apk")
        val tmp = File(dir, "meteo.apk.part")
        retryOnNetworkError {
            val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                val code = connection.responseCode
                if (code !in 200..299) throw HttpStatusException(code)
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
                connection.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        file.delete()
        tmp.renameTo(file)
        file
    }
}
