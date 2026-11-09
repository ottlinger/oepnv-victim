/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViaJourneyTest {
    @Test
    void combinesDetailsWarningsAndOriginalFieldsWithoutMutatingSources() throws Exception {
        JsonNode connections = BvgClient.JSON.readTree(BvgClientTest.fixture()).path("connections");
        JsonNode first = connections.get(0);
        JsonNode second = connections.get(1);
        JsonNode snapshot = connections.deepCopy();
        JsonNode combined = ViaJourney.combine(first, second, "Zwischenstation");
        assertThat(combined.path("summary").path("departure")).isEqualTo(first.path("summary").path("departure"));
        assertThat(combined.path("summary").path("arrival")).isEqualTo(second.path("summary").path("arrival"));
        assertThat(combined.path("legs")).hasSize(first.path("legs").size() + second.path("legs").size());
        assertThat(combined.path("summary").path("hasWarnings").asBoolean()).isTrue();
        assertThat(combined.path("viaConnections").get(0)).isEqualTo(first);
        assertThat(combined.path("viaConnections").get(1)).isEqualTo(second);
        assertThat(connections).isEqualTo(snapshot);
        String text = ConnectionFormatter.format(1, combined);
        assertThat(text).contains("Bitte Hinweis beachten.", "Fußweg / Transfer");
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
        assertThat(combined.path("summary").path("notices")).hasSize(2);
        assertThat(combined.path("warnings")).hasSize(2);
        String text = ConnectionFormatter.format(1, combined);
        for (String message : new String[]{"summary A", "summary B", "warning A", "warning B"}) {
            assertThat(text).contains(message);
        }
    }

    @Test
    void computesEffectiveTimeAndRejectsUnusableTimingData() throws Exception {
        assertThat(ViaJourney.effectiveTime(BvgClient.JSON.readTree("""
                {"date":"2026-11-07","time":"23:59:00","delay":"PT3M"}
                """))).isEqualTo(LocalDateTime.of(2026, 11, 8, 0, 2));
        assertThat(ViaJourney.effectiveTime(BvgClient.JSON.readTree("""
                {"date":"2026-11-07","time":"15:22:00"}
                """))).isEqualTo(LocalDateTime.of(2026, 11, 7, 15, 22));
        assertThatThrownBy(() -> ViaJourney.effectiveTime(BvgClient.JSON.readTree("{}")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ViaJourney.effectiveTime(BvgClient.JSON.readTree("""
                {"date":"2026-11-07","time":"15:22:00","delay":"unknown"}
                """))).isInstanceOf(IOException.class);
    }
}
