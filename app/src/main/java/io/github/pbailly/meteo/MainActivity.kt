package io.github.pbailly.meteo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.pbailly.meteo.ui.MeteoApp
import io.github.pbailly.meteo.ui.theme.MeteoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeteoTheme {
                MeteoApp()
            }
        }
    }
}
