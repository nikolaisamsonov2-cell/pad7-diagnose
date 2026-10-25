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
    private lateinit var startButton: android.widget.Button
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
        startButton = Ui.button(this, "") { startCheck() }.apply { textSize = 22f }
        root.addView(startButton)
        root.addView(Ui.button(this, "🤖 Claude steuert (Live-Modus)") { startLive() })
        programButton = Ui.button(this, "") { startActivity(Intent(this, ProgramActivity::class.java)) }
        root.addView(programButton)
        root.addView(Ui.button(this, "▶ Komplett-Check & Messfahrt") { startActivity(Intent(this, TemplatesActivity::class.java)) })
        root.addView(Ui.button(this, "Jetzt nach neuen Berichten suchen") {
            ScanWorker.runNow(this)
            Toast.makeText(this, "Suche läuft, Ergebnis kommt als Benachrichtigung.", Toast.LENGTH_LONG).show()
        })
        root.addView(Ui.button(this, "Datei auswählen und auswerten") { pickFile() })
        root.addView(Ui.button(this, "Einstellungen") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(Ui.button(this, "🔄 Nach Update suchen (Version ${Updater.versionName(this)})") { checkUpdate() })
        root.addView(Ui.title(this, "Auswertungen").apply { setPadding(0, Ui.dp(context, 20), 0, 0) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply { addView(root) })
        ScanWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Fahrzeug vom Zulassungsschein-Foto im Chat übernehmen.
        Thread {
            val name = RemoteVehicle.sync(applicationContext) ?: return@Thread
            runOnUiThread {
                refresh()
                Toast.makeText(this, "🚗 Fahrzeug gewechselt: $name", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun checkUpdate() {
        Toast.makeText(this, "Suche nach Update …", Toast.LENGTH_SHORT).show()
        Thread {
            val r = Updater.latest()
            runOnUiThread {
                when {
                    r == null -> AlertDialog.Builder(this)
                        .setMessage("Update-Server nicht erreichbar. Download-Seite im Browser öffnen?")
                        .setPositiveButton("Öffnen") { _, _ -> Updater.openReleasePage(this) }
                        .setNegativeButton("Abbrechen", null).show()
                    r.build <= Updater.currentBuild(this) ->
                        Toast.makeText(this, "Du hast schon die neueste Version (${Updater.versionName(this)}).", Toast.LENGTH_LONG).show()
                    !Updater.canInstall(this) -> AlertDialog.Builder(this)
                        .setMessage("Einmalig: Erlaube der Diagnose-App, Updates zu installieren („Aus dieser Quelle zulassen“). Danach nochmal auf Update tippen.")
                        .setPositiveButton("Einstellung öffnen") { _, _ -> Updater.openInstallPermission(this) }
                        .setNegativeButton("Abbrechen", null).show()
                    else -> {
                        Toast.makeText(this, "Neue Version 0.1.${r.build} wird geladen …", Toast.LENGTH_LONG).show()
                        val app = applicationContext
                        Thread {
                            runCatching { Updater.downloadAndInstall(app, r) }.onFailure {
                                Analysis.notifyStatus(app, "⚠️ Update fehlgeschlagen", "${it.message}\nNotfalls über den Link in Chrome installieren.")
                            }
                        }.start()
                    }
                }
            }
        }.start()
    }

    private fun checkTemplate(vehicle: String) =
        TemplateStore(this).list().filter { it.vehicle == vehicle && it.captureEnd && it.format >= Template.FORMAT }.maxByOrNull { it.id }

    /** Live-Modus: Claude sieht den Bildschirm und tippt selbst. Braucht einen eigenen API-Schlüssel. */
    private fun startLive() {
        val svc = DiagnoseService.instance
        if (svc == null) {
            AlertDialog.Builder(this)
                .setMessage("Dafür muss die Bedienungshilfe „Diagnose“ eingeschaltet sein. Jetzt öffnen?")
                .setPositiveButton("Öffnen") { _, _ -> Access.openSettings(this) }
                .setNegativeButton("Später", null).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setMessage("Der Live-Modus braucht Android 11 oder neuer (Screenshots). Dieses Gerät: ${Storage.androidVersion()}.")
                .setPositiveButton("OK", null).show()
            return
        }
        val prefs = Prefs(this)
        if (prefs.apiKey.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("API-Schlüssel fehlt")
                .setMessage(
                    "Im Live-Modus schaut Claude über das Internet auf den Bildschirm und tippt selbst. Dafür braucht die App " +
                        "deinen eigenen Schlüssel:\n\n" +
                        "1. Im Browser console.anthropic.com öffnen und anmelden.\n" +
                        "2. Unter „Billing“ Guthaben aufladen (z. B. 10 \$).\n" +
                        "3. Unter „API Keys“ → „Create Key“, Schlüssel kopieren.\n" +
                        "4. Hier unter Einstellungen einfügen und speichern.\n\n" +
                        "Den Schlüssel nie im Chat schicken."
                )
                .setPositiveButton("Einstellungen") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("Später", null).show()
            return
        }
        val names = LiveAgent.PRESETS.map { it.first } + "Eigene Aufgabe …"
        AlertDialog.Builder(this)
            .setTitle("Was soll Claude machen? (${prefs.vehicle})")
            .setItems(names.toTypedArray()) { _, i ->
                if (i < LiveAgent.PRESETS.size) confirmLive(svc, LiveAgent.PRESETS[i].first, LiveAgent.PRESETS[i].second)
                else {
                    val input = android.widget.EditText(this).apply { hint = "z. B. Im PCM die Versionsinformationen erfassen" }
                    AlertDialog.Builder(this)
                        .setTitle("Eigene Aufgabe")
                        .setView(input)
                        .setPositiveButton("Weiter") { _, _ ->
                            val goal = input.text.toString().trim()
                            if (goal.isNotEmpty()) confirmLive(svc, "Eigene Aufgabe", goal)
                        }
                        .setNegativeButton("Abbrechen", null).show()
                }
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun confirmLive(svc: DiagnoseService, name: String, goal: String) {
        AlertDialog.Builder(this)
            .setTitle("🤖 $name")
            .setMessage(
                "Claude öffnet Launch und tippt selbst. Oben steht, was gerade passiert; mit „Stopp“ hörst du jederzeit auf.\n\n" +
                    "Löschen, Codierung, Anpassungen und Stellgliedtests sind gesperrt und werden nie getippt.\n\n" +
                    "Kosten: etwa 3–4 Cent pro Schritt, höchstens ${LiveAgent.MAX_STEPS} Schritte (also höchstens etwa 2 €). " +
                    "Fahrzeug: Zündung an, Stecker drin."
            )
            .setPositiveButton("Los") { _, _ -> svc.startLive(goal) }
            .setNegativeButton("Abbrechen", null).show()
    }

    /** Ein Knopf: Launch öffnen, Fahrzeug wählen, Systemscan, Ergebnis abwarten und erfassen. */
    private fun startCheck() {
        val svc = DiagnoseService.instance
        if (svc == null) {
            AlertDialog.Builder(this)
                .setMessage("Dafür muss die Bedienungshilfe „Diagnose“ eingeschaltet sein. Jetzt öffnen?")
                .setPositiveButton("Öffnen") { _, _ -> Access.openSettings(this) }
                .setNegativeButton("Später", null).show()
            return
        }
        val vehicle = Prefs(this).vehicle
        val t = checkTemplate(vehicle)
        if (t != null) {
            svc.startCheck(t, vehicle)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Komplett-Check: $vehicle")
            .setMessage(
                "Zeigt die Launch-App schon ein Scan-Ergebnis (Topologie mit roten Steuergeräten), liest die App sofort " +
                    "alle Fehlercodes selbst.\n\n" +
                    "Sonst einmal vormachen: In der Launch-App wie immer bis zum Systemscan tippen. Sobald das Ergebnis " +
                    "erscheint, beendet die App die Aufnahme selbst und liest die Fehlercodes. Ab dann reicht dieser Knopf.\n\n" +
                    "Löschen, Codierung und Anpassungen werden nie getippt."
            )
            .setPositiveButton("Los") { _, _ -> svc.startCheck(null, vehicle) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun refresh() {
        val prefs = Prefs(this)
        vehicleButton.text = "🚗 ${prefs.vehicle}  (antippen zum Wechseln)"
        startButton.text = if (checkTemplate(prefs.vehicle) != null) "▶ START: Komplett-Check ${prefs.vehicle}"
        else "▶ START: Komplett-Check ${prefs.vehicle} (einmal vormachen)"
        programButton.text = ProgramSession.active(this)?.let { "🛡️ Laufende Prüfung fortsetzen: ${it.program?.name}" }
            ?: "🛡️ Garantie-Check / Gebrauchtwagen-Check"
        val lines = mutableListOf<String>()
        lines += if (prefs.apiKey.isBlank()) "Ohne API-Schlüssel: Berichte gehen über Google Drive an das Claude-Projekt."
        else "Auswertung direkt hier am Tablet."
        setup.removeAllViews()
        val writeProblem = if (Storage.hasAccess(this)) Storage.writeProblem(java.io.File(prefs.handoffFolder)) else "fehlt"
        if (writeProblem != null) {
            setup.addView(Ui.button(this, "⚠️ Schritt 1: Dateizugriff erlauben (Schalter „Zugriff auf alle Dateien“ einschalten)") { Storage.request(this) })
            if (writeProblem != "fehlt") lines += "⚠️ Speichern im Launch-Ordner klappt nicht: $writeProblem"
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
        lines += Storage.androidVersion()
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
