package java.io

import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

open class IOException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

class FileNotFoundException(message: String? = null) : IOException(message)

// Les fichiers de l'appli (cache des prévisions, images radar…) : en mémoire pour être lus sans attendre, comme sur
// le téléphone, et recopiés dans IndexedDB, où le navigateur les garde d'une visite à l'autre ([WebFiles.load] les
// relit au démarrage). IndexedDB tient des dizaines de Mo, là où le stockage local s'arrête à 5.

private fun idbLoad(): Promise<JsAny?> = js(
    """new Promise((resolve) => {
        try {
            const open = indexedDB.open("meteo-fichiers", 1);
            open.onupgradeneeded = () => open.result.createObjectStore("f");
            open.onerror = () => resolve(null);
            open.onsuccess = () => {
                const db = open.result;
                globalThis.meteoFilesDb = db;
                const tx = db.transaction("f", "readonly");
                const store = tx.objectStore("f");
                const keys = store.getAllKeys();
                const values = store.getAll();
                tx.oncomplete = () => resolve({ keys: keys.result, values: values.result });
                tx.onerror = () => resolve(null);
            };
        } catch (e) {
            resolve(null);
        }
    })"""
)

private fun idbCount(all: JsAny): Int = js("all.keys.length")
private fun idbKey(all: JsAny, i: Int): String = js("String(all.keys[i])")
private fun idbData(all: JsAny, i: Int): String = js("all.values[i].d")
private fun idbIsBytes(all: JsAny, i: Int): Boolean = js("!!all.values[i].b")
private fun idbTime(all: JsAny, i: Int): Double = js("all.values[i].t")

private fun idbPut(key: String, data: String, bytes: Boolean, time: Double): Unit = js(
    """{ try { globalThis.meteoFilesDb?.transaction("f", "readwrite").objectStore("f").put({ d: data, b: bytes, t: time }, key); } catch (e) {} }"""
)

private fun idbDelete(key: String): Unit = js(
    """{ try { globalThis.meteoFilesDb?.transaction("f", "readwrite").objectStore("f").delete(key); } catch (e) {} }"""
)

private fun clearOldLocalStorage(): Unit = js(
    """{ try {
        for (let i = localStorage.length - 1; i >= 0; i--) {
            const k = localStorage.key(i);
            if (k && (k.startsWith("file:") || k.startsWith("mtime:"))) localStorage.removeItem(k);
        }
    } catch (e) {} }"""
)

/** Un octet par caractère : la façon la plus rapide de passer des octets entre Kotlin et JavaScript. */
internal fun ByteArray.toLatin1(): String = CharArray(size) { (this[it].toInt() and 0xFF).toChar() }.concatToString()

internal fun String.fromLatin1(): ByteArray = ByteArray(length) { this[it].code.toByte() }

object WebFiles {
    private class Entry(var text: String?, var bytes: ByteArray?, var time: Long)

    private val entries = HashMap<String, Entry>()
    private val dirs = HashSet<String>()

