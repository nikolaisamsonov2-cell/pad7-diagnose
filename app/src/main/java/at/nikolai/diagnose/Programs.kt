package at.nikolai.diagnose

/**
 * Prüfprogramme: geführte Abläufe, bei denen jeder Punkt mit dem 🔍-Knopf (Bildschirm) oder einer Datei
 * belegt wird. Am Ende entsteht ein Gesamtbericht; alle Belege werden zusätzlich nach Drive gesichert.
 */
data class Program(
    val id: String,
    val name: String,
    val intro: String,
    val items: List<Item>,
    /** Zusätzliche Anweisungen an Claude für den Gesamtbericht. */
    val reportFocus: String,
) {
    data class Item(val id: String, val title: String, val howTo: String, val optional: Boolean = false)
}

object Programs {
    private val SHARED_SCAN = listOf(
        Program.Item("scan", "Systemscan aller Steuergeräte",
            "Launch: Fahrzeug wählen → Automatischer Scan / Systemscan. Warten bis die Liste aller Steuergeräte mit Fehleranzahl da ist, dann 🔍. Bei langen Listen nach unten scrollen und nochmal 🔍."),
        Program.Item("dtc", "Fehlerspeicher-Details",
            "Für jedes Steuergerät mit Fehlern: Fehlercodes lesen öffnen, 🔍. Wenn vorhanden auch Freeze-Frame / Umgebungsdaten öffnen und 🔍 (enthält oft km-Stand und Datum). NICHT löschen."),
        Program.Item("readiness", "Readiness / Bereitschaftstests",
            "Launch: OBD-II / EOBD → Bereitschaftsstatus (I/M Readiness), 🔍. Nicht abgeschlossene Tests deuten auf kürzlich gelöschte Fehler hin."),
        Program.Item("ecuinfo", "Steuergeräte-Infos (VIN, Teilenummern, Software)",
            "In Motor-, Getriebe-, ABS-, Airbag- und Karosserie-Steuergerät jeweils Steuergeräte-Info / ECU-Info öffnen, 🔍. Dort stehen VIN, Hardware-/Softwarestand und oft km."),
    )

    val GARANTIE = Program(
        "garantie", "Garantie-Check",
        "Vor Ablauf der Garantie alles erfassen, was reklamiert werden kann. Jeder Beleg wird mit Datum gesichert. " +
            "Nichts löschen, nichts codieren: Fehlerspeicher-Einträge sind dein Beweis beim Händler.",
        listOf(
            Program.Item("basis", "Fahrzeug, km-Stand, Garantieende",
                "Notiz: km-Stand laut Tacho, Garantieende (Datum/km), bekannte Beschwerden. Optional Foto vom Tacho über „Datei hinzufügen“."),
        ) + SHARED_SCAN + listOf(
            Program.Item("mode6", "On-Board-Testergebnisse (Mode 6)",
                "Launch: OBD-II → On-Board-Monitor-Test / Testergebnisse Bauteile, 🔍. Zeigt Kat-, Lambda-, AGR-Werte knapp an der Grenze, bevor ein Fehler gesetzt wird."),
            Program.Item("hybrid", "Hybrid-Batterie",
                "Hybrid-/Batteriesteuergerät → Datenstrom: SOC, SOH, Zellspannungen min/max, Batterietemperaturen, 🔍.", optional = true),
            Program.Item("12v", "12-V-Batterie und Ladung",
                "Datenstrom Motor oder Karosserie: Batteriespannung bei Zündung an und bei laufendem Motor, 🔍."),
            Program.Item("getriebe", "Getriebe",
                "Getriebesteuergerät → Datenstrom: Öltemperatur, Gang, Schlupf/Adaptionswerte (nur lesen), 🔍."),
            Program.Item("motor", "Motor-Datenstrom warm",
                "Motor warm, Leerlauf: Kurz-/Langzeittrimmung, Lambda vor/nach Kat, Kühlmitteltemp., Zündaussetzer-Zähler, 🔍 oder als CSV aufzeichnen und über „Datei hinzufügen“ anhängen."),
            Program.Item("fahrt", "Probefahrt-Aufzeichnung",
                "Optional: Messfahrt-Vorlage oder Datenstrom-Aufzeichnung während der Fahrt, CSV anhängen.", optional = true),
            Program.Item("notizen", "Geräusche, Sichtprüfung, Beschwerden",
                "Notiz: Klappern, Vibrationen, Undichtigkeiten, Elektrik-Aussetzer, alles was dir auffällt, auch wenn es nur manchmal auftritt."),
        ),
        """
        Das ist ein GARANTIE-CHECK kurz vor Ablauf der Herstellergarantie. Ziel: nichts übersehen, was der Händler noch auf Garantie beheben muss.
        Ergänze nach DANACH diese Abschnitte:

        GARANTIE-REKLAMATION
        Nummerierte Liste aller Punkte, die beim Händler reklamiert werden sollten, je Punkt: Befund, Beleg (Code, Messwert, Datei), warum garantierelevant.
        Auch gespeicherte, sporadische und historische Codes sowie Messwerte knapp an der Grenze (Mode 6) aufnehmen.

        BEKANNTE SCHWACHSTELLEN
        Für dieses Modell bekannte Probleme, Rückrufe oder technische Serviceinformationen, die man beim Händler ansprechen sollte.
        Kennzeichne klar, was du sicher weißt und was beim Händler nachgefragt werden muss.

        FÜR DEN HÄNDLERTERMIN
        Kurzer Text, den Nikolai dem Händler vorlegen kann (sachlich, mit Codes und Datum).
        """.trimIndent(),
    )

