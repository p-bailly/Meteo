package io.github.pbailly.meteo.data

import java.io.ByteArrayOutputStream

/**
 * Décompression bzip2 (format des fichiers du serveur ouvert du DWD), sans bibliothèque : Huffman, transformée
 * de Burrows-Wheeler inverse et répétitions. Les flux concaténés sont lus à la suite. Le CRC n'est pas vérifié.
 */
object Bzip2 {
    private const val MAX_CODE_LEN = 20
    private const val RUN_A = 0
    private const val RUN_B = 1
    private const val GROUP_SIZE = 50

    fun decompress(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size * 3)
        val bits = Bits(input)
        var first = true
        while (bits.bytesLeft() >= 4) {
            val magic = bits.read(24)
            if (magic != 0x425A68) { // « BZh »
                require(!first) { "Ce n'est pas un fichier bzip2" }
                break
            }
            first = false
            val level = bits.read(8) - '0'.code
            require(level in 1..9) { "Taille de bloc bzip2 invalide" }
            val tt = IntArray(level * 100_000)
            while (true) {
                val high = bits.read(24)
                val low = bits.read(24)
                if (high == 0x177245 && low == 0x385090) { // fin du flux
                    bits.read(32)
                    bits.alignToByte()
                    break
                }
                require(high == 0x314159 && low == 0x265359) { "Bloc bzip2 invalide" }
                bits.read(32) // CRC du bloc
                require(bits.read(1) == 0) { "Bloc bzip2 « randomisé » non pris en charge" }
                val origPtr = bits.read(24)
                val length = readBlock(bits, tt)
                writeBlock(tt, length, origPtr, out)
            }
        }
        return out.toByteArray()
    }

    /** Lit un bloc jusqu'au symbole de fin ; [tt] reçoit les octets avant la transformée inverse. Renvoie leur nombre. */
    private fun readBlock(bits: Bits, tt: IntArray): Int {
        // Octets présents dans le bloc.
        val used = bits.read(16)
        val seqToUnseq = IntArray(256)
        var inUse = 0
        for (i in 0 until 16) {
            if ((used and (0x8000 ushr i)) == 0) continue
            val group = bits.read(16)
            for (j in 0 until 16) if ((group and (0x8000 ushr j)) != 0) seqToUnseq[inUse++] = i * 16 + j
        }
        require(inUse > 0) { "Bloc bzip2 vide" }
        val alphaSize = inUse + 2

        // Tables de Huffman et sélecteurs (codés en déplacement vers l'avant).
        val groups = bits.read(3)
        require(groups in 2..6) { "Nombre de tables bzip2 invalide" }
        val selectorCount = bits.read(15)
        require(selectorCount > 0) { "Sélecteurs bzip2 absents" }
        val mtfGroups = IntArray(groups) { it }
        val selectors = IntArray(selectorCount) {
            var j = 0
            while (bits.read(1) == 1) {
                j++
                require(j < groups) { "Sélecteur bzip2 invalide" }
            }
            val v = mtfGroups[j]
            for (k in j downTo 1) mtfGroups[k] = mtfGroups[k - 1]
            mtfGroups[0] = v
            v
        }
        val tables = Array(groups) {
            val lengths = IntArray(alphaSize)
            var current = bits.read(5)
            for (s in 0 until alphaSize) {
                while (true) {
                    require(current in 1..MAX_CODE_LEN) { "Longueur de code bzip2 invalide" }
                    if (bits.read(1) == 0) break
                    current += if (bits.read(1) == 0) 1 else -1
                }
                lengths[s] = current
            }
            Huffman(lengths)
        }

        // Symboles : déplacement vers l'avant et répétitions (RUNA, RUNB).
        val mtf = IntArray(256) { it }
        val endOfBlock = alphaSize - 1
        var count = 0
        var groupIndex = 0
        var groupLeft = 0
        var table = tables[0]
        var run = 0
        var runWeight = 1
        while (true) {
            if (groupLeft == 0) {
                require(groupIndex < selectorCount) { "Bloc bzip2 tronqué" }
                table = tables[selectors[groupIndex++]]
                groupLeft = GROUP_SIZE
            }
            groupLeft--
            val symbol = table.decode(bits)
            if (symbol == RUN_A || symbol == RUN_B) {
                run += runWeight shl symbol // RUNA vaut 1 × poids, RUNB 2 × poids
                runWeight = runWeight shl 1
                require(run <= tt.size) { "Bloc bzip2 trop long" }
                continue
            }
            if (run > 0) {
                val byte = seqToUnseq[mtf[0]]
                require(count + run <= tt.size) { "Bloc bzip2 trop long" }
                repeat(run) { tt[count++] = byte }
                run = 0
                runWeight = 1
            }
            if (symbol == endOfBlock) break
            val index = symbol - 1
            val v = mtf[index]
            for (k in index downTo 1) mtf[k] = mtf[k - 1]
            mtf[0] = v
            require(count < tt.size) { "Bloc bzip2 trop long" }
            tt[count++] = seqToUnseq[v]
        }
        return count
    }

    /** Transformée de Burrows-Wheeler inverse, puis répétitions « 4 octets + nombre ». */
    private fun writeBlock(tt: IntArray, length: Int, origPtr: Int, out: ByteArrayOutputStream) {
        require(origPtr in 0 until length) { "Bloc bzip2 invalide" }
        val cftab = IntArray(257)
        for (i in 0 until length) cftab[(tt[i] and 0xFF) + 1]++
        for (i in 1..256) cftab[i] += cftab[i - 1]
        for (i in 0 until length) {
            val b = tt[i] and 0xFF
            tt[cftab[b]] = tt[cftab[b]] or (i shl 8)
            cftab[b]++
        }
        var pos = tt[origPtr] ushr 8
        var last = -1
        var same = 0
        for (n in 0 until length) {
            val entry = tt[pos]
            val b = entry and 0xFF
            pos = entry ushr 8
            if (same == 4) {
                repeat(b) { out.write(last) }
                same = 0
                last = -1
                continue
            }
            out.write(b)
            if (b == last) same++ else { last = b; same = 1 }
        }
    }

    /** Code de Huffman canonique, décodé par longueurs croissantes. */
    private class Huffman(lengths: IntArray) {
        private val minLen = lengths.min()
        private val maxLen = lengths.max()
        private val limit = IntArray(MAX_CODE_LEN + 2)
        private val base = IntArray(MAX_CODE_LEN + 2)
        private val perm = IntArray(lengths.size)

        init {
            var p = 0
            for (len in minLen..maxLen) for (s in lengths.indices) if (lengths[s] == len) perm[p++] = s
            val counts = IntArray(MAX_CODE_LEN + 2)
            for (len in lengths) counts[len]++
            var code = 0
            var index = 0
            for (len in minLen..maxLen) {
                // Codes de cette longueur : de [code] à [code + counts - 1], symboles perm[index…].
                base[len] = index - code
                code += counts[len]
                limit[len] = code - 1
                index += counts[len]
                code = code shl 1
            }
        }

        fun decode(bits: Bits): Int {
            var len = minLen
            var code = bits.read(len)
            while (code > limit[len]) {
                len++
                require(len <= maxLen) { "Code bzip2 invalide" }
                code = (code shl 1) or bits.read(1)
            }
            return perm[code + base[len]]
        }
    }

    /** Lecture bit à bit, poids fort en premier. */
    private class Bits(private val data: ByteArray) {
        private var pos = 0L

        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = (pos ushr 3).toInt()
                require(byte < data.size) { "Fichier bzip2 tronqué" }
                v = (v shl 1) or ((data[byte].toInt() ushr (7 - (pos and 7).toInt())) and 1)
                pos++
            }
            return v
        }

        fun alignToByte() {
            pos = (pos + 7) and 7L.inv()
        }

        fun bytesLeft(): Long = data.size - (pos ushr 3)
    }
}
