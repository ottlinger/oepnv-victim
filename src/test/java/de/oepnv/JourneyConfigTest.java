/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyConfigTest {
    @TempDir
    Path directory;

    private Path config(final String yaml) throws IOException {
        return Files.writeString(directory.resolve("connection.yml"), yaml);
    }

    @Test
    void exportsResolvedNamesAndConcreteQueryTimeWhenNoTimeWasSpecified() throws IOException {
        final SearchRequest request = new SearchRequest("Beelitz", "Berlin", null, 2, null, null);
        final BvgClient.Result result = new BvgClient.Result(
                "Beelitz, Stadt Bhf.", "Berlin: Halt #1 \"Nord\"\\Süd\nBahnsteig", null, List.of());
        final String yaml = JourneyConfig.queryYaml(request, result, LocalDateTime.of(2026, 11, 7, 9, 15, 42));
        assertThat(JourneyConfig.read(config(yaml))).isEqualTo(new JourneyConfig(
                result.origin(), result.destination(), null, "2026-11-07 09:15", "YYYY-MM-DD HH:mm"));
        assertThat(yaml).startsWith("# SPDX-License-Identifier: AGPL-3.0-only\n")
                .contains("# Bitte zwischen Abfahrtszeit", "entfernen oder auskommentieren")
                .doesNotContain("via:", "limit:", "timeout:", "null");
        assertThat(yaml.indexOf("# Bitte zwischen")).isLessThan(yaml.indexOf("departure:"));
    }

    @Test
    void exportsViaAndRequestedTimeRatherThanJourneyTimes() throws IOException {
        final LocalDateTime time = LocalDateTime.of(2026, 11, 7, 9, 15);
        final BvgClient.Result result = new BvgClient.Result("Start", "Ziel", "Ausgewählte Station", List.of());
        for (final boolean arrival : List.of(false, true)) {
            final SearchRequest request = new SearchRequest("A", "B", "Suchbegriff", 5,
                    arrival ? null : time, arrival ? time : null);
            final String yaml = JourneyConfig.queryYaml(request, result, time.plusDays(1));
            assertThat(JourneyConfig.read(config(yaml))).isEqualTo(new JourneyConfig(
                    "Start", "Ziel", "Ausgewählte Station",
                    arrival ? "YYYY-MM-DD HH:mm" : "2026-11-07 09:15",
                    arrival ? "2026-11-07 09:15" : "YYYY-MM-DD HH:mm"));
        }
    }

    @Test
    void usesFirstJourneyForTheAdditionalTimeAcrossMidnight() throws IOException {
        final var connection = BvgClient.JSON.readTree("""
                {"summary":{"departure":{"date":"2026-11-07","time":"23:50:00"},
                            "arrival":{"date":"2026-11-08","time":"00:30:00"}}}
                """);
        final BvgClient.Result result = new BvgClient.Result("Start", "Ziel", null, List.of(connection));
        final LocalDateTime queryTime = LocalDateTime.of(2026, 11, 1, 12, 0);
        final SearchRequest departure = new SearchRequest("A", "B", null, 5,
                LocalDateTime.of(2026, 11, 7, 23, 45), null);
        assertThat(JourneyConfig.read(config(JourneyConfig.queryYaml(departure, result, queryTime))))
                .isEqualTo(new JourneyConfig("Start", "Ziel", null, "2026-11-07 23:45", "2026-11-08 00:30"));
        final SearchRequest arrival = new SearchRequest("A", "B", null, 5,
                null, LocalDateTime.of(2026, 11, 8, 0, 40));
        assertThat(JourneyConfig.read(config(JourneyConfig.queryYaml(arrival, result, queryTime))))
                .isEqualTo(new JourneyConfig("Start", "Ziel", null, "2026-11-07 23:50", "2026-11-08 00:40"));
    }

    @Test
    void usesPlaceholderWhenAdditionalResultTimeIsMissing() throws IOException {
        final BvgClient.Result result = new BvgClient.Result("Start", "Ziel", null,
                List.of(BvgClient.JSON.readTree("{\"summary\":{}}")));
        final SearchRequest request = new SearchRequest("A", "B", null, 5, null, null);
        assertThat(JourneyConfig.queryYaml(request, result, LocalDateTime.of(2026, 11, 7, 9, 0)))
                .contains("arrival: \"YYYY-MM-DD HH:mm\"", "Platzhalter bitte ergänzen");
    }

    @Test
    void readsWithFastTrainsAndAllowsExplicitCliOverride() throws IOException {
        final Path file = config("from: Start\nto: Ziel\nwithFastTrains: true\n");
        for (final boolean enabled : List.of(true, false)) {
            final Main main = new Main((final var request, final var timeout, final var selector) -> {
                assertThat(request.withFastTrains()).isEqualTo(enabled);
                return new BvgClient.Result("Start", "Ziel", null, List.of());
            });
            final CommandLine cli = new CommandLine(main).setOut(new PrintWriter(new StringWriter()));
            assertThat(enabled ? cli.execute("--config", file.toString())
                    : cli.execute("--config", file.toString(), "--with-fast-trains=false")).isZero();
        }
        assertThat(JourneyConfig.read(file).withFastTrains()).isTrue();
        assertThat(JourneyConfig.read(config("withFastTrains: false\n")).withFastTrains()).isFalse();
        assertThat(JourneyConfig.read(config("from: Start\n")).withFastTrains()).isFalse();
    }

    @Test
    void exportsAndReloadsExplicitLongDistanceSelection() throws IOException {
        final SearchRequest request = new SearchRequest("Start", "Ziel", null, 5, null, null, true);
        final BvgClient.Result result = new BvgClient.Result("Start", "Ziel", null, List.of());
        final String yaml = JourneyConfig.queryYaml(request, result, LocalDateTime.of(2026, 11, 7, 9, 0));
        assertThat(yaml).contains("withFastTrains: true");
        assertThat(JourneyConfig.read(config(yaml)).withFastTrains()).isTrue();
    }

    @Test
    void rejectsNonBooleanWithFastTrains() {
        for (final String value : List.of("'true'", "1", "null", "[]")) {
            assertThatThrownBy(() -> JourneyConfig.read(config("withFastTrains: " + value + "\n")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("true oder false");
        }
    }

    @Test
    void readsMinimalConnectionWithDefaults() throws IOException {
        final Path file = config("# Saved connection\nfrom: Treuenbrietzen\nto: Jüterbog\n");
        final Main main = new Main((final var request, final var timeout, final var selector) -> {
            assertThat(request).isEqualTo(new SearchRequest("Treuenbrietzen", "Jüterbog", null, 5, null, null));
            assertThat(timeout).isEqualTo(Duration.ofSeconds(30));
            return new BvgClient.Result(request.from(), request.to(), request.via(), List.of());
        });
        assertThat(new CommandLine(main).setOut(new PrintWriter(new StringWriter()))
                .execute("--config", file.toString())).isZero();
    }

    @Test
    void readsViaAndBothTimeVariants() throws IOException {
        for (final String option : List.of("departure", "arrival")) {
            final Path file = config("from: 'Treuenbrietzen, Bahnhof'\nto: 'Jüterbog, Bahnhof'\n"
                    + "via: Potsdam\n" + option + ": '2026-11-07 9:15'\n");
            final Main main = new Main((final var request, final var timeout, final var selector) -> {
                assertThat(request.from()).isEqualTo("Treuenbrietzen, Bahnhof");
                assertThat(request.to()).isEqualTo("Jüterbog, Bahnhof");
                assertThat(request.via()).isEqualTo("Potsdam");
                assertThat(option.equals("departure") ? request.departure() : request.arrival())
                        .isEqualTo(LocalDateTime.of(2026, 11, 7, 9, 15));
                assertThat(option.equals("departure") ? request.arrival() : request.departure()).isNull();
                return new BvgClient.Result(request.from(), request.to(), request.via(), List.of());
            });
            assertThat(new CommandLine(main).setOut(new PrintWriter(new StringWriter()))
                    .execute("--config", file.toString())).isZero();
        }
    }

    @Test
    void cliOverridesMatchingFieldsRegardlessOfOptionOrder() throws IOException {
        final Path file = config("from: saved\nto: saved\nvia: saved\ndeparture: '2026-11-07 9:00'\n");
        for (final boolean configFirst : List.of(true, false)) {
            final Main main = new Main((final var request, final var timeout, final var selector) -> {
                assertThat(request).isEqualTo(new SearchRequest("A", "B", "C", 2,
                        LocalDateTime.of(2026, 11, 8, 10, 0), null));
                return new BvgClient.Result(request.from(), request.to(), request.via(), List.of());
            });
            final var args = new java.util.ArrayList<>(List.of("-f", "A", "--to", "B", "-v", "C",
                    "-d", "2026-11-08 10:00", "-l", "2"));
            args.addAll(configFirst ? 0 : args.size(), List.of("-c", file.toString()));
            assertThat(new CommandLine(main).setOut(new PrintWriter(new StringWriter()))
                    .execute(args.toArray(String[]::new))).isZero();
        }
    }

    @Test
    void combinesPartialConfigWithCli() throws IOException {
        final Path file = config("from: A\n");
        final Main main = new Main((final var request, final var timeout, final var selector) -> {
            assertThat(request.from()).isEqualTo("A");
            assertThat(request.to()).isEqualTo("B");
            return new BvgClient.Result(request.from(), request.to(), request.via(), List.of());
        });
        assertThat(new CommandLine(main).setOut(new PrintWriter(new StringWriter()))
                .execute("--config", file.toString(), "--to", "B")).isZero();
    }

    @Test
    void savesOnlyYamlStationsAndKeepsTimeValues() throws IOException {
        final Path file = config("from: saved\nto: saved\nvia: saved\narrival: '2026-11-07 9:00'\n");
        final Main main = new Main((final var request, final var timeout, final var selector) ->
                new BvgClient.Result("CLI Start", "Resolved destination", "CLI Via", List.of()));
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        assertThat(new CommandLine(main).setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
                .execute("--config", file.toString(), "--from", "Override", "--via", "Override", "--json")).isZero();
        assertThat(JourneyConfig.read(file)).isEqualTo(new JourneyConfig(
                "saved", "Resolved destination", "saved", null, "2026-11-07 9:00"));
        assertThat(Files.readString(file)).startsWith("# SPDX-License-Identifier: AGPL-3.0-only\n");
        assertThat(out.toString().strip()).isEqualTo("[ ]");
        assertThat(err.toString()).contains("Stationsnamen gespeichert in:");
    }

    @Test
    void shortStationOptionsOverrideConfigWithoutSavingCliStations() throws IOException {
        final String original = "# Keep saved stations\nfrom: saved origin\nto: saved destination\n";
        final Path file = config(original);
        final Main main = new Main((final var request, final var timeout, final var selector) -> {
            assertThat(request.from()).isEqualTo("CLI origin");
            assertThat(request.to()).isEqualTo("CLI destination");
            return new BvgClient.Result("Resolved CLI origin", "Resolved CLI destination", null, List.of());
        });
        final StringWriter err = new StringWriter();
        assertThat(new CommandLine(main).setOut(new PrintWriter(new StringWriter())).setErr(new PrintWriter(err))
                .execute("-c", file.toString(), "-f", "CLI origin", "-t", "CLI destination")).isZero();
        assertThat(Files.readString(file)).isEqualTo(original);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void keepsSingleLicenseHeaderWhenUpdatingExportedTemplate() throws IOException {
        final SearchRequest request = new SearchRequest("A", "B", null, 5, null, null);
        final BvgClient.Result result = new BvgClient.Result("A", "B", null, List.of());
        final Path file = config(JourneyConfig.queryYaml(request, result, LocalDateTime.of(2026, 11, 7, 9, 0)));
        assertThat(JourneyConfig.updateStations(file, java.util.Map.of("from", "Resolved A"))).isTrue();
        assertThat(Files.readString(file)).startsWith("# SPDX-License-Identifier: AGPL-3.0-only\n");
        assertThat(Files.readString(file).lines()
                .filter((final String line) -> line.contains("SPDX-License-Identifier")).count()).isEqualTo(1);
        assertThat(JourneyConfig.read(file).from()).isEqualTo("Resolved A");
    }

    @Test
    void doesNotAddCliOnlyStationsOrRewriteUnchangedFile() throws IOException {
        final String original = "# Keep this comment\nfrom: A\n";
        final Path file = config(original);
        final Main main = new Main((final var request, final var timeout, final var selector) ->
                new BvgClient.Result("A", "Resolved B", "Resolved C", List.of()));
        final StringWriter err = new StringWriter();
        assertThat(new CommandLine(main).setOut(new PrintWriter(new StringWriter())).setErr(new PrintWriter(err))
                .execute("--config", file.toString(), "--to", "B", "--via", "C")).isZero();
        assertThat(Files.readString(file)).isEqualTo(original);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void failedSearchDoesNotModifyConfig() throws IOException {
        final String original = "# Keep this comment\nfrom: A\nto: B\n";
        final Path file = config(original);
        final Main main = new Main((final var request, final var timeout, final var selector) -> {
            throw new IOException("Stationsauswahl abgebrochen");
        });
        assertThat(new CommandLine(main).setErr(new PrintWriter(new StringWriter()))
                .execute("--config", file.toString())).isEqualTo(1);
        assertThat(Files.readString(file)).isEqualTo(original);
    }

    @Test
    void rejectsInvalidFilesBeforeSearching() throws IOException {
        for (final String yaml : List.of("", "---\n", "[]", "a string", "from: [", "{}", "from: A", "to: B",
                "from: ''\nto: B", "from: A\nto: ' '", "from: A\nto: B\nvia: null",
                "from: 123\nto: B", "from: A\nto: [B]", "from: A\nto: B\nunknown: C",
                "from: A\nfrom: B\nto: C", "from: A\nto: B\n---\nfrom: C\nto: D",
                "from: A\nto: B\ndeparture: 'not a date'", "from: A\nto: B\narrival: '2026-02-29 9:00'",
                "from: A\nto: B\ndeparture: '2026-11-07 9:00'\narrival: '2026-11-07 10:00'")) {
            final Path file = config(yaml);
            final StringWriter err = new StringWriter();
            assertThat(commandWithoutSearch(err).execute("--config", file.toString()))
                    .as("YAML: %s; stderr: %s", yaml, err).isEqualTo(2);
            assertThat(err.toString()).isNotBlank();
        }
        final StringWriter err = new StringWriter();
        assertThat(commandWithoutSearch(err).execute("--config", directory.resolve("missing.yml").toString()))
                .isEqualTo(2);
        assertThat(err.toString()).contains("missing.yml");
    }

    @Test
    void rejectsConflictingTimesAcrossConfigAndCli() throws IOException {
        final Path file = config("from: A\nto: B\ndeparture: '2026-11-07 9:00'");
        final StringWriter err = new StringWriter();
        assertThat(commandWithoutSearch(err).execute("--config", file.toString(),
                "--arrival", "2026-11-07 10:00")).isEqualTo(2);
        assertThat(err.toString()).contains("--departure und --arrival");
    }

    @Test
    void helpDoesNotReadConfig() {
        assertThat(commandWithoutSearch(new StringWriter()).setOut(new PrintWriter(new StringWriter()))
                .execute("--config", directory.resolve("missing.yml").toString(), "--help")).isZero();
    }

    private CommandLine commandWithoutSearch(final StringWriter err) {
        return new CommandLine(new Main((final var request, final var timeout, final var selector) ->
                fail("Invalid configuration must not trigger a search"))).setErr(new PrintWriter(err));
    }
}
