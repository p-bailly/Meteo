package androidx.activity.compose

import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.window
import org.w3c.dom.events.Event

class ManagedActivityResultLauncher<I, O> internal constructor(
    private val contract: ActivityResultContract<I, O>,
    private val onResult: () -> (O) -> Unit,
) {
    fun launch(input: I) = onResult()(contract.grantedResult(input))
}

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> {
    val current = rememberUpdatedState(onResult)
    return remember(contract) { ManagedActivityResultLauncher(contract) { current.value } }
}

/**
 * Le retour arrière : le bouton ou le geste « précédent » du navigateur. Chaque écran qui l'écoute ajoute une étape à
 * l'historique de la page ; « précédent » la retire et appelle le dernier écran ouvert.
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val entry = BackStack.push { current.value() }
        onDispose { BackStack.remove(entry) }
    }
}

private object BackStack {
    private class Entry(val onBack: () -> Unit)

    private val entries = mutableListOf<Entry>()
    /** Étapes ajoutées à l'historique et pas encore consommées. */
    private var pushed = 0
    private var listening = false

    fun push(onBack: () -> Unit): Any {
        listen()
        val entry = Entry(onBack)
        entries += entry
        if (pushed < entries.size) {
            window.history.pushState(null, "", null)
            pushed++
        }
        return entry
    }

    fun remove(entry: Any) {
        entries.remove(entry)
    }

    private fun listen() {
        if (listening) return
        listening = true
        window.addEventListener("popstate", { _: Event ->
            if (pushed > 0) pushed--
            entries.lastOrNull()?.onBack?.invoke()
        })
    }
}
