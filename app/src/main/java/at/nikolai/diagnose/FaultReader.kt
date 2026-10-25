package at.nikolai.diagnose

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Nach dem Systemscan: alle Steuergeräte mit Fehlern nacheinander öffnen, Fehlercodes lesen,
 * Bildschirm erfassen, zurück zur Ergebnisliste. Am Ende den Bericht speichern, falls die Launch-App das anbietet.
 * Tippt nie etwas aus der Sperrliste (Löschen, Codierung, Anpassung …).
 */
class FaultReader(
    private val service: AccessibilityService,
    private val main: Handler,
    private val capture: () -> Unit,
    private val onProgress: (String) -> Unit,
    private val onDone: (ok: Boolean, message: String) -> Unit,
) {
    data class Module(val name: String, val count: Int)

    private enum class State { OPEN, WAIT_MENU, SETTLE, BACK, REPORT, REPORT_SAVE }

    private var modules = emptyList<Module>()
    private var index = 0
    private var state = State.OPEN
    private var since = 0L
    private var lastText = ""
    private var stableSince = 0L
    private var backs = 0
    private var finished = false
    private val done = mutableListOf<String>()
    private val tick = Runnable { step() }

    fun start(found: List<Module>) {
        modules = found
        if (modules.isEmpty()) {
            finish(false, "Keine Steuergeräte mit Fehlern erkannt. Falls doch welche rot sind: in der Launch-App auf " +
                "Listenansicht umschalten oder die Codes mit 🔍 erfassen.")
            return
        }
        onProgress("Fehler in: " + modules.joinToString { "${it.name} (${it.count})" })
        enter(State.OPEN)
        main.postDelayed(tick, 2000)
    }

    fun cancel() = finish(false, "Von Hand gestoppt. Erfasst: ${done.joinToString().ifEmpty { "noch nichts" }}.")

    private fun enter(s: State) {
        state = s
        since = System.currentTimeMillis()
        lastText = ""
        stableSince = since
    }

    private fun step() {
        if (finished) return
        val now = System.currentTimeMillis()
        val root = service.rootInActiveWindow
        val text = screenText()
        if (text != lastText) { lastText = text; stableSince = now }
        val waited = now - since

        when (state) {
            State.OPEN -> {
                if (index >= modules.size) { enter(State.REPORT); return next() }
                val m = modules[index]
                onProgress("${index + 1}/${modules.size}: ${m.name} öffnen …")
                val node = findExact(root, m.name)
                if (node != null && Blocklist.blocked(m.name) == null) {
                    click(node)
                    enter(State.WAIT_MENU)
                } else if (waited > 15_000) {
                    done += "${m.name} (nicht gefunden)"
                    index++
                    enter(State.OPEN)
                }
            }
            State.WAIT_MENU -> {
                val m = modules[index]
                onProgress("${index + 1}/${modules.size}: ${m.name} – Fehlercodes lesen …")
                val read = findMatching(root, READ)
                when {
                    ScreenReader.dtcCodes(text).isNotEmpty() || NO_FAULT.containsMatchIn(text) -> enter(State.SETTLE)
                    read != null && Blocklist.blocked(label(read)) == null -> { click(read); enter(State.SETTLE) }
                    waited > 90_000 -> enter(State.SETTLE)
                }
            }
            State.SETTLE -> {
                val busy = BUSY.containsMatchIn(text)
                if ((!busy && now - stableSince >= 3000 && waited >= 2500) || waited > 60_000) {
                    capture()
                    done += modules[index].name
                    backs = 0
                    main.postDelayed({ enter(State.BACK); next() }, 2500)
                    return
                }
            }
            State.BACK -> {
                // Zurück, bis die Ergebnisliste mit den Steuergeräten wieder da ist.
                // Der Name allein reicht nicht: Er steht oft auch als Überschrift auf dem Code-Bildschirm.
                val visible = modules.count { findExact(root, it.name) != null }
                // Codes sichtbar = Seitenfenster/Code-Bildschirm noch offen.
                val back = (if (modules.size >= 2) visible >= 2 else visible >= 1) && ScreenReader.dtcCodes(text).isEmpty()
                if (back && findMatching(root, READ) == null) {
                    index++
                    enter(State.OPEN)
                } else if (waited >= 2500) {
                    val confirm = findMatching(root, CONFIRM)?.takeIf { safeDialog(it) }
                    if (confirm != null) click(confirm) else service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    if (++backs > 6) return finish(false, "Zurück zur Ergebnisliste nicht gefunden. Erfasst: ${done.joinToString()}.")
                    enter(State.BACK)
                }
            }
            State.REPORT -> {
                val report = findMatching(root, REPORT)
                if (report != null && Blocklist.blocked(label(report)) == null) {
                    onProgress("Bericht speichern …")
                    click(report)
                    enter(State.REPORT_SAVE)
                } else {
                    return finish(true, "Fehlercodes erfasst: ${done.joinToString()}.")
                }
            }
            State.REPORT_SAVE -> {
                val save = findMatching(root, SAVE)?.takeIf { safeDialog(it) }
                if (save != null && waited >= 2000) {
                    click(save)
                    return finish(true, "Fehlercodes erfasst: ${done.joinToString()}. Bericht gespeichert.")
                }
                if (waited > 15_000) return finish(true, "Fehlercodes erfasst: ${done.joinToString()}. Bericht bitte selbst speichern.")
            }
        }
        next()
    }

    private fun next() {
        if (!finished) main.postDelayed(tick, 1000)
    }

    private fun finish(ok: Boolean, msg: String) {
        if (finished) return
        finished = true
        main.removeCallbacks(tick)
        onDone(ok, msg)
    }

    private fun screenText() = ScreenReader.format(ScreenReader.collect(service.rootInActiveWindow))

    /** Ja/OK nur tippen, wenn im umgebenden Dialog nichts aus der Sperrliste steht. */
    private fun safeDialog(n: AccessibilityNodeInfo): Boolean {
        var box: AccessibilityNodeInfo? = n
        repeat(3) { box = box?.parent ?: box }
        val texts = ScreenReader.collect(box).map { it.text }
        if (texts.size > 12) return false // kein kleiner Dialog, sondern ein ganzer Bildschirm
        return texts.none { Blocklist.blocked(it) != null }
    }

    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString().orEmpty()

    private fun findExact(root: AccessibilityNodeInfo?, name: String): AccessibilityNodeInfo? {
        var hit: AccessibilityNodeInfo? = null
        walk(root) { if (hit == null && it.isVisibleToUser && norm(label(it)) == norm(name)) hit = it }
        return hit
    }

    private fun findMatching(root: AccessibilityNodeInfo?, re: Regex): AccessibilityNodeInfo? {
        var hit: AccessibilityNodeInfo? = null
        walk(root) { if (hit == null && it.isVisibleToUser && re.matches(norm(label(it)))) hit = it }
        return hit
    }

    /**
     * Antippen: das Element selbst oder ein naher, kaum größerer klickbarer Rahmen; sonst ein echter Fingertipp
     * auf die Mitte (Topologie-Kästchen sind oft nicht einzeln klickbar).
     */
    private fun click(n: AccessibilityNodeInfo) {
        val own = Rect().also { n.getBoundsInScreen(it) }
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth <= 2) {
            if (cur.isClickable) {
                val b = Rect().also { cur!!.getBoundsInScreen(it) }
                val small = b.width().toLong() * b.height() <= 4L * maxOf(1, own.width()) * maxOf(1, own.height())
                if (small && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                break
            }
            cur = cur.parent
            depth++
        }
        val path = Path().apply { moveTo(own.exactCenterX(), own.exactCenterY()) }
        service.dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int = 0, f: (AccessibilityNodeInfo) -> Unit) {
        if (n == null || depth > 60) return
        f(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, f)
    }

    companion object {
        private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()

        private val READ = Regex("(fehlercodes? lesen|fehlerspeicher lesen|fehler lesen|dtc lesen|codes? lesen|read (fault |trouble )?codes?|read dtcs?|read fault)")
        private val NO_FAULT = Regex("kein(e)? fehler|no (fault|dtc|trouble)|keine fehlercodes|0 fehler", RegexOption.IGNORE_CASE)
        private val CONFIRM = Regex("(ja|yes|ok|beenden|verlassen|exit)")
        private val REPORT = Regex("(bericht|diagnosebericht|bericht speichern|report|save report|diagnostic report)")
        private val SAVE = Regex("(speichern|save|ok)")
        val BUSY = Regex("bitte warten|please wait|wird gescannt|scanning|wird gelesen|wird geladen|loading", RegexOption.IGNORE_CASE)
        private val FAULT = Regex("(fehler|fault|dtc|störung|codes?)\\D{0,4}(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
        private val NUMBER = Regex("^\\d{1,2}$")
        private val NOT_MODULE = Regex(
            "gesamt|total|gescannt|scanned|anzahl|fahrzeug|vehicle|vin|fin|km|version|software|system|steuergeräte|ecus|seite|page|zeit|time",
            RegexOption.IGNORE_CASE)

        /**
         * Steuergeräte mit Fehlern aus dem Ergebnis-Bildschirm. Liste: „GSM | Fehler: 2“;
         * Topologie: „GSM | 2 | IPMA | 1 | ABS“ (Zahl gehört zum Namen davor).
         */
        fun detect(text: String): List<Module> {
            val out = LinkedHashMap<String, Int>()
            for (line in text.lines()) {
                var lastName: String? = null
                for (raw in line.split(" | ")) {
                    val p = raw.removePrefix("[x] ").removePrefix("[ ] ").trim()
                    val count = if (NUMBER.matches(p)) p.toInt() else FAULT.find(p)?.groupValues?.get(2)?.toInt()
                    if (count != null) {
                        if (count >= 1 && lastName != null) out[lastName] = maxOf(out[lastName] ?: 0, count)
                        lastName = null
                    } else if (looksLikeName(p)) {
                        lastName = p
                    }
                }
            }
            return out.map { Module(it.key, it.value) }.take(15)
        }

        private fun looksLikeName(p: String) = p.length in 2..40 && p.any { it.isLetter() } &&
            !NOT_MODULE.containsMatchIn(p) && Blocklist.blocked(p) == null && ScreenReader.dtcCodes(p).isEmpty() &&
            !Regex("\\d{3,}").containsMatchIn(p)
    }
}
