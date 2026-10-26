package io.github.pbailly.meteo.data.maps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** Une image du radar RainViewer : son heure et le modèle d'URL de ses tuiles ({z}/{x}/{y}). */
data class RainFrame(val time: Instant, val tileUrl: String)

/**
 * Radar mondial de RainViewer (rainviewer.com), gratuit et sans clé pour un usage personnel : les mosaïques des
 * services météo nationaux (Météo-France compris), une image toutes les 10 minutes sur les deux dernières heures, en
 * tuiles jusqu'au zoom [MAX_ZOOM]. Sert à la webapp, qui ne sait pas lire le radar de Météo-France (HDF5).
 */
object RainViewer {
    const val URL = "https://api.rainviewer.com/public/weather-maps.json"
    const val ATTRIBUTION = "Radar © RainViewer"

    /** Au-delà, RainViewer ne sert plus de tuiles : la carte agrandit celles de ce zoom. */
    const val MAX_ZOOM = 7
    const val TILE_SIZE = 256

    /** Palette 2 (« Universal Blue », du bleu clair au rouge), lissée (1), neige distinguée (1). */
    private const val COLORS = "2/1_1"

    /** Les images passées, de la plus ancienne à la plus récente. */
    fun parse(body: String): List<RainFrame> {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val host = root["host"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val radar = root["radar"] as? JsonObject ?: return emptyList()
        val past = radar["past"] as? JsonArray ?: return emptyList()
        return past.mapNotNull { element ->
            val frame = element as? JsonObject ?: return@mapNotNull null
            val time = frame["time"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val path = frame["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            RainFrame(Instant.ofEpochSecond(time), "$host$path/$TILE_SIZE/{z}/{x}/{y}/$COLORS.png")
        }.sortedBy { it.time }
    }
}
