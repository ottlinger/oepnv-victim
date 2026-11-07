package de.oepnv;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ViaJourneyTest {
    @Test
    void combinesDetailsWarningsAndOriginalFieldsWithoutMutatingSources() throws Exception {
        JsonNode connections = BvgClient.JSON.readTree(BvgClientTest.fixture()).path("connections");
        JsonNode first = connections.get(0);
        JsonNode second = connections.get(1);
        JsonNode snapshot = connections.deepCopy();
        JsonNode combined = ViaJourney.combine(first, second, "Zwischenstation");
        assertEquals(first.path("summary").path("departure"), combined.path("summary").path("departure"));
        assertEquals(second.path("summary").path("arrival"), combined.path("summary").path("arrival"));
        assertEquals(first.path("legs").size() + second.path("legs").size(), combined.path("legs").size());
        assertTrue(combined.path("summary").path("hasWarnings").asBoolean());
        assertEquals(first, combined.path("viaConnections").get(0));
        assertEquals(second, combined.path("viaConnections").get(1));
        assertEquals(snapshot, connections);
        String text = ConnectionFormatter.format(1, combined);
        assertTrue(text.contains("Bitte Hinweis beachten."));
        assertTrue(text.contains("Fußweg / Transfer"));
    }

    @Test
    void mergesWarningsFromBothSectionsAndTheirSummaries() throws Exception {
        String template = """
                {"summary":{"departure":{"date":"2026-11-07","time":"15:22:00"},
                  "arrival":{"date":"2026-11-07","time":"15:40:00"},"notices":"%s"},
                 "legs":[],"warnings":["%s"]}
                """;
        JsonNode combined = ViaJourney.combine(BvgClient.JSON.readTree(template.formatted("summary A", "warning A")),
                BvgClient.JSON.readTree(template.formatted("summary B", "warning B")), "Via");
        assertEquals(2, combined.path("summary").path("notices").size());
        assertEquals(2, combined.path("warnings").size());
        String text = ConnectionFormatter.format(1, combined);
        for (String message : new String[]{"summary A", "summary B", "warning A", "warning B"}) {
            assertTrue(text.contains(message));
        }
    }

    @Test
    void computesEffectiveTimeAndRejectsUnusableTimingData() throws Exception {
        assertEquals(LocalDateTime.of(2026, 11, 8, 0, 2), ViaJourney.effectiveTime(BvgClient.JSON.readTree("""
                {"date":"2026-11-07","time":"23:59:00","delay":"PT3M"}
                """)));
        assertEquals(LocalDateTime.of(2026, 11, 7, 15, 22), ViaJourney.effectiveTime(BvgClient.JSON.readTree("""
                {"date":"2026-11-07","time":"15:22:00"}
                """)));
        assertThrows(IOException.class, () -> ViaJourney.effectiveTime(BvgClient.JSON.readTree("{}")));
        assertThrows(IOException.class, () -> ViaJourney.effectiveTime(BvgClient.JSON.readTree("""
                {"date":"2026-11-07","time":"15:22:00","delay":"unknown"}
                """)));
    }
}
