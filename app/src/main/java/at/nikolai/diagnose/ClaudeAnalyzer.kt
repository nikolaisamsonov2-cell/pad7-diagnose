package at.nikolai.diagnose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.Base64PdfSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.PlainTextSource
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import java.io.ByteArrayOutputStream
import java.time.Duration

/** Schickt einen Launch-Bericht an Claude und liefert die Auswertung als Text. */
class ClaudeAnalyzer(private val apiKey: String, private val vehicle: String) {

    class AnalysisException(message: String) : Exception(message)

    data class Input(val fileName: String, val bytes: ByteArray)

    fun analyze(input: Input): String = analyze(listOf(input))

    /** Mehrere Dateien gehören zu einem Befund, z. B. Bildschirmtext plus Screenshot vom Diagnose-Knopf. */
    fun analyze(inputs: List<Input>, history: List<ReportStore.Result> = emptyList()): String {
        if (apiKey.isBlank()) {
            throw AnalysisException("Kein API-Schlüssel eingetragen. Bitte unter Einstellungen eintragen.")
        }
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofMinutes(5))
            .maxRetries(3)
            .build()

        val blocks = inputs.map { buildFileBlock(it) }
        val instruction = buildString {
            append("Dateien: ").append(inputs.joinToString { it.fileName }).append('\n')
            blocks.mapNotNull { it.second }.forEach { append(it).append('\n') }
            if (inputs.any { it.fileName.startsWith(SCREEN_PREFIX) }) {
                append("Das ist ein Bildschirm der Launch-App, ausgelesen per Diagnose-Knopf ")
                append("(Bildschirmtext und, falls vorhanden, Screenshot desselben Moments).\n")
            }
            if (history.isNotEmpty()) {
                append("\nFrühere Auswertungen dieses Fahrzeugs (neueste zuerst) zum Vergleich:\n")
                val fmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY)
                history.forEach { h ->
                    append("--- ").append(fmt.format(java.util.Date(h.createdAt))).append(" ---\n")
                    append(h.text.take(4000)).append('\n')
                }
                append("Nenne im Abschnitt KURZ ausdrücklich, was sich seit der letzten Auswertung geändert hat ")
                append("(neue, verschwundene oder wiederkehrende Codes, Trends bei Messwerten).\n")
            }
            append("Werte das aus.")
        }

        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .system(systemPrompt(vehicle))
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.HIGH).build())
            // Bei einer Ablehnung wählt der Server selbst ein Ersatzmodell.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .addUserMessageOfContentBlockParams(
                blocks.map { it.first } + ContentBlockParam.ofText(TextBlockParam.builder().text(instruction).build())
            )
            .build()

        val response = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw AnalysisException("API-Schlüssel ungültig. Bitte in den Einstellungen prüfen.")
        } catch (e: PermissionDeniedException) {
            throw AnalysisException("Der API-Schlüssel hat keine Berechtigung (Guthaben in der Anthropic-Konsole prüfen).")
        } catch (e: RateLimitException) {
            throw AnalysisException("Zu viele Anfragen. Die Datei wird beim nächsten Durchlauf erneut versucht.")
        } catch (e: BadRequestException) {
            throw AnalysisException("Claude konnte die Datei nicht annehmen: ${e.message}")
        } catch (e: AnthropicServiceException) {
            throw AnalysisException("Claude-Fehler ${e.statusCode()}: ${e.message}")
        } catch (e: AnthropicIoException) {
            throw AnalysisException("Keine Internetverbindung zu Claude: ${e.message}")
        } finally {
            client.close()
        }

        val text = response.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .joinToString("\n").trim()
        val stop = response.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) {
            throw AnalysisException("Claude hat die Auswertung abgelehnt.")
        }
        if (text.isEmpty()) throw AnalysisException("Leere Antwort von Claude.")
        return if (stop == StopReason.MAX_TOKENS) "$text\n\n(Antwort wurde wegen Länge abgeschnitten.)" else text
    }

    private fun buildFileBlock(input: Input): Pair<ContentBlockParam, String?> {
        val name = input.fileName.lowercase()
        return when {
            name.endsWith(".pdf") -> {
                val doc = DocumentBlockParam.builder()
                    .source(Base64PdfSource.builder().data(b64(input.bytes)).build())
                    .title(input.fileName)
                    .build()
                ContentBlockParam.ofDocument(doc) to null
            }
            name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp") -> {
                val (bytes, type) = fitImage(input.bytes, name)
                val src = Base64ImageSource.builder().data(b64(bytes)).mediaType(type).build()
                ContentBlockParam.ofImage(src) to null
            }
            else -> {
                val raw = String(input.bytes, detectCharset(input.bytes))
                val (text, note) = fitCsv(raw)
                val doc = DocumentBlockParam.builder()
                    .source(PlainTextSource.builder().data(text).build())
                    .title(input.fileName)
                    .build()
                ContentBlockParam.ofDocument(doc) to note
            }
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Bilder über ~4 MB werden verkleinert, damit sie unter der API-Grenze von 5 MB bleiben. */
    private fun fitImage(bytes: ByteArray, name: String): Pair<ByteArray, Base64ImageSource.MediaType> {
        val type = when {
            name.endsWith(".png") -> Base64ImageSource.MediaType.IMAGE_PNG
            name.endsWith(".webp") -> Base64ImageSource.MediaType.IMAGE_WEBP
            else -> Base64ImageSource.MediaType.IMAGE_JPEG
        }
        if (bytes.size < 3_500_000) return bytes to type
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw AnalysisException("Bild konnte nicht gelesen werden.")
        val scale = minOf(1f, 2400f / maxOf(bmp.width, bmp.height))
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray() to Base64ImageSource.MediaType.IMAGE_JPEG
    }

    /**
     * Lange Datenstrom-Aufnahmen werden gleichmäßig ausgedünnt (Kopfzeile bleibt),
     * und Claude bekommt einen Hinweis darauf.
     */
    private fun fitCsv(raw: String): Pair<String, String?> {
        if (raw.length <= MAX_TEXT_CHARS) return raw to null
        val lines = raw.lines()
        val headerCount = minOf(5, lines.size)
        val header = lines.take(headerCount)
        val body = lines.drop(headerCount)
        val avg = (raw.length / lines.size.coerceAtLeast(1)).coerceAtLeast(1)
        val keep = (MAX_TEXT_CHARS / avg).coerceAtLeast(50)
        val step = (body.size + keep - 1) / keep
        val sampled = body.filterIndexed { i, _ -> i % step == 0 }
        val note = "Hinweis: Die Aufnahme hat ${body.size} Zeilen; gesendet wird jede $step. Zeile " +
            "(${sampled.size} Zeilen) plus Kopfzeilen. Kurze Spitzen zwischen den Stichproben können fehlen."
        return (header + sampled).joinToString("\n") to note
    }

    private fun detectCharset(bytes: ByteArray): java.nio.charset.Charset {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        return Charsets.UTF_8
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
        const val SCREEN_PREFIX = "Bildschirm_"
        private const val MAX_TEXT_CHARS = 600_000

        fun systemPrompt(vehicle: String) = """
            Du bist ein erfahrener Kfz-Diagnosetechniker und wertest Berichte eines Launch X-431 PAD VII aus
            (Fehlerspeicher-Scans als PDF oder Screenshot, Datenstrom-Aufnahmen als CSV).
            Der Nutzer ist Nikolai, er schraubt selbst an seinen Fahrzeugen und liest die Antwort am Tablet in der Werkstatt.
            Antworte auf Deutsch, knapp und praktisch.

            Fahrzeug: Nimm das Fahrzeug aus dem Bericht (Marke, Modell, Baujahr, Motor, VIN falls vorhanden).
            Wenn der Bericht es nicht nennt, gehe von diesem Fahrzeug aus: $vehicle.

            Kein Markdown mit Tabellen. Verwende genau diese Gliederung, jede Überschrift in einer eigenen Zeile:

            TITEL: <Fahrzeug> – <Befund in höchstens 8 Wörtern>
            AMPEL: GRÜN, GELB oder ROT (ROT = nicht weiterfahren oder sicherheitsrelevant)

            KURZ
            Zwei bis drei Sätze: Was ist los, wie dringend.

            FEHLERCODES
            Je Code eine Zeile: Code – Steuergerät – Bedeutung – Status (aktiv/gespeichert/sporadisch).
            Wenn keine Codes im Bericht sind, schreibe das.

            AUFFÄLLIGE WERTE
            Nur bei Datenstrom: Messwerte außerhalb des Üblichen, mit Wert und Sollbereich.

            WAHRSCHEINLICHE URSACHEN
            Nach Wahrscheinlichkeit geordnet, mit kurzer Begründung.

            PRÜFPLAN
            Nummerierte Schritte, vom einfachsten und billigsten zum aufwendigsten.
            Nenne konkrete Messungen am PAD VII (welcher Datenstrom, welcher Stellgliedtest, Sollwerte).

            DANACH
            Was ich als Nächstes brauche (z. B. bestimmter Datenstrom bei warmem Motor), oder "Nichts weiter".

            Wenn du dir bei etwas nicht sicher bist, sag es. Erfinde keine Codes oder Werte, die nicht im Bericht stehen.
        """.trimIndent()
    }
}
