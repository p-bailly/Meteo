package java.net

import java.io.IOException

class UnknownHostException(message: String? = null) : IOException(message)
open class ConnectException(message: String? = null) : IOException(message)
class SocketTimeoutException(message: String? = null) : IOException(message)

private fun jsEncodeURIComponent(text: String): String = js("encodeURIComponent(text)")

object URLEncoder {
    /** Comme Java : espace en « + », et « !'()~ » encodés (encodeURIComponent les laisse). */
    fun encode(text: String, charset: String): String = jsEncodeURIComponent(text)
        .replace("%20", "+").replace("!", "%21").replace("'", "%27").replace("(", "%28").replace(")", "%29").replace("~", "%7E")
}
