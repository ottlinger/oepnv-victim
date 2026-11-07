package de.oepnv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class BvgClient {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final String PAGE = "/de/verbindungen/fahrplanauskunft";
    private final URI base;
    private final Duration timeout;
    private final HttpClient http;

    BvgClient(Duration timeout) {
        this(URI.create("https://www.bvg.de"), timeout);
    }

    BvgClient(URI base, Duration timeout) {
        this.base = base;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    Result search(String origin, String destination, int limit) throws IOException, InterruptedException {
        return search(origin, destination, limit, null);
    }

    Result search(String origin, String destination, int limit, LocalDateTime departure)
            throws IOException, InterruptedException {
        // Die Verbindungen selbst sind nicht im SSR-HTML. Die Next.js-Konfiguration
        // enthält die Endpunkte, deren Antworten auch die UI-Detailansicht versorgen.
        Map<String, String> initialParams = new LinkedHashMap<>();
        initialParams.put("origin", origin);
        initialParams.put("destination", destination);
        departureParams(initialParams, departure, "now");
        URI initialPage = query(base.resolve(PAGE), initialParams);
        Endpoints endpoints = parseEndpoints(get(initialPage, "text/html"), base);
        Station from = resolveStation(endpoints.locations(), origin);
        Station to = resolveStation(endpoints.locations(), destination);

        Map<String, String> pageParams = new LinkedHashMap<>();
        pageParams.put("origin", from.name());
        pageParams.put("destination", to.name());
        pageParams.put("originId", from.id());
        pageParams.put("originName", from.name());
        pageParams.put("destinationId", to.id());
        pageParams.put("destinationName", to.name());
        departureParams(pageParams, departure, "now");
        for (String product : List.of("sbahn", "subway", "bus", "tram", "ferry",
                "longDistanceTraffic", "regionalTraffic")) {
            pageParams.put(product, "on");
        }
        URI pageUrl = query(base.resolve(PAGE), pageParams);
        Map<String, String> connectionParams = new LinkedHashMap<>(Map.of(
                "language", "de", "SID", from.id(), "ZID", to.id(),
                "products", "127", "realtime", "true"));
        departureParams(connectionParams, departure, "depart");
        URI connectionsUrl = query(endpoints.connections(), connectionParams);
        JsonNode response = JSON.readTree(get(connectionsUrl, "application/json"));
        JsonNode connections = response.path("connections");
        if (!connections.isArray()) {
            throw new IOException("Unerwartete BVG-Antwort: Das Feld 'connections' fehlt oder ist keine Liste.");
        }
        List<JsonNode> selected = new ArrayList<>();
        for (JsonNode connection : connections) {
            if (selected.size() == limit) break;
            if (!connection.path("summary").isObject() || !connection.path("legs").isArray()) {
                throw new IOException("Unerwartete BVG-Antwort: Verbindungsübersicht oder Teilstrecken fehlen.");
            }
            selected.add(connection);
        }
        return new Result(from.name(), to.name(), pageUrl, List.copyOf(selected));
    }

    private static void departureParams(Map<String, String> params, LocalDateTime departure, String defaultSelection) {
        params.put("timeSel", departure == null ? defaultSelection : "depart");
        if (departure != null) {
            params.put("date", departure.toLocalDate().toString());
            params.put("time", departure.format(DateTimeFormatter.ofPattern("HH:mm")));
        }
    }

    private Station resolveStation(URI endpoint, String name) throws IOException, InterruptedException {
        return selectStation(JSON.readTree(get(query(endpoint, Map.of("input", name)), "application/json")), name);
    }

    static Station selectStation(JsonNode locations, String input) throws IOException {
        if (!locations.isArray()) throw new IOException("Unerwartete BVG-Antwort bei der Bahnhofssuche.");
        List<Station> candidates = new ArrayList<>();
        for (JsonNode location : locations) {
            if ("HST".equals(location.path("type").asText()) && !location.path("id").asText().isBlank()
                    && !location.path("name").asText().isBlank()) {
                Station station = new Station(location.path("name").asText(), location.path("id").asText());
                if (!candidates.contains(station)) candidates.add(station);
            }
        }
        List<Station> exact = candidates.stream().filter(s -> s.name().equalsIgnoreCase(input.strip())).toList();
        if (exact.size() == 1) return exact.get(0);
        if (exact.isEmpty() && candidates.size() == 1) return candidates.get(0);
        if (candidates.isEmpty()) throw new IOException("Kein Bahnhof gefunden für '" + input + "'.");
        throw new IOException("Bahnhof '" + input + "' ist nicht eindeutig. Bitte einen vollständigen Namen verwenden: "
                + candidates.stream().map(Station::name).collect(Collectors.joining("; ")));
    }

    static Endpoints parseEndpoints(String html, URI base) throws IOException {
        Element data = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__");
        if (data == null) throw new IOException("BVG-HTML enthält keine __NEXT_DATA__-Konfiguration; Seitenstruktur möglicherweise geändert.");
        JsonNode props = JSON.readTree(data.data()).path("props").path("pageProps");
        String locations = props.path("apiPathAutosuggest").asText();
        String connections = props.path("apiPathConnectionSearch").asText();
        if (locations.isBlank() || connections.isBlank()) {
            throw new IOException("BVG-HTML enthält keine passenden Suchendpunkte.");
        }
        // apiPathAutosuggest enthält derzeit '?input='. Werte werden separat kodiert.
        URI locationsUri = sameOrigin(base, locations.split("\\?", 2)[0]);
        return new Endpoints(locationsUri, sameOrigin(base, connections));
    }

    private static URI sameOrigin(URI base, String path) throws IOException {
        try {
            URI uri = base.resolve(path);
            if (!base.getScheme().equals(uri.getScheme()) || !base.getAuthority().equals(uri.getAuthority())) {
                throw new IOException("BVG-Konfiguration verweist auf einen fremden Server.");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiger Suchendpunkt im BVG-HTML.", e);
        }
    }

    static URI query(URI endpoint, Map<String, String> params) {
        String query = params.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        return URI.create(endpoint + (endpoint.getRawQuery() == null ? "?" : "&") + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String get(URI uri, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", accept).header("Accept-Language", "de-DE,de;q=0.9")
                .header("User-Agent", "oepnv-victim/0.1 (Java CLI)").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("BVG-Abfrage fehlgeschlagen (HTTP " + response.statusCode() + ", " + uri.getPath() + ").");
        }
        return response.body();
    }

    record Station(String name, String id) { }
    record Endpoints(URI locations, URI connections) { }
    record Result(String origin, String destination, URI pageUrl, List<JsonNode> connections) { }
}
