package android.util

/** Cache des [maxSize] dernières valeurs utilisées, comme android.util.LruCache (taille comptée en éléments). */
open class LruCache<K : Any, V : Any>(private val maxSize: Int) {
    private val map = LinkedHashMap<K, V>()

    fun get(key: K): V? {
        val v = map.remove(key) ?: return null
        map[key] = v
        return v
    }

    fun put(key: K, value: V): V? {
        val previous = map.remove(key)
        map[key] = value
        while (map.size > maxSize) map.remove(map.keys.first())
        return previous
    }

    fun remove(key: K): V? = map.remove(key)
    fun evictAll() = map.clear()
    fun size(): Int = map.size
}
