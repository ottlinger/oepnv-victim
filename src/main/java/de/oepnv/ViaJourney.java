/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;

/** Combines two independently planned journeys at a resolved intermediate station. */
final class ViaJourney {
    private ViaJourney() { }

    static LocalDateTime effectiveTime(final JsonNode event) throws IOException {
        LocalDateTime time = scheduledTime(event);
        String delay = event.path("delay").asText();
        try {
            return delay.isBlank() ? time : time.plus(Duration.parse(delay));
        } catch (final DateTimeException e) {
            throw new IOException("Ungültige BVG-Verspätung für die Suche über eine Zwischenstation.", e);
        }
    }

    private static LocalDateTime scheduledTime(final JsonNode event) throws IOException {
        try {
            return LocalDateTime.parse(event.path("date").asText() + "T" + event.path("time").asText());
        } catch (final DateTimeException e) {
            throw new IOException("BVG liefert keine gültige Zeit für die Suche über eine Zwischenstation.", e);
        }
    }

    static JsonNode combine(final JsonNode first, final JsonNode second, final String via) throws IOException {
        JsonNode firstSummary = first.path("summary");
        JsonNode secondSummary = second.path("summary");
        ObjectNode summary = firstSummary.deepCopy();
        summary.set("arrival", secondSummary.path("arrival").deepCopy());
        summary.put("totalDuration", Duration.between(scheduledTime(firstSummary.path("departure")),
                scheduledTime(secondSummary.path("arrival"))).toString());
        summary.put("hasWarnings", firstSummary.path("hasWarnings").asBoolean()
                || secondSummary.path("hasWarnings").asBoolean());
        ArrayNode services = summary.putArray("services");
        firstSummary.path("services").forEach((final var service) -> services.add(service.deepCopy()));
        secondSummary.path("services").forEach((final var service) -> services.add(service.deepCopy()));
        mergeWarnings(summary, firstSummary, secondSummary);

        ObjectNode result = BvgClient.JSON.createObjectNode();
        result.put("date", firstSummary.path("departure").path("date").asText());
        result.put("via", via);
        result.set("summary", summary);
        ArrayNode legs = result.putArray("legs");
        first.path("legs").forEach((final var leg) -> legs.add(leg.deepCopy()));
        second.path("legs").forEach((final var leg) -> legs.add(leg.deepCopy()));
        mergeWarnings(result, first, second);
        // Preserve every original field for --json, without inventing a BVG journey ID.
        result.putArray("viaConnections").add(first.deepCopy()).add(second.deepCopy());
        return result;
    }

    private static void mergeWarnings(final ObjectNode target, final JsonNode first, final JsonNode second) {
        for (String key : new String[]{"warnings", "messages", "notices"}) {
            if (!first.hasNonNull(key) && !second.hasNonNull(key)) continue;
            ArrayNode values = target.putArray(key);
            for (JsonNode source : new JsonNode[]{first, second}) {
                JsonNode value = source.path(key);
                if (value.isArray()) value.forEach((final var item) -> values.add(item.deepCopy()));
                else if (!value.isMissingNode() && !value.isNull()) values.add(value.deepCopy());
            }
        }
    }
}
