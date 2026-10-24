package at.nikolai.diagnose

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.ScrollView

/** Zeigt eine einzelne Auswertung. */
class ResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ReportStore(this)
        val r = intent.getStringExtra(EXTRA_ID)?.let { store.get(it) }
        if (r == null) {
            finish()
            return
        }
        val root = Ui.column(this)
        root.addView(Ui.title(this, r.title).apply {
            if (r.error == null && r.ampel != null) {
                setTextColor(Color.WHITE)
                setBackgroundColor(Ui.ampelColor(r.ampel))
                val p = Ui.dp(context, 10)
                setPadding(p, p, p, p)
            }
        })
        root.addView(Ui.text(this, "Datei: ${r.fileName}", 14f))
        val body = r.error ?: r.text.lineSequence()
            .filterNot { it.startsWith("TITEL:") }
            .joinToString("\n").trim()
        root.addView(Ui.text(this, body).apply { setPadding(0, Ui.dp(context, 12), 0, 0) })
        root.addView(Ui.button(this, "Teilen (z. B. an Claude-App oder Gmail)") {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Diagnose ${r.title}")
                putExtra(Intent.EXTRA_TEXT, r.error ?: r.text)
            }
            startActivity(Intent.createChooser(send, "Auswertung teilen"))
        })
        root.addView(Ui.button(this, "Löschen") {
            AlertDialog.Builder(this).setMessage("Diese Auswertung löschen?")
                .setPositiveButton("Löschen") { _, _ -> store.delete(r.id); finish() }
                .setNegativeButton("Abbrechen", null).show()
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
