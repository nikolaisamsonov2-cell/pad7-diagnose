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

        root.addView(Ui.text(this, "Standardfahrzeug (wenn der Bericht keins nennt)", 15f))
        val vehicle = EditText(this).apply { setText(prefs.vehicle) }
        root.addView(vehicle)

        root.addView(Ui.text(this, "Überwachte Ordner (mehrere mit Komma trennen)", 15f))
        val folders = EditText(this).apply { setText(prefs.watchFolders) }
        root.addView(folders)

        val watch = CheckBox(this).apply {
            text = "Neue Berichte automatisch auswerten (alle 15 Minuten)"
            textSize = 16f
            isChecked = prefs.watchEnabled
        }
        root.addView(watch)

        root.addView(Ui.button(this, "Speichern") {
            prefs.apiKey = key.text.toString()
            prefs.vehicle = vehicle.text.toString().ifBlank { Prefs.DEFAULT_VEHICLE }
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