    val GEBRAUCHT = Program(
        "gebraucht", "Gebrauchtwagen-Check",
        "Vor dem Kauf alles prüfen: echter km-Stand, gelöschte Fehler, Unfallspuren, teure Baustellen. " +
            "Am Ende gibt es einen Kaufbericht mit Verhandlungspunkten.",
        listOf(
            Program.Item("basis", "Inserat, Tacho, VIN am Fahrzeug",
                "Notiz: Inseratspreis, km laut Inserat und Tacho, VIN vom Typenschild/Scheibe, Baujahr. Optional Fotos über „Datei hinzufügen“."),
        ) + SHARED_SCAN + listOf(
            Program.Item("km", "km-Stand in mehreren Steuergeräten",
                "km aus Kombiinstrument, Motor-, Getriebe-, ABS-Steuergerät (Datenstrom oder ECU-Info, auch km in Freeze-Frames), je 🔍. Abweichungen = Tacho-Verdacht."),
            Program.Item("airbag", "Airbag / Crashdaten",
                "Airbag-Steuergerät: Fehlerspeicher und, falls angeboten, Crash-/Unfalldaten nur LESEN, 🔍."),
            Program.Item("hybrid", "Hybrid- bzw. Antriebsbatterie",
                "SOC, SOH, Zellspannungen min/max, Temperaturen, 🔍.", optional = true),
            Program.Item("12v", "12-V-Batterie und Ladung",
                "Batteriespannung Zündung an und Motor läuft, 🔍."),
            Program.Item("getriebe", "Getriebe",
                "Datenstrom Getriebe: Öltemperatur, Schlupf, Adaptionswerte (nur lesen), 🔍."),
            Program.Item("motor", "Motor-Datenstrom warm",
                "Trimmung, Lambda, Zündaussetzer-Zähler je Zylinder, Ladedruck (falls Turbo), 🔍 oder CSV."),
            Program.Item("mode6", "On-Board-Testergebnisse (Mode 6)",
                "OBD-II → On-Board-Monitor-Test, 🔍.", optional = true),
            Program.Item("notizen", "Sichtprüfung und Probefahrt",
                "Notiz: Lack/Spaltmaße, Reifen, Bremsen, Ölstand, Geräusche, Schaltverhalten, Warnleuchten."),
        ),
        """
        Das ist ein GEBRAUCHTWAGEN-CHECK vor dem Kauf. Ziel: Manipulation und teure Mängel erkennen, Verhandlungsbasis schaffen.
        Ergänze nach DANACH diese Abschnitte:

        MANIPULATIONSHINWEISE
        km-Stände aus allen Steuergeräten und Freeze-Frames gegenüberstellen (Tabelle als Zeilen), VIN-Abgleich zwischen Steuergeräten und Notiz,
        Hinweise auf kürzlich gelöschte Fehler (Readiness unvollständig, wenige Fahrzyklen seit Löschen), Crashdaten.
        Sag klar, wenn etwas nicht geprüft werden konnte.

        TEURE BAUSTELLEN
        Befunde mit grober Kostenschätzung in Österreich (Bandbreite, als Schätzung kennzeichnen).

        KAUFEMPFEHLUNG
        Kaufen / nur mit Preisnachlass / Finger weg, mit Begründung und konkreten Verhandlungspunkten.
        """.trimIndent(),
    )

    val ALL = listOf(GARANTIE, GEBRAUCHT)

    fun get(id: String) = ALL.firstOrNull { it.id == id }
}
