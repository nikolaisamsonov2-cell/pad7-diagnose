package at.nikolai.diagnose

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ohne API-Schlüssel: Dateien in den Ordner legen, den DriveSync nach Google Drive "Diagnose" hochlädt.
 * Dort wertet der stündliche Check im Claude-Projekt sie aus.
 */
object Handoff {
    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun write(context: Context, files: List<ClaudeAnalyzer.Input>): List<File> {
        val prefs = Prefs(context)
        if (!Storage.hasAccess(context)) {
            throw ClaudeAnalyzer.AnalysisException("Kein Dateizugriff: In der Diagnose-App „Schritt 1: Dateizugriff erlauben“ antippen.")
        }
        val dir = File(prefs.handoffFolder)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw ClaudeAnalyzer.AnalysisException("Übergabeordner ${dir.absolutePath} fehlt und lässt sich nicht anlegen (Dateizugriff erlaubt?).")
        }
        val written = files.map { f ->
            val target = uniqueFile(dir, "Diagnose_" + f.fileName)
            target.writeBytes(f.bytes)
            target
        }
        // Dem System die neuen Dateien melden, damit Sync-Apps wie Autosync sie sofort sehen.
        MediaScannerConnection.scanFile(context, written.map { it.absolutePath }.toTypedArray(), null, null)
        // Eigene Übergabedateien nicht später noch einmal auswerten.
        prefs.markSeen(written.map { ScanWorker.key(it) })
        return written
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 2
        while (f.exists()) {
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            f = File(dir, if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext")
            i++
        }
        return f
    }
}
