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

    /** Alle bekannten Fahrzeuge, eins pro Zeile. Das aktive steht in [vehicle]. */
    var vehicles: List<String>
        get() = (sp.getString("vehicles", null) ?: DEFAULT_VEHICLE).lines().map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = sp.edit().putString("vehicles", value.joinToString("\n")).apply()

    /** Ohne API-Schlüssel landen Dateien hier; DriveSync lädt diesen Ordner nach Drive "Diagnose" hoch. */
    var handoffFolder: String
        get() = sp.getString("handoff_folder", defaultHandoff()) ?: defaultHandoff()
        set(value) = sp.edit().putString("handoff_folder", value.trim()).apply()

    /** Fehlercode-Bildschirme der Launch-App automatisch erfassen. */
    var autoCapture: Boolean
        get() = sp.getBoolean("auto_capture", true)
        set(value) = sp.edit().putBoolean("auto_capture", value).apply()

    /** Nach dem Systemscan die Steuergeräte mit Fehlern selbst öffnen und Codes lesen. */
    var readCodes: Boolean
        get() = sp.getBoolean("read_codes", true)
        set(value) = sp.edit().putBoolean("read_codes", value).apply()

    /** Paketname der Launch-App, wird beim ersten Öffnen erkannt. */
    var launchPackage: String
        get() = sp.getString("launch_package", "") ?: ""
        set(value) = sp.edit().putString("launch_package", value).apply()

    /** Zuletzt automatisch erfasste Fehlercodes und Zeitpunkt, um Doppelte zu vermeiden. */
    var lastAutoCodes: String
        get() = sp.getString("last_auto_codes", "") ?: ""
        set(value) = sp.edit().putString("last_auto_codes", value).putLong("last_auto_time", System.currentTimeMillis()).apply()

    val lastAutoTime: Long get() = sp.getLong("last_auto_time", 0L)

    /** Laufende Prüfung (Garantie-/Gebrauchtwagen-Check). */
    var activeSession: String
        get() = sp.getString("active_session", "") ?: ""
        set(value) = sp.edit().putString("active_session", value).apply()

    /** Prüfpunkt, dem der nächste 🔍-Druck zugeordnet wird. */
    var captureTarget: String
        get() = sp.getString("capture_target", "") ?: ""
        set(value) = sp.edit().putString("capture_target", value).apply()

    /** Zuletzt aus fahrzeug.txt übernommenes Fahrzeug. */
    var lastRemoteVehicle: String
        get() = sp.getString("last_remote_vehicle", "") ?: ""
        set(value) = sp.edit().putString("last_remote_vehicle", value).apply()

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

        fun defaultHandoff(): String {
            val root = Environment.getExternalStorageDirectory().absolutePath
            return "$root/CNLAUNCH/X431PADVII/images"
        }

        /** Sieht der Paketname nach der Launch-Diagnose-App aus? */
        fun looksLikeLaunch(pkg: String): Boolean {
            val p = pkg.lowercase()
            return "cnlaunch" in p || "x431" in p || p.startsWith("com.launch")
        }
    }
}
