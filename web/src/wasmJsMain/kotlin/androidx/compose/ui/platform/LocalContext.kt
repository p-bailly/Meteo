package androidx.compose.ui.platform

import android.app.WebApplication
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext = staticCompositionLocalOf<Context> { WebApplication }
