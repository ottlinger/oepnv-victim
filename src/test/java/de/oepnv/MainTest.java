/* SPDX-License-Identifier: AGPL-3.0-only */
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class MainTest {
    @Test
    void helpDoesNotAccessNetwork() {
        StringWriter out = new StringWriter();
        int code = new CommandLine(new Main()).setOut(new PrintWriter(out)).execute("--help");
        assertThat(code).isZero();
        assertThat(out.toString()).contains("--from", "--to", "--via", "Stationen müssen eindeutig sein",
                "BVG-Stationssuche", "--departure", "--arrival").doesNotContain("--startzeit");
    }

    @Test
    void rendersJourneysAndPassesAllSearchOptionsWithoutNetwork() throws Exception {
        var connection = BvgClient.JSON.readTree(BvgClientTest.fixture()).path("connections").get(0);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main((final var origin, final var destination, final var limit,
                              final var departure, final var arrival, final var via, final var timeout) -> {
            assertThat(arrival).isNull();
            assertThat(origin).isEqualTo("Treuenbrietzen");
            assertThat(destination).isEqualTo("Jüterbog");
            assertThat(limit).isEqualTo(2);
            assertThat(departure).isEqualTo(LocalDateTime.of(2026, 11, 7, 15, 22));
            assertThat(via).isEqualTo("Potsdam");
            assertThat(timeout).isEqualTo(Duration.ofSeconds(12));
            return new BvgClient.Result("Treuenbrietzen, Bahnhof", "Jüterbog, Bahnhof",
                    "S Potsdam Hauptbahnhof", URI.create("https://example.org/not-for-output"),
                    List.of(connection, connection));
        });
        CommandLine command = new CommandLine(main).setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
                .setColorScheme(new CommandLine.Help.ColorScheme.Builder().ansi(CommandLine.Help.Ansi.ON).build());
        int code = command.execute("--from", "Treuenbrietzen", "--to", "Jüterbog", "--via", "Potsdam",
                "--departure", "2026-11-07 15:22", "--limit", "2", "--timeout", "12");
        assertThat(code).isZero();
        assertThat(out.toString()).contains("Start: Treuenbrietzen, Bahnhof", "Ziel:  Jüterbog, Bahnhof",
                "Via:   S Potsdam Hauptbahnhof", "Verbindung 1:", "Verbindung 2:", " | Gesamt: 18 Min.",
                CommandLine.Help.Ansi.ON.string("@|bold 2026-11-07 12:29|@"))
                .doesNotContain("https://", "BVG:");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void emitsOnlyOriginalJsonWithoutHeadersOrAnsiCodes() throws Exception {
        var connection = BvgClient.JSON.readTree(BvgClientTest.fixture()).path("connections").get(0);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main((final var origin, final var destination, final var limit,
                              final var departure, final var arrival, final var via, final var timeout) ->
                new BvgClient.Result(origin, destination, via, URI.create("https://example.org"), List.of(connection)));
        CommandLine command = new CommandLine(main).setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
                .setColorScheme(new CommandLine.Help.ColorScheme.Builder().ansi(CommandLine.Help.Ansi.ON).build());
        assertThat(command.execute("--from", "A", "--to", "B", "--json")).isZero();
        var json = BvgClient.JSON.readTree(out.toString());
        assertThat(json.isArray()).isTrue();
        assertThat(json).hasSize(1);
        assertThat(json.get(0)).isEqualTo(connection);
        assertThat(out.toString()).doesNotContain("\u001B", "Start:");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void emptyResultsUseDefaultsAndReturnSuccessWithoutViaHeader() {
        StringWriter out = new StringWriter();
        Main main = new Main((final var origin, final var destination, final var limit,
                              final var departure, final var arrival, final var via, final var timeout) -> {
            assertThat(arrival).isNull();
            assertThat(limit).isEqualTo(5);
            assertThat(timeout).isEqualTo(Duration.ofSeconds(30));
            assertThat(departure).isNull();
            assertThat(via).isNull();
            return new BvgClient.Result(origin, destination, null, URI.create("https://example.org"), List.of());
        });
        assertThat(new CommandLine(main).setOut(new PrintWriter(out)).execute("--from", "A", "--to", "B")).isZero();
        assertThat(out.toString()).contains("Keine Verbindungen gefunden.").doesNotContain("Via:", "Verbindung 1:");
    }

    @Test
    void emptyJsonResultsAreAnEmptyArray() throws Exception {
        StringWriter out = new StringWriter();
        Main main = new Main((final var origin, final var destination, final var limit,
                              final var departure, final var arrival, final var via, final var timeout) ->
                new BvgClient.Result(origin, destination, via, URI.create("https://example.org"), List.of()));
        assertThat(new CommandLine(main).setOut(new PrintWriter(out))
                .execute("--from", "A", "--to", "B", "--json")).isZero();
        assertThat(BvgClient.JSON.readTree(out.toString())).isEqualTo(BvgClient.JSON.createArrayNode());
    }

    @Test
    void queryErrorsAreWrittenOnlyToStderr() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main((final var origin, final var destination, final var limit,
                              final var departure, final var arrival, final var via, final var timeout) -> {
            throw new IOException("Station ist nicht eindeutig: Station A; Station B");
        });
        int code = new CommandLine(main).setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
                .execute("--from", "A", "--to", "B");
        assertThat(code).isEqualTo(1);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).contains("Fehler: Station ist nicht eindeutig: Station A; Station B");
    }

    @Test
    void interruptedQueryRestoresInterruptFlagAndReturnsError() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main((final var origin, final var destination, final var limit,
                              final var departure, final var arrival, final var via, final var timeout) -> {
            throw new InterruptedException("cancelled");
        });
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        try {
            int code = new CommandLine(main).setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
                    .execute("--from", "A", "--to", "B");
            assertThat(code).isEqualTo(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(err.toString()).contains("Fehler: Abfrage wurde unterbrochen.");
            assertThat(out.toString()).isEmpty();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void parsesDepartureTimeWithoutNetwork() {
        var parsed = new CommandLine(new Main())
                .parseArgs("--from", "A", "--to", "B", "--departure", "2026-11-07 15:22");
        assertThat(parsed.<LocalDateTime>matchedOptionValue("--departure", null))
                .isEqualTo(LocalDateTime.of(2026, 11, 7, 15, 22));
        var defaults = new CommandLine(new Main()).parseArgs("--from", "A", "--to", "B");
        assertThat(defaults.<LocalDateTime>matchedOptionValue("--departure", null)).isNull();
    }

    @Test
    void passesArrivalTimeToSearchWithoutNetwork() {
        Main main = new Main((final var origin, final var destination, final var limit,
                              final var departure, final var arrival, final var via, final var timeout) -> {
            assertThat(departure).isNull();
            assertThat(arrival).isEqualTo(LocalDateTime.of(2026, 11, 7, 16, 0));
            assertThat(via).isEqualTo("Potsdam");
            return new BvgClient.Result(origin, destination, via, URI.create("https://example.org"), List.of());
        });
        assertThat(new CommandLine(main).setOut(new PrintWriter(new StringWriter()))
                .execute("--from", "A", "--to", "B", "--via", "Potsdam", "--arrival", "2026-11-07 16:00")).isZero();
    }

    @Test
    void acceptsOneAndTwoDigitHoursForBothTimeOptions() {
        for (final String option : List.of("--departure", "--arrival")) {
            for (final String time : List.of("0:00", "00:00", "9:00", "09:00", "9:15", "09:15", "23:59")) {
                final LocalDateTime expected = LocalDateTime.of(2026, 11, 7,
                        Integer.parseInt(time.split(":")[0]), Integer.parseInt(time.split(":")[1]));
                final Main main = new Main((final var origin, final var destination, final var limit,
                                            final var departure, final var arrival, final var via, final var timeout) -> {
                    assertThat(option.equals("--departure") ? departure : arrival).isEqualTo(expected);
                    assertThat(option.equals("--departure") ? arrival : departure).isNull();
                    return new BvgClient.Result(origin, destination, via, URI.create("https://example.org"), List.of());
                });
                assertThat(new CommandLine(main).setOut(new PrintWriter(new StringWriter()))
                        .execute("--from", "A", "--to", "B", option, "2026-11-07 " + time))
                        .as("%s %s", option, time).isZero();
            }
        }
    }

    @Test
    void rejectsConflictingTimesWithoutNetwork() {
        StringWriter err = new StringWriter();
        Main main = new Main((final var origin, final var destination, final var limit,
                              final var departure, final var arrival, final var via, final var timeout) -> {
            return fail("Invalid arguments must not trigger a search");
        });
        assertThat(new CommandLine(main).setErr(new PrintWriter(err)).execute(
                "--from", "A", "--to", "B", "--departure", "2026-11-07 15:22", "--arrival", "2026-11-07 16:00"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("--departure und --arrival");
    }

    @Test
    void parsesOptionalViaWithoutNetwork() {
        var parsed = new CommandLine(new Main()).parseArgs("--from", "A", "--to", "B", "--via", "Potsdam");
        assertThat(parsed.<String>matchedOptionValue("--via", null)).isEqualTo("Potsdam");
        var defaults = new CommandLine(new Main()).parseArgs("--from", "A", "--to", "B");
        assertThat(defaults.<String>matchedOptionValue("--via", null)).isNull();
    }

    @Test
    void rejectsInvalidDepartureTimesWithoutNetwork() {
        for (String value : new String[]{"", "2026-11-07", "2026-11-07T15:22", "2026-11-07 15:22:00",
                "2026-02-29 15:22", "2026-11-31 15:22", "2026-11-07 24:00", "2026-11-07 15:60",
                "2026-1-07 15:22", " 2026-11-07 15:22", "2026-11-07 9:0", "2026-11-07 009:00",
                "2026-11-07 9:60", "2026-11-07 9:00 Uhr", "not a date"}) {
            for (String option : List.of("--departure", "--arrival")) {
                StringWriter err = new StringWriter();
                int code = new CommandLine(new Main()).setErr(new PrintWriter(err))
                        .execute("--from", "A", "--to", "B", option, value);
                assertThat(code).as("%s", value).isEqualTo(2);
                assertThat(err.toString()).as("%s", value).contains("YYYY-MM-DD HH:mm");
            }
        }
        assertThat(new Main.DepartureTimeConverter().convert("2028-02-29 00:00"))
                .isEqualTo(LocalDateTime.of(2028, 2, 29, 0, 0));
    }

    @Test
    void rejectsOldGermanOptionsWithoutNetwork() {
        var command = new CommandLine(new Main());
        assertThat(command.getCommandSpec().findOption("--start")).isNull();
        assertThat(command.getCommandSpec().findOption("--ziel")).isNull();
        assertThat(command.getCommandSpec().findOption("--startzeit")).isNull();
        for (String[] args : new String[][]{
                {"--start", "A", "--to", "B"}, {"--from", "A", "--ziel", "B"},
                {"--from", "A", "--to", "B", "--startzeit", "2026-11-07 15:22"}}) {
            int code = new CommandLine(new Main()).setErr(new PrintWriter(new StringWriter())).execute(args);
            assertThat(code).isEqualTo(2);
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
            assertThat(code).isEqualTo(2);
        }
    }
}
