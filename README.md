# Diagnose-App für das Launch X-431 PAD VII

Kleine Android-App, die Berichte des Launch X-431 PAD VII automatisch von Claude auswerten lässt
und das Ergebnis (Fehlercodes, Ursachen, Prüfplan) direkt am Tablet zeigt.

## Was sie kann (Version 1)

- **Ordner überwachen:** Alle 15 Minuten sucht die App im Launch-Ordner (`/sdcard/CNLAUNCH`, einstellbar)
  nach neuen PDF-, CSV- und Bilddateien und wertet sie aus. Beim ersten Start merkt sie sich den Bestand
  und wertet nur neue Dateien aus.
- **Teilen:** In der Launch-App oder Galerie auf „Teilen“ → „Diagnose auswerten“.
- **Datei auswählen:** Ältere Berichte einzeln auswerten.
- Ergebnis als Benachrichtigung mit Ampel (grün/gelb/rot) und Verlauf in der App.

`.dat`- und `.x431`-Dateien sind Launch-eigene Formate und werden übersprungen.

## Installation auf dem PAD VII

1. Auf dem PAD in Chrome die Release-Seite öffnen:
   https://github.com/nikolaisamsonov2-cell/pad7-diagnose/releases/tag/neueste
   (bei GitHub angemeldet sein, das Repo ist privat).
2. `diagnose.apk` herunterladen und öffnen. Falls Android fragt: Installation aus Chrome erlauben.
3. App „Diagnose“ öffnen → Einstellungen:
   - API-Schlüssel von console.anthropic.com eintragen, speichern.
   - „Dateizugriff erlauben“ antippen und für Diagnose einschalten.

Updates: gleichen Link erneut öffnen und die neue APK über die alte installieren.

## Kosten

Jede Auswertung läuft über den eigenen Anthropic-API-Schlüssel (Modell `claude-opus-5-5`), typischerweise
ein paar Cent bis rund 20 Cent pro Bericht, je nach Größe.

## Technik

- Kotlin, keine UI-Bibliotheken, `minSdk 26`.
- Claude-Aufruf über das offizielle Anthropic-Java-SDK (`ClaudeAnalyzer.kt`).
- Hintergrundsuche mit WorkManager (`ScanWorker.kt`).
- GitHub Actions baut bei jedem Push auf `main` die APK und veröffentlicht sie als Release `neueste`.
- Der Signaturschlüssel liegt nicht im Repo, sondern als Build-Artefakt (90 Tage, bei jedem Build erneuert),
  damit Updates sich über die alte Version installieren lassen. Gibt es 90 Tage keinen Build, entsteht ein
  neuer Schlüssel; dann einmal die alte App deinstallieren.

## Geplant

2. **Diagnose-Knopf:** Knopf in der Navigationsleiste liest den Bildschirm der Launch-App (Text + Screenshot)
   und schickt ihn an Claude.
3. **Selbst bedienen:** Claude navigiert durch die Launch-Menüs, nur lesend, jeder Tipp wird vorher bestätigt.
   Löschen, Codierung, Anpassung und Stellgliedtests bleiben gesperrt.
