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
        val dir = File(prefs.handoffFolder)
        val problem = Storage.writeProblem(dir)
        if (problem != null) {
            // Nicht verlieren: in den app-eigenen Ordner retten und deutlich melden.
            val fallback = Storage.fallbackDir(context)
            val saved = runCatching { save(context, fallback, files) }.isSuccess
            throw ClaudeAnalyzer.AnalysisException(
                "Launch-Ordner gesperrt ($problem). " +
                    (if (saved) "Vorläufig gespeichert in ${fallback.absolutePath}. " else "") +
                    "In der Diagnose-App „Dateizugriff erlauben“ antippen.",
            )
        }
        val written = save(context, dir, files)
        // Eigene Übergabedateien nicht später noch einmal auswerten.
        prefs.markSeen(written.map { ScanWorker.key(it) })
        return written
    }

    private fun save(context: Context, dir: File, files: List<ClaudeAnalyzer.Input>): List<File> {
        dir.mkdirs()
        val written = files.map { f ->
            val target = uniqueFile(dir, "Diagnose_" + f.fileName)
            target.writeBytes(f.bytes)
            target
        }
        // Dem System die neuen Dateien melden, damit Sync-Apps wie Autosync sie sofort sehen.
        MediaScannerConnection.scanFile(context, written.map { it.absolutePath }.toTypedArray(), null, null)
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
