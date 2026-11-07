package de.oepnv;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionFormatterTest {
    @Test
    void rendersDetailFieldsAndHtmlWarnings() throws Exception {
        var connection = BvgClient.JSON.readTree(BvgClientTest.fixture()).path("connections").get(0);
        String text = ConnectionFormatter.format(1, connection);
        assertTrue(text.contains("Verbindung 1:"));
        assertTrue(text.contains("RB33 Richtung Jüterbog, Bahnhof"));
        assertTrue(text.contains("Treuenbrietzen, Bahnhof (Gl. 2)"));
        assertTrue(text.contains("Jüterbog, Bahnhof (Gl. 3)"));
        assertTrue(text.contains("12:29 (+5 Min.)"));
        assertTrue(text.contains("18 Min."));
        assertTrue(text.contains("Bitte Hinweis beachten."));
        assertTrue(text.contains("Störungen/Hinweise"));
        assertFalse(text.contains("<b>"));
    }

    @Test
    void handlesFootpathsMissingTracksAndMidnight() throws Exception {
        var connection = BvgClient.JSON.readTree(BvgClientTest.fixture()).path("connections").get(1);
        String text = ConnectionFormatter.format(2, connection);
        assertTrue(text.contains("Fußweg / Transfer"));
        assertTrue(text.contains("2026-11-07 23:55"));
        assertTrue(text.contains("2026-11-08 00:05"));
        assertFalse(text.contains("+0 Min."));
        assertFalse(text.contains("null"));
    }

    @Test
    void rendersEveryLegInOrder() throws Exception {
        var connection = BvgClient.JSON.readTree("""
                {"summary":{}, "legs":[{"service":{"name":"RE3"}},
                  {"service":{"name":"RB33"}}]}
                """);
        String text = ConnectionFormatter.format(1, connection);
        assertTrue(text.contains("1. RE3"));
        assertTrue(text.contains("2. RB33"));
        assertTrue(text.indexOf("RE3") < text.indexOf("RB33"));
    }

    @Test
    void handlesLongAndUnknownDurations() {
        assertEquals("1 Std. 5 Min.", ConnectionFormatter.duration("PT1H5M"));
        assertEquals("30 Sek.", ConnectionFormatter.duration("PT30S"));
        assertEquals("Dauer unbekannt", ConnectionFormatter.duration(""));
        assertEquals("unbekannt", ConnectionFormatter.duration("unbekannt"));
    }
}
