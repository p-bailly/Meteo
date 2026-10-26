package jvmcompat

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

// java.lang (Math, System) et String.format, que le code Android utilise sans import : chaque paquet partagé les
// reprend par un alias (voir Compat.kt dans data/ et ui/).

object JMath {
    const val PI: Double = kotlin.math.PI
    const val E: Double = kotlin.math.E
    fun toRadians(deg: Double): Double = deg / 180.0 * PI
    fun toDegrees(rad: Double): Double = rad * 180.0 / PI
    /** Arrondi à la java : la moitié vers le haut (Math.round(-2.5) = -2). */
    fun round(x: Double): Long = if (x.isNaN()) 0L else kotlin.math.floor(x + 0.5).toLong()
    fun round(x: Float): Int = if (x.isNaN()) 0 else kotlin.math.floor(x + 0.5f).toInt()
    fun floor(x: Double): Double = kotlin.math.floor(x)
    fun ceil(x: Double): Double = kotlin.math.ceil(x)
    fun abs(x: Double): Double = kotlin.math.abs(x)
    fun abs(x: Int): Int = kotlin.math.abs(x)
    fun sqrt(x: Double): Double = kotlin.math.sqrt(x)
    fun pow(a: Double, b: Double): Double = a.pow(b)
    fun max(a: Double, b: Double): Double = kotlin.math.max(a, b)
    fun min(a: Double, b: Double): Double = kotlin.math.min(a, b)
    fun max(a: Int, b: Int): Int = kotlin.math.max(a, b)
    fun min(a: Int, b: Int): Int = kotlin.math.min(a, b)
    fun floorDiv(a: Long, b: Long): Long = a.floorDiv(b)
    fun floorMod(a: Long, b: Long): Long = a.mod(b)
    fun floorDiv(a: Int, b: Int): Int = a.floorDiv(b)
    fun floorMod(a: Int, b: Int): Int = a.mod(b)
    fun sin(x: Double): Double = kotlin.math.sin(x)
    fun cos(x: Double): Double = kotlin.math.cos(x)
    fun atan2(y: Double, x: Double): Double = kotlin.math.atan2(y, x)
    fun hypot(x: Double, y: Double): Double = kotlin.math.hypot(x, y)
    fun log10(x: Double): Double = kotlin.math.log10(x)
}


object JSystem {
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}

object JInteger {
    fun toHexString(i: Int): String = i.toUInt().toString(16)
    fun parseInt(s: String): Int = s.toInt()
    const val MAX_VALUE: Int = Int.MAX_VALUE
    const val MIN_VALUE: Int = Int.MIN_VALUE
}

private fun jsShortest(x: Double): String = js("String(x)")

/**
 * [x] écrit avec [precision] décimales, arrondi à la moitié supérieure sur son écriture décimale la plus courte (comme
 * java.util.Formatter : 1,005 → 1,01).
 */
fun fixed(x: Double, precision: Int): String {
    if (x.isNaN()) return "NaN"
    if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
    val negative = x < 0 || (x == 0.0 && 1.0 / x < 0)
    var s = jsShortest(abs(x))
    var exp = 0
    val e = s.indexOfFirst { it == 'e' || it == 'E' }
    if (e >= 0) {
        exp = s.substring(e + 1).toInt()
        s = s.substring(0, e)
    }
    val dot = s.indexOf('.')
    var digits = if (dot < 0) s else s.removeRange(dot, dot + 1)
    var point = (if (dot < 0) s.length else dot) + exp
    // Chiffres significatifs, avec la virgule après [point] chiffres.
    if (point < 0) {
        digits = "0".repeat(-point) + digits
        point = 0
    }
    if (digits.length < point) digits += "0".repeat(point - digits.length)
    val keep = point + precision
    var result: CharArray
    if (digits.length > keep) {
        val roundUp = digits[keep] >= '5'
        result = digits.substring(0, keep).toCharArray()
        if (roundUp) {
            var k = keep - 1
            while (k >= 0) {
                if (result[k] == '9') {
                    result[k] = '0'
                    k--
                } else {
                    result[k] = result[k] + 1
                    break
                }
            }
            if (k < 0) {
                result = charArrayOf('1') + result
                point++
            }
        }
    } else {
        result = (digits + "0".repeat(keep - digits.length)).toCharArray()
    }
    val str = result.concatToString()
    val intPart = str.substring(0, point).ifEmpty { "0" }.trimStart('0').ifEmpty { "0" }
    val frac = str.substring(point)
    val body = if (precision > 0) "$intPart.$frac" else intPart
    val isZero = body.all { it == '0' || it == '.' }
    return if (negative && !isZero) "-$body" else body
}

