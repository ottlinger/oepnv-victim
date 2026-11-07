# oepnv-victim

Java-CLI für die BVG-Fahrplanauskunft in Berlin/Brandenburg. Start und Ziel sind
parametrisierbar; jede Verbindung wird mit ihren Teilstrecken ausgegeben.

## Voraussetzungen und Build

- Java 17 oder neuer
- Maven 3.9 oder neuer
- Internetzugang

```sh
./mvnw
```

## Aufruf

```sh
java -jar target/oepnv-victim.jar \
  --start "Treuenbrietzen, Bahnhof" \
  --ziel "Jüterbog, Bahnhof"
```

Weitere Optionen:

```sh
# Nur zwei Verbindungen aus der ersten Ergebnisseite
java -jar target/oepnv-victim.jar --start "Treuenbrietzen, Bahnhof" --ziel "Jüterbog, Bahnhof" --limit 2

# Alle von BVG gelieferten Detailfelder als JSON-Liste
java -jar target/oepnv-victim.jar --from "Treuenbrietzen, Bahnhof" --to "Jüterbog, Bahnhof" --json

java -jar target/oepnv-victim.jar --help
```

`--start` / `--from` und `--ziel` / `--to` sind Pflichtparameter. Bei mehreren
passenden Haltestellen verlangt die CLI einen eindeutigen Namen und nennt die
Vorschläge; sie wählt nicht einfach das erste Suchergebnis.

`--limit` begrenzt die Ausgabe auf maximal N Verbindungen der ersten Ergebnisseite
(Standard: 5). Es werden keine weiteren Ergebnisseiten nachgeladen.
`--timeout` setzt das Zeitlimit pro HTTP-Anfrage in Sekunden (Standard: 30).
Die Suche erfolgt für **Abfahrt jetzt**, mit allen Verkehrsmitteln der Beispiel-URL
und Echtzeitdaten. Zeiten sind lokale Fahrplanzeiten der BVG; fehlende
Verspätungsdaten werden nicht als Pünktlichkeit interpretiert.

Beispiel einer Verbindung (Zeiten/Gleise variieren):

```text
Verbindung 1: 2026-11-07 12:29 (+0 Min.) → 2026-11-07 12:47 (+0 Min.) | 18 Min.
  1. RB33 Richtung Jüterbog, Bahnhof
     2026-11-07 12:29 (+0 Min.)  Treuenbrietzen, Bahnhof (Gl. 2)
     2026-11-07 12:47 (+0 Min.)  Jüterbog, Bahnhof (Gl. 3)
     Dauer: 18 Min.
     Halte (BVG): 3
```

Die Textausgabe enthält außerdem die parametrisierte BVG-URL. Hinweise/Störungen
werden ausgegeben, soweit vorhanden. `--json` gibt ausschließlich die ausgewählten
Verbindungsobjekte aus, einschließlich aller von BVG gelieferten Felder.

Exitcodes: `0` erfolgreiche Abfrage (auch bei leerer Ergebnisliste), `1` Abfragefehler,
`2` ungültige Argumente. Fehlermeldungen gehen nach stderr.

## HTML und Verbindungsdetails

Das initial ausgelieferte HTML dieser BVG-Seite enthält **keine Fahrverbindungen**.
Auch der Inhalt des aufklappbaren Detailbereichs wird clientseitig dargestellt.
Reines Parsen sichtbarer HTML-Elemente liefert deshalb keine Verbindungsdaten.

Die Anwendung löst dies ohne JavaScript-Ausführung:

1. Java `HttpClient` lädt die parametrisierte Fahrplanauskunft-Seite.
2. **Jsoup** parst das HTML und liest das eingebettete `script#__NEXT_DATA__`.
3. Aus dieser Konfiguration werden die BVG-Suchendpunkte ermittelt.
4. Die Haltestellensuche löst Namen in aktuelle BVG-IDs auf; IDs aus der Beispiel-URL
   werden nicht fest einprogrammiert.
5. Der Verbindungsendpunkt liefert Übersicht und `legs` (Detail-Teilstrecken).
   Diese Daten entsprechen der Grundlage des aufgeklappten UI-Detailbereichs:
   Ein Klick auf den Detailbutton ist nicht erforderlich.

Die BVG-Webendpunkte sind keine hier garantierte stabile API. Bei Änderungen an
HTML-Konfiguration oder Antwortschema meldet die CLI einen Fehler. Sie umgeht
keine Zugangssperren; bitte nur moderat abfragen und die BVG-Nutzungsbedingungen
beachten. Zwischenhalte werden nur insoweit ausgegeben, wie die Antwort sie
enthält; aktuell liefert die Beispielstrecke eine Halteanzahl, keine Liste aller
Zwischenhalte.

## Tests

```sh
/.mvnw test
```

Die Tests laufen ohne externe BVG-Zugriffe: Ein lokaler HTTP-Testserver prüft den
Ablauf von HTML-Parsing bis Detailabfrage. Weitere Tests decken Namensauflösung,
UTF-8/URL-Parameter, Fehlerfälle, Teilstrecken, Gleise, Verspätungen, Fußwege,
Tageswechsel, Hinweise und CLI-Validierung ab.

