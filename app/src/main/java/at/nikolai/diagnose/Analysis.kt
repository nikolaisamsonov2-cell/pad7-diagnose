package at.nikolai.diagnose

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.app.Notification
import java.io.File

/** Wertet eine Datei aus, speichert das Ergebnis und meldet es per Benachrichtigung. */
object Analysis {
    private const val CHANNEL = "ergebnisse"

    /** Dateitypen, die Claude lesen kann. .dat und .x431 sind Launch-eigene Formate. */
    val SUPPORTED = setOf("pdf", "csv", "txt", "jpg", "jpeg", "png", "webp")

    fun isSupported(name: String) = name.substringAfterLast('.', "").lowercase() in SUPPORTED

    fun run(context: Context, fileName: String, bytes: ByteArray): ReportStore.Result {
        val prefs = Prefs(context)
        val store = ReportStore(context)
        val result = try {
            val text = ClaudeAnalyzer(prefs.apiKey, prefs.vehicle).analyze(ClaudeAnalyzer.Input(fileName, bytes))
            store.save(fileName, text, null)
        } catch (e: ClaudeAnalyzer.AnalysisException) {
            store.save(fileName, "", e.message)
        } catch (e: Exception) {
            store.save(fileName, "", "Unerwarteter Fehler: ${e.javaClass.simpleName}: ${e.message}")
        }
        notify(context, result)
        return result
    }

    fun run(context: Context, file: File) = run(context, file.name, file.readBytes())

    private fun notify(context: Context, r: ReportStore.Result) {
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
            .setContentText(r.error ?: "Tippen für die Auswertung")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(r.id.hashCode(), n)
    }
}
