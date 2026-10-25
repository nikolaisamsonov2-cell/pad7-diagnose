package at.nikolai.diagnose

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Bedienungshilfe "Diagnose":
 *  - Schwebeknopf über der Launch-App: Bildschirm lesen + Screenshot → Auswertung.
 *  - Fehlercode-Bildschirme automatisch erfassen.
 *  - Messfahrt-Vorlagen aufnehmen und abspielen.
 */
class DiagnoseService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private var wm: WindowManager? = null

    private var bubble: View? = null
    private var panel: TextView? = null
    private var panelWrap: View? = null

    private var foreground = ""
    private var recording: Recording? = null
    var player: TemplatePlayer? = null
        private set

    private class Recording(val name: String, val vehicle: String, val scanAfter: Boolean, val captureEnd: Boolean) {
        val steps = mutableListOf<Template.Step>()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        wm = getSystemService(WindowManager::class.java)
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        instance = this
        showBubble()
        updateBubbleVisibility()
    }

    override fun onDestroy() {
        instance = null
        player?.cancel()
        removeView(bubble)
        removeView(panelWrap)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Systemleiste, Tastatur und unsere eigenen Fenster nicht als Vordergrund-App zählen.
                if (pkg != packageName && pkg != "com.android.systemui" && !pkg.contains("inputmethod")) {
                    foreground = pkg
                    if (prefs.launchPackage.isEmpty() && Prefs.looksLikeLaunch(pkg)) prefs.launchPackage = pkg
                    updateBubbleVisibility()
                }
                if (isLaunch(pkg)) scheduleAutoCheck()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (isLaunch(pkg)) {
                scheduleAutoCheck()
                if (recording?.captureEnd == true) {
                    main.removeCallbacks(recordingEndCheck)
                    main.postDelayed(recordingEndCheck, 1500)
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (isLaunch(pkg)) recordClick(event)
        }
        player?.onEvent()
    }

    fun isLaunch(pkg: String) = pkg.isNotEmpty() && (pkg == prefs.launchPackage || Prefs.looksLikeLaunch(pkg))

    // ---------- Bildschirm erfassen ----------

    fun captureNow(auto: Boolean) {
        val text = ScreenReader.format(ScreenReader.collect(rootInActiveWindow))
        val stamp = Handoff.stamp()
        val inputs = mutableListOf(
            ClaudeAnalyzer.Input("${ClaudeAnalyzer.SCREEN_PREFIX}$stamp.txt", text.toByteArray()),
        )
        // Während einer Prüfung wird jeder Bildschirm als Beleg zum gewählten Punkt gespeichert statt einzeln ausgewertet.
        val session = ProgramSession.active(this)
        val itemId = session?.let { s ->
            prefs.captureTarget.takeIf { t -> s.program?.items?.any { it.id == t } == true } ?: if (auto) "dtc" else null
        }
        val itemTitle = session?.program?.items?.firstOrNull { it.id == itemId }?.title
        val deliver: (List<ClaudeAnalyzer.Input>) -> Unit = if (session != null && itemId != null) {
            { list ->
                list.forEach { session.addFile(itemId, it.fileName, it.bytes) }
                toast("Beleg gespeichert: $itemTitle (${session.files(itemId).count { it.name.endsWith(".txt") }})")
            }
        } else {
            { list ->
                val r = Analysis.run(applicationContext, list)
                if (r.error != null) toast("⚠️ Nicht gespeichert: ${r.error}")
            }
        }
        if (session != null && itemId == null) {
            toast("Prüfung läuft: bitte in der Diagnose-App zuerst einen Prüfpunkt antippen")
            return
        }
        if (session == null) {
            toast(when {
                prefs.apiKey.isBlank() -> "✓ Gespeichert – geht über Drive an Claude"
                auto -> "✓ Fehlercodes erkannt – Auswertung kommt als Benachrichtigung"
                else -> "✓ Erfasst – Auswertung kommt als Benachrichtigung"
            })
        }
        if (!auto) flashBubble()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bubble?.visibility = View.INVISIBLE
            main.postDelayed({
                takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        main.post { updateBubbleVisibility() }
                        val jpeg = runCatching { toJpeg(result) }.getOrNull()
                        if (jpeg != null) inputs += ClaudeAnalyzer.Input("${ClaudeAnalyzer.SCREEN_PREFIX}$stamp.jpg", jpeg)
                        deliver(inputs)
                    }

                    override fun onFailure(errorCode: Int) {
                        main.post { updateBubbleVisibility() }
                        deliver(inputs)
                    }
                })
            }, 150)
        } else {
            worker.execute { deliver(inputs) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun toJpeg(result: AccessibilityService.ScreenshotResult): ByteArray {
        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
            ?: error("Screenshot leer")
        val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
        result.hardwareBuffer.close()
        val scale = minOf(1f, 2000f / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private val autoCheck = Runnable {
        if (!prefs.autoCapture || recording != null || player != null || faultReader != null) return@Runnable
        if (!isLaunch(foreground)) return@Runnable
        val text = ScreenReader.format(ScreenReader.collect(rootInActiveWindow))
        val codes = ScreenReader.dtcCodes(text)
        if (codes.isEmpty()) return@Runnable
        val key = codes.sorted().joinToString(",")
        val recent = System.currentTimeMillis() - prefs.lastAutoTime < 6 * 3600_000L
        if (key == prefs.lastAutoCodes && recent) return@Runnable
        prefs.lastAutoCodes = key
        captureNow(auto = true)
    }

    /** Wartet, bis sich der Bildschirm 3 Sekunden lang nicht mehr ändert. */
    private fun scheduleAutoCheck() {
        main.removeCallbacks(autoCheck)
        main.postDelayed(autoCheck, 3000)
    }

    // ---------- Vorlagen aufnehmen ----------

    fun startRecording(name: String, vehicle: String, scanAfter: Boolean, captureEnd: Boolean) {
        recording = Recording(name, vehicle, scanAfter, captureEnd)
        val end = if (captureEnd) "der Scan läuft" else "die Aufzeichnung läuft"
        showPanel("● Aufnahme „$name“: tippe dich jetzt durch die Launch-App.\nTippe hier auf „Fertig“, sobald $end.",
            "Fertig") { stopRecording() }
        openLaunch()
    }

    private fun recordClick(event: AccessibilityEvent) {
        val rec = recording ?: return
        val node = event.source
        val label = labelOf(event, node)
        if (label.isEmpty()) {
            toast("Tipp ohne Beschriftung, wird übersprungen")
            return
        }
        Blocklist.blocked(label)?.let {
            toast("„$label“ ist gesperrt (enthält „$it“) und wird nicht aufgenommen")
            return
        }
        val checked = if (node?.isCheckable == true || event.isChecked) node?.isChecked ?: event.isChecked else null
        rec.steps += Template.Step(label, checked, node?.viewIdResourceName)
        panel?.text = "● Aufnahme „${rec.name}“: ${rec.steps.size} Schritte\nLetzter: $label"
    }

    private fun labelOf(event: AccessibilityEvent, node: AccessibilityNodeInfo?): String {
        val direct = event.text.joinToString(" ") { it.toString() }.trim()
        if (direct.isNotEmpty()) return direct
        node?.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Klickbarer Container ohne eigenen Text: Text der Kinder nehmen.
        return node?.let { ScreenReader.collect(it).joinToString(" ") { i -> i.text.removePrefix("[x] ").removePrefix("[ ] ") } }
            ?.trim()?.take(80).orEmpty()
    }

    private fun stopRecording() {
        val rec = recording ?: return
        recording = null
        hidePanel()
        if (rec.steps.isEmpty()) {
            toast("Keine Schritte aufgenommen")
            return
        }
        val t = Template(System.currentTimeMillis().toString(), rec.name, rec.vehicle, rec.steps, rec.scanAfter, rec.captureEnd, Template.FORMAT)
        TemplateStore(this).save(t)
        // Schon beim Vormachen das Ergebnis mitnehmen.
        if (t.captureEnd) waitForResult(t)
        toast("Vorlage „${rec.name}“ mit ${rec.steps.size} Schritten gespeichert")
    }

    // ---------- Vorlagen abspielen ----------

    fun play(t: Template) {
        if (player != null) {
            toast("Es läuft schon ein Ablauf")
            return
        }
        showPanel("▶ „${t.name}“ startet …", "Stopp") { player?.cancel() }
        player = TemplatePlayer(this, t, main,
            onProgress = { msg -> panel?.text = "▶ ${t.name}\n$msg" },
            onDone = { ok, msg ->
                player = null
                hidePanel()
                Analysis.notifyStatus(this, if (ok) "✅ ${t.name}" else "⚠️ ${t.name} angehalten", msg)
                if (ok && t.scanAfter) ScanWorker.runNow(this)
                // Systemscan dauert Minuten: warten, bis die Ergebnisseite steht, dann erfassen.
                if (ok && t.captureEnd) waitForResult(t)
            })
        openLaunch()
        player?.start()
    }

    private var waiting: Runnable? = null

    /**
     * Nach dem letzten Tipp läuft der Scan noch. Alle 2 s den Bildschirm lesen; erfasst wird, sobald er
     * 12 s unverändert ist und kein „bitte warten“/„scannen“ mehr zeigt. Spätestens nach 15 min.
     */
    private fun waitForResult(t: Template) {
        val started = System.currentTimeMillis()
        var last = ""
        var stableSince = started
        showPanel("⏳ ${t.name}: Scan läuft, ich warte auf das Ergebnis …", "Jetzt erfassen") {
            waiting?.let { main.removeCallbacks(it) }
            waiting = null
            hidePanel()
            captureNow(auto = false)
        }
        val tick = object : Runnable {
            override fun run() {
                val text = ScreenReader.format(ScreenReader.collect(rootInActiveWindow))
                val now = System.currentTimeMillis()
                if (text != last) { last = text; stableSince = now }
                val busy = FaultReader.BUSY.containsMatchIn(text)
                val secs = (now - started) / 1000
                panel?.text = "⏳ ${t.name}: Scan läuft seit ${secs / 60}:${"%02d".format(secs % 60)} …" +
                    if (busy) "" else "\nBildschirm ruhig seit ${(now - stableSince) / 1000} s"
                if ((!busy && now - stableSince >= 12_000) || now - started > 15 * 60_000) {
                    waiting = null
                    hidePanel()
                    captureNow(auto = false)
                    if (prefs.readCodes) main.postDelayed({ readFaults() }, 3000)
                } else {
                    main.postDelayed(this, 2000)
                }
            }
        }
        waiting = tick
        main.postDelayed(tick, 4000)
    }

    private var faultReader: FaultReader? = null

    fun screenText() = ScreenReader.format(ScreenReader.collect(rootInActiveWindow))

    /** Scan-Ergebnis der Launch-App (Topologie oder Systemliste mit Bericht-Knopf)? */
    fun isResultScreen(text: String) = RESULT.containsMatchIn(text) && REPORT_BUTTON.containsMatchIn(text)

    /**
     * Ein Knopf: Steht Launch schon auf einem Scan-Ergebnis, sofort die Fehlercodes lesen.
     * Sonst die Vorlage abspielen bzw. einmal vormachen lassen.
     */
    fun startCheck(t: Template?, vehicle: String) {
        if (player != null || faultReader != null || recording != null) {
            toast("Es läuft schon ein Ablauf")
            return
        }
        openLaunch()
        main.postDelayed({
            val onLaunch = isLaunch(rootInActiveWindow?.packageName?.toString().orEmpty())
            when {
                onLaunch && isResultScreen(screenText()) -> {
                    captureNow(auto = false)
                    main.postDelayed({ readFaults() }, 3000)
                }
                t != null -> play(t)
                else -> startRecording("Komplett-Check $vehicle", vehicle, scanAfter = false, captureEnd = true)
            }
        }, 3000)
    }

    /** Beim Vormachen: Aufnahme endet von selbst, sobald das Scan-Ergebnis da ist. */
    private val recordingEndCheck = Runnable {
        val rec = recording ?: return@Runnable
        if (rec.captureEnd && rec.steps.isNotEmpty() && isResultScreen(screenText())) {
            toast("Scan-Ergebnis erkannt – ab hier mache ich weiter")
            stopRecording()
        }
    }

    /** Rote Steuergeräte der Ergebnisliste nacheinander öffnen und ihre Fehlercodes erfassen. */
    fun readFaults() {
        if (faultReader != null || player != null) return
        showPanel("🔎 Suche Steuergeräte mit Fehlern …", "Stopp") { faultReader?.cancel() }
        val reader = FaultReader(this, main,
            capture = { captureNow(auto = false) },
            onProgress = { msg -> panel?.text = "🔎 $msg" },
            onDone = { ok, msg ->
                faultReader = null
                hidePanel()
                Analysis.notifyStatus(this, if (ok) "✅ Komplett-Check fertig" else "⚠️ Fehlercodes lesen angehalten", msg)
                toast(msg)
            })
        faultReader = reader
        val byText = FaultReader.detect(screenText())
        if (byText.isNotEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            reader.start(byText)
            return
        }
        // Topologie: Zahlen-Kreise sind oft nur gezeichnet. Dann rote Kästchen am Screenshot erkennen.
        panelWrap?.visibility = View.INVISIBLE
        bubble?.visibility = View.INVISIBLE
        main.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val found = runCatching { redModules(result) }.getOrDefault(emptyList())
                    main.post { panelWrap?.visibility = View.VISIBLE; updateBubbleVisibility(); reader.start(found) }
                }

                override fun onFailure(errorCode: Int) {
                    main.post { panelWrap?.visibility = View.VISIBLE; updateBubbleVisibility(); reader.start(emptyList()) }
                }
            })
        }, 200)
    }

    /** Steuergeräte-Kästchen, deren Hintergrund rot ist (Topologie: rot = Anomal). */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun redModules(result: AccessibilityService.ScreenshotResult): List<FaultReader.Module> {
        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace) ?: return emptyList()
        val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
        result.hardwareBuffer.close()
        val out = LinkedHashMap<String, FaultReader.Module>()
        for (item in ScreenReader.collect(rootInActiveWindow)) {
            val name = item.text
            if (!MODULE_NAME.matches(name) || name == "OBD" || name.startsWith("HS_")) continue
            val r = item.bounds
            var red = 0
            var total = 0
            for (fx in listOf(0.06f, 0.15f, 0.85f, 0.94f)) for (fy in listOf(0.25f, 0.75f)) {
                val x = (r.left + r.width() * fx).toInt()
                val y = (r.top + r.height() * fy).toInt()
                if (x !in 0 until bmp.width || y !in 0 until bmp.height) continue
                val c = bmp.getPixel(x, y)
                total++
                if (android.graphics.Color.red(c) > 150 && android.graphics.Color.green(c) < 110 && android.graphics.Color.blue(c) < 110) red++
            }
            if (total > 0 && red * 2 >= total) out[name] = FaultReader.Module(name, 1)
        }
        return out.values.toList()
    }

    fun openLaunch() {
        val pkg = prefs.launchPackage
        val intent = if (pkg.isNotEmpty()) packageManager.getLaunchIntentForPackage(pkg) else null
        if (intent == null) {
            toast("Launch-App bitte selbst öffnen")
            return
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // ---------- Schwebeknopf und Statusleiste ----------

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val size = Ui.dp(this, 64)
        val v = TextView(this).apply {
            text = "🔍"
            textSize = 28f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bubble)
            contentDescription = "Diagnose: Bildschirm auswerten"
        }
        val lp = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - Ui.dp(this@DiagnoseService, 16)
            y = resources.displayMetrics.heightPixels / 3
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 20) moved = true
                    if (moved) {
                        lp.x = startX + dx; lp.y = startY + dy
                        wm?.updateViewLayout(view, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) captureNow(auto = false)
            }
            true
        }
        runCatching { wm?.addView(v, lp) }.onSuccess { bubble = v }
    }

    private fun updateBubbleVisibility() {
        val show = isLaunch(foreground) && recording == null && player == null
        bubble?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showPanel(text: String, button: String, onButton: () -> Unit) {
        hidePanel()
        val p = Ui.dp(this, 12)
        val label = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val btn = TextView(this).apply {
            this.text = button
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFC62828.toInt())
            setPadding(p * 2, p, p * 2, p)
            setOnClickListener { onButton() }
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE6212121.toInt())
            setPadding(p, p, p, p)
            addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn)
        }
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.TOP }
        runCatching { wm?.addView(wrap, lp) }.onSuccess { panelWrap = wrap; panel = label }
        updateBubbleVisibility()
    }

    private fun hidePanel() {
        removeView(panelWrap)
        panelWrap = null
        panel = null
        updateBubbleVisibility()
    }

    private fun removeView(v: View?) {
        if (v != null) runCatching { wm?.removeView(v) }
    }


    private var captured = 0

    /** Sichtbare und spürbare Bestätigung: kurz vibrieren, Lupe wird grün mit Zähler. */
    private fun flashBubble() = main.post {
        val v = bubble as? TextView ?: return@post
        captured++
        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        v.background?.mutate()?.setTint(0xFF2E7D32.toInt())
        v.text = "✓$captured"
        v.textSize = 20f
        main.postDelayed({
            v.background?.setTintList(null)
            v.text = "🔍"
            v.textSize = 28f
        }, 1800)
    }

    fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    companion object {
        private val RESULT = Regex("system ?topolog|systemtopologie|systemliste|system list", RegexOption.IGNORE_CASE)
        private val REPORT_BUTTON = Regex("\\bbericht\\b|\\breport\\b", RegexOption.IGNORE_CASE)
        private val MODULE_NAME = Regex("^[A-Z][A-Z0-9]{1,9}$")

        @Volatile
        var instance: DiagnoseService? = null
            private set
    }
}
