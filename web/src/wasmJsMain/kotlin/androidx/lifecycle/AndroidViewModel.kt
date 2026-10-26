package androidx.lifecycle

import android.app.Application

/** Un ViewModel qui connaît l'application, comme sur Android. */
open class AndroidViewModel(private val application: Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    fun <T : Application> getApplication(): T = application as T
}
