package io.github.pbailly.meteo.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.pbailly.meteo.data.WeatherModel
import io.github.pbailly.meteo.ui.theme.LocalChartColors

@Composable
fun SettingsScreen(
    hiddenModels: Set<WeatherModel>,
    onModelVisible: (WeatherModel, Boolean) -> Unit,
    meteoFranceKey: String?,
    onSaveKey: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            NavyTopBar(
                title = { Text("Réglages", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("Modèles de l'onglet MultiModèle")
            WeatherModel.entries.forEach { model ->
                ModelRow(model, visible = model !in hiddenModels, onVisible = { onModelVisible(model, it) })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SectionTitle("Radar Météo-France")
            KeySettings(meteoFranceKey, onSaveKey)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SectionTitle("Sources")
            Text(
                "Prévisions : Open-Meteo.com (CC BY 4.0). Radar et prévision immédiate : Météo-France " +
                    "(Licence Ouverte). Fond de carte : OpenFreeMap, © OpenStreetMap. Polices : IBM Plex (SIL OFL 1.1).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModelRow(model: WeatherModel, visible: Boolean, onVisible: (Boolean) -> Unit) {
    val color = LocalChartColors.current.series[model.ordinal]
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onVisible(!visible) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(model.label, style = MaterialTheme.typography.bodyLarge)
            Text(model.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = visible, onCheckedChange = onVisible)
    }
}

@Composable
private fun KeySettings(current: String?, onSave: (String) -> Unit) {
    val context = LocalContext.current
    var key by rememberSaveable(current) { mutableStateOf(current.orEmpty()) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "Clé d'API gratuite du portail Météo-France. Pour le radar, s'abonner à « DonneesPubliquesRadar » ; " +
                "pour sa prévision à 3 h, aussi à « PrevisionImmediatePrecipitations ». Générer la clé après les " +
                "abonnements : une clé ne donne accès qu'aux API souscrites au moment où elle est créée.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://portail-api.meteofrance.fr")))
        }) { Text("Ouvrir le portail Météo-France") }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("Clé d'API") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { onSave(key) }, enabled = key != current.orEmpty()) { Text("Enregistrer") }
        }
    }
}
