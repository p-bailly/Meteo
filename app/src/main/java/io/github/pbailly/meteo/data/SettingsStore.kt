package io.github.pbailly.meteo.data

import android.content.Context

/** Réglages de l'app, gardés sur le téléphone. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Modèles masqués dans l'onglet MultiModèle. */
    fun hiddenModels(): Set<WeatherModel> {
        val ids = prefs.getStringSet(KEY_HIDDEN_MODELS, emptySet()).orEmpty()
        return WeatherModel.entries.filter { it.apiId in ids }.toSet()
    }

    fun saveHiddenModels(models: Set<WeatherModel>) {
        prefs.edit().putStringSet(KEY_HIDDEN_MODELS, models.map { it.apiId }.toSet()).apply()
    }

    private companion object {
        const val KEY_HIDDEN_MODELS = "hidden_models"
    }
}
