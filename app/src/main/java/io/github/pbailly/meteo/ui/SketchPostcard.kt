package io.github.pbailly.meteo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Le dessin d'Aujourd'hui en carte postale, ouvert par un appui long : en grand sur tout l'écran, sans le texte par
 * dessus, sur un carton un peu penché, avec « Bons baisers de… », la date, le temps et un timbre. Toucher ferme.
 */
@Composable
fun SketchPostcard(
    place: String,
    time: LocalDateTime,
    temperature: Double?,
    code: Int,
    onDismiss: () -> Unit,
    sketch: @Composable (Modifier) -> Unit,
) {
    val dark = LocalChartColors.current.darkTheme
    val paper = if (dark) Color(0xFF1B2733) else Color(0xFFFBF7EE)
    val ink = if (dark) Color(0xFFEAF2F8) else Color(0xFF263646)
    val faded = ink.copy(alpha = 0.65f)
    val date = remember(time) { time.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .rotate(-2f)
                    .shadow(10.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(paper)
                    .padding(12.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(330.dp)
                        .border(1.dp, faded.copy(alpha = 0.25f)),
                ) {
                    sketch(Modifier.fillMaxSize())
                    // Le timbre : la température du jour, dans un cadre dentelé.
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .rotate(4f)
                            .size(54.dp, 62.dp)
                            .background(paper)
                            .border(2.dp, faded.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                            .padding(4.dp)
                            .border(1.dp, faded.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(temperature?.let { Fmt.temp(it) } ?: "—", color = ink, fontFamily = FontFamily.Cursive, fontSize = 20.sp)
                            Text("Météo", color = faded, fontFamily = FontFamily.Cursive, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Bons baisers de $place", color = ink, fontFamily = FontFamily.Cursive, fontSize = 26.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(date.replaceFirstChar { it.uppercase() }, color = faded, fontFamily = FontFamily.Cursive, fontSize = 16.sp)
                    Text(weatherDescription(code), color = faded, fontFamily = FontFamily.Cursive, fontSize = 16.sp)
                }
            }
        }
    }
}
