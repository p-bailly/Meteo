package io.github.pbailly.meteo

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pbailly.meteo.ui.MeteoApp
import io.github.pbailly.meteo.ui.MeteoViewModel
import io.github.pbailly.meteo.ui.theme.MeteoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
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
