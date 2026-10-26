package android.graphics

/** Le pinceau d'Android, réduit à l'écriture d'un texte centré (les noms des sommets du dessin). */
class Paint(flags: Int = 0) {
    var color: Int = 0xFF000000.toInt()
    var textSize: Float = 12f
    var typeface: Typeface? = null
    var textAlign: Align = Align.LEFT

    enum class Align { LEFT, CENTER, RIGHT }

    companion object {
        const val ANTI_ALIAS_FLAG = 1
    }
}

class Typeface private constructor(val family: String) {
    companion object {
        const val NORMAL = 0
        fun create(family: String, style: Int): Typeface = Typeface(family)
    }
}
