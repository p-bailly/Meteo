package java.io

import kotlinx.browser.localStorage
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

open class IOException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

class FileNotFoundException(message: String? = null) : IOException(message)

/**
 * Un « fichier » gardé dans le stockage local du navigateur (le cache des données, comme sur le téléphone). La date de
 * modification est rangée à côté du contenu.
 */
class File(val path: String) {
    constructor(parent: File, child: String) : this(parent.path.trimEnd('/') + "/" + child)
    constructor(parent: String, child: String) : this(parent.trimEnd('/') + "/" + child)

    val name: String get() = path.substringAfterLast('/')

    private val key get() = "file:$path"
    private val timeKey get() = "mtime:$path"

    fun exists(): Boolean = runCatching { localStorage.getItem(key) != null }.getOrDefault(false)

    fun lastModified(): Long = runCatching { localStorage.getItem(timeKey)?.toLongOrNull() }.getOrNull() ?: 0L

    fun setLastModified(time: Long): Boolean = runCatching {
        if (!exists()) return false
        localStorage.setItem(timeKey, time.toString())
        true
    }.getOrDefault(false)

    fun readText(): String = runCatching { localStorage.getItem(key) }.getOrNull() ?: throw FileNotFoundException(path)

    @OptIn(ExperimentalTime::class)
    fun writeText(text: String) {
        try {
            localStorage.setItem(key, text)
            localStorage.setItem(timeKey, Clock.System.now().toEpochMilliseconds().toString())
        } catch (e: Throwable) {
            throw IOException("Stockage du navigateur plein", e)
        }
    }

    fun delete(): Boolean = runCatching {
        val existed = exists()
        localStorage.removeItem(key)
        localStorage.removeItem(timeKey)
        existed
    }.getOrDefault(false)

    fun mkdirs(): Boolean = true

    fun isDirectory(): Boolean = false

    /** Les fichiers rangés sous ce dossier. */
    fun listFiles(): Array<File>? = runCatching {
        val prefix = "file:" + path.trimEnd('/') + "/"
        (0 until localStorage.length).mapNotNull { localStorage.key(it) }.filter { it.startsWith(prefix) }
            .map { File(it.removePrefix("file:")) }.toTypedArray()
    }.getOrNull()

    fun listFiles(filter: (File) -> Boolean): Array<File>? = listFiles()?.filter(filter)?.toTypedArray()

    override fun toString(): String = path
}
