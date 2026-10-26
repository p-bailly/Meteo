package java.util

/** Langue des nombres et des dates : seul compte le séparateur décimal (virgule en français, point sinon). */
class Locale(val language: String, val country: String = "") {
    internal val decimalComma: Boolean get() = language == "fr"

    override fun equals(other: Any?): Boolean = other is Locale && other.language == language && other.country == country
    override fun hashCode(): Int = language.hashCode() * 31 + country.hashCode()

    companion object {
        val ROOT = Locale("")
        val FRENCH = Locale("fr")
        val FRANCE = Locale("fr", "FR")
        val US = Locale("en", "US")
        val ENGLISH = Locale("en")

        /** L'appli s'adresse à des francophones : comme sur leur téléphone, la virgule décimale par défaut. */
        fun getDefault(): Locale = FRANCE
    }
}
