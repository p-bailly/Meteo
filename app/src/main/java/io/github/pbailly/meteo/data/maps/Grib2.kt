package io.github.pbailly.meteo.data.maps

import io.github.pbailly.meteo.data.radar.GeoBounds
import io.github.pbailly.meteo.data.radar.GeoTiffImage
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

/**
 * Lecteur GRIB2 minimal, suffisant pour les champs du modèle ICON-EU publiés par le DWD : premier message du fichier,
 * grille régulière en latitude/longitude (gabarit 3.0), sans masque de points (section 6), valeurs en compression
 * simple (5.0) ou CCSDS (5.42, voir [Aec]).
 */
object Grib2 {
    /**
     * Lit la zone [bounds] (avec une case de marge) du champ, sous forme d'image ligne 0 au nord, en appliquant
     * [convert] aux valeurs (par exemple kelvins → °C). Le décodage s'arrête à la dernière ligne utile.
     */
    fun read(bytes: ByteArray, bounds: GeoBounds, convert: (Double) -> Double = { it }): GeoTiffImage {
        val buf = ByteBuffer.wrap(bytes)
        require(bytes.size > 16 && String(bytes, 0, 4, Charsets.US_ASCII) == "GRIB") { "Ce n'est pas un fichier GRIB" }
        require(bytes[7].toInt() == 2) { "Seul GRIB2 est pris en charge" }
        val sections = HashMap<Int, Int>()
        var p = 16
        while (p + 5 <= bytes.size && String(bytes, p, 4, Charsets.US_ASCII) != "7777") {
            val length = buf.getInt(p)
            require(length > 5) { "Section GRIB invalide" }
            sections.putIfAbsent(bytes[p + 4].toInt(), p)
            p += length
        }
        val s3 = sections[3] ?: error("Grille GRIB absente")
        val s5 = sections[5] ?: error("Compression GRIB absente")
        val s7 = sections[7] ?: error("Données GRIB absentes")
        sections[6]?.let { require((bytes[it + 5].toInt() and 0xFF) == 255) { "Masque de points GRIB non pris en charge" } }

        // Grille : octets 31 à 72 de la section 3 (gabarit 3.0).
        require(buf.getShort(s3 + 12).toInt() == 0) { "Seules les grilles latitude/longitude régulières sont prises en charge" }
        val ni = buf.getInt(s3 + 30)
        val nj = buf.getInt(s3 + 34)
        fun degrees(at: Int) = signMagnitude(buf.getInt(at).toLong() and 0xFFFFFFFFL, 32) / 1e6
        val la1 = degrees(s3 + 46)
        val lo1 = degrees(s3 + 50).let { if (it > 180) it - 360 else it }
        val di = (buf.getInt(s3 + 63).toLong() and 0xFFFFFFFFL) / 1e6
        val dj = (buf.getInt(s3 + 67).toLong() and 0xFFFFFFFFL) / 1e6
        val scan = bytes[s3 + 71].toInt() and 0xFF
        require((scan and 0xA0) == 0) { "Ordre de balayage GRIB non pris en charge" }
        val northward = (scan and 0x40) != 0

        // Lignes et colonnes de la zone, dans l'ordre du fichier.
        fun rowOf(lat: Double) = if (northward) (lat - la1) / dj else (la1 - lat) / dj
        val rowsInFile = listOf(rowOf(bounds.south), rowOf(bounds.north))
        val r0 = (floor(rowsInFile.min()).toInt() - 1).coerceIn(0, nj - 1)
        val r1 = (ceil(rowsInFile.max()).toInt() + 1).coerceIn(0, nj - 1)
        val c0 = (floor((bounds.west - lo1) / di).toInt() - 1).coerceIn(0, ni - 1)
        val c1 = (ceil((bounds.east - lo1) / di).toInt() + 1).coerceIn(0, ni - 1)
        val count = (r1 + 1) * ni

        // Valeurs : Y = (R + X × 2^E) / 10^D.
        val points = buf.getInt(s5 + 5)
        require(points == ni * nj) { "Nombre de points GRIB inattendu" }
        val template = buf.getShort(s5 + 9).toInt()
        val reference = buf.getFloat(s5 + 11).toDouble()
        val binaryScale = 2.0.pow(signMagnitude((buf.getShort(s5 + 15).toInt() and 0xFFFF).toLong(), 16).toDouble())
        val decimalScale = 10.0.pow(-signMagnitude((buf.getShort(s5 + 17).toInt() and 0xFFFF).toLong(), 16).toDouble())
        val bits = bytes[s5 + 19].toInt() and 0xFF
        val data = s7 + 5
        val raw: IntArray = when {
            bits == 0 -> IntArray(count)
            template == 0 -> unpackSimple(bytes, data, bits, count)
            template == 42 -> Aec.decode(
                bytes, data, bits,
                flags = bytes[s5 + 21].toInt() and 0xFF,
                blockSize = bytes[s5 + 22].toInt() and 0xFF,
                rsi = buf.getShort(s5 + 23).toInt() and 0xFFFF,
                count = count,
            )
            else -> error("Compression GRIB non prise en charge : 5.$template")
        }

        val width = c1 - c0 + 1
        val height = r1 - r0 + 1
        val values = FloatArray(width * height)
        for (y in 0 until height) {
            // Ligne 0 de l'image au nord.
            val fileRow = if (northward) r1 - y else r0 + y
            for (x in 0 until width) {
                val v = (reference + raw[fileRow * ni + c0 + x] * binaryScale) * decimalScale
                values[y * width + x] = convert(v).toFloat()
            }
        }
        val northLat = if (northward) la1 + r1 * dj else la1 - r0 * dj
        return GeoTiffImage(
            width, height, values,
            originX = lo1 + c0 * di - di / 2,
            originY = northLat + dj / 2,
            scaleX = di,
            scaleY = dj,
            noData = null,
        )
    }