/** printf à la Java : %[flags][largeur][.précision](d|s|f|x|X|c|%|n). */
fun jformat(locale: Locale?, format: String, args: Array<out Any?>): String {
    val comma = (locale ?: Locale.getDefault()).decimalComma
    val out = StringBuilder()
    var argIndex = 0
    var i = 0
    while (i < format.length) {
        val c = format[i]
        if (c != '%') {
            out.append(c)
            i++
            continue
        }
        var j = i + 1
        val flagsStart = j
        while (j < format.length && format[j] in "-+0 ,#(") j++
        val flags = format.substring(flagsStart, j)
        val widthStart = j
        while (j < format.length && format[j].isDigit()) j++
        val width = format.substring(widthStart, j).toIntOrNull() ?: 0
        var precision = -1
        if (j < format.length && format[j] == '.') {
            j++
            val ps = j
            while (j < format.length && format[j].isDigit()) j++
            precision = format.substring(ps, j).toInt()
        }
        val conv = format[j]
        j++
        val text: String = when (conv) {
            '%' -> "%"
            'n' -> "\n"
            'd' -> {
                val v = (args[argIndex++] as Number).toLong()
                var t = abs(v).toString()
                if (',' in flags) t = group(t, if (comma) ' ' else ',')
                sign(v < 0, flags) + t
            }
            'x', 'X' -> {
                val v = args[argIndex++]
                val t = when (v) {
                    is Int -> v.toUInt().toString(16)
                    is Long -> v.toULong().toString(16)
                    else -> (v as Number).toLong().toString(16)
                }
                if (conv == 'X') t.uppercase() else t
            }
            'f' -> {
                val v = (args[argIndex++] as Number).toDouble()
                var t = fixed(abs(v), if (precision < 0) 6 else precision)
                if (',' in flags) {
                    val ip = t.substringBefore('.')
                    t = group(ip, if (comma) ' ' else ',') + t.removePrefix(ip)
                }
                if (comma) t = t.replace('.', ',')
                val negative = v < 0 && fixed(abs(v), if (precision < 0) 6 else precision).any { it in '1'..'9' }
                sign(negative, flags) + t
            }
            's', 'S' -> {
                val t = args[argIndex++].toString().let { if (precision >= 0) it.take(precision) else it }
                if (conv == 'S') t.uppercase() else t
            }
            'c' -> args[argIndex++].toString()
            else -> throw IllegalArgumentException("Format non pris en charge : %$conv")
        }
        val padded = when {
            text.length >= width -> text
            '-' in flags -> text.padEnd(width)
            '0' in flags && conv in "dfxX" -> {
                val signed = text.startsWith('-') || text.startsWith('+')
                if (signed) text[0] + text.substring(1).padStart(width - 1, '0') else text.padStart(width, '0')
            }
            else -> text.padStart(width)
        }
        out.append(padded)
        i = j
    }
    return out.toString()
}

private fun sign(negative: Boolean, flags: String): String = when {
    negative -> "-"
    '+' in flags -> "+"
    ' ' in flags -> " "
    else -> ""
}

private fun group(digits: String, sep: Char): String {
    val sb = StringBuilder()
    digits.forEachIndexed { k, ch ->
        if (k > 0 && (digits.length - k) % 3 == 0) sb.append(sep)
        sb.append(ch)
    }
    return sb.toString()
}

/** sortedMapOf() de la JVM : une table parcourue dans l'ordre de ses clés. */
class SortedMutableMap<K : Comparable<K>, V> : AbstractMutableMap<K, V>() {
    private val map = HashMap<K, V>()
    override fun put(key: K, value: V): V? = map.put(key, value)
    override fun remove(key: K): V? = map.remove(key)
    override fun get(key: K): V? = map[key]
    override fun containsKey(key: K): Boolean = map.containsKey(key)
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = map.entries.sortedBy { it.key }.toMutableSet()
}
