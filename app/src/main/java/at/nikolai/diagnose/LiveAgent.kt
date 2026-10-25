package at.nikolai.diagnose

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAuto
import java.time.Duration

/**
 * Live-Modus "Claude steuert": Bei jedem Schritt gehen Screenshot und Elementliste der Launch-App an Claude,
 * Claude wählt den nächsten Tipp, die App führt ihn aus. Die Sperrliste prüft jeden Tipp hier im Code,
 * unabhängig davon, was Claude vorschlägt.
 */
class LiveAgent(
    private val service: DiagnoseService,
    private val apiKey: String,
    private val vehicle: String,
    private val goal: String,
    private val onStatus: (String) -> Unit,
    private val onDone: (ok: Boolean, msg: String) -> Unit,
) {
    private class Element(val id: Int, val label: String, val bounds: Rect, val node: AccessibilityNodeInfo)

    @Volatile private var cancelled = false
    private val log = mutableListOf<String>()
    private val captures = mutableListOf<ClaudeAnalyzer.Input>()
    private var inputTokens = 0L
    private var outputTokens = 0L

    val costUsd get() = inputTokens * 4.0 / 1_000_000 + outputTokens * 20.0 / 1_000_000

    fun start() = Thread({ runLoop() }, "live-agent").start()

    fun cancel() {
        cancelled = true
    }

    private fun runLoop() {
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofMinutes(2)).maxRetries(2).build()
        var errors = 0
        var finish: Pair<Boolean, String>? = null
        try {
            for (step in 1..MAX_STEPS) {
                if (cancelled) { finish = false to "Gestoppt."; break }
                status(step, "schaue auf den Bildschirm …")
                Thread.sleep(SETTLE_MS)
                val elements = elements()
                val shot = service.screenshotJpeg(MAX_SIDE)
                val action = try {
                    ask(client, step, elements, shot).also { errors = 0 }
                } catch (e: ClaudeAnalyzer.AnalysisException) {
                    if (++errors >= 3) { finish = false to (e.message ?: "Fehler"); break }
                    log += "$step. (Anfrage fehlgeschlagen: ${e.message})"
                    Thread.sleep(5000)
                    continue
                }
                if (cancelled) { finish = false to "Gestoppt."; break }
                val result = execute(step, action, elements, shot)
                log += "$step. ${action.summary} → $result"
                if (action.name == "done") { finish = true to (action.str("summary") ?: "Fertig."); break }
                if (action.name == "give_up") { finish = false to "Claude kommt nicht weiter: ${action.str("reason")}"; break }
            }
            if (finish == null) finish = false to "Höchstzahl von $MAX_STEPS Schritten erreicht."
        } catch (e: InterruptedException) {
            finish = false to "Gestoppt."
        } catch (e: Exception) {
            finish = false to "Unerwarteter Fehler: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            client.close()
        }
        val (ok, msg) = finish!!
        val cost = String.format(java.util.Locale.GERMANY, "%.2f", costUsd)
        val saved = deliverCaptures()
        onDone(ok, "$msg\n\n${log.size} Schritte, ${captures.size} Bildschirme erfasst, Kosten etwa $cost \$.$saved")
    }

    /** Alle erfassten Bildschirme gesammelt in einer Auswertung; zusätzlich Sicherung über Drive. */
    private fun deliverCaptures(): String {
        if (captures.isEmpty()) return ""
        val ctx = service.applicationContext
        val backup = runCatching { Handoff.write(ctx, captures) }
        val r = Analysis.run(ctx, captures + ClaudeAnalyzer.Input("Live_Ziel.txt", "Ziel: $goal\n\n${log.joinToString("\n")}".toByteArray()))
        return buildString {
            append(if (r.error == null) "\nAuswertung ist fertig (siehe Benachrichtigung)." else "\n⚠️ Auswertung: ${r.error}")
            backup.exceptionOrNull()?.let { append("\n⚠️ Sicherung: ${it.message}") }
        }
    }

    private fun status(step: Int, what: String) {
        val cost = String.format(java.util.Locale.GERMANY, "%.2f", costUsd)
        onStatus("🤖 Schritt $step/$MAX_STEPS · $cost \$ · $what")
    }

    // ---------- Bildschirm beschreiben ----------

    private fun elements(): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60 || out.size >= MAX_ELEMENTS) return
            if (!n.isVisibleToUser) return
            val label = (n.text ?: n.contentDescription)?.toString()?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
            if (label.isNotEmpty() || n.isClickable || n.isEditable || n.isScrollable) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0) out += Element(out.size + 1, label, r, n)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(service.rootInActiveWindow, 0)
        return out
    }

    private fun describe(elements: List<Element>): String {
        val (w, h) = service.displaySize()
        return elements.joinToString("\n") { e ->
            val n = e.node
            val flags = buildList {
                if (n.isClickable) add("klickbar")
                if (n.isEditable) add("Eingabe")
                if (n.isScrollable) add("scrollbar")
                if (n.isCheckable) add(if (n.isChecked) "angehakt" else "nicht angehakt")
            }.joinToString(",")
            val b = e.bounds
            "#${e.id} \"${e.label.take(80)}\" [${b.left * 1000 / w},${b.top * 1000 / h},${b.right * 1000 / w},${b.bottom * 1000 / h}]" +
                if (flags.isEmpty()) "" else " ($flags)"
        }
    }

    // ---------- Claude fragen ----------

    private class Action(val name: String, val input: Map<String, Any?>) {
        fun str(k: String) = input[k]?.toString()
        fun int(k: String) = (input[k] as? Number)?.toInt() ?: input[k]?.toString()?.toDoubleOrNull()?.toInt()
        val why get() = str("why") ?: str("label") ?: str("summary") ?: str("reason") ?: ""
        val summary get() = "$name(${input.filterKeys { it != "why" }.entries.joinToString { "${it.key}=${it.value}" }}) – $why"
    }

    private fun ask(client: com.anthropic.client.AnthropicClient, step: Int, elements: List<Element>, shot: ByteArray?): Action {
        val text = buildString {
            append("ZIEL: ").append(goal).append('\n')
            append("FAHRZEUG: ").append(vehicle).append('\n')
            append("SCHRITT: ").append(step).append(" von höchstens ").append(MAX_STEPS).append('\n')
            append("VORDERGRUND-APP: ").append(if (service.launchInFront()) "Launch-Diagnose" else "NICHT die Launch-App").append('\n')
            append("\nBISHERIGE SCHRITTE:\n").append(if (log.isEmpty()) "(keine)" else log.takeLast(30).joinToString("\n")).append('\n')
            append("\nELEMENTE AM BILDSCHIRM (Koordinaten 0–1000: links,oben,rechts,unten):\n")
            append(if (elements.isEmpty()) "(keine lesbaren Elemente – nutze den Screenshot und tap_xy)" else describe(elements))
            append("\n\nWähle genau eine nächste Aktion.")
        }
        val blocks = mutableListOf<ContentBlockParam>()
        if (shot != null) {
            blocks += ContentBlockParam.ofImage(
                Base64ImageSource.builder().data(Base64.encodeToString(shot, Base64.NO_WRAP))
                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()
            )
        }
        blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(text).build())

        val builder = MessageCreateParams.builder()
            .model(ClaudeAnalyzer.MODEL)
            .maxTokens(4000L)
            .system(SYSTEM)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .toolChoice(ToolChoiceAuto.builder().disableParallelToolUse(true).build())
            .addUserMessageOfContentBlockParams(blocks)
        TOOLS.forEach { builder.addTool(it) }

        val response = try {
            client.messages().create(builder.build())
        } catch (e: UnauthorizedException) {
            throw ClaudeAnalyzer.AnalysisException("API-Schlüssel ungültig. Bitte in den Einstellungen prüfen.")
        } catch (e: PermissionDeniedException) {
            throw ClaudeAnalyzer.AnalysisException("Der API-Schlüssel hat keine Berechtigung (Guthaben prüfen).")
        } catch (e: RateLimitException) {
            throw ClaudeAnalyzer.AnalysisException("Zu viele Anfragen, kurz warten.")
        } catch (e: AnthropicServiceException) {
            val m = e.message.orEmpty()
            throw ClaudeAnalyzer.AnalysisException(
                if (m.contains("credit", true)) "Kein Guthaben mehr auf dem Anthropic-Konto." else "Claude-Fehler ${e.statusCode()}: $m"
            )
        } catch (e: AnthropicIoException) {
            throw ClaudeAnalyzer.AnalysisException("Keine Internetverbindung zu Claude.")
        }
        response.usage().let { inputTokens += it.inputTokens(); outputTokens += it.outputTokens() }
        val use = response.content().firstNotNullOfOrNull { it.toolUse().orElse(null) }
            ?: return Action("wait", mapOf("seconds" to 3, "why" to "keine Aktion gewählt"))
        @Suppress("UNCHECKED_CAST")
        val input = runCatching { use._input().convert(Map::class.java) as Map<String, Any?> }.getOrDefault(emptyMap())
        return Action(use.name(), input)
    }

    // ---------- Aktion ausführen ----------

    private fun execute(step: Int, a: Action, elements: List<Element>, shot: ByteArray?): String {
        status(step, a.why.ifBlank { a.name })
        return when (a.name) {
            "tap" -> {
                val e = elements.firstOrNull { it.id == a.int("id") } ?: return "Element gibt es nicht"
                blockedNear(e.node, e.label)?.let { return "GESPERRT (enthält „$it“), nicht getippt" }
                click(e.node, e.bounds)
                "getippt"
            }
            "tap_xy" -> {
                val (w, h) = service.displaySize()
                val x = (a.int("x") ?: return "x fehlt").coerceIn(0, 1000) * w / 1000
                val y = (a.int("y") ?: return "y fehlt").coerceIn(0, 1000) * h / 1000
                elements.filter { it.bounds.contains(x, y) }.forEach { e ->
                    blockedNear(e.node, e.label)?.let { return "GESPERRT (an der Stelle steht „$it“), nicht getippt" }
                }
                gestureTap(x.toFloat(), y.toFloat())
                "getippt"
            }
            "scroll" -> if (scroll(a.str("direction") == "down")) "gescrollt" else "nichts zum Scrollen"
            "back" -> { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); "zurück" }
            "open_launch" -> { service.openLaunchNow(); Thread.sleep(3000); "Launch geöffnet" }
            "type_text" -> {
                val txt = a.str("text").orEmpty()
                Blocklist.blocked(txt)?.let { return "GESPERRT (Text enthält „$it“)" }
                val e = elements.firstOrNull { it.id == a.int("id") && it.node.isEditable } ?: return "kein Eingabefeld"
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, txt) }
                if (e.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "eingetippt" else "Eingabe fehlgeschlagen"
            }
            "capture" -> {
                val stamp = Handoff.stamp()
                val label = a.str("label").orEmpty().replace(Regex("[^A-Za-z0-9ÄÖÜäöüß_-]+"), "_").take(40)
                val name = "${ClaudeAnalyzer.SCREEN_PREFIX}${stamp}_$label"
                captures += ClaudeAnalyzer.Input("$name.txt", ("Erfasst: ${a.str("label")}\n\n" + service.screenText()).toByteArray())
                if (shot != null) captures += ClaudeAnalyzer.Input("$name.jpg", shot)
                service.vibrateShort()
                "erfasst (${captures.count { it.fileName.endsWith(".txt") }})"
            }
            "wait" -> { Thread.sleep(((a.int("seconds") ?: 5).coerceIn(1, 60)) * 1000L); "gewartet" }
            "done", "give_up" -> "Ende"
            else -> "unbekannte Aktion"
        }
    }

    /** Sperrliste auf das Element, seine Kinder und den klickbaren Rahmen darum. */
    private fun blockedNear(n: AccessibilityNodeInfo, label: String): String? {
        Blocklist.blocked(label)?.let { return it }
        var hit: String? = null
        fun walk(x: AccessibilityNodeInfo?, d: Int) {
            if (x == null || d > 6 || hit != null) return
            val l = (x.text ?: x.contentDescription)?.toString().orEmpty()
            if (l.isNotEmpty()) hit = Blocklist.blocked(l)
            for (i in 0 until x.childCount) walk(x.getChild(i), d + 1)
        }
        walk(n, 0)
        if (hit != null) return hit
        var p = n.parent
        var depth = 0
        while (p != null && depth < 3) {
            if (p.isClickable) { walk(p, 0); return hit }
            p = p.parent
            depth++
        }
        return null
    }

    private fun click(n: AccessibilityNodeInfo, own: Rect) {
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
        gestureTap(own.exactCenterX(), own.exactCenterY())
    }

    private fun gestureTap(x: Float, y: Float) {
        service.withOverlaysHidden {
            val path = Path().apply { moveTo(x, y) }
            service.dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
        }
    }

    private fun scroll(down: Boolean): Boolean {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 60) return
            if (n.isScrollable && n.isVisibleToUser) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val area = r.width().toLong() * r.height()
                if (area > bestArea) { best = n; bestArea = area }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(service.rootInActiveWindow, 0)
        val target = best
        if (target != null && target.performAction(
                if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        // Ohne scrollbares Element: wischen in der Bildschirmmitte.
        val (w, h) = service.displaySize()
        val path = Path().apply {
            moveTo(w / 2f, if (down) h * 0.7f else h * 0.3f)
            lineTo(w / 2f, if (down) h * 0.3f else h * 0.7f)
        }
        service.withOverlaysHidden {
            service.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 400)).build(), null, null)
        }
        return true
    }

    companion object {
        const val MAX_STEPS = 60
        private const val MAX_ELEMENTS = 150
        private const val MAX_SIDE = 1280
        private const val SETTLE_MS = 2000L

        val PRESETS = listOf(
            "Komplett-Check" to "Falls noch kein Ergebnis eines Systemscans angezeigt wird: in der Launch-App die Ford-Diagnose " +
                "öffnen, Auto-Suche / automatische Fahrzeugerkennung starten, Fahrzeug bestätigen und den Systemscan aller " +
                "Steuergeräte durchführen; auf das Ergebnis warten. Dann jedes Steuergerät mit Fehlern (rot markiert oder mit " +
                "Fehleranzahl) öffnen, „Fehlercode lesen“ wählen und jeden Fehlercode-Bildschirm mit capture erfassen (auch " +
                "Details / Freeze-Frame, falls vorhanden; bei langen Listen scrollen und jede Seite erfassen). Zurück zur Liste, " +
                "nächstes Steuergerät. Zum Schluss den Ergebnis-Bildschirm des Systemscans erfassen.",
            "Hybrid-Werte (Garantie)" to "Für den Garantie-Nachweis der Hybridkomponenten: im Steuergerät BECM (Batterie-" +
                "Energiesteuermodul) zuerst Steuergeräte-Info (Softwarestand, Teilenummern) erfassen, dann Datenstrom / Live-Daten " +
                "öffnen und alle Werte zu Ladezustand, Gesundheitszustand (SOH), Zellspannungen (min/max/Differenz), " +
                "Temperaturen und Isolationswiderstand erfassen; durch die Liste scrollen und jede Seite erfassen. Danach dasselbe " +
                "kurz für SOBDMC und DCDC (Steuergeräte-Info, Fehlercodes, Datenstrom-Hauptwerte). Nur lesen.",
        )

        private val SYSTEM = """
            Du bedienst die Launch X-431 PAD VII Diagnose-App auf einem Android-Tablet für den Kfz-Techniker Nikolai.
            Du bekommst bei jedem Schritt einen Screenshot, eine Liste der Bildschirmelemente mit Nummern und Koordinaten
            (0–1000) und das Protokoll der bisherigen Schritte. Du wählst genau EINE nächste Aktion über ein Werkzeug.

            Regeln:
            - Nur LESEN: Fehlercodes lesen, Datenstrom/Live-Daten ansehen, Steuergeräte-Info, Berichte.
            - NIEMALS: Fehler löschen, Codierung, Programmierung, Anpassung, Anlernen, Stellgliedtests/Aktortests,
              Sonderfunktionen, Service-Rückstellungen, Software-Updates. Solche Knöpfe sind gesperrt; die App verweigert sie.
            - Dialoge, die nur informieren oder fragen, ob ein Lesevorgang fortgesetzt werden soll, mit OK/Ja bestätigen.
              Dialoge, die etwas verändern würden, mit Abbrechen/Nein schließen.
            - Bevorzuge tap mit Elementnummer; tap_xy nur, wenn das Ziel nicht in der Liste steht (z. B. gezeichnete Kästchen).
            - Wenn der Bildschirm lädt (Fortschrittsbalken, „bitte warten“, Verbindungsaufbau), nimm wait.
            - Jeden Bildschirm mit Ergebnissen (Fehlercodes, Messwerte, Steuergeräte-Info, Scan-Ergebnis) mit capture erfassen,
              bevor du weitergehst. Denselben Bildschirm nicht doppelt erfassen.
            - Wenn eine Aktion zweimal nichts bewirkt hat, versuche einen anderen Weg. Drehe dich nicht im Kreis.
            - Wenn das Ziel erreicht ist: done mit kurzer deutscher Zusammenfassung (gefundene Codes nennen).
              Wenn es nicht weitergeht (z. B. keine Verbindung zum Fahrzeug, Zündung aus): give_up mit Grund.
            - "why" kurz auf Deutsch, höchstens 8 Wörter; es wird Nikolai als Statuszeile angezeigt.
        """.trimIndent()

        private fun tool(name: String, description: String, props: Map<String, Map<String, Any>>): Tool {
            val properties = Tool.InputSchema.Properties.builder().apply {
                props.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
            }.build()
            return Tool.builder()
                .name(name)
                .description(description)
                .strict(true)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(properties)
                        .required(props.keys.toList())
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build()
                )
                .build()
        }

        private val WHY = mapOf("type" to "string", "description" to "Kurzer Grund auf Deutsch (Statuszeile)")
        private val INT = mapOf("type" to "integer")

        private val TOOLS = listOf(
            tool("tap", "Element mit dieser Nummer aus der Elementliste antippen.", mapOf("id" to INT, "why" to WHY)),
            tool("tap_xy", "Stelle im Screenshot antippen, Koordinaten 0–1000 (x von links, y von oben).",
                mapOf("x" to INT, "y" to INT, "why" to WHY)),
            tool("scroll", "Liste scrollen.", mapOf("direction" to mapOf("type" to "string", "enum" to listOf("down", "up")), "why" to WHY)),
            tool("back", "Zurück-Taste von Android.", mapOf("why" to WHY)),
            tool("open_launch", "Launch-Diagnose-App wieder in den Vordergrund holen.", mapOf("why" to WHY)),
            tool("type_text", "Text in ein Eingabefeld (Elementnummer) schreiben.",
                mapOf("id" to INT, "text" to mapOf("type" to "string"), "why" to WHY)),
            tool("capture", "Aktuellen Bildschirm als Beleg erfassen (Text + Screenshot) für den Bericht.",
                mapOf("label" to mapOf("type" to "string", "description" to "Was zu sehen ist, z. B. 'GSM Fehlercodes'"))),
            tool("wait", "Warten, z. B. während die App lädt oder sich verbindet.",
                mapOf("seconds" to mapOf("type" to "integer", "description" to "1 bis 60"), "why" to WHY)),
            tool("done", "Ziel erreicht.", mapOf("summary" to mapOf("type" to "string"))),
            tool("give_up", "Es geht nicht weiter.", mapOf("reason" to mapOf("type" to "string"))),
        )
    }
}
