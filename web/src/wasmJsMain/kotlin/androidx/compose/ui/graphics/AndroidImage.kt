package androidx.compose.ui.graphics

/** L'image d'Android ([android.graphics.Bitmap]) pour Compose. */
fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = skiaImage().toComposeImageBitmap()
