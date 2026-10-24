package at.nikolai.diagnose

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Wertet Dateien aus, speichert das Ergebnis und meldet es per Benachrichtigung.
 * Ohne API-Schlüssel gehen die Dateien stattdessen über den Drive-Ordner an den Projekt-Check.
 */
object Analysis {
    private const val CHANNEL = "ergebnisse"

    /** Dateitypen, die Claude lesen kann. .dat und .x431 sind Launch-eigene Formate. */
    val SUPPORTED = setOf("pdf", "csv", "txt", "jpg", "jpeg", "png", "webp")

    fun isSupported(name: String) = name.substringAfterLast('.', "").lowercase() in SUPPORTED

    fun run(context: Context, fileName: String, bytes: ByteArray): ReportStore.Result =
        run(context, listOf(ClaudeAnalyzer.Input(fileName, bytes)))

    fun run(context: Context, file: File) = run(context, file.name, file.readBytes())

    fun run(context: Context, inputs: List<ClaudeAnalyzer.Input>): ReportStore.Result {
        val prefs = Prefs(context)
        val store = ReportStore(context)
        val label = inputs.joinToString(", ") { it.fileName }
        val result = try {
            if (prefs.apiKey.isBlank()) {
                val written = Handoff.write(context, withVehicleNote(inputs, prefs.vehicle))
                store.save(
                    label,
                    "TITEL: An Drive übergeben – ${prefs.vehicle}\n\n" +
                        "Noch kein API-Schlüssel eingetragen. Die Dateien liegen im Übergabeordner und werden von DriveSync " +
                        "nach Google Drive „Diagnose“ hochgeladen. Die Auswertung kommt dann im Claude-Projekt.\n\n" +
                        written.joinToString("\n") { it.absolutePath },
                    null,
                    prefs.vehicle,
                )
            } else {
                val history = store.history(prefs.vehicle, 3)
                val text = ClaudeAnalyzer(prefs.apiKey, prefs.vehicle).analyze(inputs, history)
                store.save(label, text, null, prefs.vehicle)
            }
        } catch (e: ClaudeAnalyzer.AnalysisException) {
            store.save(label, "", e.message)
        } catch (e: Exception) {
            store.save(label, "", "Unerwarteter Fehler: ${e.javaClass.simpleName}: ${e.message}")
        }
        notify(context, result)
        return result
    }

    /**
     * Gesamtbericht einer Prüfung. Alle Belege werden immer nach Drive gesichert (damit nichts verloren geht);
     * mit API-Schlüssel wertet Claude sie zusätzlich direkt aus.
     */
    fun runSession(context: Context, session: ProgramSession): ReportStore.Result {
        val prefs = Prefs(context)
        val store = ReportStore(context)
        val program = session.program
        val label = "${program?.name ?: "Prüfung"} ${session.id}"
        val manifest = ClaudeAnalyzer.Input("${session.id}_Uebersicht.txt", session.manifest().toByteArray())
        val files = session.allFiles().map { ClaudeAnalyzer.Input("${session.id}_${it.name}", it.readBytes()) }

        val backup = runCatching { Handoff.write(context, listOf(manifest) + files) }
        val backupNote = backup.fold(
            { "Alle ${it.size} Belege sind im Übergabeordner gesichert und gehen per DriveSync nach Google Drive „Diagnose“." },
            { "⚠️ Sicherung in den Übergabeordner fehlgeschlagen: ${it.message}" },
        )

        val result = try {
            if (prefs.apiKey.isBlank()) {
                store.save(
                    label,
                    "TITEL: ${program?.name ?: "Prüfung"} an Drive übergeben – ${session.vehicle}\n\n" +
                        "Noch kein API-Schlüssel: Der Gesamtbericht kommt im Claude-Projekt.\n\n$backupNote\n\n${session.manifest()}",
                    null, session.vehicle,
                )
            } else {
                // Anfragegrenze 32 MB: notfalls Screenshots weglassen, Texte und Dateien bleiben.
                var budget = 22_000_000L - files.filterNot { isImage(it.fileName) }.sumOf { it.bytes.size.toLong() }
                val keep = files.filter { f -> !isImage(f.fileName) || (budget - f.bytes.size >= 0).also { if (it) budget -= f.bytes.size } }
                val dropped = files.size - keep.size
                val extra = if (dropped > 0) ClaudeAnalyzer.Input("Hinweis.txt",
                    "$dropped Screenshots wegen Größe weggelassen; Bildschirmtexte sind vollständig.".toByteArray()) else null
                val text = ClaudeAnalyzer(prefs.apiKey, session.vehicle)
                    .analyze(listOfNotNull(manifest, extra) + keep, store.history(session.vehicle, 3), program?.reportFocus)
                store.save(label, "$text\n\n$backupNote", null, session.vehicle)
            }
        } catch (e: ClaudeAnalyzer.AnalysisException) {
            store.save(label, "", "${e.message}\n\n$backupNote", session.vehicle)
        } catch (e: Exception) {
            store.save(label, "", "Unerwarteter Fehler: ${e.javaClass.simpleName}: ${e.message}\n\n$backupNote", session.vehicle)
        }
        notify(context, result)
        return result
    }

    private fun isImage(name: String) = name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp")

    /** Für den Drive-Weg: eine kleine Textdatei mit dem aktiven Fahrzeug beilegen. */
    private fun withVehicleNote(inputs: List<ClaudeAnalyzer.Input>, vehicle: String): List<ClaudeAnalyzer.Input> {
        val stem = inputs.first().fileName.substringBeforeLast('.')
        val note = "Fahrzeug laut Diagnose-App: $vehicle\nDateien: ${inputs.joinToString { it.fileName }}\n"
        return inputs + ClaudeAnalyzer.Input("${stem}_Fahrzeug.txt", note.toByteArray())
    }

    fun notify(context: Context, r: ReportStore.Result) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Diagnose-Ergebnisse", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, r.id.hashCode(),
            Intent(context, ResultActivity::class.java).putExtra(ResultActivity.EXTRA_ID, r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val prefix = when (r.ampel) {
            "ROT" -> "🔴 "
            "GELB" -> "🟡 "
            "GRÜN" -> "🟢 "
            else -> ""
        }
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_diagnose)
            .setContentTitle(prefix + r.title)
            .setContentText(r.error ?: "Tippen für Details")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(r.id.hashCode(), n)
    }

    /** Kurze Statusmeldung (z. B. vom Vorlagen-Ablauf), ohne gespeichertes Ergebnis. */
    fun notifyStatus(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Diagnose-Ergebnisse", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_diagnose)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(STATUS_ID, n)
    }

    private const val STATUS_ID = 4711
}
