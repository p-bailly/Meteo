package android.content

import android.net.Uri

/** Une intention d'Android, réduite à « ouvrir ce lien » ([Context.startActivity] l'ouvre dans un nouvel onglet). */
class Intent(val action: String, val data: Uri? = null) {
    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
    }
}
