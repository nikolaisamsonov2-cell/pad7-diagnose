package at.nikolai.diagnose

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast

/** Nimmt geteilte Dateien aus der Launch-App (oder Galerie) an und wertet sie im Hintergrund aus. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "Keine Datei erhalten.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Inhalte sofort lesen: die Leseberechtigung gilt nur, solange diese Activity läuft.
        val files = uris.mapNotNull { uri ->
            val name = displayName(uri) ?: "bericht"
            contentResolver.openInputStream(uri)?.use { name to it.readBytes() }
        }
        val usable = files.filter { Analysis.isSupported(it.first) }
        val skipped = files.size - usable.size
        val msg = buildString {
            append(if (usable.isEmpty()) "Nichts Lesbares dabei." else "${usable.size} Datei(en) werden ausgewertet. Ergebnis kommt als Benachrichtigung.")
            if (skipped > 0) append(" $skipped übersprungen (.dat/.x431 sind Launch-eigene Formate).")
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        val app = applicationContext
        Thread { usable.forEach { (name, bytes) -> Analysis.run(app, name, bytes) } }.start()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE -> (
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            ).orEmpty()
        else -> emptyList()
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
}
