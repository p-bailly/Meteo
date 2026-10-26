package java.text

private fun jsNormalize(text: String, form: String): String = js("text.normalize(form)")

object Normalizer {
    enum class Form { NFD, NFC, NFKD, NFKC }

    fun normalize(text: CharSequence, form: Form): String = jsNormalize(text.toString(), form.name)
}
