package io.github.pbailly.meteo.data.radar

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Image GeoTIFF à une bande, lue en valeurs flottantes (ligne 0 au nord).
 * [originX]/[originY] : coin haut-gauche du pixel (0, 0) ; [scaleX]/[scaleY] : taille d'un pixel (degrés).
 */
class GeoTiffImage(
    val width: Int,
    val height: Int,
    val values: FloatArray,
    val originX: Double?,
    val originY: Double?,
    val scaleX: Double?,
    val scaleY: Double?,
    val noData: Double?,
)

/**
 * Lecteur GeoTIFF minimal, suffisant pour les grilles des services WCS de Météo-France :
 * bandes en lignes ou en tuiles ; compressions aucune, LZW, Deflate, PackBits ;
 * prédicteurs horizontal et flottant ; entiers 8/16/32 bits et flottants 32/64 bits.
 */
object GeoTiff {
    private const val IMAGE_WIDTH = 256
    private const val IMAGE_LENGTH = 257
    private const val BITS_PER_SAMPLE = 258
    private const val COMPRESSION = 259
    private const val STRIP_OFFSETS = 273
    private const val SAMPLES_PER_PIXEL = 277
    private const val ROWS_PER_STRIP = 278
    private const val STRIP_BYTE_COUNTS = 279
    private const val PREDICTOR = 317
    private const val TILE_WIDTH = 322
    private const val TILE_LENGTH = 323
    private const val TILE_OFFSETS = 324
    private const val TILE_BYTE_COUNTS = 325
    private const val SAMPLE_FORMAT = 339
    private const val MODEL_PIXEL_SCALE = 33550
    private const val MODEL_TIEPOINT = 33922
    private const val GEO_KEY_DIRECTORY = 34735
    private const val GDAL_NODATA = 42113

    fun read(bytes: ByteArray): GeoTiffImage {
        val order = when {
            bytes.size >= 8 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes.size >= 8 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> throw IllegalArgumentException("Ce n'est pas un fichier TIFF")
        }
        val buf = ByteBuffer.wrap(bytes).order(order)
        val magic = buf.getShort(2).toInt() and 0xFFFF
        require(magic == 42) { "Format TIFF non pris en charge ($magic)" }
        val tags = readIfd(buf, buf.getInt(4).toLong() and 0xFFFFFFFFL)

        fun long(tag: Int, default: Long? = null): Long =
            tags[tag]?.longs?.firstOrNull() ?: default ?: error("Balise TIFF manquante : $tag")

        val width = long(IMAGE_WIDTH).toInt()
        val height = long(IMAGE_LENGTH).toInt()
        val bits = long(BITS_PER_SAMPLE, 8).toInt()
        val samples = long(SAMPLES_PER_PIXEL, 1).toInt()
        val compression = long(COMPRESSION, 1).toInt()
        val predictor = long(PREDICTOR, 1).toInt()
        val format = long(SAMPLE_FORMAT, 1).toInt()
        require(bits % 8 == 0) { "Profondeur TIFF non prise en charge : $bits bits" }
        val bytesPerSample = bits / 8
        val pixelBytes = bytesPerSample * samples

        val tiled = TILE_OFFSETS in tags
        val chunkW = if (tiled) long(TILE_WIDTH).toInt() else width
        val chunkH = if (tiled) long(TILE_LENGTH).toInt() else long(ROWS_PER_STRIP, height.toLong()).toInt().coerceAtMost(height)
        val offsets = (if (tiled) tags[TILE_OFFSETS] else tags[STRIP_OFFSETS])?.longs ?: error("Données TIFF absentes")
        val counts = (if (tiled) tags[TILE_BYTE_COUNTS] else tags[STRIP_BYTE_COUNTS])?.longs ?: error("Données TIFF absentes")
        val chunksAcross = if (tiled) (width + chunkW - 1) / chunkW else 1

        val values = FloatArray(width * height) { Float.NaN }
        for (index in offsets.indices) {
            val raw = bytes.copyOfRange(offsets[index].toInt(), (offsets[index] + counts[index]).toInt())
            val expected = chunkW * chunkH * pixelBytes
            val data = decompress(raw, compression, expected)
            when (predictor) {
                1 -> Unit
                2 -> undoHorizontal(data, chunkW, chunkH, samples, bytesPerSample, order)
                3 -> undoFloatingPoint(data, chunkW, chunkH, samples, bytesPerSample)
                else -> error("Prédicteur TIFF non pris en charge : $predictor")
            }
            // Après le prédicteur flottant, les octets sont en ordre grand-boutiste.
            val chunk = ByteBuffer.wrap(data).order(if (predictor == 3) ByteOrder.BIG_ENDIAN else order)
            val x0 = if (tiled) (index % chunksAcross) * chunkW else 0
            val y0 = if (tiled) (index / chunksAcross) * chunkH else index * chunkH
            for (cy in 0 until chunkH) {
                val y = y0 + cy
                if (y >= height) break
                for (cx in 0 until chunkW) {
                    val x = x0 + cx
                    if (x >= width) break
                    val pos = (cy * chunkW + cx) * pixelBytes
                    if (pos + bytesPerSample > data.size) continue
                    values[y * width + x] = sample(chunk, pos, bytesPerSample, format)
                }
            }
        }

        val scale = tags[MODEL_PIXEL_SCALE]?.doubles
        val tie = tags[MODEL_TIEPOINT]?.doubles
        // GeoKey 1025 = 2 : le point de calage est le centre du pixel, et non son coin.
        val pixelIsPoint = tags[GEO_KEY_DIRECTORY]?.longs?.let { keys ->
            (4 until keys.size step 4).any { keys[it] == 1025L && keys.getOrNull(it + 3) == 2L }
        } ?: false
        val shift = if (pixelIsPoint) 0.5 else 0.0
        val noData = tags[GDAL_NODATA]?.text?.trim()?.trimEnd('\u0000')?.toDoubleOrNull()
        return GeoTiffImage(
            width, height, values,
            originX = if (tie != null && scale != null) tie[3] - (tie[0] + shift) * scale[0] else null,
            originY = if (tie != null && scale != null) tie[4] + (tie[1] + shift) * scale[1] else null,
            scaleX = scale?.getOrNull(0),
            scaleY = scale?.getOrNull(1),
            noData = noData,
        )
    }

