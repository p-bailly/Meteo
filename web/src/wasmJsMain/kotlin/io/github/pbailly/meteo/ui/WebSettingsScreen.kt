package io.github.pbailly.meteo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.WeatherModel
import io.github.pbailly.meteo.ui.theme.LocalChartColors

/** Les réglages de la webapp : ceux de l'appli qui ont un sens dans le navigateur (affichage, dessin, modèles). */
@Composable
fun WebSettingsScreen(
    theme: Int,
    onTheme: (Int) -> Unit,
    textSize: Int,
    onTextSize: (Int) -> Unit,
    sketchStyle: Int,
    onSketchStyle: (Int) -> Unit,
    sketchMotion: Boolean,
    onSketchMotion: (Boolean) -> Unit,
    hiddenModels: Set<WeatherModel>,
    onModelVisible: (WeatherModel, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailHeader("Réglages", onBack)
        SectionLabel("Affichage")
        BorderedCard {
            Column(Modifier.padding(14.dp)) {
                Text("Thème", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                SegmentedToggle(listOf("Auto", "Jour", "Nuit"), theme, onTheme, Modifier.padding(top = 8.dp), fill = true)
                MonoText("Auto : comme le téléphone", size = 10.5.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(16.dp))
                Text("Taille du texte", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                SegmentedToggle(listOf("100 %", "115 %", "130 %"), textSize, onTextSize, Modifier.padding(top = 8.dp), fill = true)
                MonoText("Plus grand, certains écrans cachent des détails secondaires", size = 10.5.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
            }
        }
        SectionLabel("Dessin d'Aujourd'hui")
        BorderedCard {
            Column(Modifier.padding(14.dp)) {
                Text("Couleurs", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                SegmentedToggle(listOf("Discret", "Normal", "Coloré"), sketchStyle, onSketchStyle, Modifier.padding(top = 8.dp), fill = true)
                MonoText("Discret : le texte ressort mieux ; coloré : comme un vrai coloriage", size = 10.5.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Animation", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        MonoText("La pluie et la neige tombent, les nuages glissent", size = 10.5.sp, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
                    }
                    Switch(checked = sketchMotion, onCheckedChange = onSketchMotion)
                }
            }
        }
        SectionLabel("Modèles de la comparaison")
        BorderedCard {
            WeatherModel.entries.forEachIndexed { i, model ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val visible = model !in hiddenModels
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onModelVisible(model, !visible) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(LocalChartColors.current.series[model.ordinal]))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(model.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        MonoText(model.detail, size = 10.5.sp)
                    }
                    Switch(checked = visible, onCheckedChange = { onModelVisible(model, it) })
                }
            }
        }
        SectionLabel("Sources")
        MonoText(
            "Prévisions : Open-Meteo.com (CC BY 4.0). Radar et prévision immédiate : Météo-France (Licence Ouverte). " +
                "Satellite et foudre : EUMETSAT. Webcams : Windy. Relief : Copernicus DEM (via Open-Meteo), sommets et " +
                "environs © contributeurs OpenStreetMap. Fond de carte : OpenFreeMap, © OpenStreetMap. Lecture du radar : " +
                "h5wasm (NIST). Polices : Archivo et Martian Mono (SIL OFL 1.1).",
            size = 10.5.sp,
            maxLines = 12,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}
