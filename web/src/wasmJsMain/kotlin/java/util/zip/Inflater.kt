package java.util.zip

/**
 * Décompression « deflate » (RFC 1951), avec ou sans l'enveloppe zlib (RFC 1950), comme java.util.zip.Inflater :
 * le navigateur n'en offre pas de synchrone. Tout le flux est décompressé à la première lecture.
 */
class Inflater(private val nowrap: Boolean = false) {
    private var input = ByteArray(0)
    private var output: ByteArray? = null
    private var read = 0

    fun setInput(bytes: ByteArray) = setInput(bytes, 0, bytes.size)
    fun setInput(bytes: ByteArray, off: Int, len: Int) {
        input = bytes.copyOfRange(off, off + len)
        output = null
        read = 0
    }

    fun inflate(b: ByteArray): Int = inflate(b, 0, b.size)

    fun inflate(b: ByteArray, off: Int, len: Int): Int {
        val out = output ?: decode().also { output = it }
        val n = minOf(len, out.size - read)
        out.copyInto(b, off, read, read + n)
        read += n
        return n
    }

    fun finished(): Boolean = output?.let { read >= it.size } ?: false
    fun needsInput(): Boolean = input.isEmpty()
    fun needsDictionary(): Boolean = false
    fun end() {
        input = ByteArray(0)
        output = null
    }

    private fun decode(): ByteArray {
        // Enveloppe zlib : deux octets d'en-tête (CM = 8, somme de contrôle sur 31).
        val zlib = !nowrap && input.size >= 2 && (input[0].toInt() and 0x0F) == 8 &&
            (((input[0].toInt() and 0xFF) shl 8) or (input[1].toInt() and 0xFF)) % 31 == 0
        return Deflate(input, if (zlib) 2 else 0).run()
    }
}

/** Décodeur « deflate » : blocs stockés, Huffman fixe et Huffman dynamique. */
private class Deflate(private val src: ByteArray, start: Int) {
    private var pos = start
    private var bitBuf = 0
    private var bitCount = 0
    private var out = ByteArray(maxOf(1024, src.size * 4))
    private var outLen = 0

    private fun bits(n: Int): Int {
        while (bitCount < n) {
            val b = if (pos < src.size) src[pos].toInt() and 0xFF else throw DataFormatException("Flux deflate tronqué")
            pos++
            bitBuf = bitBuf or (b shl bitCount)
            bitCount += 8
        }
        val v = bitBuf and ((1 shl n) - 1)
        bitBuf = bitBuf ushr n
        bitCount -= n
        return v
    }

    private fun put(b: Int) {
        if (outLen == out.size) out = out.copyOf(out.size * 2)
        out[outLen++] = b.toByte()
    }

    /** Table de Huffman canonique : nombre de codes par longueur, puis symboles rangés. */
    private class Huffman(lengths: IntArray) {
        val counts = IntArray(16)
        val symbols = IntArray(lengths.size)

        init {
            for (l in lengths) counts[l]++
            counts[0] = 0
            val offs = IntArray(16)
            for (i in 1 until 16) offs[i] = offs[i - 1] + counts[i - 1]
            for (s in lengths.indices) if (lengths[s] != 0) symbols[offs[lengths[s]]++] = s
        }
    }

    private fun decodeSymbol(h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1 until 16) {
            code = code or bits(1)
            val count = h.counts[len]
            if (code - count < first) return h.symbols[index + (code - first)]
            index += count
            first += count
            first = first shl 1
            code = code shl 1
        }
        throw DataFormatException("Code de Huffman invalide")
    }

    fun run(): ByteArray {
        do {
            val last = bits(1)
            when (bits(2)) {
                0 -> stored()
                1 -> codes(FIXED_LIT, FIXED_DIST)
                2 -> dynamic()
                else -> throw DataFormatException("Type de bloc deflate invalide")
            }
        } while (last == 0)
        return out.copyOf(outLen)
    }

    private fun stored() {
        bitBuf = 0
        bitCount = 0
        if (pos + 4 > src.size) throw DataFormatException("Bloc stocké tronqué")
        val len = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
        pos += 4
        if (pos + len > src.size) throw DataFormatException("Bloc stocké tronqué")
        for (k in 0 until len) put(src[pos + k].toInt())
        pos += len
    }

    private fun dynamic() {
        val nlen = bits(5) + 257
        val ndist = bits(5) + 1
        val ncode = bits(4) + 4
        val lengths = IntArray(19)
        for (k in 0 until ncode) lengths[ORDER[k]] = bits(3)
        val lencode = Huffman(lengths)
        val all = IntArray(nlen + ndist)
        var k = 0
        while (k < nlen + ndist) {
            val sym = decodeSymbol(lencode)
            when {
                sym < 16 -> all[k++] = sym
                else -> {
                    var len = 0
                    val repeat = when (sym) {
                        16 -> {
                            if (k == 0) throw DataFormatException("Répétition sans longueur précédente")
                            len = all[k - 1]
                            3 + bits(2)
                        }
                        17 -> 3 + bits(3)
                        else -> 11 + bits(7)
                    }
                    repeat(repeat) { all[k++] = len }
                }
            }
        }
        codes(Huffman(all.copyOfRange(0, nlen)), Huffman(all.copyOfRange(nlen, nlen + ndist)))
    }

    private fun codes(lit: Huffman, dist: Huffman) {
        while (true) {
            var sym = decodeSymbol(lit)
            if (sym < 256) {
                put(sym)
                continue
            }
            if (sym == 256) return
            sym -= 257
            if (sym >= 29) throw DataFormatException("Longueur deflate invalide")
            val len = LBASE[sym] + bits(LEXT[sym])
            val dsym = decodeSymbol(dist)
            if (dsym >= 30) throw DataFormatException("Distance deflate invalide")
            val d = DBASE[dsym] + bits(DEXT[dsym])
            if (d > outLen) throw DataFormatException("Distance deflate trop grande")
            repeat(len) { put(out[outLen - d].toInt()) }
        }
    }

    private companion object {
        val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
        val LBASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
        val LEXT = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        val DBASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
        val DEXT = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
        val FIXED_LIT = Huffman(IntArray(288) { if (it < 144) 8 else if (it < 256) 9 else if (it < 280) 7 else 8 })
        val FIXED_DIST = Huffman(IntArray(30) { 5 })
    }
}

class DataFormatException(message: String) : Exception(message)
