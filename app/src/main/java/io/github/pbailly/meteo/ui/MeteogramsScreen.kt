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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pbailly.meteo.ui.theme.SecondaryOnNavy
import io.github.pbailly.meteo.ui.theme.SelectedOnCard

private val TABS = listOf("7 jours", "14 jours", "MultiModèle")

/** Écran « Météogrammes » : météogramme horaire, graphique 14 jours et comparaison des modèles. */
@Composable
fun MeteogramsScreen(
    state: MeteoUiState,
    onBack: () -> Unit,
    onMultiModelNeeded: () -> Unit,
    onRetryMultiModel: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { onMultiModelNeeded() }
    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text("Météogrammes", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SegmentedTabs(tab, onSelect = { tab = it })
            val data = state.forecast.data
            if (data == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(12.dp))
                when (tab) {
                    0 -> MeteogramTab(data)
                    1 -> DaysTab(data, state.multiModel.data)
                    else -> MultiModelTab(state.multiModel, state.hiddenModels, onRetry = onRetryMultiModel)
                }
                Attribution()
            }
        }
    }
}

/** Trois boutons côte à côte dans une barre marine, comme les onglets de Meteoblue. */
@Composable
private fun SegmentedTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(NavyCard)
            .padding(4.dp),
    ) {
        TABS.forEachIndexed { index, title ->
            Text(
                title,
                color = if (index == selected) Color.White else SecondaryOnNavy,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (index == selected) FontWeight.SemiBold else null,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (index == selected) SelectedOnCard else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
            )
        }
    }
}
