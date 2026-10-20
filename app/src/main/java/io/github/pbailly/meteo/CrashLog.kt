package io.github.pbailly.meteo

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** L'application : garde la trace d'un plantage dès le démarrage du processus (appli ou widgets). */
class MeteoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * Trace du dernier plantage, gardée sur le téléphone : au lancement suivant, l'appli la montre et propose de la
 * copier, pour qu'on sache enfin ce qui s'est passé.
 */
object CrashLog {
    private const val FILE = "dernier-plantage.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText(
                    buildString {
                        appendLine("Météo ${BuildConfig.VERSION_NAME} · commit ${BuildConfig.GIT_SHA.take(7).ifEmpty { "local" }}")
                        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))} · fil « ${thread.name} »")
                        appendLine()
                        append(error.stackTraceToString())
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
