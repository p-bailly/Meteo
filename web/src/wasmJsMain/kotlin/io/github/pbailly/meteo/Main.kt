package io.github.pbailly.meteo

import android.app.WebApplication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pbailly.meteo.ui.MeteoViewModel
import io.github.pbailly.meteo.ui.WebApp
import io.github.pbailly.meteo.ui.radar.LayersViewModel
import io.github.pbailly.meteo.ui.radar.RadarViewModel
import java.io.WebFiles
import io.github.pbailly.meteo.ui.theme.MeteoTheme
import io.github.pbailly.meteo.ui.theme.WebFonts
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import kotlin.js.Promise

private fun fetchArrayBuffer(url: String): Promise<ArrayBuffer> = js(
    "fetch(url).then((r) => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.arrayBuffer(); })"
)

private suspend fun fetchBytes(url: String): ByteArray {
    val array = Int8Array(fetchArrayBuffer(url).await<ArrayBuffer>())
    return ByteArray(array.length) { array[it] }
}

private fun locationHash(): String = js("window.location.hash")
private fun clearHash(): Unit = js("{ history.replaceState(null, '', window.location.pathname + window.location.search); }")

/**
 * Les clés passées dans le lien (« …/Meteo/#cle=<clé Météo-France>&windy=<clé Windy> ») : enregistrées dans le
 * navigateur comme si on les avait saisies, puis retirées de l'adresse. Ce qui suit « # » n'est jamais envoyé au
 * serveur.
 */
private fun saveKeysFromLink() {
    val params = locationHash().removePrefix("#").split('&').mapNotNull { part ->
        val k = part.substringBefore('=', "")
        val v = part.substringAfter('=', "")
        if (k.isEmpty() || v.isEmpty()) null else k to decodeComponent(v).trim()
    }.toMap()
    if (params.isEmpty()) return
    params["cle"]?.let { WebApplication.getSharedPreferences("radar", 0).edit().putString("meteofrance_api_key", it).apply() }
    params["windy"]?.let { WebApplication.getSharedPreferences("settings", 0).edit().putString("windy_api_key", it).apply() }
    if ("cle" in params || "windy" in params) clearHash()
}

private fun decodeComponent(text: String): String = js("decodeURIComponent(text)")

/** Les polices d'abord (tout le texte en dépend), puis l'appli dans toute la page. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        WebFonts.load(::fetchBytes)
        WebFiles.load()
        saveKeysFromLink()
        val viewModel = MeteoViewModel(WebApplication)
        val radar = RadarViewModel(WebApplication)
        val layers = LayersViewModel(WebApplication)
        document.getElementById("loading")?.remove()
        ComposeViewport(document.body!!) {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val dark = when (state.theme) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            MeteoTheme(darkTheme = dark, textSize = state.textSize) {
                WebApp(viewModel, radar, layers)
            }
        }
    }
}
