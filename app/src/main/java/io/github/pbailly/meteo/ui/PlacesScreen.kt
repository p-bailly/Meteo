package io.github.pbailly.meteo.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.pbailly.meteo.data.Place

@Composable
fun PlacesScreen(
    state: MeteoUiState,
    onBack: (() -> Unit)?,
    onSelect: (Place) -> Unit,
    onToggleFavorite: (Place) -> Unit,
    onQueryChange: (String) -> Unit,
    onLocate: () -> Unit,
    onLocationDenied: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) onLocate() else onLocationDenied()
    }
    if (onBack != null) BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text("Lieux", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            OutlinedTextField(
                value = state.search.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Rechercher une ville, un village…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.search.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Effacer") }
                    }
                },
                singleLine = true,
            )

            if (state.search.query.trim().length >= 2) {
                SearchResults(state, onSelect, onToggleFavorite)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        FilledTonalButton(
                            onClick = {
                                if (hasLocationPermission(context)) {
                                    onLocate()
                                } else {
                                    permissionLauncher.launch(
                                        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                                    )
                                }
                            },
                            enabled = !state.locating,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        ) {
                            if (state.locating) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.MyLocation, contentDescription = null)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.locating) "Localisation…" else "Météo à ma position")
                        }
                        state.locationError?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Favoris",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        if (state.favorites.isEmpty()) {
                            Text(
                                "Aucun favori. Recherche un lieu puis touche l'étoile pour l'ajouter.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    items(state.favorites, key = { it.id }) { place ->
                        PlaceRow(place, isFavorite = true, isCurrent = place.id == state.place?.id, onSelect, onToggleFavorite)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(state: MeteoUiState, onSelect: (Place) -> Unit, onToggleFavorite: (Place) -> Unit) {
    val search = state.search
    when {
        search.loading && search.results.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        search.error != null -> Text(search.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        search.results.isEmpty() -> Text("Aucun lieu trouvé.", modifier = Modifier.padding(16.dp))
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(search.results) { place ->
                PlaceRow(
                    place,
                    isFavorite = state.favorites.any { it.id == place.id },
                    isCurrent = false,
                    onSelect,
                    onToggleFavorite,
                )
            }
        }
    }
}

@Composable
private fun PlaceRow(
    place: Place,
    isFavorite: Boolean,
    isCurrent: Boolean,
    onSelect: (Place) -> Unit,
    onToggleFavorite: (Place) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { onSelect(place) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(place.subtitle.ifBlank { null }, Fmt.meters(place.elevation))
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { onToggleFavorite(place) }) {
                Icon(
                    if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                    tint = if (isFavorite) Color(0xFFEDA100) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
