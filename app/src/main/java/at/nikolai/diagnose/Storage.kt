package at.nikolai.diagnose

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** Zugriff auf den internen Speicher, in dem die Launch-App ihre Dateien ablegt. */
object Storage {
    const val REQUEST_CODE = 41

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        }

    private val PERMISSIONS = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /** Echte Schreibprobe: Erlaubnis allein heißt nicht, dass das Gerät das Schreiben zulässt. Liefert die Fehlermeldung oder null. */
    fun writeProblem(dir: java.io.File): String? = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return "Ordner ${dir.absolutePath} lässt sich nicht anlegen"
        val probe = java.io.File(dir, ".diagnose_probe")
        probe.writeText("ok")
        probe.delete()
        null
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    /** Ersatzordner, in den die App immer schreiben darf. */
    fun fallbackDir(context: Context): java.io.File =
        context.getExternalFilesDir("Uebergabe") ?: java.io.File(context.filesDir, "Uebergabe")

    fun androidVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /** Ab Android 11 öffnet das die Systemseite "Zugriff auf alle Dateien". */
    fun request(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${activity.packageName}"),
            )
            runCatching { activity.startActivity(intent) }
                .onFailure { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            activity.requestPermissions(PERMISSIONS, REQUEST_CODE)
        }
    }
}
