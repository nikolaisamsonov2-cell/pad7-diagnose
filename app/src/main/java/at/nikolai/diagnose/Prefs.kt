package at.nikolai.diagnose

import android.content.Context
import android.os.Environment
import java.io.File

/** Einstellungen und Merkliste bereits verarbeiteter Dateien. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("diagnose", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(value) = sp.edit().putString("api_key", value.trim()).apply()

    /** Standardfahrzeug, falls der Bericht das Fahrzeug nicht nennt. */
    var vehicle: String
        get() = sp.getString("vehicle", DEFAULT_VEHICLE) ?: DEFAULT_VEHICLE
        set(value) = sp.edit().putString("vehicle", value.trim()).apply()

    /** Ordner, die auf neue Launch-Dateien überwacht werden, durch Komma getrennt. */
    var watchFolders: String
        get() = sp.getString("watch_folders", defaultFolders()) ?: defaultFolders()
        set(value) = sp.edit().putString("watch_folders", value.trim()).apply()

    var watchEnabled: Boolean
        get() = sp.getBoolean("watch_enabled", true)
        set(value) = sp.edit().putBoolean("watch_enabled", value).apply()

    /** Wurde der Bestand schon einmal erfasst? Beim ersten Lauf wird nichts Altes ausgewertet. */
    var baselineDone: Boolean
        get() = sp.getBoolean("baseline_done", false)
        set(value) = sp.edit().putBoolean("baseline_done", value).apply()

    var lastScan: Long
        get() = sp.getLong("last_scan", 0L)
        set(value) = sp.edit().putLong("last_scan", value).apply()

    fun folders(): List<File> =
        watchFolders.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { File(it) }

    fun isSeen(key: String): Boolean = sp.getStringSet("seen", emptySet())!!.contains(key)

    fun markSeen(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val set = HashSet(sp.getStringSet("seen", emptySet())!!)
        set.addAll(keys)
        sp.edit().putStringSet("seen", set).apply()
    }

    companion object {
        const val DEFAULT_VEHICLE = "Ford Kuga 2023 FHEV"

        fun defaultFolders(): String {
            val root = Environment.getExternalStorageDirectory().absolutePath
            return "$root/CNLAUNCH"
        }
    }
}