    private class Tag(val longs: LongArray?, val doubles: DoubleArray?, val text: String?)

    private fun readIfd(buf: ByteBuffer, offset: Long): Map<Int, Tag> {
        val count = buf.getShort(offset.toInt()).toInt() and 0xFFFF
        val tags = HashMap<Int, Tag>()
        for (i in 0 until count) {
            val entry = offset.toInt() + 2 + i * 12
            val tag = buf.getShort(entry).toInt() and 0xFFFF
            val type = buf.getShort(entry + 2).toInt() and 0xFFFF
            val n = buf.getInt(entry + 4).toLong() and 0xFFFFFFFFL
            val size = when (type) {
                1, 2, 6, 7 -> 1
                3, 8 -> 2
                4, 9, 11 -> 4
                5, 10, 12 -> 8
                else -> continue
            }
            val total = size * n
            val at = if (total <= 4) entry + 8 else (buf.getInt(entry + 8).toLong() and 0xFFFFFFFFL).toInt()
            val longs = LongArray(n.toInt())
            val doubles = DoubleArray(n.toInt())
            for (k in 0 until n.toInt()) {
                val p = at + k * size
                val value: Double = when (type) {
                    1, 2, 7 -> (buf.get(p).toInt() and 0xFF).toDouble()
                    6 -> buf.get(p).toDouble()
                    3 -> (buf.getShort(p).toInt() and 0xFFFF).toDouble()
                    8 -> buf.getShort(p).toDouble()
                    4 -> (buf.getInt(p).toLong() and 0xFFFFFFFFL).toDouble()
                    9 -> buf.getInt(p).toDouble()
                    11 -> buf.getFloat(p).toDouble()
                    12 -> buf.getDouble(p)
                    5 -> (buf.getInt(p).toLong() and 0xFFFFFFFFL).toDouble() / (buf.getInt(p + 4).toLong() and 0xFFFFFFFFL)
                    10 -> buf.getInt(p).toDouble() / buf.getInt(p + 4)
                    else -> 0.0
                }
                doubles[k] = value
                longs[k] = value.toLong()
            }
            val text = if (type == 2) String(ByteArray(n.toInt()) { buf.get(at + it) }, Charsets.US_ASCII) else null
            tags[tag] = Tag(longs, doubles, text)
        }
        return tags
    }

    private fun decompress(raw: ByteArray, compression: Int, expected: Int): ByteArray = when (compression) {
        1 -> raw
        5 -> lzw(raw, expected)
        8, 32946 -> Inflater().run {
            setInput(raw)
            val out = ByteArray(expected)
            var n = 0
            while (n < expected && !finished()) {
                val read = inflate(out, n, expected - n)
                if (read == 0 && (needsInput() || needsDictionary())) break
                n += read
            }
            end()
            out
        }
        32773 -> packBits(raw, expected)
        else -> error("Compression TIFF non prise en charge : $compression")
    }

