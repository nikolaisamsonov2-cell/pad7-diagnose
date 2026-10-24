package at.nikolai.diagnose

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Spielt eine Vorlage Schritt für Schritt ab. Findet jede Schaltfläche über ihre Beschriftung,
 * scrollt notfalls, und hält an, statt zu raten, wenn etwas nicht auftaucht.
 */
class TemplatePlayer(
    private val service: AccessibilityService,
    private val template: Template,
    private val main: Handler,
    private val onProgress: (String) -> Unit,
    private val onDone: (ok: Boolean, message: String) -> Unit,
) {
    private var index = 0
    private var tries = 0
    private var scrolls = 0
    private var backs = 0
    private var cancelled = false
    private var finished = false

    private val tick = Runnable { attempt() }

    fun start() {
        onProgress("Launch-App wird geöffnet …")
        main.postDelayed(tick, 2500)
    }

    fun cancel() {
        if (finished) return
        cancelled = true
        main.removeCallbacks(tick)
        finish(false, "Von Hand gestoppt bei Schritt ${index + 1} von ${template.steps.size}.")
    }

    /** Wird bei jedem Bildschirm-Ereignis aufgerufen; aktuell reicht das Abfragen im Takt. */
    fun onEvent() {}

    private fun attempt() {
        if (cancelled || finished) return
        if (index >= template.steps.size) {
            finish(true, "Alle ${template.steps.size} Schritte erledigt.")
            return
        }
        val step = template.steps[index]
        onProgress("Schritt ${index + 1}/${template.steps.size}: ${step.label}")

        Blocklist.blocked(step.label)?.let {
            finish(false, "Schritt ${index + 1} „${step.label}“ ist gesperrt (enthält „$it“). Nichts ausgeführt.")
            return
        }

        val node = find(step)
        if (node != null) {
            act(step, node)
            index++
            tries = 0; scrolls = 0; backs = 0
            main.postDelayed(tick, STEP_PAUSE)
            return
        }

        tries++
        when {
            // Beim ersten Schritt steht die Launch-App vielleicht noch auf einer anderen Seite: zurück gehen.
            index == 0 && tries % 5 == 0 && backs < 4 -> {
                backs++
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            tries % 4 == 0 && scrolls < 8 -> {
                scrolls++
                scrollSomewhere(forward = scrolls <= 5)
            }
        }
        if (tries > MAX_TRIES) {
            finish(false, "Schritt ${index + 1} von ${template.steps.size}: „${step.label}“ nicht gefunden. " +
                "Bitte ab hier von Hand weitermachen und die Vorlage ggf. neu aufnehmen.")
            return
        }
        main.postDelayed(tick, RETRY_PAUSE)
    }

    private fun find(step: Template.Step): AccessibilityNodeInfo? {
        val roots = buildList {
            service.rootInActiveWindow?.let { add(it) }
            service.windows.mapNotNull { it.root }.forEach { if (it !in this) add(it) }
        }
        val want = norm(step.label)
        for (root in roots) {
            val byText = root.findAccessibilityNodeInfosByText(step.label).orEmpty().filter { it.isVisibleToUser }
            val exact = byText.filter { norm(it.text) == want || norm(it.contentDescription) == want }
            pick(exact, step)?.let { return it }
        }
        // Container ohne eigenen Text: Kindertexte zusammensetzen wie bei der Aufnahme.
        for (root in roots) {
            val hits = mutableListOf<AccessibilityNodeInfo>()
            walk(root) { n ->
                if (n.isClickable && n.isVisibleToUser && n.text.isNullOrBlank()) {
                    val joined = ScreenReader.collect(n).joinToString(" ") { it.text.removePrefix("[x] ").removePrefix("[ ] ") }
                    if (norm(joined.take(80)) == want) hits += n
                }
            }
            pick(hits, step)?.let { return it }
        }
        return null
    }

    private fun pick(list: List<AccessibilityNodeInfo>, step: Template.Step): AccessibilityNodeInfo? {
        if (list.isEmpty()) return null
        return step.viewId?.let { id -> list.firstOrNull { it.viewIdResourceName == id } } ?: list.first()
    }

    private fun act(step: Template.Step, node: AccessibilityNodeInfo) {
        if (step.checked != null) {
            val box = checkable(node)
            if (box != null && box.isChecked == step.checked) return // schon richtig angehakt
        }
        val target = clickable(node) ?: node
        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(target)
    }

    private fun clickable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = n
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun checkable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isCheckable) return n
        var found: AccessibilityNodeInfo? = null
        walk(n) { if (found == null && it.isCheckable) found = it }
        if (found != null) return found
        var p = n.parent
        var depth = 0
        while (p != null && depth < 4) {
            if (p.isCheckable) return p
            var inSibling: AccessibilityNodeInfo? = null
            walk(p) { if (inSibling == null && it.isCheckable) inSibling = it }
            if (inSibling != null) return inSibling
            p = p.parent
            depth++
        }
        return null
    }

    private fun tap(n: AccessibilityNodeInfo) {
        val r = Rect()
        n.getBoundsInScreen(r)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun scrollSomewhere(forward: Boolean) {
        val root = service.rootInActiveWindow ?: return
        var target: AccessibilityNodeInfo? = null
        walk(root) { if (target == null && it.isScrollable && it.isVisibleToUser) target = it }
        target?.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int = 0, f: (AccessibilityNodeInfo) -> Unit) {
        if (n == null || depth > 60) return
        f(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, f)
    }

    private fun norm(s: CharSequence?) = s?.toString()?.trim()?.replace(Regex("\\s+"), " ")?.lowercase().orEmpty()

    private fun finish(ok: Boolean, msg: String) {
        if (finished) return
        finished = true
        main.removeCallbacks(tick)
        onDone(ok, msg)
    }

    companion object {
        private const val STEP_PAUSE = 1800L
        private const val RETRY_PAUSE = 700L
        // ~90 s je Schritt: Auto-Suche (FIN lesen) und Verbindungsaufbau zum Steuergerät dauern oft länger als eine halbe Minute.
        private const val MAX_TRIES = 130
    }
}
