package android.app

import android.content.Context

/** L'application : un seul contexte pour toute la page. */
open class Application : Context() {
    companion object {
        const val MODE_PRIVATE = Context.MODE_PRIVATE
    }
}

/** L'application de la page web. */
object WebApplication : Application()
