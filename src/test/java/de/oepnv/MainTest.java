package de.oepnv;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    @Test
    void helpDoesNotAccessNetwork() {
        StringWriter out = new StringWriter();
        int code = new CommandLine(new Main()).setOut(new PrintWriter(out)).execute("--help");
        assertEquals(0, code);
        assertTrue(out.toString().contains("--from"));
        assertTrue(out.toString().contains("--to"));
        assertTrue(out.toString().contains("--via"));
        assertTrue(out.toString().contains("Stationen müssen eindeutig sein"));
        assertTrue(out.toString().contains("BVG-Stationssuche"));
        assertFalse(out.toString().contains("--startzeit"));
        assertTrue(out.toString().contains("--departure"));
    }

    @Test
    void rendersJourneysAndPassesAllSearchOptionsWithoutNetwork() throws Exception {
        var connection = BvgClient.JSON.readTree(BvgClientTest.fixture()).path("connections").get(0);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main((origin, destination, limit, departure, via, timeout) -> {
            assertEquals("Treuenbrietzen", origin);
            assertEquals("Jüterbog", destination);
            assertEquals(2, limit);
            assertEquals(LocalDateTime.of(2026, 11, 7, 15, 22), departure);
            assertEquals("Potsdam", via);
            assertEquals(Duration.ofSeconds(12), timeout);
            return new BvgClient.Result("Treuenbrietzen, Bahnhof", "Jüterbog, Bahnhof",
                    "S Potsdam Hauptbahnhof", URI.create("https://example.org/not-for-output"),
                    List.of(connection, connection));
        });
        CommandLine command = new CommandLine(main).setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
                .setColorScheme(new CommandLine.Help.ColorScheme.Builder().ansi(CommandLine.Help.Ansi.ON).build());
        int code = command.execute("--from", "Treuenbrietzen", "--to", "Jüterbog", "--via", "Potsdam",
                "--departure", "2026-11-07 15:22", "--limit", "2", "--timeout", "12");
        assertEquals(0, code);
        assertTrue(out.toString().contains("Start: Treuenbrietzen, Bahnhof"));
        assertTrue(out.toString().contains("Ziel:  Jüterbog, Bahnhof"));
        assertTrue(out.toString().contains("Via:   S Potsdam Hauptbahnhof"));
        assertTrue(out.toString().contains("Verbindung 1:"));
        assertTrue(out.toString().contains("Verbindung 2:"));
        assertTrue(out.toString().contains(CommandLine.Help.Ansi.ON.string("@|bold 2026-11-07 12:29|@")));
        assertFalse(out.toString().contains("https://"));
        assertFalse(out.toString().contains("BVG:"));
        assertEquals("", err.toString());
    }

    @Test
    void emitsOnlyOriginalJsonWithoutHeadersOrAnsiCodes() throws Exception {
        var connection = BvgClient.JSON.readTree(BvgClientTest.fixture()).path("connections").get(0);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main((origin, destination, limit, departure, via, timeout) ->
                new BvgClient.Result(origin, destination, via, URI.create("https://example.org"), List.of(connection)));
        CommandLine command = new CommandLine(main).setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
                .setColorScheme(new CommandLine.Help.ColorScheme.Builder().ansi(CommandLine.Help.Ansi.ON).build());
        assertEquals(0, command.execute("--from", "A", "--to", "B", "--json"));
        var json = BvgClient.JSON.readTree(out.toString());
        assertTrue(json.isArray());
        assertEquals(1, json.size());
        assertEquals(connection, json.get(0));
        assertFalse(out.toString().contains("\u001B"));
        assertFalse(out.toString().contains("Start:"));
        assertEquals("", err.toString());
    }

    @Test
    void emptyResultsUseDefaultsAndReturnSuccessWithoutViaHeader() {
        StringWriter out = new StringWriter();
        Main main = new Main((origin, destination, limit, departure, via, timeout) -> {
            assertEquals(5, limit);
            assertEquals(Duration.ofSeconds(30), timeout);
            assertNull(departure);
            assertNull(via);
            return new BvgClient.Result(origin, destination, null, URI.create("https://example.org"), List.of());
        });
        assertEquals(0, new CommandLine(main).setOut(new PrintWriter(out)).execute("--from", "A", "--to", "B"));
        assertTrue(out.toString().contains("Keine Verbindungen gefunden."));
        assertFalse(out.toString().contains("Via:"));
        assertFalse(out.toString().contains("Verbindung 1:"));
    }

    @Test
    void emptyJsonResultsAreAnEmptyArray() throws Exception {
        StringWriter out = new StringWriter();
        Main main = new Main((origin, destination, limit, departure, via, timeout) ->
                new BvgClient.Result(origin, destination, via, URI.create("https://example.org"), List.of()));
        assertEquals(0, new CommandLine(main).setOut(new PrintWriter(out))
                .execute("--from", "A", "--to", "B", "--json"));
        assertEquals(BvgClient.JSON.createArrayNode(), BvgClient.JSON.readTree(out.toString()));
    }

    @Test
    void queryErrorsAreWrittenOnlyToStderr() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main((origin, destination, limit, departure, via, timeout) -> {
            throw new IOException("Station ist nicht eindeutig: Station A; Station B");
        });
        int code = new CommandLine(main).setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
                .execute("--from", "A", "--to", "B");
        assertEquals(1, code);
        assertEquals("", out.toString());
        assertTrue(err.toString().contains("Fehler: Station ist nicht eindeutig: Station A; Station B"));
    }

    @Test
    void interruptedQueryRestoresInterruptFlagAndReturnsError() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main((origin, destination, limit, departure, via, timeout) -> {
            throw new InterruptedException("cancelled");
        });
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            int code = new CommandLine(main).setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
                    .execute("--from", "A", "--to", "B");
            assertEquals(1, code);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(err.toString().contains("Fehler: Abfrage wurde unterbrochen."));
            assertEquals("", out.toString());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void parsesDepartureTimeWithoutNetwork() {
        var parsed = new CommandLine(new Main())
                .parseArgs("--from", "A", "--to", "B", "--departure", "2026-11-07 15:22");
        assertEquals(LocalDateTime.of(2026, 11, 7, 15, 22), parsed.matchedOptionValue("--departure", null));
        var defaults = new CommandLine(new Main()).parseArgs("--from", "A", "--to", "B");
        assertNull(defaults.matchedOptionValue("--departure", null));
    }

    @Test
    void parsesOptionalViaWithoutNetwork() {
        var parsed = new CommandLine(new Main()).parseArgs("--from", "A", "--to", "B", "--via", "Potsdam");
        assertEquals("Potsdam", parsed.matchedOptionValue("--via", null));
        var defaults = new CommandLine(new Main()).parseArgs("--from", "A", "--to", "B");
        assertNull(defaults.matchedOptionValue("--via", null));
    }

    @Test
    void rejectsInvalidDepartureTimesWithoutNetwork() {
        for (String value : new String[]{"", "2026-11-07", "2026-11-07T15:22", "2026-11-07 15:22:00",
                "2026-02-29 15:22", "2026-11-31 15:22", "2026-11-07 24:00", "2026-11-07 15:60",
                "2026-1-07 15:22", " 2026-11-07 15:22", "not a date"}) {
            StringWriter err = new StringWriter();
            int code = new CommandLine(new Main()).setErr(new PrintWriter(err))
                    .execute("--from", "A", "--to", "B", "--departure", value);
            assertEquals(2, code, value);
            assertTrue(err.toString().contains("YYYY-MM-DD HH:mm"), value);
        }
        assertEquals(LocalDateTime.of(2028, 2, 29, 0, 0),
                new Main.DepartureTimeConverter().convert("2028-02-29 00:00"));
    }

    @Test
    void rejectsOldGermanOptionsWithoutNetwork() {
        var command = new CommandLine(new Main());
        assertNull(command.getCommandSpec().findOption("--start"));
        assertNull(command.getCommandSpec().findOption("--ziel"));
        assertNull(command.getCommandSpec().findOption("--startzeit"));
        for (String[] args : new String[][]{
                {"--start", "A", "--to", "B"}, {"--from", "A", "--ziel", "B"},
                {"--from", "A", "--to", "B", "--startzeit", "2026-11-07 15:22"}}) {
            int code = new CommandLine(new Main()).setErr(new PrintWriter(new StringWriter())).execute(args);
            assertEquals(2, code);
        }
    }

    @Test
    void invalidArgumentsReturnUsageErrorWithoutNetwork() {
        for (String[] args : new String[][]{
                {}, {"--from", "A"}, {"--to", "B"},
                {"--from", " ", "--to", "B"}, {"--from", "A", "--to", " "},
                {"--from", "A", "--to", "B", "--via", ""},
                {"--from", "A", "--to", "B", "--via", " "},
                {"--from", "A", "--to", "B", "--via"},
                {"--from", "A", "--to", "B", "--limit", "0"},
                {"--from", "A", "--to", "B", "--timeout", "-1"}}) {
            int code = new CommandLine(new Main()).setErr(new PrintWriter(new StringWriter())).execute(args);
            assertEquals(2, code);
        }
    }
}
