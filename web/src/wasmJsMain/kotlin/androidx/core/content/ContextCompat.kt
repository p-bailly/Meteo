package androidx.core.content

import android.content.Context
import android.content.pm.PackageManager

object ContextCompat {
    /** Dans le navigateur, c'est la demande de position elle-même qui fait apparaître l'autorisation. */
    fun checkSelfPermission(context: Context, permission: String): Int = PackageManager.PERMISSION_GRANTED
}
