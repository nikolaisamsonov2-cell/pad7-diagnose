package at.nikolai.diagnose

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Messfahrt-Vorlage: eine aufgenommene Folge von Tipps in der Launch-App.
 * Jeder Schritt wird über seine Beschriftung wiedergefunden, nicht über Bildschirmkoordinaten.
 */
data class Template(
    val id: String,
    val name: String,
    val vehicle: String,
    val steps: List<Step>,
    /** Nach dem Ablauf neue Dateien suchen und auswerten (z. B. Vorlage "Messfahrt beenden" mit CSV-Export). */
    val scanAfter: Boolean,
    /** Am Ende den Bildschirm erfassen und auswerten (Komplett-Check: Ergebnisseite des Systemscans). */
    val captureEnd: Boolean = false,
) {
    data class Step(
        val label: String,
        /** Für Häkchen-Felder: gewünschter Zustand nach dem Schritt; null bei normalen Tipps. */
        val checked: Boolean?,
        val viewId: String?,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("vehicle", vehicle).put("scanAfter", scanAfter).put("captureEnd", captureEnd)
        .put("steps", JSONArray().apply {
            steps.forEach { s ->
                put(JSONObject().put("label", s.label)
                    .put("checked", s.checked ?: JSONObject.NULL)
                    .put("viewId", s.viewId ?: JSONObject.NULL))
            }
        })

    companion object {
        fun fromJson(o: JSONObject): Template {
            val arr = o.getJSONArray("steps")
            val steps = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                Step(
                    s.getString("label"),
                    if (s.isNull("checked")) null else s.getBoolean("checked"),
                    if (s.isNull("viewId")) null else s.getString("viewId"),
                )
            }
            return Template(o.getString("id"), o.getString("name"), o.optString("vehicle"), steps, o.optBoolean("scanAfter"), o.optBoolean("captureEnd"))
        }
    }
}

class TemplateStore(context: Context) {
    private val dir = File(context.filesDir, "templates").apply { mkdirs() }

    fun list(): List<Template> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { runCatching { Template.fromJson(JSONObject(it.readText())) }.getOrNull() }
        .sortedBy { it.name.lowercase() }

    fun get(id: String): Template? = list().firstOrNull { it.id == id }

    fun save(t: Template) = File(dir, "${t.id}.json").writeText(t.toJson().toString(2))

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}

/** Was die App in der Launch-App niemals antippt, weder beim Aufnehmen noch beim Abspielen. */
object Blocklist {
    private val WORDS = listOf(
        "lösch", "losch", "clear", "erase", "delete", "entfern",
        "codier", "kodier", "coding", "programmier", "program", "flash",
        "anpass", "adapt", "anlern", "lernwert", "learn", "kalibrier", "calibrat",
        "stellglied", "aktor", "aktuator", "actuat", "aktiver test", "active test", "ansteuer",
        "reset", "zurücksetz", "rücksetz", "initialis", "sonderfunktion", "special function",
        "service", "wartung", "regenerat", "entlüft", "bleed", "update", "upgrade", "aktualisier",
    )

    fun blocked(label: String): String? {
        val l = label.lowercase()
        return WORDS.firstOrNull { it in l }
    }
}
