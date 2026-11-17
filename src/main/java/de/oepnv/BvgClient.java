/* SPDX-License-Identifier: AGPL-3.0-only */
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
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

final class BvgClient {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final String PAGE = "/de/verbindungen/fahrplanauskunft";
    private final URI base;
    private final Duration timeout;
    private final HttpClient http;
    private final StationSelector stationSelector;

    BvgClient(final URI base, final Duration timeout) {
        this(base, timeout, BvgClient::ambiguousStation);
    }

    BvgClient(final Duration timeout, final StationSelector stationSelector) {
        this(URI.create("https://www.bvg.de"), timeout, stationSelector);
    }

    BvgClient(final URI base, final Duration timeout, final StationSelector stationSelector) {
        this.stationSelector = stationSelector;
        this.base = base;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    Result search(final SearchRequest request) throws IOException, InterruptedException {
        // Journey data are not included in the server-rendered HTML. The Next.js configuration
        // provides the endpoints whose responses also populate the UI's journey details panel.
        Map<String, String> initialParams = new LinkedHashMap<>();
        initialParams.put("origin", request.from());
        initialParams.put("destination", request.to());
        timeParams(initialParams, request.departure(), request.arrival(), "now");
        URI initialPage = query(base.resolve(PAGE), initialParams);
        Endpoints endpoints = parseEndpoints(get(initialPage, "text/html"), base);
        final Station from = resolveStation(endpoints.locations(), request.from());
        final Station to = resolveStation(endpoints.locations(), request.to());
        final Station via = request.via() == null ? null : resolveStation(endpoints.locations(), request.via());
        // BVG product bits: S-Bahn 1, U-Bahn 2, tram 4, bus 8, ferry 16,
        // long-distance 32, regional rail 64.
        final String products = request.withFastTrains() ? "127" : "95";
        final List<JsonNode> selected;
        if (via == null) {
            selected = searchConnections(endpoints.connections(), from, to,
                    request.limit(), request.departure(), request.arrival(), products);
        } else if (request.arrival() != null) {
            selected = searchViaArrival(endpoints.connections(), from, via, to, request, products);
        } else {
            selected = searchViaDeparture(endpoints.connections(), from, via, to, request, products);
        }
        return new Result(from.name(), to.name(), via == null ? null : via.name(), selected);
    }

    private List<JsonNode> searchViaDeparture(final URI endpoint, final Station from, final Station intermediate,
                                             final Station to, final SearchRequest request, final String products)
            throws IOException, InterruptedException {
        final List<JsonNode> preceding = searchConnections(endpoint, from, intermediate,
                request.limit(), request.departure(), null, products);
        final List<JsonNode> combined = new ArrayList<>();
        for (final JsonNode first : preceding) {
            final LocalDateTime ready = ViaJourney.effectiveTime(first.path("summary").path("arrival"));
            // The endpoint accepts minutes only: round up rather than request a departure before arrival.
            final LocalDateTime onwardTime = ready.getSecond() == 0 ? ready : ready.plusMinutes(1).withSecond(0);
            final List<JsonNode> onward = searchConnections(endpoint, intermediate, to,
                    Integer.MAX_VALUE, onwardTime, null, products);
            for (final JsonNode second : onward) {
                if (!ViaJourney.effectiveTime(second.path("summary").path("departure")).isBefore(ready)) {
                    combined.add(ViaJourney.combine(first, second, intermediate.name()));
                    break;
                }
            }
        }
        return List.copyOf(combined);
    }

    private List<JsonNode> searchViaArrival(final URI endpoint, final Station from, final Station intermediate,
                                           final Station to, final SearchRequest request, final String products)
            throws IOException, InterruptedException {
        final List<JsonNode> onward = searchConnections(endpoint, intermediate, to,
                request.limit(), null, request.arrival(), products);
        final List<JsonNode> combined = new ArrayList<>();
        for (JsonNode second : onward) {
            LocalDateTime latest = ViaJourney.effectiveTime(second.path("summary").path("departure"));
            List<JsonNode> preceding = searchConnections(endpoint, from, intermediate,
                    Integer.MAX_VALUE, null, latest.withSecond(0).withNano(0), products);
            JsonNode best = null;
            for (JsonNode first : preceding) {
                LocalDateTime ready = ViaJourney.effectiveTime(first.path("summary").path("arrival"));
                if (!ready.isAfter(latest) && (best == null || ready.isAfter(
                        ViaJourney.effectiveTime(best.path("summary").path("arrival"))))) {
                    best = first;
                }
            }
            if (best != null) combined.add(ViaJourney.combine(best, second, intermediate.name()));
        }
        return List.copyOf(combined);
    }

    private List<JsonNode> searchConnections(final URI endpoint, final Station from, final Station to,
                                            final int limit, final LocalDateTime departure,
                                            final LocalDateTime arrival, final String products) throws IOException, InterruptedException {
        Map<String, String> connectionParams = new LinkedHashMap<>(Map.of(
                "language", "de", "SID", from.id(), "ZID", to.id(),
                "products", products, "realtime", "true"));
        timeParams(connectionParams, departure, arrival, "depart");
        URI connectionsUrl = query(endpoint, connectionParams);
        JsonNode response = JSON.readTree(get(connectionsUrl, "application/json"));
        if (response.isObject() && response.isEmpty()) {
            throw new IOException("Die BVG-Auskunft liefert für '" + from.name() + "' → '" + to.name()
                    + "' keine Verbindungsdaten (leere Antwort). "
                    + "Ob eine Verbindung existiert, lässt sich damit nicht feststellen. "
                    + "Bitte die Strecke in einer anderen Reiseauskunft prüfen, z. B. bei der Deutschen Bahn.");
        }
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
        return List.copyOf(selected);
    }

    private static void timeParams(final Map<String, String> params, final LocalDateTime departure,
                                   final LocalDateTime arrival, final String defaultSelection) {
        LocalDateTime time = arrival == null ? departure : arrival;
        params.put("timeSel", arrival != null ? "arrive" : departure == null ? defaultSelection : "depart");
        if (time != null) {
            params.put("date", time.toLocalDate().toString());
            params.put("time", time.format(DateTimeFormatter.ofPattern("HH:mm")));
        }
    }

    private Station resolveStation(final URI endpoint, final String name) throws IOException, InterruptedException {
        return selectStation(JSON.readTree(get(query(endpoint, Map.of("input", name)), "application/json")),
                name, stationSelector);
    }

    static Station selectStation(final JsonNode locations, final String input) throws IOException {
        return selectStation(locations, input, BvgClient::ambiguousStation);
    }

    static Station selectStation(final JsonNode locations, final String input, final StationSelector selector)
            throws IOException {
        if (!locations.isArray()) throw new IOException("Unerwartete BVG-Antwort bei der Bahnhofssuche.");
        List<Station> candidates = new ArrayList<>();
        for (JsonNode location : locations) {
            if ("HST".equals(location.path("type").asText()) && !location.path("id").asText().isBlank()
                    && !location.path("name").asText().isBlank()) {
                Station station = new Station(location.path("name").asText(), location.path("id").asText());
                if (!candidates.contains(station)) candidates.add(station);
            }
        }
        List<Station> exact = candidates.stream().filter((final var s) -> s.name().equalsIgnoreCase(input.strip())).toList();
        if (exact.size() == 1) return exact.get(0);
        if (candidates.size() == 1) return candidates.get(0);
        if (candidates.isEmpty()) throw new IOException("Kein Bahnhof gefunden für '" + input + "'.");
        final String normalized = normalizeStationName(input);
        final List<Station> matching = candidates.stream()
                .filter((final var station) -> normalizeStationName(station.name()).equals(normalized)).toList();
        if (matching.size() == 1) return matching.get(0);
        // Never guess between distinct station IDs, even when their names are identical.
        return selector.select(input, List.copyOf(matching.isEmpty() ? candidates : matching));
    }

    private static String normalizeStationName(final String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s\\p{Z}]+", " ").strip()
                .replaceAll("\\bbhf\\b", "bahnhof").replaceAll("\\bhbf\\b", "hauptbahnhof");
    }

