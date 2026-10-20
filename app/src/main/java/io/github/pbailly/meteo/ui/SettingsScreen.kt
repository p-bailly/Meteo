package io.github.pbailly.meteo.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.pbailly.meteo.BuildConfig
import io.github.pbailly.meteo.data.AppUpdate
import io.github.pbailly.meteo.data.Release
import io.github.pbailly.meteo.data.WeatherModel
import io.github.pbailly.meteo.data.networkMessage
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    theme: Int,
    onTheme: (Int) -> Unit,
    textSize: Int,
    onTextSize: (Int) -> Unit,
    hiddenModels: Set<WeatherModel>,
    onModelVisible: (WeatherModel, Boolean) -> Unit,
    meteoFranceKey: String?,
    onSaveKey: (String) -> Unit,
    windyKey: String?,
    onSaveWindyKey: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
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
        SectionLabel("Application")
        BorderedCard { UpdateSettings() }
        SectionLabel("Modèles de la comparaison")
        BorderedCard {
            WeatherModel.entries.forEachIndexed { i, model ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ModelRow(model, visible = model !in hiddenModels, onVisible = { onModelVisible(model, it) })
            }
        }
        SectionLabel("Clé Météo-France")
        BorderedCard {
            KeySettings(
                current = meteoFranceKey,
                onSave = onSaveKey,
                explanation = "Clé d'API gratuite du portail Météo-France. Pour le radar, s'abonner à « DonneesPubliquesRadar » ; " +
                    "pour sa prévision à 3 h, aussi à « PrevisionImmediatePrecipitations » ; pour les relevés des stations sur " +
                    "la carte de température, à « DonneesPubliquesPaquetObservation ». Générer la clé après les " +
                    "abonnements : une clé ne donne accès qu'aux API souscrites au moment où elle est créée.",
                portalUrl = "https://portail-api.meteofrance.fr",
                portalLabel = "Ouvrir le portail Météo-France",
            )
        }
        SectionLabel("Webcams · clé Windy")
        BorderedCard {
            KeySettings(
                current = windyKey,
                onSave = onSaveWindyKey,
                explanation = "Clé gratuite de l'API Webcams de Windy : créer un compte sur api.windy.com, puis une clé " +
                    "« Webcams API » avec l'offre gratuite.",
                portalUrl = "https://api.windy.com/keys",
                portalLabel = "Ouvrir api.windy.com",
            )
        }
        SectionLabel("Sources")
        MonoText(
            "Prévisions : Open-Meteo.com (CC BY 4.0). Radar, prévision immédiate et relevés : Météo-France " +
                "(Licence Ouverte). Relevés : MétéoSuisse, NOAA (METAR des aéroports). Carte de température : DWD (ICON-EU). Relief : Copernicus DEM (via " +
                "Open-Meteo), sommets © contributeurs OpenStreetMap. Fond de carte : OpenFreeMap, © OpenStreetMap. " +
                "Polices : Archivo et Martian Mono (SIL OFL 1.1).",
            size = 10.5.sp,
            maxLines = 12,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModelRow(model: WeatherModel, visible: Boolean, onVisible: (Boolean) -> Unit) {
    val color = LocalChartColors.current.series[model.ordinal]
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onVisible(!visible) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(model.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            MonoText(model.detail, size = 10.5.sp)
        }
        Switch(checked = visible, onCheckedChange = onVisible)
    }
}

@Composable
private fun KeySettings(
    current: String?,
    onSave: (String) -> Unit,
    explanation: String,
    portalUrl: String,
    portalLabel: String,
) {
    val context = LocalContext.current
    var key by rememberSaveable(current) { mutableStateOf(current.orEmpty()) }
    var visible by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(14.dp)) {
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(portalUrl)))
        }) { Text(portalLabel) }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("Clé d'API") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Row {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (visible) "Masquer la clé" else "Afficher la clé",
                        )
                    }
                    if (key.isNotEmpty()) {
                        IconButton(onClick = { key = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Effacer") }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { onSave(key) }, enabled = key != current.orEmpty(), shape = RoundedCornerShape(12.dp)) { Text("Enregistrer") }
        }
    }
}

/** Où en est la mise à jour de l'appli. */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    class Available(val release: Release) : UpdateState
    class Downloading(val progress: Float) : UpdateState
    class Ready(val apk: File) : UpdateState
    class Failed(val message: String) : UpdateState
}

/**
 * Version installée et mise à jour : on compare le commit de l'appli à celui de la dernière version publiée, on
 * télécharge l'APK puis l'installeur d'Android prend le relais (il demande la permission la première fois).
 */
@Composable
private fun UpdateSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val commit = BuildConfig.GIT_SHA.take(7)
    fun install(apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            // Première fois : Android demande d'autoriser l'appli à installer des mises à jour.
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    fun check() {
        state = UpdateState.Checking
        scope.launch {
            state = try {
                val release = AppUpdate.latest()
                if (AppUpdate.isNewer(release, BuildConfig.GIT_SHA)) UpdateState.Available(release) else UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(if (e is IOException) e.networkMessage() else "Vérification impossible")
            }
        }
    }
    fun download(release: Release) {
        state = UpdateState.Downloading(0f)
        scope.launch {
            state = try {
                val apk = AppUpdate.download(release, File(context.cacheDir, "updates")) { p ->
                    scope.launch { if (state is UpdateState.Downloading) state = UpdateState.Downloading(p) }
                }
                install(apk)
                UpdateState.Ready(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(if (e is IOException) e.networkMessage() else "Téléchargement impossible")
            }
        }
    }
    Column(Modifier.padding(14.dp)) {
        Text("Version ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        MonoText(if (commit.isEmpty()) "compilée hors de GitHub" else "commit $commit", size = 10.5.sp)
        Spacer(Modifier.height(10.dp))
        when (val s = state) {
            UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Recherche d'une mise à jour…", style = MaterialTheme.typography.bodyMedium)
            }
            is UpdateState.Available -> {
                val date = s.release.publishedAt?.let {
                    DateTimeFormatter.ofPattern("d MMMM 'à' HH:mm", Locale.FRANCE).format(it.atZone(ZoneId.systemDefault()))
                }
                Text(
                    "Nouvelle version" + (date?.let { " du $it" } ?: "") +
                        (if (s.release.size > 0) " (${s.release.size / 1_000_000} Mo)" else ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { download(s.release) }, modifier = Modifier.padding(top = 8.dp)) { Text("Télécharger et installer") }
            }
            is UpdateState.Downloading -> {
                Text("Téléchargement… ${(s.progress * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            is UpdateState.Ready -> {
                Text("Mise à jour téléchargée. Si l'installation ne s'est pas ouverte, autorise Météo à installer des applis puis touche Installer.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { install(s.apk) }, modifier = Modifier.padding(top = 8.dp)) { Text("Installer") }
            }
            else -> {
                when (s) {
                    UpdateState.UpToDate -> Text("L'appli est à jour.", style = MaterialTheme.typography.bodyMedium)
                    is UpdateState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    else -> Unit
                }
                Button(onClick = { check() }, modifier = Modifier.padding(top = 6.dp)) { Text("Rechercher une mise à jour") }
            }
        }
    }
}
