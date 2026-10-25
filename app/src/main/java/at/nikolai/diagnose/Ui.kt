package at.nikolai.diagnose

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Kleine Helfer für die Oberfläche, groß genug für Werkstatt-Finger. */
object Ui {
    fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun column(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(context, 16)
        setPadding(p, p, p, p)
    }

    fun title(context: Context, text: String) = TextView(context).apply {
        this.text = text
        textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(context, 8))
    }

    fun text(context: Context, text: String, size: Float = 17f) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextIsSelectable(true)
    }

    fun button(context: Context, label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        minHeight = dp(context, 56)
        // Jeder Druck spürbar: kurz vibrieren und kurz abdunkeln.
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            alpha = 0.5f
            animate().alpha(1f).setDuration(400).start()
            onClick()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(context, 6) }
    }

    fun ampelColor(ampel: String?) = when (ampel) {
        "ROT" -> Color.parseColor("#C62828")
        "GELB" -> Color.parseColor("#F9A825")
        "GRÜN" -> Color.parseColor("#2E7D32")
        else -> Color.GRAY
    }
}
