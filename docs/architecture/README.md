# Architecture

Technical documentation of the system, filled in as it is built:

- **Hexagonal modules and their dependency rules** (Gradle enforces the module direction, ArchUnit the package rules), with Mermaid diagrams.
- **The ingestion and search flows**, from the providers to the dashboard. The ingestion is described in [Ingestion](ingestion.md), the search, re-price and admin API in [Search API](search-api.md), and its contract is [`openapi.json`](openapi.json).
- **The mock providers**: the three GDS mocks that the system ingests.
- **The domain glossary** (ubiquitous language): leg, offer, itinerary, dead leg, connection, minimum connection time, interlining, markup.

The reasoning behind each decision lives in the [design spec](../planning/design-spec.md); `SOLUTION.md`, at the repository root, summarises it for the reader.

## Hexagonal modules

Arrows point from a module to the modules it may depend on. Gradle enforces only this module-level direction; the package rules below are enforced by ArchUnit.

```mermaid
flowchart LR
    bootstrap --> web[adapters:web]
    bootstrap --> persistence[adapters:persistence]
    bootstrap --> providers[adapters:providers]
    bootstrap --> application
    web --> application
    persistence --> application
    providers --> application
    application --> domain
```

Port rules:

- Inbound ports are interfaces in `com.flightaggregator.application.port.in`; outbound ports are interfaces in `com.flightaggregator.application.port.out`.
- Services in `com.flightaggregator.application.service` implement the inbound ports and call the outbound ports.
- Adapters implement or call ports and never depend on each other.
- The domain depends on nothing, and the application layer may use Spring while the domain never does.
- Only `bootstrap` wires adapters to the application.

