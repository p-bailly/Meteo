package android.graphics

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Une image en pixels ARGB, comme android.graphics.Bitmap ; encodée et décodée (PNG, JPEG) par Skia, le moteur de
 * dessin de Compose dans le navigateur.
 */
class Bitmap internal constructor(val width: Int, val height: Int, internal val argb: IntArray) {
    enum class Config { ARGB_8888 }
    enum class CompressFormat { PNG, JPEG, WEBP }

    /** L'image encodée en PNG, pour la carte (gardée : une image ne change pas). */
    internal val png: ByteArray by lazy { encode(EncodedImageFormat.PNG, 100) }

    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        for (row in 0 until height) {
            argb.copyInto(pixels, offset + row * stride, (y + row) * this.width + x, (y + row) * this.width + x + width)
        }
    }

    fun getPixel(x: Int, y: Int): Int = argb[y * width + x]

    fun compress(format: CompressFormat, quality: Int, stream: java.io.OutputStream): Boolean {
        val bytes = if (format == CompressFormat.PNG) png else encode(EncodedImageFormat.JPEG, quality)
        stream.write(bytes)
        return true
    }

    fun recycle() = Unit

    internal fun skiaImage(): Image = Image.makeRaster(INFO(width, height), toBgra(argb), width * 4)

    private fun encode(format: EncodedImageFormat, quality: Int): ByteArray =
        skiaImage().encodeToData(format, quality)?.bytes ?: throw IllegalStateException("Image impossible à encoder")

    companion object {
        internal fun INFO(width: Int, height: Int) = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)

        fun createBitmap(colors: IntArray, width: Int, height: Int, config: Config): Bitmap =
            Bitmap(width, height, colors.copyOf(width * height))

        fun createBitmap(width: Int, height: Int, config: Config): Bitmap = Bitmap(width, height, IntArray(width * height))

        /** ARGB (entiers) → octets B, G, R, A : l'ordre de BGRA_8888. */
        internal fun toBgra(argb: IntArray): ByteArray {
            val out = ByteArray(argb.size * 4)
            for (i in argb.indices) {
                val c = argb[i]
                out[4 * i] = c.toByte()
                out[4 * i + 1] = (c shr 8).toByte()
                out[4 * i + 2] = (c shr 16).toByte()
                out[4 * i + 3] = (c ushr 24).toByte()
            }
            return out
        }

        internal fun fromBgra(bytes: ByteArray, count: Int): IntArray = IntArray(count) { i ->
            (bytes[4 * i].toInt() and 0xFF) or ((bytes[4 * i + 1].toInt() and 0xFF) shl 8) or
                ((bytes[4 * i + 2].toInt() and 0xFF) shl 16) or ((bytes[4 * i + 3].toInt() and 0xFF) shl 24)
        }
    }
}

object BitmapFactory {
    fun decodeFile(path: String): Bitmap? = runCatching { decodeByteArray(java.io.File(path).readBytes()) }.getOrNull()

    fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap? = decodeByteArray(data.copyOfRange(offset, offset + length))

    private fun decodeByteArray(data: ByteArray): Bitmap? = runCatching {
        val image = Image.makeFromEncoded(data)
        val w = image.width
        val h = image.height
        val skBitmap = org.jetbrains.skia.Bitmap()
        skBitmap.allocPixels(Bitmap.INFO(w, h))
        image.readPixels(skBitmap, 0, 0)
        val bytes = skBitmap.readPixels(Bitmap.INFO(w, h), w * 4, 0, 0) ?: return@runCatching null
        Bitmap(w, h, Bitmap.fromBgra(bytes, w * h))
    }.getOrNull()
}
