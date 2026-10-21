package io.github.pbailly.meteo.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.pbailly.meteo.ui.weatherDescription
import io.github.pbailly.meteo.widget.SketchSource
import io.github.pbailly.meteo.widget.WidgetData
import io.github.pbailly.meteo.widget.widgetSketch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate

/**
 * Ce que Météo partage avec Nimbus, le launcher de l'utilisateur (`fr.perso.nimbus`), pour sa page de déverrouillage :
 * la météo du lieu ouvert dans l'app (`now`) et le dessin d'Aujourd'hui (`sketch`). Les données sont celles des
 * widgets. Lecture seule, et réservé à Nimbus et à l'app elle-même : les deux applis n'ont pas la même signature,
 * donc on vérifie le paquet de l'appelant à chaque demande.
 *
 * Les demandes arrivent sur un fil binder : on peut y attendre la prévision, au plus [TIMEOUT_MS].
 */
class NimbusProvider : ContentProvider() {

    /** Les téléchargements continuent après le délai : la prévision arrive dans le cache, et Nimbus en est prévenu. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        checkCaller()
        require(uri.pathSegments == listOf(NOW)) { "Adresse inconnue : $uri" }
        val context = checkNotNull(context)
        // Toutes les colonnes, dans l'ordre convenu avec Nimbus, quelle que soit la projection demandée.
        val cursor = MatrixCursor(COLUMNS.toTypedArray())
        nowRow(load(context))?.let { cursor.addRow(it) }
        cursor.setNotificationUri(context.contentResolver, Uri.parse("content://$AUTHORITY"))
        return cursor
    }

    /**
     * Le dessin en PNG, fond transparent, sur papier clair (Nimbus le pose sur son propre fond). Avec `text`, le texte
     * que Nimbus posera dessus ([textZones]) : le coloriage s'arrête avant lui, le soleil et les nuages se mettent à
     * côté. Écrit dans un fichier du cache, ouvert en lecture puis effacé : il reste lisible par le descripteur, et rien
     * ne s'accumule.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        if (mode != "r") throw UnsupportedOperationException("Lecture seule")
        if (uri.pathSegments != listOf(SKETCH)) throw FileNotFoundException("Adresse inconnue : $uri")
        val context = checkNotNull(context)
        val (width, height) = sketchSize(uri.getQueryParameter("width"), uri.getQueryParameter("height"))
        val data = load(context)
        val image = try {
            val source = runBlocking { SketchSource.load(context, data.place) }
            // Composé pour 411 dp de large, comme sur l'écran du téléphone, quelle que soit la taille demandée.
            val text = textZones(uri.getQueryParameter("text")).map { Rect(it.left * width, it.top * height, it.right * width, it.bottom * height) }
            widgetSketch(data, source, dark = false, width, height, density = width / SKETCH_WIDTH_DP, text = text)
        } catch (e: Exception) {
            // Un dessin raté ne fait pas tomber l'app : Nimbus garde simplement le précédent.
            Log.w(TAG, "Dessin impossible", e)
            null
        } ?: throw FileNotFoundException("Pas de dessin sans prévision")
        val bitmap = image.asAndroidBitmap()
        var file: File? = null
        try {
            file = File.createTempFile("dessin", ".png", File(context.cacheDir, "nimbus").apply { mkdirs() })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            // De toutes les erreurs d'entrée-sortie, seule celle-ci arrive jusqu'à Nimbus.
            throw e as? FileNotFoundException ?: FileNotFoundException("Dessin non écrit : ${e.message}")
        } finally {
            bitmap.recycle()
            file?.delete()
        }
    }

    /**
     * Refusé à une appli étrangère ; mais les anciennes versions d'Android ne disent pas qui demande le type, et le
     * type n'est pas une donnée : sans appelant connu, on répond.
     */
    override fun getType(uri: Uri): String? {
        val caller = callingPackage
        if (caller != null && !allowed(caller, context?.packageName)) throw SecurityException("Réservé à Nimbus")
        return when (uri.pathSegments) {
            listOf(NOW) -> NOW_TYPE
            listOf(SKETCH) -> "image/png"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        checkCaller()
        throw UnsupportedOperationException("Lecture seule")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        checkCaller()
        throw UnsupportedOperationException("Lecture seule")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        checkCaller()
        throw UnsupportedOperationException("Lecture seule")
    }

    private fun checkCaller() {
        if (!allowed(callingPackage, context?.packageName)) throw SecurityException("Réservé à Nimbus")
    }

    /**
     * Les données des widgets, en [TIMEOUT_MS] au plus. Au-delà, ce que l'app a déjà gardé (marqué hors ligne si c'est
     * vieux) ; le téléchargement continue, et Nimbus sera prévenu quand la prévision arrivera.
     */
    private fun load(context: Context): WidgetData = runBlocking {
        val loading = scope.async { WidgetData.load(context) }
        withTimeoutOrNull(TIMEOUT_MS) { loading.await() } ?: WidgetData.load(context, offline = true)
    }

    companion object {
        const val AUTHORITY = "io.github.pbailly.meteo.nimbus"
        const val NIMBUS = "fr.perso.nimbus"
        const val NOW = "now"
        const val SKETCH = "sketch"
        const val NOW_TYPE = "vnd.android.cursor.item/vnd.meteo.now"
        const val TIMEOUT_MS = 10_000L
        private const val TAG = "NimbusProvider"

        /** Les colonnes de `now`, dans l'ordre convenu avec Nimbus. */
        val COLUMNS = listOf(
            "place", "region", "temperature", "feels_like", "sky", "today_max", "today_min",
            "weather_code", "is_day", "fetched_at", "stale",
        )

        const val DEFAULT_WIDTH = 1027
        const val DEFAULT_HEIGHT = 717
        const val MIN_SIDE = 64
        const val MAX_SIDE = 2048
        /** Largeur du dessin en dp : celle du widget de 411 × 287 dp (1027 × 717 pixels à 2,5). */
        const val SKETCH_WIDTH_DP = 411f

        /** Nimbus, et l'app elle-même ; un appelant inconnu est refusé. */
        internal fun allowed(caller: String?, own: String?): Boolean = caller != null && (caller == NIMBUS || caller == own)

        /** La taille demandée en pixels, de [MIN_SIDE] à [MAX_SIDE] ; sans valeur lisible, 1027 × 717. */
        internal fun sketchSize(width: String?, height: String?): Pair<Int, Int> =
            (width?.trim()?.toIntOrNull() ?: DEFAULT_WIDTH).coerceIn(MIN_SIDE, MAX_SIDE) to
                (height?.trim()?.toIntOrNull() ?: DEFAULT_HEIGHT).coerceIn(MIN_SIDE, MAX_SIDE)

        /** Zones de texte au plus, pour qu'une adresse folle ne coûte rien. */
        const val MAX_ZONES = 24

        /**
         * Les zones de texte du paramètre `text` : « gauche,haut,droite,bas » en fractions de l'image (de 0 à 1),
         * séparées par des points-virgules. Une zone illisible, vide ou hors de l'image est ignorée.
         */
        internal fun textZones(text: String?): List<Rect> = text.orEmpty().split(';').asSequence().mapNotNull { zone ->
            val v = zone.split(',').map { it.trim().toFloatOrNull() ?: return@mapNotNull null }
            if (v.size != 4 || v.any { !it.isFinite() }) return@mapNotNull null
            Rect(v[0].coerceIn(0f, 1f), v[1].coerceIn(0f, 1f), v[2].coerceIn(0f, 1f), v[3].coerceIn(0f, 1f)).takeIf { it.width > 0f && it.height > 0f }
        }.take(MAX_ZONES).toList()

        /**
         * La ligne de `now`, comme l'écrivent Aujourd'hui et le widget « Maintenant » : le ciel en toutes lettres, le
         * département sous le nom, les extrêmes du jour du lieu ([today]). Null sans lieu, sans prévision, ou s'il y
         * manque une des températures (Nimbus n'a pas de case vide à afficher).
         */
        internal fun nowRow(data: WidgetData, today: LocalDate? = data.today): Array<Any?>? {
            val place = data.place ?: return null
            val f = data.forecast ?: return null
            val c = f.current ?: return null
            val day = f.daily.date.indexOf(today).coerceAtLeast(0)
            val max = f.daily.temperatureMax.getOrElse(day) { Double.NaN }
            val min = f.daily.temperatureMin.getOrElse(day) { Double.NaN }
            if (listOf(c.temperature, c.apparentTemperature, max, min).any { it.isNaN() }) return null
            return arrayOf(
                place.name,
                place.departmentOrCountry,
                c.temperature,
                c.apparentTemperature,
                weatherDescription(c.weatherCode),
                max,
                min,
                c.weatherCode,
                if (c.isDay) 1 else 0,
                data.fetchedAt,
                if (data.stale) 1 else 0,
            )
        }

        /** À appeler après chaque nouvelle prévision du lieu : Nimbus relit `now` et `sketch`. */
        fun notifyChange(context: Context) {
            try {
                context.contentResolver.notifyChange(Uri.parse("content://$AUTHORITY"), null)
            } catch (e: Exception) {
                Log.w(TAG, "Nimbus non prévenu", e)
            }
        }
    }
}
