# oepnv-victim

[![GitHub Actions main branch status](https://github.com/ottlinger/oepnv-victim/actions/workflows/maven.yml/badge.svg?branch=main)](https://github.com/ottlinger/oepnv-victim/actions/workflows/maven.yml)
[![Dependabot enabled](https://img.shields.io/badge/Dependabot-enabled-brightgreen?logo=dependabot)](https://github.com/ottlinger/oepnv-victim/blob/main/.github/dependabot.yml)
[![codecov](https://codecov.io/gh/ottlinger/oepnv-victim/branch/main/graph/badge.svg)](https://codecov.io/gh/ottlinger/oepnv-victim)

A Java command-line application for [BVG](https://www.bvg) journey planning in Berlin and Brandenburg.
The origin and destination are configurable; each journey is displayed with its
individual legs.

## Requirements and build

- Java 17 or later
- Maven 3.9 or later
- Internet access

```sh
./mvnw
```

## Usage

```sh
java -jar target/oepnv-victim.jar \
  --from "Treuenbrietzen, Bahnhof" \
  --to "Jüterbog, Bahnhof"
```

Additional options:

```sh
# Display only two journeys from the first page of results
java -jar target/oepnv-victim.jar --from "Treuenbrietzen, Bahnhof" --to "Jüterbog, Bahnhof" --limit 2

# Search for journeys departing on 7 November 2026 at 15:22 (Berlin local time)
java -jar target/oepnv-victim.jar \
  --from "Treuenbrietzen, Bahnhof" \
  --to "Jüterbog, Bahnhof" \
  --departure "2026-11-07 15:22"

# Output all detail fields supplied by BVG as a JSON array
java -jar target/oepnv-victim.jar --from "Treuenbrietzen, Bahnhof" --to "Jüterbog, Bahnhof" --json

java -jar target/oepnv-victim.jar --help
```

`--start` / `--from` and `--ziel` / `--to` are required parameters. If several stops
match a name, the CLI requires an unambiguous name and lists suggestions; it does
not simply select the first search result.

`--limit` restricts the output to at most N journeys from the first page of results
(default: 5). No further pages are fetched.
`--timeout` sets the timeout for each HTTP request in seconds (default: 30).
`--startzeit` / `--departure` optionally sets the departure date and time in the
strict format `YYYY-MM-DD HH:mm`, for example `"2026-11-07 15:22"`. Quote the value
because it contains a space. It is interpreted as local time in Berlin
(`Europe/Berlin`), not the time zone of the computer running the CLI. Invalid dates,
times and formats are rejected with exit code `2` before any network request.
If omitted, the search uses **departure now**.

The search includes all modes of transport from the example URL and real-time
data. Times are local BVG timetable times; missing delay data is not interpreted
as evidence that a service is on time.

Example journey (times and platforms vary; CLI output remains in German):

```text
Verbindung 1: 2026-11-07 12:29 (+0 Min.) → 2026-11-07 12:47 (+0 Min.) | 18 Min.
  1. RB33 Richtung Jüterbog, Bahnhof
     2026-11-07 12:29 (+0 Min.)  Treuenbrietzen, Bahnhof (Gl. 2)
     2026-11-07 12:47 (+0 Min.)  Jüterbog, Bahnhof (Gl. 3)
     Dauer: 18 Min.
     Halte (BVG): 3
```

Departure dates and times are shown in **bold** in both the journey summary and
its individual legs when ANSI styling is enabled. Arrival times and delay
information remain unstyled. Picocli detects terminal support automatically;
redirected output remains plain text by default. Styling can be forced with
`-Dpicocli.ansi=true` or disabled with `-Dpicocli.ansi=false` (place the option
before `-jar`). The plain-text example above does not show terminal styling.

The text output also includes the parameterised BVG URL. Notices and disruptions
are displayed where available. `--json` outputs only the selected journey objects,
including every field supplied by BVG, without terminal styling.

Exit codes: `0` for a successful query (including an empty result list), `1` for a
query error, and `2` for invalid arguments. Error messages are written to stderr.

## HTML and journey details

The HTML initially served by this BVG page contains **no journey results**.
The contents of the expandable details panel are also rendered on the client.
Simply parsing visible HTML elements therefore provides no journey data.

The application handles this without executing JavaScript:

1. Java's `HttpClient` fetches the parameterised journey-planning page.
2. **Jsoup** parses the HTML and reads the embedded `script#__NEXT_DATA__`.
3. The BVG search endpoints are extracted from this configuration.
4. The stop search resolves names to current BVG IDs; IDs from the example URL
   are not hard-coded.
5. The journey endpoint returns the summary and `legs` (individual journey legs).
   These data underpin the expanded details panel in the user interface:
   there is no need to click the details button.

The BVG web endpoints are not guaranteed to be a stable API. If the HTML
configuration or response schema changes, the CLI reports an error. It does not
bypass access restrictions; please keep queries moderate and observe BVG's terms
of use. Intermediate stops are displayed only to the extent that they are
included in the response; the example route currently returns a stop count,
not a list of all intermediate stops.

## Tests

```sh
./mvnw test
```

The tests run without accessing external BVG services: a local HTTP test server
checks the workflow from HTML parsing to the details query. Further tests cover
name resolution, UTF-8 and URL parameters, error cases, journey legs, platforms,
delays, walking transfers, journeys crossing midnight, notices, terminal styling,
configured departure times, and CLI validation.
