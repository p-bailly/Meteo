package io.github.pbailly.meteo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.ui.radar.MapOverlay
import io.github.pbailly.meteo.ui.radar.WeatherMap
import io.github.pbailly.meteo.ui.theme.HeaderBlue
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import org.maplibre.android.geometry.LatLng

/** Centre de la France métropolitaine, quand aucun lieu n'est encore choisi. */
private val FRANCE = LatLng(46.6, 2.4)

/**
 * « Choisir sur la carte », depuis l'onglet Lieux : toucher la carte y pose un point orange (toucher ailleurs le
 * déplace), qui prend le nom de la commune et l'altitude du terrain. L'étoile l'ajoute aux favoris ; « Voir la
 * météo » l'ouvre, et il rejoint les lieux consultés.
 */
@Composable
fun MapPickScreen(
    state: MeteoUiState,
    onPick: (Double, Double) -> Unit,
    onSelect: (Place) -> Unit,
    onToggleFavorite: (Place) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val start = state.place
    val pick = state.mapPick
    Column(Modifier.fillMaxSize()) {
        DetailHeader("Choisir sur la carte", onBack)
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            WeatherMap(
                overlay = MapOverlay.None,
                center = start?.let { LatLng(it.latitude, it.longitude) } ?: FRANCE,
                modifier = Modifier.fillMaxSize(),
                // Autour du lieu affiché, assez près pour viser un village ; sinon toute la France.
                zoom = if (start != null) 8.5 else 4.6,
                showPlace = start != null,
                pin = pick?.let { LatLng(it.place.latitude, it.place.longitude) },
                onMapClick = { onPick(it.latitude, it.longitude) },
            )
            if (pick == null) {
                // En bas : le logo et l'attribution de la carte occupent le haut.
                Text(
                    "Touche la carte à l'endroit voulu",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(HeaderBlue.copy(alpha = 0.85f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            } else {
                PickCard(
                    pick,
                    favorite = state.favorites.any { it.id == pick.place.id },
                    onSelect = onSelect,
                    onToggleFavorite = onToggleFavorite,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }
    }
}

/** Le point choisi : son nom, son département et son altitude, l'étoile des favoris et « Voir la météo ». */
@Composable
private fun PickCard(
    pick: MapPick,
    favorite: Boolean,
    onSelect: (Place) -> Unit,
    onToggleFavorite: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val place = pick.place
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (pick.naming) "Recherche du lieu…" else place.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = if (pick.naming) {
                        Fmt.coordinates(place.latitude, place.longitude)
                    } else {
                        listOfNotNull(place.subtitle.takeIf { it.isNotBlank() }, place.elevation?.let { Fmt.altitude(it) }).joinToString(" · ")
                    }
                    if (details.isNotEmpty()) MonoText(details, size = 11.sp)
                }
                if (pick.naming) {
                    CircularProgressIndicator(Modifier.padding(13.dp).size(22.dp), strokeWidth = 2.dp)
                } else {
                    // Étoile : pleine et orange pour un favori, comme dans la liste des lieux.
                    IconButton(onClick = { onToggleFavorite(place) }) {
                        Icon(
                            if (favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                            contentDescription = if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
                            tint = if (favorite) LocalChartColors.current.accent else colors.onSurfaceVariant,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
            Button(
                onClick = { onSelect(place) },
                enabled = !pick.naming,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 10.dp).height(48.dp),
            ) {
                Text("Voir la météo", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
    }
}
