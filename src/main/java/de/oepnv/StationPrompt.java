/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.List;

/** Keeps station selection off stdout, including when journey output is JSON. */
final class StationPrompt implements BvgClient.StationSelector {
    private final BufferedReader input;
    private final PrintWriter output;

    StationPrompt(final Reader input, final PrintWriter output) {
        this.input = new BufferedReader(input);
        this.output = output;
    }

    @Override
    public BvgClient.Station select(final String name, final List<BvgClient.Station> candidates) throws IOException {
        output.println("Bahnhof '" + name + "' ist nicht eindeutig. Bitte auswählen:");
        for (int index = 0; index < candidates.size(); index++) {
            final BvgClient.Station station = candidates.get(index);
            output.println("  " + (index + 1) + ") " + station.name());
        }
        while (true) {
            output.print("Nummer (1-" + candidates.size() + ", Enter zum Abbrechen): ");
            output.flush();
            final String answer = input.readLine();
            if (answer == null || answer.isBlank()) {
                throw new IOException("Stationsauswahl abgebrochen für '" + name + "'.");
            }
            try {
                final int index = Integer.parseInt(answer.strip()) - 1;
                if (index >= 0 && index < candidates.size()) {
                    final BvgClient.Station selected = candidates.get(index);
                    output.println("Übernommen: " + selected.name());
                    output.flush();
                    return selected;
                }
            } catch (final NumberFormatException e) {
                // Invalid input is recoverable; keep the same candidate list.
            }
            output.println("Bitte eine gültige Nummer eingeben.");
        }
    }
}