    /** Compression simple : [bits] bits par valeur, poids fort en premier. */
    private fun unpackSimple(bytes: ByteArray, offset: Int, bits: Int, count: Int): IntArray {
        val out = IntArray(count)
        var pos = offset * 8L
        for (i in 0 until count) {
            var v = 0
            repeat(bits) {
                v = (v shl 1) or ((bytes[(pos ushr 3).toInt()].toInt() ushr (7 - (pos and 7).toInt())) and 1)
                pos++
            }
            out[i] = v
        }
        return out
    }

    /** Entiers GRIB : le bit de poids fort est le signe, pas un complément à deux. */
    private fun signMagnitude(v: Long, bits: Int): Long {
        val sign = 1L shl (bits - 1)
        return if ((v and sign) != 0L) -(v and (sign - 1)) else v
    }
}

/**
 * Décompression CCSDS 121.0 (« AEC », celle de libaec) des champs GRIB2 en gabarit 5.42 : blocs de [blockSize]
 * valeurs codés en Rice (k bits bas + préfixe unaire), blocs nuls, seconde extension ou valeurs brutes ; avec le
 * prétraitement, chaque intervalle de référence commence par une valeur brute suivie d'écarts à la précédente.
 */
internal object Aec {
    private const val FLAG_SIGNED = 1
    private const val FLAG_RESTRICTED = 16
    private const val FLAG_PREPROCESS = 8
    private const val FLAG_PAD_RSI = 32
    /** « Reste du segment » dans le codage des blocs nuls. */
    private const val ROS = 5

    /** Seconde extension : la valeur m code la paire (β − b, b), avec m = β(β + 1)/2 + b. */
    private val SE_BETA = IntArray(91)
    private val SE_START = IntArray(91)

    init {
        var m = 0
        for (beta in 0 until 13) {
            val start = m
            for (b in 0..beta) {
                if (m <= 90) {
                    SE_BETA[m] = beta
                    SE_START[m] = start
                }
                m++
            }
        }
    }

