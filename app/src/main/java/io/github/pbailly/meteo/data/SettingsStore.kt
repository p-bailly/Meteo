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

    /** Thème : 0 = comme le téléphone, 1 = clair, 2 = sombre. */
    fun theme(): Int = prefs.getInt(KEY_THEME, 0).coerceIn(0, 2)

    fun saveTheme(theme: Int) {
        prefs.edit().putInt(KEY_THEME, theme).apply()
    }

    /** Taille du texte : 0 = normale, 1 = grande, 2 = très grande. */
    fun textSize(): Int = prefs.getInt(KEY_TEXT_SIZE, 0).coerceIn(0, 2)

    fun saveTextSize(size: Int) {
        prefs.edit().putInt(KEY_TEXT_SIZE, size).apply()
    }

    /** Dessin d'Aujourd'hui : 0 = discret, 1 = normal, 2 = coloré. */
    fun sketchStyle(): Int = prefs.getInt(KEY_SKETCH_STYLE, 1).coerceIn(0, 2)

    fun saveSketchStyle(style: Int) {
        prefs.edit().putInt(KEY_SKETCH_STYLE, style).apply()
    }

    /** La pluie, la neige et les nuages du dessin bougent. */
    fun sketchMotion(): Boolean = prefs.getBoolean(KEY_SKETCH_MOTION, true)

    fun saveSketchMotion(motion: Boolean) {
        prefs.edit().putBoolean(KEY_SKETCH_MOTION, motion).apply()
    }

    private companion object {
        const val KEY_HIDDEN_MODELS = "hidden_models"
        const val KEY_THEME = "theme"
        const val KEY_TEXT_SIZE = "text_size"
        const val KEY_SKETCH_STYLE = "sketch_style"
        const val KEY_SKETCH_MOTION = "sketch_motion"
    }
}
