/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

record JourneyConfig(String from, String to, String via, String departure, String arrival) {
    private static final Set<String> FIELDS = Set.of("from", "to", "via", "departure", "arrival");
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static JourneyConfig read(final Path path) throws IOException {
        final JsonNode root = YAML.readTree(path.toFile());
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Die Konfiguration muss eine YAML-Zuordnung enthalten.");
        }
        final var fields = root.fields();
        while (fields.hasNext()) {
            final var field = fields.next();
            if (!FIELDS.contains(field.getKey())) {
                throw new IllegalArgumentException("Unbekannter Konfigurationseintrag: " + field.getKey());
            }
            if (!field.getValue().isTextual() || field.getValue().textValue().isBlank()) {
                throw new IllegalArgumentException("Konfigurationseintrag '" + field.getKey()
                        + "' muss eine nicht leere Zeichenkette sein.");
            }
        }
        return new JourneyConfig(root.path("from").textValue(), root.path("to").textValue(),
                root.path("via").textValue(), root.path("departure").textValue(), root.path("arrival").textValue());
    }
}