    fun decode(data: ByteArray, offset: Int, bits: Int, flags: Int, blockSize: Int, rsi: Int, count: Int): IntArray {
        require((flags and FLAG_SIGNED) == 0) { "Valeurs CCSDS signées non prises en charge" }
        require(bits in 1..30 && blockSize > 0 && rsi > 0) { "Paramètres CCSDS invalides" }
        val idLen = when {
            bits > 16 -> 5
            bits > 8 -> 4
            (flags and FLAG_RESTRICTED) != 0 && bits <= 2 -> 1
            (flags and FLAG_RESTRICTED) != 0 && bits <= 4 -> 2
            else -> 3
        }
        val uncompressed = (1 shl idLen) - 1
        val preprocess = (flags and FLAG_PREPROCESS) != 0
        val xmax = (1L shl bits) - 1
        val reader = BitReader(data, offset)
        val out = IntArray(count)
        val buffer = LongArray(rsi * blockSize)
        var written = 0
        while (written < count) {
            // Un intervalle de référence : au plus [rsi] blocs.
            var used = 0
            var blocks = 0
            while (blocks < rsi && written + used < count) {
                val ref = preprocess && used == 0
                val id = reader.read(idLen)
                val start = used
                when (id) {
                    0 -> {
                        val secondExtension = reader.read(1) == 1
                        if (ref) buffer[used++] = reader.read(bits).toLong()
                        if (secondExtension) {
                            var i = if (ref) 1 else 0
                            while (i < blockSize) {
                                val m = reader.fs()
                                require(m <= 90) { "Flux CCSDS invalide" }
                                val second = m - SE_START[m]
                                if ((i and 1) == 0) {
                                    buffer[used++] = (SE_BETA[m] - second).toLong()
                                    i++
                                }
                                buffer[used++] = second.toLong()
                                i++
                            }
                            blocks++
                        } else {
                            var zeroBlocks = reader.fs() + 1
                            if (zeroBlocks == ROS) {
                                val b = used / blockSize
                                zeroBlocks = min(rsi - b, 64 - b % 64)
                            } else if (zeroBlocks > ROS) {
                                zeroBlocks--
                            }
                            val zeros = (zeroBlocks * blockSize - (used - start)).coerceAtMost(buffer.size - used)
                            repeat(zeros) { buffer[used++] = 0 }
                            blocks += zeroBlocks
                        }
                    }
                    uncompressed -> {
                        repeat(blockSize) { buffer[used++] = reader.read(bits).toLong() }
                        blocks++
                    }
                    else -> {
                        val k = id - 1
                        if (ref) buffer[used++] = reader.read(bits).toLong()
                        val first = used
                        repeat(blockSize - (used - start)) { buffer[used++] = reader.fs().toLong() shl k }
                        if (k > 0) for (i in first until used) buffer[i] += reader.read(k).toLong()
                        blocks++
                    }
                }
            }
            // Valeurs de l'intervalle : reconstruites à partir des écarts si prétraitement.
            var last = 0L
            for (i in 0 until used) {
                if (written >= count) break
                val value = if (!preprocess || i == 0) {
                    buffer[i]
                } else {
                    val d = buffer[i]
                    val theta = min(last, xmax - last)
                    when {
                        d <= 2 * theta -> if ((d and 1L) == 0L) last + d / 2 else last - (d + 1) / 2
                        last <= xmax - last -> d
                        else -> xmax - d
                    }
                }
                last = value
                out[written++] = value.toInt()
            }
            if ((flags and FLAG_PAD_RSI) != 0) reader.alignToByte()
        }
        return out
    }

    private class BitReader(private val data: ByteArray, offset: Int) {
        private var pos = offset * 8L

        fun read(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bit() }
            return v
        }

        /** Séquence fondamentale : nombre de zéros avant le prochain 1. */
        fun fs(): Int {
            var n = 0
            while (bit() == 0) n++
            return n
        }

        fun alignToByte() {
            pos = (pos + 7) and 7L.inv()
        }

        private fun bit(): Int {
            val byte = (pos ushr 3).toInt()
            require(byte < data.size) { "Données CCSDS tronquées" }
            val b = (data[byte].toInt() ushr (7 - (pos and 7).toInt())) and 1
            pos++
            return b
        }
    }
}
