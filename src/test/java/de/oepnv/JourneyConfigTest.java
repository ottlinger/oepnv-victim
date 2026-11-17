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

class JourneyConfigTest {
    @TempDir
    Path directory;

    private Path config(final String yaml) throws IOException {
        return Files.writeString(directory.resolve("connection.yml"), yaml);
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
            final var args = new java.util.ArrayList<>(List.of("--from", "A", "--to", "B", "--via", "C",
                    "--departure", "2026-11-08 10:00", "--limit", "2"));
            args.addAll(configFirst ? 0 : args.size(), List.of("--config", file.toString()));
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
        assertThat(out.toString().strip()).isEqualTo("[ ]");
        assertThat(err.toString()).contains("Stationsnamen gespeichert in:");
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
