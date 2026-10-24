package at.nikolai.diagnose

import android.app.Activity
import android.content.Intent
import android.provider.Settings

/** Status und Einschalten der Bedienungshilfe "Diagnose". */
object Access {
    fun isEnabled(activity: Activity): Boolean {
        if (DiagnoseService.instance != null) return true
        val enabled = Settings.Secure.getString(
            activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.contains("${activity.packageName}/") && enabled.contains("DiagnoseService")
    }

    fun openSettings(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
