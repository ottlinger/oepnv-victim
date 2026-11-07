package de.oepnv;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.concurrent.Callable;

@Command(name = "oepnv-victim", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "BVG-Verbindungen inklusive Teilstrecken, Gleisen und Verspätungen.",
        footer = "Stationen müssen eindeutig sein. Bei mehrdeutigen Namen gibt das Tool eine Liste möglicher "
                + "Stationen aus der BVG-Stationssuche aus; bitte mit einem vollständigen Namen erneut aufrufen.")
public final class Main implements Callable<Integer> {
    private final JourneySearch search;

    public Main() {
        this((final var origin, final var destination, final var limit,
              final var departure, final var via, final var timeout) ->
                new BvgClient(timeout).search(origin, destination, limit, departure, via));
    }

    Main(final JourneySearch search) {
        this.search = search;
    }

    @FunctionalInterface
    interface JourneySearch {
        BvgClient.Result search(final String origin, final String destination, final int limit,
                                final LocalDateTime departure, final String via, final Duration timeout) throws IOException, InterruptedException;
    }

    @Option(names = "--from", required = true, paramLabel = "STATION",
            description = "Eindeutige Startstation, z. B. 'Treuenbrietzen, Bahnhof'.")
    private String origin;

    @Option(names = "--to", required = true, paramLabel = "STATION",
            description = "Eindeutige Zielstation, z. B. 'Jüterbog, Bahnhof'.")
    private String destination;

    @Option(names = "--via", paramLabel = "STATION",
            description = "Optionale eindeutige Zwischenstation, z. B. 'S Potsdam Hauptbahnhof'.")
    private String via;

    @Option(names = "--departure", paramLabel = "\"YYYY-MM-DD HH:mm\"",
            converter = DepartureTimeConverter.class,
            description = "Abfahrtszeit in Berlin, z. B. '2026-11-07 15:22' (Standard: jetzt).")
    private LocalDateTime departure;

    @Option(names = "--limit", defaultValue = "5", description = "Maximale Anzahl aus der ersten Ergebnisseite (Standard: 5).")
    private int limit;

    @Option(names = "--json", description = "Verbindungen mit allen gelieferten Details als JSON ausgeben.")
    private boolean json;

    @Option(names = "--timeout", defaultValue = "30", description = "Zeitlimit pro HTTP-Anfrage in Sekunden (Standard: 30).")
    private int timeout;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        if (origin.isBlank() || destination.isBlank() || (via != null && via.isBlank()) || limit < 1 || timeout < 1) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "--from, --to und --via dürfen nicht leer sein; --limit und --timeout müssen größer als 0 sein.");
        }
        try {
            BvgClient.Result result = search.search(origin, destination, limit, departure, via, Duration.ofSeconds(timeout));
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

    public static final class DepartureTimeConverter implements CommandLine.ITypeConverter<LocalDateTime> {
        private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                .withResolverStyle(ResolverStyle.STRICT);

        @Override
        public LocalDateTime convert(final String value) {
            try {
                if (!value.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) {
                    throw new CommandLine.TypeConversionException("Format: YYYY-MM-DD HH:mm, z. B. 2026-11-07 15:22.");
                }
                return LocalDateTime.parse(value, FORMAT);
            } catch (final DateTimeParseException e) {
                throw new CommandLine.TypeConversionException("Ungültige Abfahrtszeit; Format: YYYY-MM-DD HH:mm.");
            }
        }
    }

    public static void main(final String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
