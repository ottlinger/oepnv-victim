/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import org.junit.jupiter.api.Test;
import picocli.CommandLine.Help.Ansi;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionFormatterTest {
    @Test
    void rendersDetailFieldsAndHtmlWarnings() throws Exception {
        var connection = BvgClient.JSON.readTree(BvgClientTest.fixture()).path("connections").get(0);
        String text = ConnectionFormatter.format(1, connection);
        assertThat(text).contains("Verbindung 1:", "RB33 Richtung Jüterbog, Bahnhof",
                "Treuenbrietzen, Bahnhof (Gl. 2)", "Jüterbog, Bahnhof (Gl. 3)", "12:29 (+5 Min.)",
                " | Gesamt: 18 Min.\n", "     Dauer: 18 Min.", "     Halte: 3\n",
                "Bitte Hinweis beachten.", "Störungen/Hinweise")
                .doesNotContain("Halte (BVG)", "<b>");
    }

    @Test
    void highlightsOnlyDepartureTimesWhenAnsiIsEnabled() throws Exception {
        var connection = BvgClient.JSON.readTree(BvgClientTest.fixture()).path("connections").get(0);
        String text = ConnectionFormatter.format(1, connection, Ansi.ON);
        String departure = Ansi.ON.string("@|bold 2026-11-07 12:29|@");
        assertThat(departure).isNotEqualTo("2026-11-07 12:29");
        assertThat(text).contains("Verbindung 1: " + departure + " (+5 Min.) → ",
                "     " + departure + " (+5 Min.)  Treuenbrietzen")
                .doesNotContain(Ansi.ON.string("@|bold 2026-11-07 12:47|@"));
        assertThat(text.replaceAll("\\u001B\\[[;\\d]*m", ""))
                .isEqualTo(ConnectionFormatter.format(1, connection));
    }

    @Test
    void leavesPlainTextFreeOfAnsiCodesAndMarkup() throws Exception {
        var connection = BvgClient.JSON.readTree(BvgClientTest.fixture()).path("connections").get(0);
        String text = ConnectionFormatter.format(1, connection, Ansi.OFF);
        assertThat(text).contains("Verbindung 1: 2026-11-07 12:29 (+5 Min.) → ")
                .doesNotContain("\u001B", "@|");
    }

    @Test
    void handlesFootpathsMissingTracksAndMidnight() throws Exception {
        var connection = BvgClient.JSON.readTree(BvgClientTest.fixture()).path("connections").get(1);
        String text = ConnectionFormatter.format(2, connection);
        assertThat(text).contains("Fußweg / Transfer", "2026-11-07 23:55", "2026-11-08 00:05")
                .doesNotContain("+0 Min.", "null");
    }

    @Test
    void rendersEveryLegInOrder() throws Exception {
        var connection = BvgClient.JSON.readTree("""
                {"summary":{}, "legs":[{"service":{"name":"RE3"}},
                  {"service":{"name":"RB33"}}]}
                """);
        String text = ConnectionFormatter.format(1, connection);
        assertThat(text).containsSubsequence("1. RE3", "2. RB33");
    }

    @Test
    void handlesLongAndUnknownDurations() {
        assertThat(ConnectionFormatter.duration("PT1H5M")).isEqualTo("1 Std. 5 Min.");
        assertThat(ConnectionFormatter.duration("PT30S")).isEqualTo("30 Sek.");
        assertThat(ConnectionFormatter.duration("")).isEqualTo("Dauer unbekannt");
        assertThat(ConnectionFormatter.duration("unbekannt")).isEqualTo("unbekannt");
    }
}
