package io.github.pbailly.meteo.share

import android.content.Context

/** Pas de partage avec Nimbus dans le navigateur. */
class NimbusProvider {
    companion object {
        fun notifyChange(context: Context) = Unit
    }
}
