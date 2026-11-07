package de.oepnv;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.time.Duration;
import java.util.concurrent.Callable;

@Command(name = "oepnv-victim", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "BVG-Verbindungen inklusive Teilstrecken, Gleisen und Verspätungen.")
public final class Main implements Callable<Integer> {
    @Option(names = {"--start", "--from"}, required = true, paramLabel = "BAHNHOF",
            description = "Startbahnhof, z. B. 'Treuenbrietzen, Bahnhof'.")
    private String start;

    @Option(names = {"--ziel", "--to"}, required = true, paramLabel = "BAHNHOF",
            description = "Zielbahnhof, z. B. 'Jüterbog, Bahnhof'.")
    private String destination;

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
        if (start.isBlank() || destination.isBlank() || limit < 1 || timeout < 1) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "Start und Ziel dürfen nicht leer sein; --limit und --timeout müssen größer als 0 sein.");
        }
        try {
            BvgClient.Result result = new BvgClient(Duration.ofSeconds(timeout)).search(start, destination, limit);
            if (json) {
                spec.commandLine().getOut().println(BvgClient.JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result.connections()));
            } else {
                spec.commandLine().getOut().println("Start: " + result.origin());
                spec.commandLine().getOut().println("Ziel:  " + result.destination());
                spec.commandLine().getOut().println("BVG:   " + result.pageUrl());
                if (result.connections().isEmpty()) {
                    spec.commandLine().getOut().println("Keine Verbindungen gefunden.");
                }
                int index = 1;
                for (JsonNode connection : result.connections()) {
                    spec.commandLine().getOut().println(ConnectionFormatter.format(index++, connection));
                }
            }
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            spec.commandLine().getErr().println("Fehler: Abfrage wurde unterbrochen.");
            return 1;
        } catch (Exception e) {
            spec.commandLine().getErr().println("Fehler: " + e.getMessage());
            return 1;
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
