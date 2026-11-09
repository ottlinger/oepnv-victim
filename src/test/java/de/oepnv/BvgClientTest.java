/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                .search("Treuenbrietzen, Bahnhof", "Jüterbog, Bahnhof", 1);
        assertEquals(1, result.connections().size());
        assertEquals("RB33", result.connections().get(0).path("legs").get(0).path("service").path("name").asText());
        assertEquals("Jüterbog, Bahnhof", result.destination());
        assertNull(result.via());
        assertEquals(4, requests.size());
        assertTrue(requests.get(0).startsWith("/de/verbindungen/fahrplanauskunft?"));
        assertTrue(requests.stream().anyMatch((final var r) -> r.contains("input=Jüterbog, Bahnhof")));
        assertTrue(requests.stream().anyMatch((final var r) -> r.contains("SID=A=1@O=Treuenbrietzen, Bahnhof@")
                && r.contains("ZID=A=1@O=Jüterbog, Bahnhof@") && r.contains("products=127")));
        String page = URLDecoder.decode(result.pageUrl().toString(), StandardCharsets.UTF_8);
        assertTrue(page.contains("destinationName=Jüterbog, Bahnhof"));
        assertTrue(page.contains("regionalTraffic=on"));
        assertTrue(page.contains("timeSel=now"));
        assertTrue(requests.get(0).contains("timeSel=now"));
        String connectionRequest = requests.stream().filter((final var r) -> r.startsWith("/connections?")).findFirst().orElseThrow();
        assertTrue(connectionRequest.contains("timeSel=depart"));
        for (String uri : List.of(page, requests.get(0), connectionRequest)) {
            assertFalse(uri.matches(".*[?&]date=.*"));
            assertFalse(uri.matches(".*[?&]time=.*"));
        }
    }

    @Test
    void passesConfiguredDepartureToPageAndConnectionSearch() throws Exception {
        var result = new BvgClient(base, Duration.ofSeconds(5))
                .search("Treuenbrietzen, Bahnhof", "Jüterbog, Bahnhof", 1,
                        LocalDateTime.of(2026, 11, 7, 15, 22));
        String page = URLDecoder.decode(result.pageUrl().toString(), StandardCharsets.UTF_8);
        String connectionRequest = requests.stream().filter((final var r) -> r.startsWith("/connections?")).findFirst().orElseThrow();
        for (String uri : List.of(requests.get(0), page, connectionRequest)) {
            assertTrue(uri.contains("timeSel=depart"), uri);
            assertTrue(uri.contains("date=2026-11-07"), uri);
            assertTrue(uri.contains("time=15:22"), uri);
            assertFalse(uri.contains("timeSel=now"), uri);
        }
        assertEquals(1, result.connections().size());
    }

    @Test
    void passesConfiguredArrivalToPageAndConnectionSearch() throws Exception {
        var result = new BvgClient(base, Duration.ofSeconds(5))
                .search("Treuenbrietzen", "Jüterbog", 1, null, LocalDateTime.of(2026, 11, 7, 16, 0), null);
        String page = URLDecoder.decode(result.pageUrl().toString(), StandardCharsets.UTF_8);
        String connectionRequest = requests.stream().filter((final var r) -> r.startsWith("/connections?")).findFirst().orElseThrow();
        for (String uri : List.of(requests.get(0), page, connectionRequest)) {
            assertTrue(uri.contains("timeSel=arrive"), uri);
            assertTrue(uri.contains("date=2026-11-07"), uri);
            assertTrue(uri.contains("time=16:00"), uri);
        }
        assertEquals(1, result.connections().size());
    }

    @Test
    void rejectsBothTimesBeforeAnyRequest() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 7, 16, 0);
        assertThrows(IllegalArgumentException.class, () -> new BvgClient(base, Duration.ofSeconds(5))
                .search("Treuenbrietzen", "Jüterbog", 1, time, time, null));
        assertTrue(requests.isEmpty());
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
        var result = new BvgClient(base, Duration.ofSeconds(5)).search("Treuenbrietzen", "Jüterbog", 1,
                null, LocalDateTime.of(2026, 11, 8, 0, 30), "Potsdam");
        assertEquals(1, result.connections().size());
        var combined = result.connections().get(0);
        assertEquals("23:40:00", combined.path("summary").path("departure").path("time").asText());
        assertEquals("00:30:00", combined.path("summary").path("arrival").path("time").asText());
        assertEquals(2, combined.path("legs").size());
        assertTrue(requests.stream().anyMatch((final var r) -> r.contains("SID=via-id")
                && r.contains("timeSel=arrive") && r.contains("date=2026-11-08") && r.contains("time=00:30")));
        assertTrue(requests.stream().anyMatch((final var r) -> r.contains("ZID=via-id")
                && r.contains("timeSel=arrive") && r.contains("date=2026-11-07") && r.contains("time=23:59")));
    }

    @Test
    void arrivalViaWithoutFeasiblePrecedingJourneyReturnsNoResults() throws Exception {
        onwardResponse = "{\"connections\":[" + journey("S Potsdam Hauptbahnhof", "Jüterbog",
                "2026-11-07T15:40:00", "2026-11-07T16:00:00", "") + "]}";
        for (String preceding : List.of("{\"connections\":[]}", "{\"connections\":["
                + journey("Treuenbrietzen", "S Potsdam Hauptbahnhof", "2026-11-07T15:30:00",
                "2026-11-07T15:39:00", "PT2M") + "]}")) {
            response = preceding;
            assertTrue(new BvgClient(base, Duration.ofSeconds(5)).search("Treuenbrietzen", "Jüterbog", 1,
                    null, LocalDateTime.of(2026, 11, 7, 16, 0), "Potsdam").connections().isEmpty());
        }
    }

    @Test
    void plansViaWithResolvedStationAndArrivalTimeAcrossMidnight() throws Exception {
        response = "{\"connections\":[" + journey("Treuenbrietzen, Bahnhof", "S Potsdam Hauptbahnhof",
                "2026-11-07T23:55:00", "2026-11-08T00:05:00", "PT2M30S") + "]}";
        onwardResponse = "{\"connections\":["
                + journey("S Potsdam Hauptbahnhof", "Jüterbog, Bahnhof", "2026-11-08T00:07:00", "2026-11-08T00:25:00", "") + ","
                + journey("S Potsdam Hauptbahnhof", "Jüterbog, Bahnhof", "2026-11-08T00:10:00", "2026-11-08T00:30:00", "") + "]}";
        var result = new BvgClient(base, Duration.ofSeconds(5)).search("Treuenbrietzen", "Jüterbog", 1,
                LocalDateTime.of(2026, 11, 7, 23, 50), "Potsdam");
        assertEquals("S Potsdam Hauptbahnhof", result.via());
        assertEquals(1, result.connections().size());
        assertCombinedViaJourney(result.connections().get(0));
        assertTrue(requests.stream().anyMatch((final var r) -> r.contains("SID=A=1@O=Treuenbrietzen, Bahnhof@")
                && r.contains("ZID=via-id") && r.contains("time=23:50")));
        assertTrue(requests.stream().anyMatch((final var r) -> r.contains("SID=via-id")
                && r.contains("ZID=A=1@O=Jüterbog, Bahnhof@") && r.contains("date=2026-11-08")
                && r.contains("time=00:08")));
        assertEquals(6, requests.size());
    }

    private static void assertCombinedViaJourney(final com.fasterxml.jackson.databind.JsonNode combined) {
        assertEquals(2, combined.path("legs").size());
        assertEquals("S Potsdam Hauptbahnhof", combined.path("legs").get(0).path("arrivalStop").path("name").asText());
        assertEquals("00:10:00", combined.path("legs").get(1).path("departure").path("time").asText());
        assertEquals("PT35M", combined.path("summary").path("totalDuration").asText());
        assertEquals(2, combined.path("viaConnections").size());
        assertTrue(ConnectionFormatter.format(1, combined).contains("35 Min."));
    }

    @Test
    void viaWithNoOnwardJourneyReturnsNoResults() throws Exception {
        response = "{\"connections\":[" + journey("Treuenbrietzen", "S Potsdam Hauptbahnhof",
                "2026-11-07T15:22:00", "2026-11-07T15:40:00", "") + "]}";
        assertTrue(new BvgClient(base, Duration.ofSeconds(5))
                .search("Treuenbrietzen", "Jüterbog", 1, null, "Potsdam").connections().isEmpty());
    }

    @Test
    void ambiguousViaListsStationsBeforePlanningAnyJourney() {
        viaLocations = "[{\"type\":\"HST\",\"name\":\"Potsdam A\",\"id\":\"a\"},"
                + "{\"type\":\"HST\",\"name\":\"Potsdam B\",\"id\":\"b\"}]";
        IOException error = assertThrows(IOException.class, () -> new BvgClient(base, Duration.ofSeconds(5))
                .search("Treuenbrietzen", "Jüterbog", 1, null, "Potsdam"));
        assertTrue(error.getMessage().contains("Potsdam A; Potsdam B"));
        assertFalse(requests.stream().anyMatch((final var r) -> r.startsWith("/connections?")));
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
        assertTrue(new BvgClient(base, Duration.ofSeconds(5)).search("Treuenbrietzen", "Jüterbog", 5)
                .connections().isEmpty());
    }

    @Test
    void reportsHttpErrors() {
        status = 503;
        IOException error = assertThrows(IOException.class, () -> new BvgClient(base, Duration.ofSeconds(5))
                .search("Treuenbrietzen", "Jüterbog", 5));
        assertTrue(error.getMessage().contains("HTTP 503"));
    }

    @Test
    void reportsChangedResponseSchema() {
        response = "{\"error\":\"unavailable\"}";
        IOException error = assertThrows(IOException.class, () -> new BvgClient(base, Duration.ofSeconds(5))
                .search("Treuenbrietzen", "Jüterbog", 5));
        assertTrue(error.getMessage().contains("connections"));
    }

    @Test
    void parsesEmbeddedJsonNotVisibleText() throws Exception {
        var endpoints = BvgClient.parseEndpoints(html(), base);
        assertEquals(base.resolve("/locations"), endpoints.locations());
        assertEquals(base.resolve("/connections"), endpoints.connections());
    }

    @Test
    void rejectsMissingConfigurationAndForeignEndpoints() {
        assertThrows(IOException.class, () -> BvgClient.parseEndpoints("<html>Fehlerseite</html>", base));
        assertThrows(IOException.class, () -> BvgClient.parseEndpoints(html().replace("/connections", "https://example.org/connections"), base));
        assertThrows(IOException.class, () -> BvgClient.parseEndpoints("<script id='__NEXT_DATA__'>{}</script>", base));
    }

    @Test
    void selectsExactStationRatherThanFirstSuggestionAndIgnoresPois() throws Exception {
        var locations = BvgClient.JSON.readTree("""
                [{"type":"POI","name":"Jüterbog, Bahnhof","id":"poi"},
                 {"type":"HST","name":"Jüterbog, Bahnhof Süd","id":"south"},
                 {"type":"HST","name":"Jüterbog, Bahnhof","id":"correct"}]
                """);
        assertEquals("correct", BvgClient.selectStation(locations, "jüterbog, bahnhof").id());
        IOException ambiguity = assertThrows(IOException.class,
                () -> BvgClient.selectStation(locations, "Jüterbog"));
        assertTrue(ambiguity.getMessage().contains("nicht eindeutig"));
        assertTrue(ambiguity.getMessage().contains("Jüterbog, Bahnhof Süd; Jüterbog, Bahnhof"));
        assertFalse(ambiguity.getMessage().contains("poi"));
        assertThrows(IOException.class, () -> BvgClient.selectStation(BvgClient.JSON.readTree("[]"), "unbekannt"));
    }

    static String fixture() throws IOException {
        try (var in = BvgClientTest.class.getResourceAsStream("/connections.json")) {
            assertNotNull(in);
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
