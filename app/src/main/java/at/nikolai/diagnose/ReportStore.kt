package at.nikolai.diagnose

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Gespeicherte Auswertungen, eine JSON-Datei pro Bericht im privaten App-Speicher. */
class ReportStore(context: Context) {
    private val dir = File(context.filesDir, "results").apply { mkdirs() }

    data class Result(
        val id: String,
        val fileName: String,
        val createdAt: Long,
        val text: String,
        val error: String?,
        val vehicle: String = "",
    ) {
        /** Erste Zeile "TITEL: ..." aus der Antwort, sonst der Dateiname. */
        val title: String
            get() = error?.let { "Fehler: $fileName" }
                ?: text.lineSequence().firstOrNull { it.startsWith("TITEL:") }?.removePrefix("TITEL:")?.trim()
                ?: fileName

        /** GRÜN, GELB oder ROT aus der Zeile "AMPEL: ...", sonst null. */
        val ampel: String?
            get() = text.lineSequence().firstOrNull { it.startsWith("AMPEL:") }
                ?.removePrefix("AMPEL:")?.trim()?.uppercase()?.let { a ->
                    listOf("ROT", "GELB", "GRÜN").firstOrNull { a.startsWith(it) }
                }
    }

    fun save(fileName: String, text: String, error: String?, vehicle: String = ""): Result {
        val now = System.currentTimeMillis()
        val r = Result(now.toString(), fileName, now, text, error, vehicle)
        val json = JSONObject()
            .put("id", r.id).put("fileName", fileName).put("createdAt", now)
            .put("text", text).put("error", error ?: JSONObject.NULL).put("vehicle", vehicle)
        File(dir, "${r.id}.json").writeText(json.toString())
        return r
    }

    fun list(): List<Result> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { parse(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    /** Frühere erfolgreiche Claude-Auswertungen desselben Fahrzeugs, neueste zuerst. */
    fun history(vehicle: String, limit: Int): List<Result> =
        list().filter { it.vehicle == vehicle && it.error == null && it.ampel != null }.take(limit)

    fun get(id: String): Result? = File(dir, "$id.json").takeIf { it.exists() }?.let { parse(it.readText()) }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    private fun parse(s: String): Result {
        val o = JSONObject(s)
        return Result(
            o.getString("id"), o.getString("fileName"), o.getLong("createdAt"), o.optString("text"),
            if (o.isNull("error")) null else o.getString("error"),
            o.optString("vehicle"),
        )
    }
}
