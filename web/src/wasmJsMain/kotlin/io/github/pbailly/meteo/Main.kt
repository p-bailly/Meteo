package io.github.pbailly.meteo

import android.app.WebApplication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pbailly.meteo.ui.MeteoViewModel
import io.github.pbailly.meteo.ui.WebApp
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

/** Les polices d'abord (tout le texte en dépend), puis l'appli dans toute la page. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        WebFonts.load(::fetchBytes)
        val viewModel = MeteoViewModel(WebApplication)
        document.getElementById("loading")?.remove()
        ComposeViewport(document.body!!) {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val dark = when (state.theme) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            MeteoTheme(darkTheme = dark, textSize = state.textSize) {
                WebApp(viewModel)
            }
        }
    }
}
