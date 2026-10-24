package at.nikolai.diagnose

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** API-Schlüssel, Standardfahrzeug, überwachte Ordner, Dateizugriff. */
class SettingsActivity : Activity() {
    private lateinit var access: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val root = Ui.column(this)
        root.addView(Ui.title(this, "Einstellungen"))

        root.addView(Ui.text(this, "Anthropic-API-Schlüssel (von console.anthropic.com, beginnt mit sk-ant-)", 15f))
        val key = EditText(this).apply {
            setText(prefs.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-ant-..."
        }
        root.addView(key)

        root.addView(Ui.text(this, "Fahrzeuge (eins pro Zeile). Das aktive wählst du auf der Startseite.", 15f))
        val vehicles = EditText(this).apply {
            setText(prefs.vehicles.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        root.addView(vehicles)

        root.addView(Ui.text(this, "Überwachte Ordner (mehrere mit Komma trennen)", 15f))
        val folders = EditText(this).apply { setText(prefs.watchFolders) }
        root.addView(folders)

        val watch = CheckBox(this).apply {
            text = "Neue Berichte automatisch auswerten (alle 15 Minuten)"
            textSize = 16f
            isChecked = prefs.watchEnabled
        }
        root.addView(watch)

        val auto = CheckBox(this).apply {
            text = "Fehlercode-Bildschirme der Launch-App automatisch erfassen"
            textSize = 16f
            isChecked = prefs.autoCapture
        }
        root.addView(auto)

        root.addView(Ui.text(this, "Übergabeordner ohne API-Schlüssel (wird von DriveSync nach Drive „Diagnose“ hochgeladen)", 15f))
        val handoff = EditText(this).apply { setText(prefs.handoffFolder) }
        root.addView(handoff)

        root.addView(Ui.button(this, "Speichern") {
            prefs.apiKey = key.text.toString()
            val list = vehicles.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                .ifEmpty { listOf(Prefs.DEFAULT_VEHICLE) }
            prefs.vehicles = list
            if (prefs.vehicle !in list) prefs.vehicle = list.first()
            prefs.autoCapture = auto.isChecked
            prefs.handoffFolder = handoff.text.toString().ifBlank { Prefs.defaultHandoff() }
            prefs.watchFolders = folders.text.toString().ifBlank { Prefs.defaultFolders() }
            prefs.watchEnabled = watch.isChecked
            ScanWorker.schedule(this)
            Toast.makeText(this, "Gespeichert", Toast.LENGTH_SHORT).show()
            finish()
        })

        access = Ui.text(this, "", 15f).apply { setPadding(0, Ui.dp(context, 20), 0, 0) }
        root.addView(access)
        root.addView(Ui.button(this, "Dateizugriff erlauben") { Storage.request(this) })

        root.addView(Ui.text(this,
            "Hinweis: Beim ersten Durchlauf merkt sich die App alle vorhandenen Dateien und wertet nur neue aus. " +
                "Ältere Berichte kannst du auf der Startseite mit „Datei auswählen“ einzeln auswerten. " +
                "Jede Auswertung kostet über den API-Schlüssel ein paar Cent.", 14f).apply {
            setPadding(0, Ui.dp(context, 20), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        access.text = if (Storage.hasAccess(this)) "✅ Dateizugriff erlaubt" else "⚠️ Dateizugriff fehlt"
    }
}
