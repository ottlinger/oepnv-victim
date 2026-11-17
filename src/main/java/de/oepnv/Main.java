/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "oepnv-victim", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "BVG-Verbindungen inklusive Teilstrecken, Gleisen und Verspätungen.",
        footer = "Stationen müssen eindeutig sein. Schreibvarianten werden automatisch erkannt. Bei mehrdeutigen "
                + "Namen bietet die BVG-Stationssuche im Terminal eine Auswahl; ohne Terminal --interactive verwenden "
                + "oder mit einem vollständigen Namen erneut aufrufen.")
public final class Main implements Callable<Integer> {
    private final JourneySearch search;

    public Main() {
        this(null);
    }

    Main(final JourneySearch search) {
        this.search = search;
    }

    @FunctionalInterface
    interface JourneySearch {
        BvgClient.Result search(final String origin, final String destination, final int limit,
                                final LocalDateTime departure, final LocalDateTime arrival,
                                final String via, final Duration timeout) throws IOException, InterruptedException;
    }

    @Option(names = "--config", paramLabel = "FILE",
            description = "YAML-Datei mit from, to, via, departure oder arrival; aufgelöste Stationsnamen werden gespeichert. CLI-Optionen haben Vorrang.")
    private Path config;

    @Option(names = "--from", paramLabel = "STATION",
            description = "Eindeutige Startstation, z. B. 'Treuenbrietzen, Bahnhof'.")
    private String origin;

    @Option(names = "--to", paramLabel = "STATION",
            description = "Eindeutige Zielstation, z. B. 'Jüterbog, Bahnhof'.")
    private String destination;

    @Option(names = "--via", paramLabel = "STATION",
            description = "Optionale eindeutige Zwischenstation, z. B. 'S Potsdam Hauptbahnhof'.")
    private String via;

    @Option(names = "--departure", paramLabel = "\"YYYY-MM-DD HH:mm\"",
            converter = DepartureTimeConverter.class,
            description = "Abfahrtszeit in Berlin, z. B. '2026-11-07 9:00' (Stunde ein- oder zweistellig; Standard: jetzt).")
    private LocalDateTime departure;

    @Option(names = "--arrival", paramLabel = "\"YYYY-MM-DD HH:mm\"",
            converter = DepartureTimeConverter.class,
            description = "Ankunftszeit in Berlin, z. B. '2026-11-07 9:00' (Stunde ein- oder zweistellig; nicht mit --departure kombinierbar).")
    private LocalDateTime arrival;

    @Option(names = "--limit", defaultValue = "5", description = "Maximale Anzahl aus der ersten Ergebnisseite (Standard: 5).")
    private int limit;

    @Option(names = "--json", description = "Verbindungen mit allen gelieferten Details als JSON ausgeben.")
    private boolean json;

    @Option(names = "--interactive", description = "Stationsauswahl auch ohne erkanntes Terminal anbieten (Eingabe über stdin).")
    private boolean interactive;

    @Option(names = "--timeout", defaultValue = "30", description = "Zeitlimit pro HTTP-Anfrage in Sekunden (Standard: 30).")
    private int timeout;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        loadConfig();
        if (origin == null || destination == null) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "--from und --to sind erforderlich (als CLI-Optionen oder in --config).");
        }
        if (origin.isBlank() || destination.isBlank() || (via != null && via.isBlank()) || limit < 1 || timeout < 1) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "--from, --to und --via dürfen nicht leer sein; --limit und --timeout müssen größer als 0 sein.");
        }
        if (departure != null && arrival != null) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "--departure und --arrival dürfen nicht gemeinsam angegeben werden.");
        }
        try {
            final BvgClient.Result result;
            if (search == null) {
                final BvgClient.StationSelector selector = interactive || System.console() != null
                        ? new StationPrompt(System.console() == null
                                ? new InputStreamReader(System.in, StandardCharsets.UTF_8) : System.console().reader(),
                                spec.commandLine().getErr())
                        : BvgClient::ambiguousStation;
                result = new BvgClient(Duration.ofSeconds(timeout), selector)
                        .search(origin, destination, limit, departure, arrival, via);
            } else {
                result = search.search(origin, destination, limit, departure, arrival, via, Duration.ofSeconds(timeout));
            }
            updateConfig(result);
            if (json) {
                spec.commandLine().getOut().println(BvgClient.JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result.connections()));
            } else {
                spec.commandLine().getOut().println("Start: " + result.origin());
                spec.commandLine().getOut().println("Ziel:  " + result.destination());
                if (result.via() != null) spec.commandLine().getOut().println("Via:   " + result.via());
                if (result.connections().isEmpty()) {
                    spec.commandLine().getOut().println("Keine Verbindungen gefunden.");
                }
                int index = 1;
                for (JsonNode connection : result.connections()) {
                    spec.commandLine().getOut().println(ConnectionFormatter.format(index++, connection,
                            spec.commandLine().getColorScheme().ansi()));
                }
            }
            return 0;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            spec.commandLine().getErr().println("Fehler: Abfrage wurde unterbrochen.");
            return 1;
        } catch (final Exception e) {
            spec.commandLine().getErr().println("Fehler: " + e.getMessage());
            return 1;
        }
    }

    private void updateConfig(final BvgClient.Result result) throws IOException {
        if (config == null) return;
        final Map<String, String> stations = new LinkedHashMap<>();
        if (!spec.commandLine().getParseResult().hasMatchedOption("--from")) stations.put("from", result.origin());
        if (!spec.commandLine().getParseResult().hasMatchedOption("--to")) stations.put("to", result.destination());
        if (result.via() != null && !spec.commandLine().getParseResult().hasMatchedOption("--via")) {
            stations.put("via", result.via());
        }
        if (JourneyConfig.updateStations(config, stations)) {
            spec.commandLine().getErr().println("Stationsnamen gespeichert in: " + config);
        }
    }

    private void loadConfig() {
        if (config == null) return;
        try {
            final JourneyConfig saved = JourneyConfig.read(config);
            if (origin == null) origin = saved.from();
            if (destination == null) destination = saved.to();
            if (via == null) via = saved.via();
            final DepartureTimeConverter converter = new DepartureTimeConverter();
            if (departure == null && saved.departure() != null) departure = converter.convert(saved.departure());
            if (arrival == null && saved.arrival() != null) arrival = converter.convert(saved.arrival());
        } catch (final IOException | IllegalArgumentException | CommandLine.TypeConversionException e) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "Ungültige Konfiguration '" + config + "': " + e.getMessage(), e);
        }
    }

    public static final class DepartureTimeConverter implements CommandLine.ITypeConverter<LocalDateTime> {
        private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd H:mm")
                .withResolverStyle(ResolverStyle.STRICT);

        @Override
        public LocalDateTime convert(final String value) {
            try {
                if (!value.matches("\\d{4}-\\d{2}-\\d{2} \\d{1,2}:\\d{2}")) {
                    throw new CommandLine.TypeConversionException("Format: YYYY-MM-DD HH:mm (Stunde ein- oder zweistellig), z. B. 2026-11-07 9:00.");
                }
                return LocalDateTime.parse(value, FORMAT);
            } catch (final DateTimeParseException e) {
                throw new CommandLine.TypeConversionException("Ungültige Zeit; Format: YYYY-MM-DD HH:mm (Stunde ein- oder zweistellig).");
            }
        }
    }

    public static void main(final String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
