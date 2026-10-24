package at.nikolai.diagnose

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Startseite: Status, Knöpfe und Liste der bisherigen Auswertungen. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var setup: LinearLayout
    private lateinit var vehicleButton: android.widget.Button
    private lateinit var programButton: android.widget.Button
    private val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = Ui.column(this)
        root.addView(Ui.title(this, "Diagnose"))
        vehicleButton = Ui.button(this, "") { chooseVehicle() }
        root.addView(vehicleButton)
        status = Ui.text(this, "", 15f)
        root.addView(status)
        setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(setup)
        programButton = Ui.button(this, "") { startActivity(Intent(this, ProgramActivity::class.java)) }
        root.addView(programButton)
        root.addView(Ui.button(this, "▶ Komplett-Check & Messfahrt") { startActivity(Intent(this, TemplatesActivity::class.java)) })
        root.addView(Ui.button(this, "Jetzt nach neuen Berichten suchen") {
            ScanWorker.runNow(this)
            Toast.makeText(this, "Suche läuft, Ergebnis kommt als Benachrichtigung.", Toast.LENGTH_LONG).show()
        })
        root.addView(Ui.button(this, "Datei auswählen und auswerten") { pickFile() })
        root.addView(Ui.button(this, "Einstellungen") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(Ui.title(this, "Auswertungen").apply { setPadding(0, Ui.dp(context, 20), 0, 0) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply { addView(root) })
        ScanWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val prefs = Prefs(this)
        vehicleButton.text = "🚗 ${prefs.vehicle}  (antippen zum Wechseln)"
        programButton.text = ProgramSession.active(this)?.let { "🛡️ Laufende Prüfung fortsetzen: ${it.program?.name}" }
            ?: "🛡️ Garantie-Check / Gebrauchtwagen-Check"
        val lines = mutableListOf<String>()
        lines += if (prefs.apiKey.isBlank()) "Ohne API-Schlüssel: Berichte gehen über Google Drive an das Claude-Projekt."
        else "Auswertung direkt hier am Tablet."
        setup.removeAllViews()
        if (!Storage.hasAccess(this)) {
            setup.addView(Ui.button(this, "⚠️ Schritt 1: Dateizugriff erlauben") { Storage.request(this) })
        }
        if (!Access.isEnabled(this)) {
            setup.addView(Ui.button(this, "⚠️ Schritt 2: Bedienungshilfe „Diagnose“ einschalten") { explainAccess() })
        } else {
            lines += "🔍-Knopf ist über der Launch-App aktiv" + if (prefs.autoCapture) ", Fehlercodes werden automatisch erfasst." else "."
        }
        if (prefs.watchEnabled) {
            lines += "Überwacht: ${prefs.watchFolders}"
            lines += if (prefs.lastScan > 0) "Zuletzt geprüft: ${fmt.format(Date(prefs.lastScan))}" else "Noch nicht geprüft."
        } else {
            lines += "Automatische Überwachung ist aus."
        }
        status.text = lines.joinToString("\n")

        list.removeAllViews()
        val results = ReportStore(this).list()
        if (results.isEmpty()) {
            list.addView(Ui.text(this, "Noch keine Auswertungen. Teile einen Bericht aus der Launch-App mit „Diagnose auswerten“.", 15f))
        }
        for (r in results) {
            val row = TextView(this).apply {
                text = "${r.title}\n${fmt.format(Date(r.createdAt))} · ${r.fileName}"
                textSize = 16f
                val p = Ui.dp(context, 12)
                setPadding(p, p, p, p)
                setBackgroundColor(0x11000000)
                setOnClickListener {
                    startActivity(Intent(context, ResultActivity::class.java).putExtra(ResultActivity.EXTRA_ID, r.id))
                }
            }
            val bar = View(this).apply { setBackgroundColor(Ui.ampelColor(if (r.error != null) null else r.ampel)) }
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(bar, LinearLayout.LayoutParams(Ui.dp(context, 8), LinearLayout.LayoutParams.MATCH_PARENT))
                addView(row, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(context, 8) }
            }
            list.addView(wrap)
        }
    }

    private fun chooseVehicle() {
        val prefs = Prefs(this)
        val all = prefs.vehicles
        val items = (all + "➕ Neues Fahrzeug …").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Welches Fahrzeug?")
            .setItems(items) { _, which ->
                if (which < all.size) {
                    prefs.vehicle = all[which]
                    refresh()
                } else {
                    val input = android.widget.EditText(this).apply { hint = "z. B. Toyota Corolla 2021 Hybrid" }
                    AlertDialog.Builder(this)
                        .setTitle("Neues Fahrzeug")
                        .setView(input)
                        .setPositiveButton("Hinzufügen") { _, _ ->
                            val v = input.text.toString().trim()
                            if (v.isNotEmpty()) {
                                prefs.vehicles = (all + v).distinct()
                                prefs.vehicle = v
                                refresh()
                            }
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            }
            .show()
    }

    private fun explainAccess() {
        AlertDialog.Builder(this)
            .setTitle("Bedienungshilfe einschalten")
            .setMessage(
                "In der folgenden Liste „Diagnose“ antippen und einschalten.\n\n" +
                    "Android warnt, dass die App den Bildschirm sehen und bedienen kann. Das braucht sie für den 🔍-Knopf " +
                    "und die Messfahrt-Vorlagen. Sie liest nur die Launch-App und tippt nie auf Löschen, Codierung, " +
                    "Anpassung oder Stellgliedtests."
            )
            .setPositiveButton("Weiter") { _, _ -> Access.openSettings(this) }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/*", "image/*"))
        }
        startActivityForResult(intent, PICK)
    }

    @Deprecated("Activity-API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK && resultCode == RESULT_OK && uri != null) analyzeUri(uri)
    }

    private fun analyzeUri(uri: Uri) {
        val name = displayName(uri) ?: "bericht"
        if (!Analysis.isSupported(name)) {
            AlertDialog.Builder(this)
                .setMessage("„$name“ kann Claude nicht lesen. Geht: PDF, CSV, TXT und Bilder. Die .dat- und .x431-Dateien sind Launch-eigene Formate.")
                .setPositiveButton("OK", null).show()
            return
        }
        Toast.makeText(this, "Wird ausgewertet, das dauert etwa eine Minute …", Toast.LENGTH_LONG).show()
        Thread {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                val r = Analysis.run(applicationContext, name, bytes)
                runOnUiThread {
                    refresh()
                    startActivity(Intent(this, ResultActivity::class.java).putExtra(ResultActivity.EXTRA_ID, r.id))
                }
            }
        }.start()
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment

    companion object {
        private const val PICK = 7
    }
}