    static Station ambiguousStation(final String input, final List<Station> candidates) throws IOException {
        throw new IOException("Bahnhof '" + input + "' ist nicht eindeutig. Bitte einen vollständigen Namen verwenden "
                + "oder mit --interactive auswählen: "
                + candidates.stream().map(Station::name).collect(Collectors.joining("; ")));
    }

    @FunctionalInterface
    interface StationSelector {
        Station select(final String input, final List<Station> candidates) throws IOException;
    }

    static Endpoints parseEndpoints(final String html, final URI base) throws IOException {
        Element data = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__");
        if (data == null) throw new IOException("BVG-HTML enthält keine __NEXT_DATA__-Konfiguration; Seitenstruktur möglicherweise geändert.");
        JsonNode props = JSON.readTree(data.data()).path("props").path("pageProps");
        String locations = props.path("apiPathAutosuggest").asText();
        String connections = props.path("apiPathConnectionSearch").asText();
        if (locations.isBlank() || connections.isBlank()) {
            throw new IOException("BVG-HTML enthält keine passenden Suchendpunkte.");
        }
        // apiPathAutosuggest currently includes '?input='. Parameter values are encoded separately.
        URI locationsUri = sameOrigin(base, locations.split("\\?", 2)[0]);
        return new Endpoints(locationsUri, sameOrigin(base, connections));
    }

    private static URI sameOrigin(final URI base, final String path) throws IOException {
        try {
            URI uri = base.resolve(path);
            if (!base.getScheme().equals(uri.getScheme()) || !base.getAuthority().equals(uri.getAuthority())) {
                throw new IOException("BVG-Konfiguration verweist auf einen fremden Server.");
            }
            return uri;
        } catch (final IllegalArgumentException e) {
            throw new IOException("Ungültiger Suchendpunkt im BVG-HTML.", e);
        }
    }

    static URI query(final URI endpoint, final Map<String, String> params) {
        String query = params.entrySet().stream().map((final var e) -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        return URI.create(endpoint + (endpoint.getRawQuery() == null ? "?" : "&") + query);
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String get(final URI uri, final String accept) throws IOException, InterruptedException {
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
    record Result(String origin, String destination, String via, List<JsonNode> connections) { }
}
