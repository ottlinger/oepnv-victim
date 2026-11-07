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

import static org.junit.jupiter.api.Assertions.*;

class BvgClientTest {
    private HttpServer server;
    private URI base;
    private final List<String> requests = new ArrayList<>();
    private String response;
    private int status;

    @BeforeEach
    void setUp() throws IOException {
        response = fixture();
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
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
            } else {
                body = response;
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
        assertTrue(requests.get(0).startsWith("/de/verbindungen/fahrplanauskunft?"));
        assertTrue(requests.stream().anyMatch(r -> r.contains("input=Jüterbog, Bahnhof")));
        assertTrue(requests.stream().anyMatch(r -> r.contains("SID=A=1@O=Treuenbrietzen, Bahnhof@")
                && r.contains("ZID=A=1@O=Jüterbog, Bahnhof@") && r.contains("products=127")));
        String page = URLDecoder.decode(result.pageUrl().toString(), StandardCharsets.UTF_8);
        assertTrue(page.contains("destinationName=Jüterbog, Bahnhof"));
        assertTrue(page.contains("regionalTraffic=on"));
        assertTrue(page.contains("timeSel=now"));
        assertTrue(requests.get(0).contains("timeSel=now"));
        String connectionRequest = requests.stream().filter(r -> r.startsWith("/connections?")).findFirst().orElseThrow();
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
        String connectionRequest = requests.stream().filter(r -> r.startsWith("/connections?")).findFirst().orElseThrow();
        for (String uri : List.of(requests.get(0), page, connectionRequest)) {
            assertTrue(uri.contains("timeSel=depart"), uri);
            assertTrue(uri.contains("date=2026-11-07"), uri);
            assertTrue(uri.contains("time=15:22"), uri);
            assertFalse(uri.contains("timeSel=now"), uri);
        }
        assertEquals(1, result.connections().size());
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
        assertThrows(IOException.class, () -> BvgClient.selectStation(locations, "Jüterbog"));
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
