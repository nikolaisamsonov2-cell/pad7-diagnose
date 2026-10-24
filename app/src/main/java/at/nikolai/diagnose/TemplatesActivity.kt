package at.nikolai.diagnose

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Messfahrt-Vorlagen: aufnehmen, starten, ansehen, löschen. */
class TemplatesActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.column(this)
        root.addView(Ui.title(this, "Vorlagen: Komplett-Check & Messfahrt"))
        root.addView(Ui.text(this,
            "Einmal vormachen, danach tippt die App selbst: Fahrzeug, Steuergerät, Datenstrom, Werte anhaken, " +
                "Aufzeichnung starten. Für den Komplett-Check: Fahrzeug wählen, Systemscan starten, bis die Ergebnisliste da ist.\n\n" +
                "Für das Ende einer Messfahrt eine zweite Vorlage aufnehmen (Aufzeichnung stoppen, CSV exportieren) " +
                "und dort „Danach Dateien auswerten“ anhaken.\n\n" +
                "Gesperrt (wird nie getippt): Löschen, Codierung, Anpassung, Stellgliedtests, Resets, Updates.", 15f))
        root.addView(Ui.button(this, "Neue Vorlage aufnehmen") { newTemplate() })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val prefs = Prefs(this)
        val all = TemplateStore(this).list()
        if (all.isEmpty()) {
            list.addView(Ui.text(this, "\nNoch keine Vorlagen.", 15f))
            return
        }
        // Vorlagen des aktiven Fahrzeugs zuerst.
        for (t in all.sortedByDescending { it.vehicle == prefs.vehicle }) {
            list.addView(Ui.title(this, t.name).apply {
                textSize = 19f
                setPadding(0, Ui.dp(context, 18), 0, 0)
            })
            list.addView(Ui.text(this, "${t.vehicle} · ${t.steps.size} Schritte" +
                (if (t.captureEnd) " · Komplett-Check" else "") + (if (t.scanAfter) " · danach auswerten" else ""), 14f))
            list.addView(Ui.button(this, "▶ Starten") { play(t) })
            list.addView(Ui.button(this, "Schritte ansehen / löschen") { details(t) })
        }
    }

    private fun play(t: Template) {
        val svc = DiagnoseService.instance
        if (svc == null) {
            needService()
            return
        }
        svc.play(t)
    }

    private fun newTemplate() {
        if (DiagnoseService.instance == null) {
            needService()
            return
        }
        val prefs = Prefs(this)
        val box = Ui.column(this)
        val name = EditText(this).apply { hint = "Name, z. B. Kat-Check Kuga Start" }
        val check = CheckBox(this).apply {
            text = "Komplett-Check: am Ende die Ergebnisseite erfassen und mit dem letzten Check vergleichen"
        }
        val scan = CheckBox(this).apply { text = "Danach Dateien auswerten (für Ende-Vorlagen mit CSV-Export)" }
        box.addView(name)
        box.addView(check)
        box.addView(scan)
        AlertDialog.Builder(this)
            .setTitle("Neue Vorlage für ${prefs.vehicle}")
            .setView(box)
            .setPositiveButton("Aufnahme starten") { _, _ ->
                val n = name.text.toString().trim().ifEmpty { "Vorlage ${TemplateStore(this).list().size + 1}" }
                DiagnoseService.instance?.startRecording(n, prefs.vehicle, scan.isChecked, check.isChecked)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun details(t: Template) {
        val steps = t.steps.mapIndexed { i, s ->
            val mark = when (s.checked) {
                true -> " [anhaken]"
                false -> " [abhaken]"
                null -> ""
            }
            "${i + 1}. ${s.label}$mark"
        }.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle(t.name)
            .setMessage(steps)
            .setPositiveButton("OK", null)
            .setNegativeButton("Vorlage löschen") { _, _ ->
                TemplateStore(this).delete(t.id)
                Toast.makeText(this, "Gelöscht", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .show()
    }

    private fun needService() {
        AlertDialog.Builder(this)
            .setMessage("Dafür muss die Bedienungshilfe „Diagnose“ eingeschaltet sein. Jetzt öffnen?")
            .setPositiveButton("Öffnen") { _, _ -> Access.openSettings(this) }
            .setNegativeButton("Später", null)
            .show()
    }
}
