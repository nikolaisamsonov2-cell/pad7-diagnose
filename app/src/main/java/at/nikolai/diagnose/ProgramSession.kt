package at.nikolai.diagnose

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Eine laufende oder abgeschlossene Prüfung: Belege (Dateien) und Notizen pro Prüfpunkt.
 * Liegt im privaten App-Speicher, damit nichts verloren geht, auch ohne Internet.
 */
class ProgramSession private constructor(val dir: File) {
    private val meta = File(dir, "session.json")
    private var json: JSONObject = if (meta.exists()) JSONObject(meta.readText()) else JSONObject()

    val id: String get() = dir.name
    val programId: String get() = json.optString("program")
    val vehicle: String get() = json.optString("vehicle")
    val startedAt: Long get() = json.optLong("startedAt")
    val program: Program? get() = Programs.get(programId)

    var finished: Boolean
        get() = json.optBoolean("finished")
        set(v) { json.put("finished", v); save() }

    fun note(itemId: String): String = json.optJSONObject("notes")?.optString(itemId).orEmpty()

    fun setNote(itemId: String, text: String) {
        val notes = json.optJSONObject("notes") ?: JSONObject().also { json.put("notes", it) }
        notes.put(itemId, text)
        save()
    }

    /** Belegdateien eines Punkts, Dateinamen beginnen mit "<itemId>__". */
    fun files(itemId: String): List<File> =
        dir.listFiles { f -> f.name.startsWith("${itemId}__") }.orEmpty().sortedBy { it.name }

    fun allFiles(): List<File> = dir.listFiles { f -> f.name != "session.json" }.orEmpty().sortedBy { it.name }

    fun addFile(itemId: String, name: String, bytes: ByteArray): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var f = File(dir, "${itemId}__${Handoff.stamp()}_$safe")
        var i = 2
        while (f.exists()) f = File(dir, "${itemId}__${Handoff.stamp()}_${i++}_$safe")
        f.writeBytes(bytes)
        return f
    }

    fun isDone(item: Program.Item) = files(item.id).isNotEmpty() || note(item.id).isNotBlank()

    /** Übersicht für Claude und für die Drive-Sicherung: welcher Beleg zu welchem Punkt gehört, plus Notizen. */
    fun manifest(): String = buildString {
        val p = program
        append("Prüfprogramm: ${p?.name ?: programId}\n")
        append("Fahrzeug: $vehicle\n")
        append("Begonnen: ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMANY).format(java.util.Date(startedAt))}\n\n")
        p?.items?.forEach { item ->
            append("## ${item.title}\n")
            val n = note(item.id)
            if (n.isNotBlank()) append("Notiz: $n\n")
            val fs = files(item.id)
            if (fs.isEmpty() && n.isBlank()) append(if (item.optional) "(optional, nicht erfasst)\n" else "(NICHT ERFASST)\n")
            fs.forEach { append("Beleg: ${it.name}\n") }
            append('\n')
        }
    }

    private fun save() = meta.writeText(json.toString(2))

    companion object {
        private fun root(context: Context) = File(context.filesDir, "sessions").apply { mkdirs() }

        fun start(context: Context, program: Program, vehicle: String): ProgramSession {
            val dir = File(root(context), "${program.id}_${Handoff.stamp()}").apply { mkdirs() }
            val s = ProgramSession(dir)
            s.json.put("program", program.id).put("vehicle", vehicle).put("startedAt", System.currentTimeMillis())
            s.save()
            Prefs(context).activeSession = s.id
            return s
        }

        fun get(context: Context, id: String): ProgramSession? =
            File(root(context), id).takeIf { File(it, "session.json").exists() }?.let { ProgramSession(it) }

        fun active(context: Context): ProgramSession? =
            Prefs(context).activeSession.takeIf { it.isNotEmpty() }?.let { get(context, it) }?.takeIf { !it.finished }

        fun list(context: Context): List<ProgramSession> =
            root(context).listFiles().orEmpty().mapNotNull { get(context, it.name) }.sortedByDescending { it.startedAt }
    }
}
