/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StationPromptTest {
    private static final List<BvgClient.Station> STATIONS = List.of(
            new BvgClient.Station("Beelitz, Stadt Bhf.", "stadt"),
            new BvgClient.Station("Beelitz, Heilstätten Bhf.", "heil"));

    @Test
    void retriesInvalidAnswersAndReturnsSelectedStationIncludingId() throws Exception {
        final StringWriter output = new StringWriter();
        final StationPrompt prompt = new StationPrompt(new StringReader("abc\n0\n3\n 2 \n1\n"), new PrintWriter(output));
        assertThat(prompt.select("Beelitz", STATIONS)).isEqualTo(STATIONS.get(1));
        assertThat(prompt.select("Beelitz", STATIONS)).isEqualTo(STATIONS.get(0));
        assertThat(output.toString()).contains("1) Beelitz, Stadt Bhf.", "2) Beelitz, Heilstätten Bhf.",
                "Bitte eine gültige Nummer", "Übernommen: Beelitz, Heilstätten Bhf.")
                .doesNotContain("[", "]");
    }

    @Test
    void cancelsOnEndOfInputOrBlankLine() {
        for (final String answer : List.of("", "\n", "  \n")) {
            final StationPrompt prompt = new StationPrompt(new StringReader(answer), new PrintWriter(new StringWriter()));
            assertThatThrownBy(() -> prompt.select("Beelitz", STATIONS))
                    .isInstanceOf(IOException.class).hasMessageContaining("abgebrochen");
        }
    }
}
