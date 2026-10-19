package io.github.pbailly.meteo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val TABS = listOf("7 jours", "14 jours", "Modèles")

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
    Column(Modifier.fillMaxSize()) {
        DetailHeader("Météogrammes", onBack)
        state.place?.let { PlaceLine(it.name, it.elevation ?: state.forecast.data?.elevation) }
        SegmentedToggle(TABS, tab, { tab = it }, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), fill = true)
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
