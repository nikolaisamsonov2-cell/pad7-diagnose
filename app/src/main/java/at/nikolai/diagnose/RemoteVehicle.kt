package at.nikolai.diagnose

import android.content.Context

/**
 * Aktives Fahrzeug aus der Datei fahrzeug.txt im Repo. Claude trägt dort das Fahrzeug ein,
 * sobald Nikolai im Chat ein Foto vom Zulassungsschein schickt. Nur der Fahrzeugname, nie FIN oder Kennzeichen.
 */
object RemoteVehicle {
    private const val URL = "https://raw.githubusercontent.com/${Updater.REPO}/main/fahrzeug.txt"

    /** Übernimmt einen neuen Eintrag und gibt ihn zurück; null, wenn nichts Neues (oder nicht erreichbar). */
    fun sync(context: Context): String? {
        val text = runCatching { Updater.get("$URL?t=${System.currentTimeMillis() / 60_000}").decodeToString() }.getOrNull()
            ?: return null
        val name = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.take(60)
            ?: return null
        val prefs = Prefs(context)
        // Nur bei geänderter Datei umstellen, damit eine Auswahl von Hand nicht ständig überschrieben wird.
        if (name == prefs.lastRemoteVehicle) return null
        prefs.lastRemoteVehicle = name
        if (prefs.vehicles.none { it == name }) prefs.vehicles = prefs.vehicles + name
        if (prefs.vehicle == name) return null
        prefs.vehicle = name
        return name
    }
}
