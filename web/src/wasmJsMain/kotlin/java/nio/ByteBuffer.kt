package java.nio

/** Ordre des octets d'un nombre lu dans un [ByteBuffer]. */
class ByteOrder private constructor(private val name: String) {
    override fun toString(): String = name

    companion object {
        val BIG_ENDIAN = ByteOrder("BIG_ENDIAN")
        val LITTLE_ENDIAN = ByteOrder("LITTLE_ENDIAN")
        fun nativeOrder(): ByteOrder = LITTLE_ENDIAN
    }
}

/** Lecture de nombres dans un tableau d'octets (GeoTIFF, GRIB2), comme java.nio.ByteBuffer. */
class ByteBuffer private constructor(private val bytes: ByteArray, private val offset: Int, private val size: Int) {
    private var order = ByteOrder.BIG_ENDIAN
    private var pos = 0

    fun order(): ByteOrder = order
    fun order(order: ByteOrder): ByteBuffer = apply { this.order = order }
    fun position(): Int = pos
    fun position(newPosition: Int): ByteBuffer = apply { pos = newPosition }
    fun limit(): Int = size
    fun capacity(): Int = size
    fun remaining(): Int = size - pos
    fun hasRemaining(): Boolean = pos < size
    fun array(): ByteArray = bytes

    private fun at(i: Int): Int {
        if (i < 0 || i >= size) throw IndexOutOfBoundsException("Lecture hors du tampon : $i")
        return bytes[offset + i].toInt() and 0xFF
    }

    private fun read(i: Int, n: Int): Long {
        var v = 0L
        if (order === ByteOrder.BIG_ENDIAN) {
            for (k in 0 until n) v = (v shl 8) or at(i + k).toLong()
        } else {
            for (k in n - 1 downTo 0) v = (v shl 8) or at(i + k).toLong()
        }
        return v
    }

    fun get(): Byte = bytes[offset + pos].also { at(pos); pos++ }
    fun get(index: Int): Byte = at(index).toByte()
    fun get(dst: ByteArray): ByteBuffer = get(dst, 0, dst.size)
    fun get(dst: ByteArray, off: Int, len: Int): ByteBuffer {
        for (k in 0 until len) dst[off + k] = at(pos + k).toByte()
        pos += len
        return this
    }

    private fun write(i: Int, n: Int, value: Long) {
        for (k in 0 until n) {
            val shift = if (order === ByteOrder.BIG_ENDIAN) 8 * (n - 1 - k) else 8 * k
            at(i + k)
            bytes[offset + i + k] = (value shr shift).toByte()
        }
    }

    fun putShort(index: Int, value: Short): ByteBuffer = apply { write(index, 2, value.toLong()) }
    fun putInt(index: Int, value: Int): ByteBuffer = apply { write(index, 4, value.toLong()) }
    fun putLong(index: Int, value: Long): ByteBuffer = apply { write(index, 8, value) }

    fun getShort(index: Int): Short = read(index, 2).toInt().toShort()
    fun getShort(): Short = getShort(pos).also { pos += 2 }
    fun getInt(index: Int): Int = read(index, 4).toInt()
    fun getInt(): Int = getInt(pos).also { pos += 4 }
    fun getLong(index: Int): Long = read(index, 8)
    fun getLong(): Long = getLong(pos).also { pos += 8 }
    fun getFloat(index: Int): Float = Float.fromBits(getInt(index))
    fun getFloat(): Float = getFloat(pos).also { pos += 4 }
    fun getDouble(index: Int): Double = Double.fromBits(getLong(index))
    fun getDouble(): Double = getDouble(pos).also { pos += 8 }

    companion object {
        fun wrap(array: ByteArray): ByteBuffer = ByteBuffer(array, 0, array.size)
        fun wrap(array: ByteArray, offset: Int, length: Int): ByteBuffer =
            ByteBuffer(array, offset, length).also { it.pos = 0 }
    }
}
