/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import picocli.CommandLine.Help.Ansi;

import java.time.Duration;
import java.time.format.DateTimeParseException;

final class ConnectionFormatter {
    private ConnectionFormatter() { }

    static String format(final int index, final JsonNode connection) {
        return format(index, connection, Ansi.OFF);
    }

    static String format(final int index, final JsonNode connection, final Ansi ansi) {
        JsonNode summary = connection.path("summary");
        StringBuilder out = new StringBuilder("\nVerbindung " + index + ": ");
        out.append(time(summary.path("departure"), ansi)).append(" → ").append(time(summary.path("arrival")))
                .append(" | Gesamt: ").append(duration(summary.path("totalDuration").asText())).append('\n');
        int legIndex = 1;
        for (JsonNode leg : connection.path("legs")) {
            JsonNode service = leg.path("service");
            String name = service.path("name").asText("");
            if (name.isBlank()) name = "Fußweg / Transfer";
            out.append("  ").append(legIndex++).append(". ").append(name);
            String direction = service.path("direction").asText();
            if (!direction.isBlank()) out.append(" Richtung ").append(direction);
            out.append('\n');
            out.append("     ").append(time(leg.path("departure"), ansi)).append("  ").append(stop(leg.path("departureStop"))).append('\n');
            out.append("     ").append(time(leg.path("arrival"))).append("  ").append(stop(leg.path("arrivalStop"))).append('\n');
            String legDuration = leg.path("duration").asText(service.path("duration").asText());
            if (!legDuration.isBlank()) out.append("     Dauer: ").append(duration(legDuration)).append('\n');
            if (leg.has("stopCount")) out.append("     Halte: ").append(leg.path("stopCount").asText()).append('\n');
            warnings(out, leg);
        }
        warnings(out, connection);
        warnings(out, summary);
        if (summary.path("hasWarnings").asBoolean()) {
            out.append("  Achtung: BVG meldet Störungen/Hinweise zu dieser Verbindung.\n");
        }
        return out.toString().stripTrailing();
    }

    private static String stop(final JsonNode stop) {
        String name = stop.path("name").asText("unbekannte Haltestelle");
        String track = stop.path("track").asText();
        return name + (track.isBlank() ? "" : " (" + track + ")");
    }

    private static String time(final JsonNode event) {
        return time(event, Ansi.OFF);
    }

    private static String time(final JsonNode event, final Ansi ansi) {
        String date = event.path("date").asText();
        String time = event.path("time").asText("?");
        if (time.matches("\\d{2}:\\d{2}:\\d{2}")) time = time.substring(0, 5);
        String result = ansi.string("@|bold " + (date.isBlank() ? "" : date + " ") + time + "|@");
        String delay = event.path("delay").asText();
        if (!delay.isBlank()) {
            try {
                long minutes = Duration.parse(delay).toMinutes();
                result += " (" + (minutes >= 0 ? "+" : "") + minutes + " Min.)";
            } catch (final DateTimeParseException e) {
                result += " (Verspätung: " + delay + ")";
            }
        }
        return result;
    }

    static String duration(final String value) {
        if (value.isBlank()) return "Dauer unbekannt";
        try {
            long seconds = Duration.parse(value).getSeconds();
            long minutes = seconds / 60;
            if (seconds % 60 != 0) return seconds + " Sek.";
            return minutes < 60 ? minutes + " Min." : (minutes / 60) + " Std. " + (minutes % 60) + " Min.";
        } catch (final DateTimeParseException e) {
            return value;
        }
    }

    private static void warnings(final StringBuilder out, final JsonNode node) {
        for (String key : new String[]{"warnings", "messages", "notices"}) {
            JsonNode values = node.path(key);
            if (values.isMissingNode() || values.isNull()) continue;
            if (values.isArray()) {
                for (JsonNode value : values) warning(out, value);
            } else {
                warning(out, values);
            }
        }
    }

    private static void warning(final StringBuilder out, final JsonNode warning) {
        String text = warning.isTextual() ? warning.asText()
                : warning.path("text").asText(warning.path("message").asText(warning.toString()));
        out.append("     Hinweis: ").append(Jsoup.parse(text).text()).append('\n');
    }
}