    /** Relit les fichiers gardés par le navigateur ; à appeler une fois, avant l'appli. */
    suspend fun load() {
        clearOldLocalStorage()
        val all = idbLoad().await<JsAny?>() ?: return
        for (i in 0 until idbCount(all)) {
            val data = idbData(all, i)
            entries[idbKey(all, i)] = if (idbIsBytes(all, i)) {
                Entry(null, data.fromLatin1(), idbTime(all, i).toLong())
            } else {
                Entry(data, null, idbTime(all, i).toLong())
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    internal fun now(): Long = Clock.System.now().toEpochMilliseconds()

    internal fun get(path: String): Pair<String?, ByteArray?>? = entries[path]?.let { it.text to it.bytes }
    internal fun exists(path: String): Boolean = path in entries
    internal fun time(path: String): Long = entries[path]?.time ?: 0L

    internal fun put(path: String, text: String?, bytes: ByteArray?, time: Long = now()) {
        entries[path] = Entry(text, bytes, time)
        persist(path)
    }

    internal fun touch(path: String, time: Long): Boolean {
        val e = entries[path] ?: return false
        e.time = time
        persist(path)
        return true
    }

    private fun persist(path: String) {
        val e = entries[path] ?: return
        if (e.bytes != null) idbPut(path, e.bytes!!.toLatin1(), true, e.time.toDouble())
        else idbPut(path, e.text.orEmpty(), false, e.time.toDouble())
    }

    internal fun remove(path: String): Boolean {
        val existed = entries.remove(path) != null
        if (existed) idbDelete(path)
        return existed
    }

    internal fun mkdirs(path: String) {
        dirs += path.trimEnd('/')
    }

    internal fun isDirectory(path: String): Boolean {
        val p = path.trimEnd('/')
        return p in dirs || entries.keys.any { it.startsWith("$p/") } || dirs.any { it.startsWith("$p/") }
    }

    /** Fichiers et dossiers directement sous [path]. */
    internal fun children(path: String): List<String> {
        val prefix = path.trimEnd('/') + "/"
        return (entries.keys.asSequence() + dirs.asSequence())
            .filter { it.startsWith(prefix) }
            .map { prefix + it.removePrefix(prefix).substringBefore('/') }
            .distinct()
            .toList()
    }

    internal fun removeTree(path: String) {
        val prefix = path.trimEnd('/') + "/"
        entries.keys.filter { it == path || it.startsWith(prefix) }.forEach { remove(it) }
        dirs.removeAll { it == path.trimEnd('/') || it.startsWith(prefix) }
    }
}

/** Un fichier de l'appli dans le navigateur ([WebFiles]). */
class File(path: String) {
    val path: String = path.trimEnd('/').ifEmpty { "/" }

    constructor(parent: File, child: String) : this(parent.path + "/" + child)
    constructor(parent: String, child: String) : this(parent.trimEnd('/') + "/" + child)

    val name: String get() = path.substringAfterLast('/')
    val nameWithoutExtension: String get() = name.substringBeforeLast('.')
    val extension: String get() = name.substringAfterLast('.', "")
    val parentFile: File? get() = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(::File)
    val absolutePath: String get() = path

    fun exists(): Boolean = WebFiles.exists(path) || WebFiles.isDirectory(path)
    fun isFile(): Boolean = WebFiles.exists(path)
    val isDirectory: Boolean get() = WebFiles.isDirectory(path)
    fun lastModified(): Long = WebFiles.time(path)
    fun setLastModified(time: Long): Boolean = WebFiles.touch(path, time)
    fun length(): Long = WebFiles.get(path)?.let { (t, b) -> b?.size?.toLong() ?: t?.encodeToByteArray()?.size?.toLong() } ?: 0L

    fun readText(): String {
        val (text, bytes) = WebFiles.get(path) ?: throw FileNotFoundException(path)
        return text ?: bytes!!.decodeToString()
    }

    fun readBytes(): ByteArray {
        val (text, bytes) = WebFiles.get(path) ?: throw FileNotFoundException(path)
        return bytes ?: text!!.encodeToByteArray()
    }

    fun writeText(text: String) = WebFiles.put(path, text, null)
    fun writeBytes(bytes: ByteArray) = WebFiles.put(path, null, bytes)

    fun outputStream(): OutputStream = object : ByteArrayOutputStream() {
        override fun close() = writeBytes(toByteArray())
    }

    fun delete(): Boolean = WebFiles.remove(path)
    fun deleteRecursively(): Boolean {
        WebFiles.removeTree(path)
        return true
    }

    fun mkdirs(): Boolean {
        WebFiles.mkdirs(path)
        return true
    }

    fun renameTo(dest: File): Boolean {
        val (text, bytes) = WebFiles.get(path) ?: return false
        WebFiles.put(dest.path, text, bytes, WebFiles.time(path))
        WebFiles.remove(path)
        return true
    }

    fun listFiles(): Array<File>? = if (isDirectory) WebFiles.children(path).map(::File).toTypedArray() else null
    fun listFiles(filter: (File) -> Boolean): Array<File>? = listFiles()?.filter(filter)?.toTypedArray()

    override fun equals(other: Any?): Boolean = other is File && other.path == path
    override fun hashCode(): Int = path.hashCode()
    override fun toString(): String = path
}

abstract class OutputStream : AutoCloseable {
    abstract fun write(b: Int)
    open fun write(b: ByteArray) = write(b, 0, b.size)
    open fun write(b: ByteArray, off: Int, len: Int) {
        for (k in off until off + len) write(b[k].toInt())
    }
    open fun flush() = Unit
    override fun close() = Unit
}

open class ByteArrayOutputStream(initial: Int = 32) : OutputStream() {
    private var buf = ByteArray(maxOf(initial, 16))
    private var count = 0

    private fun ensure(extra: Int) {
        if (count + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, count + extra))
    }

    override fun write(b: Int) {
        ensure(1)
        buf[count++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensure(len)
        b.copyInto(buf, count, off, off + len)
        count += len
    }

    fun size(): Int = count
    fun reset() {
        count = 0
    }
    fun toByteArray(): ByteArray = buf.copyOf(count)
    override fun toString(): String = toByteArray().decodeToString()
}
