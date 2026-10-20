package io.github.pbailly.meteo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Au lancement qui suit un plantage : ce qui s'est passé, à copier pour le signaler. */
@Composable
fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("L'appli s'est fermée la dernière fois") },
        text = {
            Column {
                Text("Copie ce rapport et envoie-le pour qu'on corrige le problème.")
                Spacer(Modifier.height(10.dp))
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    MonoText(report, size = 9.sp, maxLines = Int.MAX_VALUE)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Plantage Météo", report))
                    Toast.makeText(context, "Rapport copié", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
            ) { Text("Copier") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}
