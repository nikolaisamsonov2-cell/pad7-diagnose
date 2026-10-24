package at.nikolai.diagnose

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Prüfprogramme (Garantie-Check, Gebrauchtwagen-Check): Punkt für Punkt belegen, dann Gesamtbericht. */
class ProgramActivity : Activity() {
    private lateinit var content: LinearLayout
    private var pendingItem: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = Ui.column(this)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        val session = ProgramSession.active(this)
        if (session == null) renderChooser() else renderSession(session)
    }

    private fun renderChooser() {
        val prefs = Prefs(this)
        content.addView(Ui.title(this, "Prüfprogramme"))
        content.addView(Ui.text(this, "Fahrzeug: ${prefs.vehicle} (auf der Startseite wechseln)", 15f))
        for (p in Programs.ALL) {
            content.addView(Ui.title(this, p.name).apply { textSize = 20f; setPadding(0, Ui.dp(context, 18), 0, 0) })
            content.addView(Ui.text(this, p.intro, 15f))
            content.addView(Ui.button(this, "▶ ${p.name} starten") {
                ProgramSession.start(this, p, prefs.vehicle)
                render()
            })
        }
        val old = ProgramSession.list(this).filter { it.finished }.take(10)
        if (old.isNotEmpty()) {
            content.addView(Ui.title(this, "Frühere Prüfungen").apply { textSize = 18f; setPadding(0, Ui.dp(context, 24), 0, 0) })
            old.forEach { s ->
                content.addView(Ui.text(this, "${s.program?.name} · ${s.vehicle} · ${s.id.substringAfter('_')} · ${s.allFiles().size} Belege", 14f))
            }
        }
    }

    private fun renderSession(s: ProgramSession) {
        val p = s.program ?: return
        val prefs = Prefs(this)
        content.addView(Ui.title(this, "${p.name}: ${s.vehicle}"))
        val done = p.items.count { s.isDone(it) }
        content.addView(Ui.text(this,
            "$done von ${p.items.size} Punkten belegt. Punkt antippen → „Erfassen“ öffnet die Launch-App, dort mit 🔍 Belege machen " +
                "(beliebig oft). Alles wird sofort gespeichert.", 15f))

        for (item in p.items) {
            val mark = when {
                s.isDone(item) -> "✅"
                item.optional -> "⬜ (optional)"
                else -> "⬜"
            }
            val count = s.files(item.id).count { it.name.endsWith(".txt") || !it.name.contains(ClaudeAnalyzer.SCREEN_PREFIX) }
            val active = prefs.captureTarget == item.id
            content.addView(TextView(this).apply {
                text = "$mark ${item.title}" + (if (count > 0) "  ($count)" else "") + if (active) "  ← 🔍 aktiv" else ""
                textSize = 18f
                setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
                val pad = Ui.dp(context, 12)
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(if (active) 0x33C62828 else 0x11000000)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(context, 6) }
                setOnClickListener { itemDialog(s, item) }
            })
        }

        content.addView(Ui.button(this, "📋 Gesamtbericht erstellen") { finishSession(s) })
        content.addView(Ui.button(this, "Prüfung abbrechen (Belege bleiben gespeichert)") {
            AlertDialog.Builder(this).setMessage("Prüfung beenden ohne Bericht?")
                .setPositiveButton("Beenden") { _, _ -> s.finished = true; prefs.captureTarget = ""; render() }
                .setNegativeButton("Weiter prüfen", null).show()
        })
    }

    private fun itemDialog(s: ProgramSession, item: Program.Item) {
        val actions = arrayOf("🔍 Erfassen in der Launch-App", "✏️ Notiz", "📎 Datei hinzufügen (CSV, PDF, Foto)")
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setMessage(item.howTo + (s.note(item.id).takeIf { it.isNotBlank() }?.let { "\n\nNotiz: $it" } ?: ""))
            .setPositiveButton(actions[0]) { _, _ -> capture(item) }
            .setNeutralButton("✏️ Notiz") { _, _ -> editNote(s, item) }
            .setNegativeButton("📎 Datei") { _, _ -> pickFile(item) }
            .show()
    }

    private fun capture(item: Program.Item) {
        val prefs = Prefs(this)
        prefs.captureTarget = item.id
        val svc = DiagnoseService.instance
        if (svc == null) {
            AlertDialog.Builder(this)
                .setMessage("Für 🔍 muss die Bedienungshilfe „Diagnose“ eingeschaltet sein. Jetzt öffnen?")
                .setPositiveButton("Öffnen") { _, _ -> Access.openSettings(this) }
                .setNegativeButton("Später", null).show()
            return
        }
        Toast.makeText(this, "🔍 speichert jetzt Belege für „${item.title}“", Toast.LENGTH_LONG).show()
        svc.openLaunch()
    }

    private fun editNote(s: ProgramSession, item: Program.Item) {
        val input = EditText(this).apply {
            setText(s.note(item.id))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            hint = "Tipp: Mikrofon auf der Tastatur zum Diktieren"
        }
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setView(input)
            .setPositiveButton("Speichern") { _, _ -> s.setNote(item.id, input.text.toString().trim()); render() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun pickFile(item: Program.Item) {
        pendingItem = item.id
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/*", "image/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, PICK)
    }

    @Deprecated("Activity-API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val itemId = pendingItem ?: return
        val s = ProgramSession.active(this) ?: return
        if (requestCode != PICK || resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.data?.let { add(it) }
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
        }.distinct()
        var added = 0
        for (uri in uris) {
            val name = displayName(uri) ?: "datei"
            if (!Analysis.isSupported(name)) continue
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
            s.addFile(itemId, name, bytes)
            added++
        }
        Toast.makeText(this, "$added Datei(en) hinzugefügt", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun finishSession(s: ProgramSession) {
        val p = s.program ?: return
        val missing = p.items.filter { !it.optional && !s.isDone(it) }
        val msg = if (missing.isEmpty()) "Alle Pflichtpunkte sind belegt. Gesamtbericht jetzt erstellen?"
        else "Noch nicht belegt:\n" + missing.joinToString("\n") { "• ${it.title}" } + "\n\nTrotzdem Bericht erstellen?"
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Bericht erstellen") { _, _ ->
                s.finished = true
                Prefs(this).captureTarget = ""
                Toast.makeText(this, "Bericht wird erstellt, kommt als Benachrichtigung …", Toast.LENGTH_LONG).show()
                val app = applicationContext
                Thread { Analysis.runSession(app, s) }.start()
                render()
            }
            .setNegativeButton("Weiter prüfen", null)
            .show()
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment

    companion object {
        private const val PICK = 9
    }
}
