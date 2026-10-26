package android.net

class Uri private constructor(private val text: String) {
    override fun toString(): String = text

    companion object {
        fun parse(text: String): Uri = Uri(text)
    }
}
