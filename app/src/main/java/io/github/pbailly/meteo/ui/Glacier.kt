package io.github.pbailly.meteo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.ui.theme.AppMono
import io.github.pbailly.meteo.ui.theme.AppSans

/** Les cinq onglets de la barre du bas. */
enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Aujourd'hui", Icons.Outlined.WbSunny),
    FORECAST("Prévisions", Icons.Outlined.CalendarMonth),
    ALTITUDE("Altitude", Icons.Outlined.Terrain),
    RADAR("Radar", Icons.Outlined.TrackChanges),
    PLACES("Lieux", Icons.Outlined.Place),
}

/** Barre du bas : pictogramme au trait et libellé ; l'onglet actif en bleu glacier, souligné d'un trait au-dessus. */
@Composable
fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerLowest) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth().height(64.dp)) {
                Tab.entries.forEach { tab ->
                    val active = tab == selected
                    val tint = if (active) colors.primary else colors.onSurfaceVariant
                    Column(
                        Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clickable { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .width(30.dp)
                                .height(3.dp)
                                .background(if (active) colors.primary else Color.Transparent, RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)),
                        )
                        Spacer(Modifier.height(9.dp))
                        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(3.dp))
                        Text(
                            tab.label,
                            color = tint,
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** En-tête d'un onglet : grand titre, et à droite un sélecteur ou un bouton. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 16.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Le titre rétrécit un peu plutôt que d'être coupé (« Neige & altitude » à côté de son sélecteur).
        BasicText(
            title,
            style = TextStyle(fontSize = 29.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppSans, color = MaterialTheme.colorScheme.onBackground),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 21.sp, maxFontSize = 29.sp, stepSize = 1.sp),
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        trailing()
    }
}

/** Ligne « ⌖ Annecy · 448 m » sous les titres. */
@Composable
fun PlaceLine(name: String, elevation: Double?, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            listOfNotNull(name, elevation?.takeIf { !it.isNaN() }?.let { Fmt.altitude(it) }).joinToString(" · "),
            fontFamily = AppMono,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Sélecteur à deux ou trois choix : l'option active en nuit, texte blanc. */
@Composable
fun SegmentedToggle(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerLow)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Text(
                label,
                color = if (active) Color.White else colors.onSurfaceVariant,
                fontSize = 13.5.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) colors.onBackground else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            )
        }
    }
}

/** Carte bleu glacier pâle (bloc altitude, coupe du relief). */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        content = content,
    )
}

/** Titre de carte : pictogramme, titre en gras, et un complément en Martian Mono à droite. */
@Composable
fun PanelTitle(icon: ImageVector?, title: String, aside: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (aside != null) {
            Spacer(Modifier.width(8.dp))
            MonoText(aside, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 11.sp)
        }
    }
}

/** Texte en Martian Mono (données, unités, heures). */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: TextUnit = 12.sp,
    bold: Boolean = false,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text,
        modifier = modifier,
        color = color,
        fontFamily = AppMono,
        fontSize = size,
        fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
        textAlign = align,
        lineHeight = size * 1.35f,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Bouton contour, coins arrondis (« Modifier »). */
@Composable
fun OutlineChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** Bandeau d'état (hors ligne, erreur). */
@Composable
fun StatusLine(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Message d'état de la prévision (hors ligne, erreur), ou null. */
fun statusText(forecast: Remote<*>): String? = when {
    forecast.stale -> "Hors ligne : prévisions du ${Fmt.clockOfMillis(forecast.fetchedAt)}"
    forecast.error != null -> forecast.error
    else -> null
}

/** Espace vertical. */
@Composable
fun Gap(height: Dp) = Spacer(Modifier.height(height))