    /** LZW façon TIFF : codes de 9 à 12 bits, poids fort en premier, changement de taille anticipé. */
    private fun lzw(input: ByteArray, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(expected)
        val table = arrayOfNulls<ByteArray>(4096)
        for (i in 0 until 256) table[i] = byteArrayOf(i.toByte())
        var next = 258
        var width = 9
        var bitPos = 0L
        var previous: ByteArray? = null
        val totalBits = input.size * 8L
        while (bitPos + width <= totalBits) {
            var code = 0
            for (b in 0 until width) {
                val p = bitPos + b
                val bit = (input[(p / 8).toInt()].toInt() shr (7 - (p % 8).toInt())) and 1
                code = (code shl 1) or bit
            }
            bitPos += width
            when (code) {
                256 -> {
                    for (i in 258 until 4096) table[i] = null
                    next = 258
                    width = 9
                    previous = null
                    continue
                }
                257 -> break
            }
            val entry = table.getOrNull(code)
                ?: previous?.let { it + it[0] }
                ?: error("Flux LZW invalide")
            out.write(entry)
            if (previous != null && next < 4096) {
                table[next++] = previous + entry[0]
            }
            previous = entry
            width = when {
                next >= 2047 -> 12
                next >= 1023 -> 11
                next >= 511 -> 10
                else -> 9
            }
        }
        return out.toByteArray()
    }

    private fun packBits(input: ByteArray, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(expected)
        var i = 0
        while (i < input.size) {
            val n = input[i++].toInt()
            when {
                n in 0..127 -> { out.write(input, i, n + 1); i += n + 1 }
                n in -127..-1 -> { val b = input[i++].toInt(); repeat(1 - n) { out.write(b) } }
                else -> Unit
            }
        }
        return out.toByteArray()
    }

    private fun undoHorizontal(data: ByteArray, w: Int, h: Int, samples: Int, bytesPerSample: Int, order: ByteOrder) {
        val buf = ByteBuffer.wrap(data).order(order)
        val rowBytes = w * samples * bytesPerSample
        for (y in 0 until h) {
            val row = y * rowBytes
            if (row + rowBytes > data.size) break
            for (i in samples until w * samples) {
                val p = row + i * bytesPerSample
                val q = p - samples * bytesPerSample
                when (bytesPerSample) {
                    1 -> data[p] = (data[p] + data[q]).toByte()
                    2 -> buf.putShort(p, (buf.getShort(p) + buf.getShort(q)).toShort())
                    4 -> buf.putInt(p, buf.getInt(p) + buf.getInt(q))
                    8 -> buf.putLong(p, buf.getLong(p) + buf.getLong(q))
                }
            }
        }
    }

    /** Prédicteur 3 : différences sur les octets, puis octets regroupés par poids (poids fort d'abord). */
    private fun undoFloatingPoint(data: ByteArray, w: Int, h: Int, samples: Int, bytesPerSample: Int) {
        val rowBytes = w * samples * bytesPerSample
        val tmp = ByteArray(rowBytes)
        for (y in 0 until h) {
            val row = y * rowBytes
            if (row + rowBytes > data.size) break
            for (i in samples until rowBytes) data[row + i] = (data[row + i] + data[row + i - samples]).toByte()
            System.arraycopy(data, row, tmp, 0, rowBytes)
            val count = w * samples
            for (s in 0 until count) {
                for (b in 0 until bytesPerSample) data[row + s * bytesPerSample + b] = tmp[b * count + s]
            }
        }
    }

    private fun sample(buf: ByteBuffer, pos: Int, bytes: Int, format: Int): Float = when (format) {
        3 -> if (bytes == 8) buf.getDouble(pos).toFloat() else buf.getFloat(pos)
        2 -> when (bytes) {
            1 -> buf.get(pos).toFloat()
            2 -> buf.getShort(pos).toFloat()
            else -> buf.getInt(pos).toFloat()
        }
        else -> when (bytes) {
            1 -> (buf.get(pos).toInt() and 0xFF).toFloat()
            2 -> (buf.getShort(pos).toInt() and 0xFFFF).toFloat()
            else -> (buf.getInt(pos).toLong() and 0xFFFFFFFFL).toFloat()
        }
    }
}
