package at.nikolai.diagnose

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Selbst-Update aus dem GitHub-Release "neueste". Klappt nur, wenn das Repo öffentlich ist;
 * sonst wird die Download-Seite im Browser geöffnet.
 */
object Updater {
    const val REPO = "nikolaisamsonov2-cell/pad7-diagnose"
    const val RELEASE_PAGE = "https://github.com/$REPO/releases/tag/neueste"

    class Release(val build: Int, val apkUrl: String)

    fun currentBuild(context: Context): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
        }

    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** null = nicht erreichbar (privat oder offline). */
    fun latest(): Release? = runCatching {
        val json = JSONObject(get("https://api.github.com/repos/$REPO/releases/tags/neueste").decodeToString())
        val build = Regex("Build (\\d+)").find(json.optString("name") + " " + json.optString("body"))
            ?.groupValues?.get(1)?.toInt() ?: return null
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
        Release(build, apk.getString("browser_download_url"))
    }.getOrNull()

    fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "pad7-diagnose")
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    fun canInstall(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openReleasePage(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Lädt die APK und übergibt sie dem Paket-Installer; Android fragt dann einmal „Aktualisieren?“. */
    fun downloadAndInstall(context: Context, r: Release) {
        val file = File(context.cacheDir, "update.apk")
        file.writeBytes(get(r.apkUrl))
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("diagnose.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
    }
}

/** Zeigt die Bestätigung des Installers an bzw. meldet Fehler. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Analysis.notifyStatus(context, "⚠️ Update fehlgeschlagen",
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unbekannter Fehler") +
                    "\nNotfalls über den Link in Chrome installieren.")
        }
    }
}
