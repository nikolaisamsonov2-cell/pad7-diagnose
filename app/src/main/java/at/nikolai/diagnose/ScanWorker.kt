package at.nikolai.diagnose

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Durchsucht die überwachten Ordner nach neuen Launch-Dateien und wertet sie aus.
 * Läuft alle 15 Minuten (kürzer erlaubt Android nicht) und beim Tippen auf "Jetzt prüfen".
 */
class ScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        RemoteVehicle.sync(applicationContext)?.let { Analysis.notifyStatus(applicationContext, "🚗 Fahrzeug gewechselt", it) }
        if (!Storage.hasAccess(applicationContext)) return Result.success()

        val candidates = findCandidates(prefs)

        // Beim allerersten Lauf nur den Bestand merken, damit keine alten Berichte nachträglich kosten.
        if (!prefs.baselineDone) {
            prefs.markSeen(candidates.map { key(it) })
            prefs.baselineDone = true
            prefs.lastScan = System.currentTimeMillis()
            return Result.success()
        }

        if (prefs.apiKey.isBlank()) {
            prefs.lastScan = System.currentTimeMillis()
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val fresh = candidates
            .filter { !prefs.isSeen(key(it)) }
            // Datei muss fertig geschrieben sein.
            .filter { now - it.lastModified() > 20_000 }
            .sortedBy { it.lastModified() }
            .take(MAX_PER_RUN)

        var retry = false
        for (f in fresh) {
            val r = Analysis.run(applicationContext, f)
            val transient = r.error?.let { e -> TRANSIENT.any { e.contains(it) } } ?: false
            if (transient) retry = true else prefs.markSeen(listOf(key(f)))
        }
        prefs.lastScan = System.currentTimeMillis()
        return if (retry) Result.retry() else Result.success()
    }

    private fun findCandidates(prefs: Prefs): List<File> =
        prefs.folders().filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().maxDepth(6).filter { it.isFile && Analysis.isSupported(it.name) }.toList()
        }.distinctBy { it.canonicalPath }

    companion object {
        private const val MAX_PER_RUN = 5
        private const val PERIODIC = "scan-periodic"
        private const val NOW = "scan-now"
        private val TRANSIENT = listOf("Zu viele Anfragen", "Keine Internetverbindung", "Claude-Fehler 5")

        fun key(f: File) = "${f.canonicalPath}|${f.length()}|${f.lastModified()}"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!Prefs(context).watchEnabled) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val req = PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<ScanWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        }
    }
}
