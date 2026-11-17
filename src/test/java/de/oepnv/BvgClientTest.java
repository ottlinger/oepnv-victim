/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BvgClientTest {
    private HttpServer server;
    private URI base;
    private final List<String> requests = new ArrayList<>();
    private String response;
    private String onwardResponse;
    private String viaLocations;
    private int status;

    @BeforeEach
    void setUp() throws IOException {
        response = fixture();
        onwardResponse = "{\"connections\":[]}";
        viaLocations = "[{\"type\":\"HST\",\"name\":\"S Potsdam Hauptbahnhof\",\"id\":\"via-id\"}]";
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (final var exchange) -> {
            String uri = URLDecoder.decode(exchange.getRequestURI().toString(), StandardCharsets.UTF_8);
            requests.add(uri);
            String path = exchange.getRequestURI().getPath();
            String body;
            int code = 200;
            if (path.equals("/de/verbindungen/fahrplanauskunft")) {
                body = html();
            } else if (path.equals("/locations")) {
                boolean from = uri.contains("Treuenbrietzen");
                body = "[{\"type\":\"HST\",\"name\":\"" + (from ? "Treuenbrietzen, Bahnhof" : "Jüterbog, Bahnhof")
                        + "\",\"id\":\"" + (from ? "A=1@O=Treuenbrietzen, Bahnhof@" : "A=1@O=Jüterbog, Bahnhof@") + "\"}]";
                if (uri.contains("input=Potsdam") || uri.contains("input=S Potsdam")) body = viaLocations;
            } else {
                body = uri.contains("SID=via-id") ? onwardResponse : response;
                code = status;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void parsesHtmlAndFetchesDetailsWithResolvedIds() throws Exception {
        var result = new BvgClient(base, Duration.ofSeconds(5))
                .search(new SearchRequest("Treuenbrietzen, Bahnhof", "Jüterbog, Bahnhof", null, 1, null, null));
        assertThat(result.connections()).hasSize(1);
        assertThat(result.connections().get(0).path("legs").get(0).path("service").path("name").asText()).isEqualTo("RB33");
        assertThat(result.destination()).isEqualTo("Jüterbog, Bahnhof");
        assertThat(result.via()).isNull();
        assertThat(requests).hasSize(4);
        assertThat(requests.get(0)).startsWith("/de/verbindungen/fahrplanauskunft?");
        assertThat(requests).anyMatch((final var r) -> r.contains("input=Jüterbog, Bahnhof"));
        assertThat(requests).anyMatch((final var r) -> r.contains("SID=A=1@O=Treuenbrietzen, Bahnhof@")
                && r.contains("ZID=A=1@O=Jüterbog, Bahnhof@") && r.contains("products=127"));
        assertThat(requests.get(0)).contains("timeSel=now");
        String connectionRequest = requests.stream().filter((final var r) -> r.startsWith("/connections?")).findFirst().orElseThrow();
        assertThat(connectionRequest).contains("timeSel=depart");
        for (String uri : List.of(requests.get(0), connectionRequest)) {
            assertThat(uri).doesNotMatch(".*[?&]date=.*").doesNotMatch(".*[?&]time=.*");
        }
    }

    @Test
    void passesConfiguredDepartureToPageAndConnectionSearch() throws Exception {
        var result = new BvgClient(base, Duration.ofSeconds(5))
                .search(new SearchRequest("Treuenbrietzen, Bahnhof", "Jüterbog, Bahnhof", null, 1,
                        LocalDateTime.of(2026, 11, 7, 15, 22), null));
        String connectionRequest = requests.stream().filter((final var r) -> r.startsWith("/connections?")).findFirst().orElseThrow();
        for (String uri : List.of(requests.get(0), connectionRequest)) {
            assertThat(uri).contains("timeSel=depart", "date=2026-11-07", "time=15:22")
                    .doesNotContain("timeSel=now");
        }
        assertThat(result.connections()).hasSize(1);
    }

    @Test
    void passesConfiguredArrivalToPageAndConnectionSearch() throws Exception {
        var result = new BvgClient(base, Duration.ofSeconds(5))
                .search(new SearchRequest("Treuenbrietzen", "Jüterbog", null, 1,
                        null, LocalDateTime.of(2026, 11, 7, 16, 0)));
        String connectionRequest = requests.stream().filter((final var r) -> r.startsWith("/connections?")).findFirst().orElseThrow();
        for (String uri : List.of(requests.get(0), connectionRequest)) {
            assertThat(uri).contains("timeSel=arrive", "date=2026-11-07", "time=16:00");
        }
        assertThat(result.connections()).hasSize(1);
    }

    @Test
    void cliNormalizesSingleDigitHoursForHttpRequests() {
        for (final String option : List.of("--departure", "--arrival")) {
            requests.clear();
            final Main main = new Main((final var request, final var timeout, final var selector) ->
                    new BvgClient(base, timeout, selector).search(request));
            final StringWriter err = new StringWriter();
            assertThat(new CommandLine(main).setOut(new PrintWriter(new StringWriter()))
                    .setErr(new PrintWriter(err)).execute("--from", "Treuenbrietzen", "--to", "Jüterbog",
                            option, "2026-11-07 9:00")).as("%s", err).isZero();
            assertThat(requests).hasSize(4);
            for (final String request : List.of(requests.get(0), requests.get(3))) {
                assertThat(request).contains("time=09:00",
                        "timeSel=" + (option.equals("--arrival") ? "arrive" : "depart"));
            }
        }
    }

    @Test
    void cliLoadsYamlAndQueriesLocalServer(@TempDir final Path directory) throws IOException {
        final Path config = Files.writeString(directory.resolve("journey.yml"), """
                from: Treuenbrietzen
                to: Jüterbog
                arrival: '2026-11-07 9:00'
                """);
        final Main main = new Main((final var request, final var timeout, final var selector) ->
                new BvgClient(base, timeout, selector).search(request));
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        assertThat(new CommandLine(main).setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
                .execute("--config", config.toString(), "--json")).as("%s", err).isZero();
        assertThat(err.toString()).contains("Stationsnamen gespeichert in:");
        assertThat(JourneyConfig.read(config)).isEqualTo(new JourneyConfig(
                "Treuenbrietzen, Bahnhof", "Jüterbog, Bahnhof", null, null, "2026-11-07 9:00"));
        assertThat(BvgClient.JSON.readTree(out.toString())).isNotEmpty();
        assertThat(requests).hasSize(4);
        assertThat(requests.get(3)).contains("timeSel=arrive", "date=2026-11-07", "time=09:00");
    }

    @Test
    void rejectsBothTimesBeforeAnyRequest() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 7, 16, 0);
        assertThatThrownBy(() -> new BvgClient(base, Duration.ofSeconds(5))
                .search(new SearchRequest("Treuenbrietzen", "Jüterbog", null, 1, time, time)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void plansArrivalViaBackwardsAcrossMidnightAndRejectsLateTransfers() throws Exception {
        onwardResponse = "{\"connections\":[" + journey("S Potsdam Hauptbahnhof", "Jüterbog, Bahnhof",
                "2026-11-07T23:59:30", "2026-11-08T00:30:00", "") + "]}";
        response = "{\"connections\":["
                + journey("Treuenbrietzen", "S Potsdam Hauptbahnhof", "2026-11-07T23:30:00", "2026-11-07T23:40:00", "") + ","
                + journey("Treuenbrietzen", "S Potsdam Hauptbahnhof", "2026-11-07T23:40:00", "2026-11-07T23:55:00", "PT2M") + ","
                + journey("Treuenbrietzen", "S Potsdam Hauptbahnhof", "2026-11-07T23:20:00", "2026-11-07T23:35:00", "") + ","
                + journey("Treuenbrietzen", "S Potsdam Hauptbahnhof", "2026-11-07T23:45:00", "2026-11-07T23:58:00", "PT2M") + "]}";
        var result = new BvgClient(base, Duration.ofSeconds(5)).search(new SearchRequest(
                "Treuenbrietzen", "Jüterbog", "Potsdam", 1, null, LocalDateTime.of(2026, 11, 8, 0, 30)));
        assertThat(result.connections()).hasSize(1);
        var combined = result.connections().get(0);
        assertThat(combined.path("summary").path("departure").path("time").asText()).isEqualTo("23:40:00");
        assertThat(combined.path("summary").path("arrival").path("time").asText()).isEqualTo("00:30:00");
        assertThat(combined.path("legs")).hasSize(2);
        assertThat(requests).anyMatch((final var r) -> r.contains("SID=via-id")
                && r.contains("timeSel=arrive") && r.contains("date=2026-11-08") && r.contains("time=00:30"));
        assertThat(requests).anyMatch((final var r) -> r.contains("ZID=via-id")
                && r.contains("timeSel=arrive") && r.contains("date=2026-11-07") && r.contains("time=23:59"));
    }

    @Test
    void arrivalViaWithoutFeasiblePrecedingJourneyReturnsNoResults() throws Exception {
        onwardResponse = "{\"connections\":[" + journey("S Potsdam Hauptbahnhof", "Jüterbog",
                "2026-11-07T15:40:00", "2026-11-07T16:00:00", "") + "]}";
        for (String preceding : List.of("{\"connections\":[]}", "{\"connections\":["
                + journey("Treuenbrietzen", "S Potsdam Hauptbahnhof", "2026-11-07T15:30:00",
                "2026-11-07T15:39:00", "PT2M") + "]}")) {
            response = preceding;
            assertThat(new BvgClient(base, Duration.ofSeconds(5)).search(new SearchRequest(
                    "Treuenbrietzen", "Jüterbog", "Potsdam", 1, null, LocalDateTime.of(2026, 11, 7, 16, 0)))
                    .connections()).isEmpty();
        }
    }

    @Test
    void plansViaWithResolvedStationAndArrivalTimeAcrossMidnight() throws Exception {
        response = "{\"connections\":[" + journey("Treuenbrietzen, Bahnhof", "S Potsdam Hauptbahnhof",
                "2026-11-07T23:55:00", "2026-11-08T00:05:00", "PT2M30S") + "]}";
        onwardResponse = "{\"connections\":["
                + journey("S Potsdam Hauptbahnhof", "Jüterbog, Bahnhof", "2026-11-08T00:07:00", "2026-11-08T00:25:00", "") + ","
                + journey("S Potsdam Hauptbahnhof", "Jüterbog, Bahnhof", "2026-11-08T00:10:00", "2026-11-08T00:30:00", "") + "]}";
        var result = new BvgClient(base, Duration.ofSeconds(5)).search(new SearchRequest(
                "Treuenbrietzen", "Jüterbog", "Potsdam", 1, LocalDateTime.of(2026, 11, 7, 23, 50), null));
        assertThat(result.via()).isEqualTo("S Potsdam Hauptbahnhof");
        assertThat(result.connections()).hasSize(1);
        assertCombinedViaJourney(result.connections().get(0));
        assertThat(requests).anyMatch((final var r) -> r.contains("SID=A=1@O=Treuenbrietzen, Bahnhof@")
                && r.contains("ZID=via-id") && r.contains("time=23:50"));
        assertThat(requests).anyMatch((final var r) -> r.contains("SID=via-id")
                && r.contains("ZID=A=1@O=Jüterbog, Bahnhof@") && r.contains("date=2026-11-08")
                && r.contains("time=00:08"));
        assertThat(requests).hasSize(6);
    }

    private static void assertCombinedViaJourney(final com.fasterxml.jackson.databind.JsonNode combined) {
        assertThat(combined.path("legs")).hasSize(2);
        assertThat(combined.path("legs").get(0).path("arrivalStop").path("name").asText()).isEqualTo("S Potsdam Hauptbahnhof");
        assertThat(combined.path("legs").get(1).path("departure").path("time").asText()).isEqualTo("00:10:00");
        assertThat(combined.path("summary").path("totalDuration").asText()).isEqualTo("PT35M");
        assertThat(combined.path("viaConnections")).hasSize(2);
        assertThat(ConnectionFormatter.format(1, combined)).contains("35 Min.");
    }

    @Test
    void viaWithNoOnwardJourneyReturnsNoResults() throws Exception {
        response = "{\"connections\":[" + journey("Treuenbrietzen", "S Potsdam Hauptbahnhof",
                "2026-11-07T15:22:00", "2026-11-07T15:40:00", "") + "]}";
        assertThat(new BvgClient(base, Duration.ofSeconds(5))
                .search(new SearchRequest("Treuenbrietzen", "Jüterbog", "Potsdam", 1, null, null))
                .connections()).isEmpty();
    }

    @Test
    void ambiguousViaListsStationsBeforePlanningAnyJourney() {
        viaLocations = "[{\"type\":\"HST\",\"name\":\"Potsdam A\",\"id\":\"a\"},"
                + "{\"type\":\"HST\",\"name\":\"Potsdam B\",\"id\":\"b\"}]";
        assertThatThrownBy(() -> new BvgClient(base, Duration.ofSeconds(5))
                .search(new SearchRequest("Treuenbrietzen", "Jüterbog", "Potsdam", 1, null, null)))
                .isInstanceOf(IOException.class).hasMessageContaining("Potsdam A; Potsdam B");
        assertThat(requests).noneMatch((final var r) -> r.startsWith("/connections?"));
    }

    private static String journey(final String from, final String to, final String departure,
                                  final String arrival, final String delay) {
        String departureEvent = "{\"date\":\"" + departure.substring(0, 10) + "\",\"time\":\"" + departure.substring(11) + "\"}";
        String arrivalEvent = "{\"date\":\"" + arrival.substring(0, 10) + "\",\"time\":\"" + arrival.substring(11)
                + "\",\"delay\":\"" + delay + "\"}";
        return "{\"summary\":{\"departure\":" + departureEvent + ",\"arrival\":" + arrivalEvent + "},"
                + "\"legs\":[{\"departure\":" + departureEvent + ",\"arrival\":" + arrivalEvent
                + ",\"departureStop\":{\"name\":\"" + from + "\"},\"arrivalStop\":{\"name\":\"" + to + "\"}}]}";
    }

    @Test
    void emptyResultsAreNotASchemaError() throws Exception {
        response = "{\"connections\":[]}";
        assertThat(new BvgClient(base, Duration.ofSeconds(5))
                .search(new SearchRequest("Treuenbrietzen", "Jüterbog", null, 5, null, null))
                .connections()).isEmpty();
    }

    @Test
    void reportsHttpErrors() {
        status = 503;
        assertThatThrownBy(() -> new BvgClient(base, Duration.ofSeconds(5))
                .search(new SearchRequest("Treuenbrietzen", "Jüterbog", null, 5, null, null)))
                .isInstanceOf(IOException.class).hasMessageContaining("HTTP 503");
    }

    @Test
    void reportsChangedResponseSchema() {
        response = "{\"error\":\"unavailable\"}";
        assertThatThrownBy(() -> new BvgClient(base, Duration.ofSeconds(5))
                .search(new SearchRequest("Treuenbrietzen", "Jüterbog", null, 5, null, null)))
                .isInstanceOf(IOException.class).hasMessageContaining("connections");
    }

    @Test
    void parsesEmbeddedJsonNotVisibleText() throws Exception {
        var endpoints = BvgClient.parseEndpoints(html(), base);
        assertThat(endpoints.locations()).isEqualTo(base.resolve("/locations"));
        assertThat(endpoints.connections()).isEqualTo(base.resolve("/connections"));
    }

    @Test
    void rejectsMissingConfigurationAndForeignEndpoints() {
        assertThatThrownBy(() -> BvgClient.parseEndpoints("<html>Fehlerseite</html>", base))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> BvgClient.parseEndpoints(html().replace("/connections", "https://example.org/connections"), base))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> BvgClient.parseEndpoints("<script id='__NEXT_DATA__'>{}</script>", base))
                .isInstanceOf(IOException.class);
    }

    @Test
    void normalizesPunctuationWhitespaceAndCommonStationAbbreviations() throws Exception {
        final var locations = BvgClient.JSON.readTree("""
                [{"type":"HST","name":"Beelitz, Heilstätten Bhf.","id":"other"},
                 {"type":"HST","name":"Beelitz, Stadt Bhf.","id":"stadt"},
                 {"type":"HST","name":"Beelitz, Stadt Bhf.","id":"stadt"}]
                """);
        for (final String name : List.of("Beelitz Stadt Bhf.", " BEELITZ   Stadt Bahnhof ", "Beelitz, Stadt Bhf.")) {
            assertThat(BvgClient.selectStation(locations, name).id()).isEqualTo("stadt");
        }
        final var mainStations = BvgClient.JSON.readTree("""
                [{"type":"HST","name":"Berlin Hauptbahnhof","id":"main"},
                 {"type":"HST","name":"Berlin Ostbahnhof","id":"east"}]
                """);
        assertThat(BvgClient.selectStation(mainStations, "Berlin Hbf.").id()).isEqualTo("main");
    }

    @Test
    void doesNotGuessBetweenDifferentIdsWithEquivalentNames() throws Exception {
        final var locations = BvgClient.JSON.readTree("""
                [{"type":"HST","name":"Beelitz, Stadt Bhf.","id":"a"},
                 {"type":"HST","name":"Beelitz Stadt Bahnhof","id":"b"}]
                """);
        assertThatThrownBy(() -> BvgClient.selectStation(locations, "Beelitz Stadt Bhf"))
                .isInstanceOf(IOException.class).hasMessageContaining("--interactive");
        assertThat(BvgClient.selectStation(locations, "Beelitz Stadt Bahnhof").id()).isEqualTo("b");
        final var sameNames = BvgClient.JSON.readTree(locations.toString()
                .replace("Beelitz Stadt Bahnhof", "Beelitz, Stadt Bhf."));
        assertThatThrownBy(() -> BvgClient.selectStation(sameNames, "Beelitz, Stadt Bhf."))
                .isInstanceOf(IOException.class).hasMessageContaining("nicht eindeutig");
    }

    @Test
    void interactiveSelectionPassesChosenIdToJourneySearchAndKeepsJsonClean() throws Exception {
        viaLocations = """
                [{"type":"HST","name":"Potsdam A","id":"wrong"},
                 {"type":"HST","name":"Potsdam B","id":"via-id"}]
                """;
        response = "{\"connections\":[" + journey("Treuenbrietzen", "Potsdam B",
                "2026-11-07T15:00:00", "2026-11-07T15:30:00", "") + "]}";
        onwardResponse = "{\"connections\":[" + journey("Potsdam B", "Jüterbog",
                "2026-11-07T15:40:00", "2026-11-07T16:00:00", "") + "]}";
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final Main main = new Main((final var request, final var timeout, final var selector) ->
                new BvgClient(base, timeout, selector).search(request));
        assertThat(executeWithInput(new CommandLine(main).setOut(new PrintWriter(out)).setErr(new PrintWriter(err)),
                "2\n", "--from", "Treuenbrietzen", "--to", "Jüterbog", "--via", "Potsdam", "--json", "--interactive"))
                .isZero();
        assertThat(BvgClient.JSON.readTree(out.toString()).get(0).path("via").asText()).isEqualTo("Potsdam B");
        assertThat(err.toString()).contains("1) Potsdam A", "2) Potsdam B", "Übernommen: Potsdam B");
        assertThat(requests).anyMatch((final var r) -> r.contains("ZID=via-id"))
                .anyMatch((final var r) -> r.contains("SID=via-id"))
                .noneMatch((final var r) -> r.contains("SID=wrong") || r.contains("ZID=wrong"));
    }

    @Test
    void printsSelectedStationBelowJourneysAsReusableYaml(@TempDir final Path directory) throws Exception {
        viaLocations = """
                [{"type":"HST","name":"Potsdam A","id":"wrong"},
                 {"type":"HST","name":"Potsdam B","id":"via-id"}]
                """;
        response = "{\"connections\":[" + journey("Treuenbrietzen", "Potsdam B",
                "2026-11-07T15:00:00", "2026-11-07T15:30:00", "") + "]}";
        onwardResponse = "{\"connections\":[" + journey("Potsdam B", "Jüterbog",
                "2026-11-07T15:40:00", "2026-11-07T16:00:00", "") + "]}";
        final Main.JourneySearch search = (final var request, final var timeout, final var selector) ->
                new BvgClient(base, timeout, selector).search(request);
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        assertThat(executeWithInput(new CommandLine(new Main(search)).setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err)), "2\n", "--from", "Treuenbrietzen", "--to", "Jüterbog",
                "--via", "Potsdam", "--departure", "2026-11-07 15:00", "--interactive")).isZero();
        final int yamlStart = out.toString().indexOf("# Aktuelle Abfrage als YAML");
        assertThat(yamlStart).isGreaterThan(out.toString().indexOf("Verbindung 1:"));
        final String yaml = out.toString().substring(yamlStart);
        final Path config = Files.writeString(directory.resolve("copied.yml"), yaml);
        assertThat(JourneyConfig.read(config)).isEqualTo(new JourneyConfig(
                "Treuenbrietzen, Bahnhof", "Jüterbog, Bahnhof", "Potsdam B", "2026-11-07 15:00", "2026-11-07 16:00"));
        assertThat(yaml).contains("# Bitte zwischen Abfahrtszeit", "entfernen oder auskommentieren")
                .doesNotContain("Potsdam A", "via-id", "SID=", "Übernommen:");
        final StringWriter conflictErr = new StringWriter();
        assertThat(new CommandLine(new Main(search)).setErr(new PrintWriter(conflictErr))
                .execute("--config", config.toString())).isEqualTo(2);
        assertThat(conflictErr.toString()).contains("--departure und --arrival");
        // Follow the exported comment: select departure by commenting out arrival.
        Files.writeString(config, yaml.replace("\narrival:", "\n# arrival:"));
        final StringWriter repeatErr = new StringWriter();
        assertThat(new CommandLine(new Main(search)).setOut(new PrintWriter(new StringWriter()))
                .setErr(new PrintWriter(repeatErr)).execute("--config", config.toString())).isZero();
        assertThat(repeatErr.toString()).isEmpty();
    }

    @Test
    void savesInteractiveSelectionToYamlAndReusesItWithoutPrompt(@TempDir final Path directory) throws Exception {
        final Path config = Files.writeString(directory.resolve("journey.yml"), """
                from: Treuenbrietzen
                to: Jüterbog
                via: Potsdam
                arrival: '2026-11-07 16:00'
                """);
        viaLocations = """
                [{"type":"HST","name":"Potsdam A","id":"wrong"},
                 {"type":"HST","name":"Potsdam B","id":"via-id"}]
                """;
        onwardResponse = "{\"connections\":[]}";
        final StringWriter err = new StringWriter();
        final Main main = new Main((final var request, final var timeout, final var selector) ->
                new BvgClient(base, timeout, selector).search(request));
        assertThat(executeWithInput(new CommandLine(main).setOut(new PrintWriter(new StringWriter()))
                .setErr(new PrintWriter(err)), "2\n", "--config", config.toString(), "--interactive")).isZero();
        assertThat(JourneyConfig.read(config)).isEqualTo(new JourneyConfig(
                "Treuenbrietzen, Bahnhof", "Jüterbog, Bahnhof", "Potsdam B", null, "2026-11-07 16:00"));
        assertThat(err.toString()).contains("Übernommen: Potsdam B", "Stationsnamen gespeichert in:");
        final Main repeat = new Main((final var request, final var timeout, final var selector) ->
                new BvgClient(base, timeout, selector).search(request));
        final StringWriter repeatErr = new StringWriter();
        assertThat(new CommandLine(repeat).setOut(new PrintWriter(new StringWriter())).setErr(new PrintWriter(repeatErr))
                .execute("--config", config.toString())).isZero();
        assertThat(repeatErr.toString()).isEmpty();
    }

    @Test
    void normalizedNameUsesOfficialNameAndIdWithoutPrompting() throws Exception {
        viaLocations = """
                [{"type":"HST","name":"Potsdam, Anderer Bahnhof","id":"other"},
                 {"type":"HST","name":"Potsdam, Stadt Bhf.","id":"stadt"}]
                """;
        response = "{\"connections\":[]}";
        final var result = new BvgClient(base, Duration.ofSeconds(5))
                .search(new SearchRequest("Treuenbrietzen", "Jüterbog", "Potsdam Stadt Bahnhof", 1, null, null));
        assertThat(result.via()).isEqualTo("Potsdam, Stadt Bhf.");
        assertThat(requests).anyMatch((final var r) -> r.contains("ZID=stadt"))
                .noneMatch((final var r) -> r.contains("ZID=other"));
    }

    @Test
    void cliCancellationUsesSharedSelectorAndLeavesYamlUntouched(@TempDir final Path directory) throws Exception {
        final String original = "from: Treuenbrietzen\nto: Jüterbog\nvia: Potsdam\n";
        final Path config = Files.writeString(directory.resolve("journey.yml"), original);
        viaLocations = """
                [{"type":"HST","name":"Potsdam A","id":"a"},
                 {"type":"HST","name":"Potsdam B","id":"b"}]
                """;
        final Main main = new Main((final var request, final var timeout, final var selector) ->
                new BvgClient(base, timeout, selector).search(request));
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        assertThat(executeWithInput(new CommandLine(main).setOut(new PrintWriter(out)).setErr(new PrintWriter(err)),
                "\n", "--config", config.toString(), "--interactive", "--json")).isEqualTo(1);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).contains("Bitte auswählen", "Stationsauswahl abgebrochen");
        assertThat(requests).noneMatch((final var r) -> r.startsWith("/connections?"));
        assertThat(Files.readString(config)).isEqualTo(original);
    }

    @Test
    void cancelledSelectionDoesNotPlanAnyJourney() {
        viaLocations = """
                [{"type":"HST","name":"Potsdam A","id":"a"},
                 {"type":"HST","name":"Potsdam B","id":"b"}]
                """;
        final StationPrompt prompt = new StationPrompt(new StringReader(""), new PrintWriter(new StringWriter()));
        assertThatThrownBy(() -> new BvgClient(base, Duration.ofSeconds(5), prompt)
                .search(new SearchRequest("Treuenbrietzen", "Jüterbog", "Potsdam", 1, null, null)))
                .isInstanceOf(IOException.class).hasMessageContaining("abgebrochen");
        assertThat(requests).noneMatch((final var r) -> r.startsWith("/connections?"));
    }

    @Test
    void selectsExactStationRatherThanFirstSuggestionAndIgnoresPois() throws Exception {
        var locations = BvgClient.JSON.readTree("""
                [{"type":"POI","name":"Jüterbog, Bahnhof","id":"poi"},
                 {"type":"HST","name":"Jüterbog, Bahnhof Süd","id":"south"},
                 {"type":"HST","name":"Jüterbog, Bahnhof","id":"correct"}]
                """);
        assertThat(BvgClient.selectStation(locations, "jüterbog, bahnhof").id()).isEqualTo("correct");
        assertThatThrownBy(() -> BvgClient.selectStation(locations, "Jüterbog"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("nicht eindeutig")
                .hasMessageContaining("Jüterbog, Bahnhof Süd; Jüterbog, Bahnhof")
                .hasMessageNotContaining("poi");
        assertThatThrownBy(() -> BvgClient.selectStation(BvgClient.JSON.readTree("[]"), "unbekannt"))
                .isInstanceOf(IOException.class);
    }

    private static int executeWithInput(final CommandLine command, final String input, final String... args) {
        final InputStream original = System.in;
        try {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            return command.execute(args);
        } finally {
            System.setIn(original);
        }
    }

    static String fixture() throws IOException {
        try (var in = BvgClientTest.class.getResourceAsStream("/connections.json")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String html() {
        return """
                <!doctype html><html><body><form>Verbindungssuche</form>
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"apiPathAutosuggest":"/locations?input=",
                "apiPathConnectionSearch":"/connections"}}}
                </script></body></html>
                """;
    }
}
