package android.content

import kotlinx.browser.localStorage
import java.io.File

/** Le contexte d'Android, réduit à ce que le code partagé lui demande : ses dossiers et ses préférences. */
open class Context {
    val cacheDir: File = File("cache")
    val filesDir: File = File("files")

    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences(name)

    /** Ouvre le lien de [intent] dans un nouvel onglet. */
    fun startActivity(intent: Intent) {
        intent.data?.toString()?.let(::openUrl)
    }

    companion object {
        const val MODE_PRIVATE = 0
    }
}

private fun openUrl(url: String): Unit = js("{ window.open(url, '_blank', 'noopener'); }")

/** Préférences gardées dans le stockage local du navigateur (« pref:<nom>:<clé> »). */
class SharedPreferences internal constructor(private val name: String) {
    private fun key(key: String) = "pref:$name:$key"
    private fun raw(key: String): String? = runCatching { localStorage.getItem(key(key)) }.getOrNull()

    fun contains(key: String): Boolean = raw(key) != null
    fun getString(key: String, default: String?): String? = raw(key) ?: default
    fun getInt(key: String, default: Int): Int = raw(key)?.toIntOrNull() ?: default
    fun getLong(key: String, default: Long): Long = raw(key)?.toLongOrNull() ?: default
    fun getFloat(key: String, default: Float): Float = raw(key)?.toFloatOrNull() ?: default
    fun getBoolean(key: String, default: Boolean): Boolean = raw(key)?.toBooleanStrictOrNull() ?: default
    fun getStringSet(key: String, default: Set<String>?): Set<String>? =
        raw(key)?.let { if (it.isEmpty()) emptySet() else it.split('\u0001').toSet() } ?: default

    fun edit(): Editor = Editor()

    inner class Editor internal constructor() {
        private val changes = mutableListOf<Pair<String, String?>>()
        fun putString(key: String, value: String?): Editor = apply { changes += key to value }
        fun putInt(key: String, value: Int): Editor = apply { changes += key to value.toString() }
        fun putLong(key: String, value: Long): Editor = apply { changes += key to value.toString() }
        fun putFloat(key: String, value: Float): Editor = apply { changes += key to value.toString() }
        fun putBoolean(key: String, value: Boolean): Editor = apply { changes += key to value.toString() }
        fun putStringSet(key: String, value: Set<String>?): Editor = apply { changes += key to value?.joinToString("\u0001") }
        fun remove(key: String): Editor = apply { changes += key to null }

        fun apply() {
            commit()
        }

        fun commit(): Boolean = runCatching {
            for ((k, v) in changes) if (v == null) localStorage.removeItem(key(k)) else localStorage.setItem(key(k), v)
            true
        }.getOrDefault(false)
    }
}
