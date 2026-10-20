package io.github.pbailly.meteo

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pbailly.meteo.ui.CrashDialog
import io.github.pbailly.meteo.ui.MeteoApp
import io.github.pbailly.meteo.ui.MeteoViewModel
import io.github.pbailly.meteo.ui.theme.MeteoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Après un plantage, d'abord sa trace, à copier ; le reste de l'appli (qui a peut-être planté au
            // démarrage) ne se lance qu'ensuite.
            var crash by remember { mutableStateOf(CrashLog.read(this)) }
            crash?.let { report ->
                MeteoTheme {
                    Surface(Modifier.fillMaxSize()) {
                        CrashDialog(report) {
                            CrashLog.clear(this)
                            crash = null
                        }
                    }
                }
                return@setContent
            }
            val viewModel: MeteoViewModel = viewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val dark = when (state.theme) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            // Icônes de la barre d'état et de navigation lisibles avec le thème choisi, même s'il diffère du téléphone.
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
            }
            MeteoTheme(darkTheme = dark, textSize = state.textSize) {
                MeteoApp(viewModel)
            }
        }
    }
}

/** Voiles de la barre de navigation à trois boutons, ceux d'Android par défaut. */
private val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