ArchUnit rules, in `HexagonalRules` (`architecture-tests`), run by `./gradlew build`. Every rule is checked against real classes and fails if it matches none (rules 3 and 4 since plan 4's persistence adapter):

1. `domainDependsOnlyOnTheJdk`: the domain depends only on the JDK and itself.
2. `applicationDependsOnlyOnTheDomainAndSpring`: the application layer depends only on the JDK, Spring, the domain and itself.
3. `adaptersUseOnlyPorts`: adapters reach the application layer only through its ports (`application.port..`): never a service or any other application class.
4. `adaptersAreIndependent`: no adapter depends on another adapter.
5. `nothingDependsOnBootstrap`: nothing outside `bootstrap` depends on it.
6. `everyClassResidesInALayer`: every class under `com.flightaggregator` (except `mockgds`) lives in one of the eight layer packages (`domain`, `application.port.in`, `application.port.out`, `application.service`, `adapter.provider`, `adapter.persistence`, `adapter.web`, `bootstrap`), so a misnamed package such as `adapters.web` cannot escape the other rules.
7. `domainNeverReadsTheClockOrRandomness`: the domain never calls a `now(..)` method of a `java.time` class, `System.currentTimeMillis()`, `System.nanoTime()`, `Math.random()`, `StrictMath.random()`, `UUID.randomUUID()`, `Calendar.getInstance(..)`, `Collections.shuffle(List)` or the no-argument `new Date()`, and never depends on `Clock`, `InstantSource`, `Random` (and subclasses), `SplittableRandom` or `java.util.random..`. Rule 1 allows every `java.*` type, so this one keeps the same inventory building the same itineraries, and the tests independent of the day they run. `UUID.nameUUIDFromBytes` (the itinerary id) and `new Date(long)` stay allowed.

Fixtures under `architecture-tests/src/test/java/.../fixture` check each rule against a tiny violating package tree (`HexagonalRulesTest`), so a rule that stopped detecting anything would fail the build.

## Domain model

The [domain visibility rule](domain-visibility.md) says which domain members are public.

The `domain` module is one flat package, `com.flightaggregator.domain`, of immutable records and stateless rule classes in plain Java (rule 1 above). It never reads a clock: every rule that depends on the time takes `now` as an `Instant`.

Types:

- `Airport` and `Carrier`: IATA codes, checked when created (three upper-case letters; two upper-case letters or digits).
- `Money`: an amount of euros, kept in cents, never negative. It rejects an amount that would round to one billion or more (999,999,999.995 upwards), checked before rounding so that a huge exponent fails fast.
- `Provider`: the closed set of mock providers, `ALPHA`, `BETA` and `GAMMA`.
- `Leg`: one provider's offer of one flight (flight number 1 to 9999), with its price and the time it was quoted. A price of 100 million euros or more is rejected (`Leg.PRICE_CEILING`), which keeps an itinerary's price, three legs with the markup, under the `Money` bound. `LegKey` is the flight's identity: carrier, flight number, origin, destination and departure instant.
- `LegDeduplicator`: merges the offers of each flight into one leg and returns a `Deduplication`: `legs()`, one offer per flight, and `conflicting()`, every offer of a flight that its provider gave with two different arrivals (the provider contradicts itself, so none of its offers of that flight is used). Offers from different providers never conflict: the normal rules pick the winner, with that offer's arrival. The same offer twice is not a conflict. The caller counts and logs `conflicting()`.
- `ConnectionRules`: when two legs connect, and when a leg can still be sold. It owns the connection window, which the builder reads too.
- `PricingPolicy`: the final price of an itinerary.
- `Itinerary`: one to three connecting legs, with the trip's origin, destination, times, duration, price, freshness and carrier. It refuses to exist with no legs or more than three, or with a leg that does not connect to the one before it or lands at an airport already on the trip (`Itinerary.canFollow`). It does not check that its legs are still sellable, because that needs `now`: the builder drops departed legs. `ItineraryId` is its identity.
- `ItineraryBuilder`: turns a `Deduplication` into every sellable itinerary. It does not deduplicate, so the caller can count and log the conflicting offers first.

Rules, where they are enforced, and the section of the [design spec](../planning/design-spec.md) that sets each one:

| Rule | Where | Source |
|---|---|---|
| The same carrier, flight number, origin, destination and departure instant is the same flight. The cheapest offer wins; at the same price, the latest quote; at the same price and quote time, the provider declared first | `LegDeduplicator` | §4, leg deduplication |
| A provider that offers one flight with two different arrivals has all its offers of that flight discarded (`Deduplication.conflicting()`); other providers' offers are not affected | `LegDeduplicator` | §4, leg deduplication |
| A leg arrives after it departs (as instants), and does not start and end at the same airport | `Leg` | §4, itinerary, with the dead legs of [session 01](../ai/sessions/01-requirements-clarification.md); §4, no airport visited twice |
| A leg departing at or before now is not sold | `ConnectionRules.isSellable` | §4, itinerary |
| Two legs connect when the second leaves from the airport where the first lands, 45 min to 24 h after it lands, both ends included, measured between instants | `ConnectionRules` (the window and `connects`) | §4, itinerary; §4.2 |
| An itinerary has 1 to 3 legs, so at most two stops | `Itinerary` | §4, itinerary |
| No airport is visited twice, the origin included | `Itinerary.canFollow` | §4, no airport visited twice |
| A price with more than two decimals is rounded half-up to cents; a negative price is rejected, and so is a leg priced at zero. An amount that would round to one billion euros or more is rejected before rounding, as malformed input, and so is a leg priced at 100 million euros or more | `Money`, `Leg` | §4, price |
| An itinerary costs the sum of its legs plus a 5% markup, rounded to cents once, half-up | `PricingPolicy` | §4, price |
| The id is a name-based UUID of the leg keys; price, provider and quote time are not part of it | `ItineraryId` | §4, itinerary identity |
| An itinerary is as fresh as its oldest leg quote | `Itinerary.quotedAt` | §4, price freshness |
| An itinerary belongs to a carrier only when that carrier operates every leg; mixing carriers and providers is valid | `Itinerary.singleCarrier` | §4, carrier filter and interlining |
| The providers are a closed set: a fourth one adds a constant | `Provider` | §4, providers |

`ItineraryBuilder.build(deduplication, now)` works in four steps, so its cost grows with the itineraries it produces rather than with every combination of legs (§5.3):

1. It takes the deduplicated legs (the caller ran `LegDeduplicator`) and drops the legs already departed.
2. It indexes the remaining legs by origin airport: a `TreeMap` per airport, keyed by departure instant. Legs leaving at the same instant share a list.
3. It extends every leg depth-first, up to three legs. At the airport where the trip lands, the index reads the window that `ConnectionRules` gives (45 min to 24 h after the arrival) with `subMap`, both ends inclusive. That finds the window's first departure in O(log n), the same cost as a binary search, without hand-written search code; the price is a little more memory than a sorted array. A candidate is kept only if `Itinerary.canFollow` accepts it, so a leg that would land at an airport already on the trip is skipped.
4. It returns a lazy stream, one first leg at a time, so the ingestion can store the ~1M itineraries of the dataset (§4.1) in batches without holding them all in memory.

Each itinerary is built exactly once: once the offers are deduplicated, the only path to it is its own legs in order. The builder and `Itinerary`'s constructor ask the same rules (the window of `ConnectionRules`, `Itinerary.canFollow` and `Itinerary.MAXIMUM_LEGS`), so a rule changes in one place and the builder cannot produce an itinerary that the constructor rejects. The constructor still checks every itinerary, so a builder bug fails loudly instead of producing an invalid one.

Glossary:

- **Leg:** one flight between two airports with no stop, identified by its carrier, flight number, origin, destination and departure instant.
- **Offer:** one provider's price for a leg, with the time it was quoted. Several providers can offer the same leg; deduplication keeps the best offer, and the domain's `Leg` type is that offer.
- **Itinerary:** what a traveller buys: one to three legs flown one after the other. Providers sell legs; the aggregator builds the itineraries.
- **Dead leg:** a leg that cannot be flown or sold: it has already departed, or it does not arrive after it departs. The builder drops departed legs; `Leg` rejects the others when it is created.
- **Connection:** the change from one leg to the next at the same airport. It is valid when the next leg leaves at least the minimum connection time, and at most 24 hours, after the first one lands.
- **Minimum connection time (MCT):** the shortest time in which a traveller can change planes: 45 minutes here, at every airport.
- **Interlining:** one itinerary with legs of different airlines, or from different providers. It is valid, as at real online travel agencies; the Carrier filter still requires every leg to be operated by that carrier.
- **Markup:** what the aggregator adds to the providers' prices: 5% of the sum of the legs, included in the final price.

## Mock providers

`mock-gds` is a Spring Boot application, independent of the other modules (§6), that serves one generated inventory through three legacy formats. It runs in Docker (`mock-gds/Dockerfile`, `compose.yaml`), on port 8081 of the host's loopback interface only, because the demo keys are public. Every provider serves the same inventory of legs, each one priced at the request instant, in the dataset's order.

| Provider | Endpoint | Format | Pagination | End of the data |
|---|---|---|---|---|
| Alpha | `GET /alpha/legs` | JSON | `cursor` (opaque, from `nextCursor`) and `limit` | `"nextCursor": null` |
| Beta | `GET /beta/segments` | XML | `page` (from 1) and `pageSize` | `page` reaches `totalPages`; a page beyond is an empty `<Segments/>` |
| Gamma | `GET /gamma/legs.csv` | CSV | `offset` (from 0) and `limit` | a page shorter than `limit`; a header-only page when the total is a multiple of `limit` |

`limit` and `pageSize` default to 500 and go from 1 to 1000; anything else, or a parameter that is not a plain integer, answers `400`. Times are ISO-8601 with a numeric offset (`2026-11-20T08:15:00+01:00`, never `Z`). Prices are in euros: Alpha writes a decimal string, Beta an integer number of cents, Gamma a decimal.

### Origin filter and quote

Every listing also takes `origin=<IATA>`: only the legs departing from that airport, filtered before paging, so cursors, pages and offsets run over the filtered list. Without it the listing is unchanged.

A quote re-prices one flight in the provider's own format: the same listing with all five of `carrier`, `flightNumber`, `origin`, `destination` and `departure` present. It answers at most one record, priced like the listing (same drift model), and ends its own pagination (Alpha `"nextCursor": null`, Beta `totalPages="1"`, Gamma a short page). A flight the provider does not sell is not an error: the answer is the listing's own empty page, with status `200` (Alpha `{"legs":[],"nextCursor":null}`, Beta an empty `<Segments/>` with `totalPages="1"`, Gamma the header row only).

These answer `400`: a flight key with a missing part, an `origin` or `destination` that is not three uppercase letters (an empty, lowercase or repeated `origin` included), a `carrier` that is not two uppercase letters or digits, a `flightNumber` that is not a plain integer, and a `departure` that is not an ISO-8601 UTC instant ending in `Z` (`2026-11-20T07:15:00Z`; an offset is refused). That `Z` belongs to the request only: the times a provider writes never use it.

The examples below are trimmed to one record. Prices differ per provider and per request: each is the drifted price at the request instant.

Alpha:

```json
{"legs":[{"carrier":"AF","flightNumber":1079,"origin":"CDG","destination":"JFK","departureTime":"2026-11-15T09:00:00+01:00","arrivalTime":"2026-11-15T10:50:00-05:00","price":"484.62"}],"nextCursor":"MQ"}
```

Beta:

```xml
<SegmentsResponse page="1" totalPages="1808"><Segments><Segment><Carrier code="AF"/><FlightNumber>1079</FlightNumber><Departure airport="CDG">2026-11-15T09:00:00+01:00</Departure><Arrival airport="JFK">2026-11-15T10:50:00-05:00</Arrival><PriceCents>41362</PriceCents></Segment></Segments></SegmentsResponse>
```

Gamma (lines end with `\r\n`):

```csv
airline,flight_number,from,to,departs_at,arrives_at,price_eur
AF,1079,CDG,JFK,2026-11-15T09:00:00+01:00,2026-11-15T10:50:00-05:00,377.28
```

**Defects.** Some records are malformed on purpose, and each defect breaks only its own record, so the rest of the page stays parseable:

| Defect | Effect |
|---|---|
| `MISSING_FIELD` | the flight number is omitted (an absent field in Alpha and Beta, an empty cell in Gamma's CSV) |
| `BAD_DATE` | the departure is `2026-02-30T25:61:00+01:00` |
| `NEGATIVE_PRICE` | the price is negated |
| `UNKNOWN_AIRPORT` | the destination is `ZZZ` |

**Keys.** Each provider needs its own key in the `X-Api-Key` header; a missing or wrong one answers `401`. Keys come from environment variables (`MOCKGDS_PROVIDERS_<PROVIDER>_APIKEY`), the application does not start without them, and the mock never logs one: a misspelt variable such as `MOCKGDS_PROVIDERS_ALPHA_APIKYE` stops it with the name alone. Spring Boot's own report of an unknown setting does print the value, so a key given under a misspelt name on the command line or in a properties file, such as `--mockgds.providers.alpha.api-kye=...`, is printed before the mock stops. `compose.yaml` commits demo keys as overridable defaults so that the project runs with one command (§6.6); the [README](../../README.md#run-the-mock-providers) says why that is safe here and why it would not be in production.

**Latency and errors.** Per provider, the latency has a long tail: each request waits a random time between `FASTLATENCYMIN` and `FASTLATENCYMAX` (100 to 1000 ms by default), except the `SLOWSHARE` of them (10%), which waits between `SLOWLATENCYMIN` and `SLOWLATENCYMAX` (1000 to 5000 ms). After its wait, a request fails with HTTP 500 at the `ERRORRATE` (5%). Each `401` and each injected `500` is logged as one `WARN` line with the provider and the path, never a key or a header value. The [README](../../README.md#run-the-mock-providers) lists the settings and their rules. A bad value stops startup, and so does an unknown setting: Spring Boot checks properties files and command-line arguments, and the mock's `SettingsConfig` checks the `MOCKGDS_PROVIDERS_` environment variables, which Boot leaves out, naming them without their values. A JVM system property is not checked. The tests set every latency and the error rate to zero. The `images` job in CI builds the image on every push, without publishing it, and Dependabot watches its base images, leaving a major version (Java 26 is not an LTS) to a decision.
