package at.nikolai.diagnose

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Liest den Text eines Bildschirms in Lesereihenfolge (Zeilen von oben nach unten, links nach rechts). */
object ScreenReader {
    data class Item(val text: String, val bounds: Rect)

    private val DTC = Regex("\\b[PBCU][0-3][0-9A-F]{3}\\b")

    fun collect(root: AccessibilityNodeInfo?): List<Item> {
        val out = mutableListOf<Item>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            if (!n.isVisibleToUser) return
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val label = when {
                t.isNotEmpty() -> t
                d.isNotEmpty() -> d
                else -> ""
            }
            if (label.isNotEmpty()) {
                val r = Rect()
                n.getBoundsInScreen(r)
                val mark = if (n.isCheckable) (if (n.isChecked) "[x] " else "[ ] ") else ""
                out += Item(mark + label, r)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    /** Fasst Elemente mit ähnlicher Höhe zu einer Zeile zusammen, getrennt durch " | ". */
    fun format(items: List<Item>): String {
        if (items.isEmpty()) return ""
        val sorted = items.sortedWith(compareBy({ it.bounds.centerY() }, { it.bounds.left }))
        val rows = mutableListOf<MutableList<Item>>()
        for (it in sorted) {
            val row = rows.lastOrNull()
            val tol = maxOf(12, it.bounds.height() / 2)
            if (row != null && kotlin.math.abs(row.first().bounds.centerY() - it.bounds.centerY()) <= tol) row += it
            else rows += mutableListOf(it)
        }
        return rows.joinToString("\n") { r -> r.sortedBy { it.bounds.left }.joinToString(" | ") { it.text } }
    }

    fun dtcCodes(text: String): Set<String> = DTC.findAll(text).map { it.value }.toSet()
}
