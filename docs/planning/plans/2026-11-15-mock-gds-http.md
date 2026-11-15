# Mock GDS over HTTP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Every production change follows `superpowers:test-driven-development` and `CLAUDE.md`. Reviews follow the review protocol in `CLAUDE.md`: `functional-reviewer` and `code-reviewer` after every task, plus `risk-reviewer` on the whole plan at the end.

**Goal:** `mock-gds` serves the generated inventory of the three mock providers over HTTP. Each provider has its own format and pagination, its own API key, and a configurable latency and HTTP 500 rate. The service runs as a Docker image in `docker compose`.

**Architecture:** A Spring Boot application inside `mock-gds`, with these parts:
- `ProviderInventory` generates the dataset once at startup and serves pages of a provider's records, priced at the instant of the request (the price drift of §6.3).
- One pure serializer per provider writes a page in that provider's format, with every malformed record broken in its own way.
- A thin controller per provider maps its pagination parameters onto the inventory.
- `ProviderGate`, one interceptor for all three providers, checks the API key, waits the latency and injects the 500s.

**Tech Stack:** Java 25, Spring Boot 4.1.2 (Web MVC, Actuator, Jackson 3 for JSON), the JDK's StAX for XML, Docker with Eclipse Temurin 25 images, docker compose, GitHub Actions and Dependabot.

**Spec:** [design spec](../design-spec.md), sections:
- §2: goals; one `docker compose up --build`.
- §4.1: full paginated inventory, rolling window, ISO-8601 with offset.
- §6.1: version policy.
- §6.3: price drift.
- §6.6: an API key per provider.
- §6.7: payload schemas, malformed records.
- §6.11: CI images, Dependabot.
- §6: `mock-gds` module.

Roadmap: [plan 3](../roadmap.md).

## Global Constraints

- **Package and dependencies:** code lives in `com.flightaggregator.mockgds`, under `mock-gds/src/main/java/com/flightaggregator/mockgds/`. `mock-gds` depends on no other project module (§6).
- **Version policy (§6.1):** every new version (Spring Boot, the Boot Gradle plugin, Docker base images) is a stable GA at least **14 days old**, checked against its primary source and recorded in the progress report. No release candidates.
- **Times (§4.1, §4.2):** ISO-8601 with offset in all three formats, always with seconds and a numeric offset (`2026-11-20T08:15:00+01:00`, `2026-11-20T09:30:00+00:00`, never `Z`). Formatter pattern: `uuuu-MM-dd'T'HH:mm:ssxxx`.
- **Prices (§6.7):**
  - Alpha: a decimal string, `"129.90"`.
  - Beta: an integer number of cents, `12990`.
  - Gamma: a decimal with a dot, `129.90`.
  - All prices are in EUR. Each is the drifted price at the request instant (§6.3).
- **Pagination (§6.7):** Alpha uses a cursor (`nextCursor`), Beta a page number, Gamma `offset`/`limit`. Pages of the full inventory, in the dataset's order; every record exactly once to the last page.
- **Malformed records (§6.7):** each `Defect` breaks only its own record, and the rest of the page stays parseable.
  - `MISSING_FIELD`: the flight number is omitted.
  - `BAD_DATE`: the departure is written as `Defect.INVALID_DATE_TEXT`.
  - `NEGATIVE_PRICE`: the price is negated.
  - `UNKNOWN_AIRPORT`: the destination is `Defect.UNKNOWN_AIRPORT_CODE`.
- **API keys (§6.6):** each provider requires its own key. A missing or wrong key gets `401`. Keys come from configuration (environment variables), and none is ever logged.
- **Latency and errors (user's decision):** by default, uniform latency of 100–5000 ms per request and a 5% rate of HTTP 500s. Both are configurable per provider through environment variables. Tests set them to 0.
- **Gates:** `./gradlew build` is green before every commit, with Spotless, ArchUnit and JaCoCo (90% lines, 80% branches). TDD, one test at a time. The red → green evidence goes to `docs/planning/progress/2026-11-15.md`, section "Mock GDS run (plan 3)".
- **Scope (`brief-scope`):** only what this plan lists. An extra found mid-task goes to the progress report under "Extras for the user to decide".
- **Repo rules:** English everywhere. No AI attribution in commits. Push only after `gh api user --jq .login` prints `dromero001` (`CLAUDE.md`).

## Decisions this plan takes (for the user's review)

| Decision | Why |
|---|---|
| **User:** Spring Boot 4.1 serves the mocks | Same stack as the application. Jackson writes JSON, Actuator gives the health check compose needs, and configuration comes from environment variables |
| **User:** only the full inventory endpoint in this plan | The roadmap's deliverable. The origin filter for the stale-route refresh (§4.1) comes with plan 4, and the single-leg quote for re-price (§6.3) with plan 5, each with the code that uses it |
| **User:** 100–5000 ms and 5% of 500s by default, per provider | The brief's numbers. With pages of 500, one provider is about 9 requests and roughly 25 s per ingestion. A provider can be set to fail every request, for the outage demo |
| Spring Boot's BOM through `platform(libs.spring.boot.dependencies)` and the Boot Gradle plugin from the version catalog, without the `io.spring.dependency-management` plugin | Gradle's native platforms already do what that plugin did, and one version in the catalog drives both. This settles the "BOM decision" left open by plan 1 |
| The dataset is generated once, at startup, from the system clock. The 30-day window starts on the startup date | Regenerating mid-run would move records under a client that is paging. Limitation, for `SOLUTION.md`: a mock left running for days serves a shrinking window. Prices still drift, because they are computed per request |
| Paths: `GET /alpha/legs?cursor=&limit=`, `GET /beta/segments?page=&pageSize=` (1-based), `GET /gamma/legs.csv?offset=&limit=`. Default page size 500, maximum 1000. An out-of-range parameter or an unreadable cursor → `400` | One path per provider on one deployable (§6). A strict API, as real GDSs are, so an adapter bug fails loudly |
| Alpha's cursor is the next offset in Base64URL, with `nextCursor: null` on the last page. Beta returns `totalPages`. Gamma's end is a page shorter than `limit` | Three different end-of-data signals, so each adapter really translates (§6.7) |
| JSON through Jackson's streaming writer, XML through the JDK's `XMLStreamWriter`, CSV written by hand | Each serializer decides exactly how a defect breaks its record (an omitted element, a literal bad date). Object mapping would fight the deliberately broken output |
| Header `X-Api-Key` on all three, with a different value per provider, compared in constant time (`MessageDigest.isEqual`). The key is checked before the latency | A wrong key is rejected at once, and comparing in constant time leaks nothing about the key |
| **User:** `compose.yaml` commits demo keys as defaults (`${ALPHA_API_KEY:-…}`), which the environment can override | The take-home must run with a single `docker compose up --build` (§2), so the keys are committed on purpose. They protect only the local mocks. In a real production environment they would be secrets and never committed: the README says so, and `SOLUTION.md` will (§6.6) |
| Virtual threads (`spring.threads.virtual.enabled=true`) | A request sleeping up to 5 s holds no platform thread, so the three providers can be downloaded in parallel |
| Image: a multi-stage `mock-gds/Dockerfile` built from the repo root. Temurin 25 JDK to build, Temurin 25 JRE to run, both pinned by tag and digest. Runs as a non-root user | No local Gradle is needed for `docker compose up --build`, and Dependabot can bump the pins |
| CI builds the `mock-gds` image without pushing it. Dependabot's `docker` ecosystem covers `/mock-gds`, with the 14-day cooldown | §6.11 step 4 for the first image, and the `docker` ecosystem plan 1 deferred to this plan |

## Out of scope (carried to later plans)

- The origin filter (plan 4) and the single-leg quote (plan 5), as decided above.
- The compose services for the application, PostgreSQL, Valkey, Keycloak and Grafana (plans 4–7), and the end-to-end compose job in CI (plan 6). The `docker-compose` Dependabot ecosystem waits until compose references a pinned image (plan 4).
- Regenerating the dataset while running.

## Review Focus

1. **A defect must not break its neighbours.** Beta's XML stays well formed, and Gamma keeps one row per record with the header's column count. Pinned by each serializer's golden test with all four defects on one page.
2. **Every record exactly once to the last page**, including when the total is a multiple of the page size. Pinned by Task 5 `downloadsEveryRecordOfEveryProviderExactlyOnce` and the serializers' last-page tests.
3. **A UTC airport is written `+00:00`, never `Z`, and always with seconds.** The golden tests include an `LHR` arrival.
4. **A missing or wrong key gets `401` even when the provider is set to fail every request,** and the key never appears in a response or a log. Pinned by Task 2 `rejectsAWrongKeyBeforeAnyLatencyOrError`.
5. **Price drift.** Within one 15-minute bucket the same leg keeps its price across pages and requests, and in the next bucket it changes. Pinned by Task 1 `pricesTheSameRecordAlikeWithinABucket` and `repricesTheRecordInTheNextBucket`.

---

### Task 1: The Spring Boot application and the provider inventory

**Files:**
- Modify: `gradle/libs.versions.toml` (the Boot plugin and the starters), `mock-gds/build.gradle.kts`, `dataset/Dataset.java` (its constructor becomes public, so tests can build a small dataset; it is plain data)
- Create: `mock-gds/src/main/java/com/flightaggregator/mockgds/MockGdsApplication.java`; `serving/ProviderInventory.java`, `serving/InventoryPage.java`, `serving/PricedRecord.java`; `mock-gds/src/main/resources/application.yaml`
- Test: `serving/ProviderInventoryTest.java`, `MockGdsApplicationTest.java`

**Interfaces:**
- Produces:
  - `record PricedRecord(ProviderRecord record, BigDecimal price)`: the record with its price at the request instant.
  - `record InventoryPage(List<PricedRecord> records, int offset, int total)`, with `boolean isLast()`.
  - `final class ProviderInventory`, constructed from `(Dataset dataset, PriceModel prices, Clock clock)`, with `InventoryPage page(Provider provider, int offset, int limit)`.
  - Spring beans: `Clock` (the system UTC clock, replaceable in tests), `ProviderInventory` (the dataset generated at startup with `GeneratorConfig.defaults()` and the standard reference data).

- [ ] **Step 1: `ProviderInventoryTest`, one test at a time,** over a small hand-built `Dataset` and a fixed `Clock`:
  - `servesARangeOfTheProvidersRecordsInOrder`: offset 2, limit 3 gives records 2–4 of that provider.
  - `servesAShortLastPage`: offset 8, limit 5 over 10 records gives 2 records and `isLast()`.
  - `servesAnEmptyPageAfterTheEnd`: offset 10 over 10 records gives no records and `isLast()`.
  - `pricesTheSameRecordAlikeWithinABucket`: two calls 14 minutes apart give the same price.
  - `repricesTheRecordInTheNextBucket`: a call in the next 15-minute bucket gives the price `PriceModel.price(key, provider, thatInstant)`, which differs for the fixture leg.
  - `rejectsANegativeOffsetOrANonPositiveLimit`: `IllegalArgumentException`.
- [ ] **Step 2: Implement `ProviderInventory`.** A malformed record keeps its defect and gets the drifted price of its leg; the serializer applies the defect.
- [ ] **Step 3: Boot setup.**
  - Add the Spring Boot 4.1.2 Gradle plugin and starters to the catalog. Verify the 14-day age first and record it.
  - Apply them in `mock-gds/build.gradle.kts` with `platform(libs.spring.boot.dependencies)`. Include the Web MVC, Actuator and test starters, in their Boot 4 names.
  - `application.yaml`:
    - virtual threads on;
    - only `health` exposed by Actuator;
    - `server.port: 8080`.
- [ ] **Step 4: `MockGdsApplicationTest`** (Spring tests set the three API keys as test properties, since Task 2 makes startup fail without them): `startsAndReportsHealthUp`. A `@SpringBootTest` with a random port shows `GET /actuator/health` returning `UP`, and the inventory bean serves records for every provider.
- [ ] **Step 5: `./gradlew build` green, review, commit:** `git commit -m "Serve the mock inventory from a Spring Boot application, priced at request time"`.

### Task 2: The provider gate: API key, latency and errors

**Files:**
- Create: `serving/ProviderSettings.java` (configuration properties), `serving/ProviderGate.java` (a `HandlerInterceptor` for `/alpha/**`, `/beta/**`, `/gamma/**`), `serving/WebConfig.java`
- Modify: `application.yaml` (defaults)
- Test: `serving/ProviderGateTest.java`, `serving/ProviderSettingsTest.java`

**Interfaces:**
- Produces:
  - `@ConfigurationProperties("mockgds.providers")`: a map from `alpha`, `beta`, `gamma` to `(String apiKey, Duration latencyMin, Duration latencyMax, double errorRate)`.
  - Defaults: 100 ms, 5000 ms and 0.05. `apiKey` has no default, so startup fails without one.
  - Validation: min ≤ max, both ≥ 0, and `errorRate` within [0, 1].
  - The environment variables follow Boot's relaxed binding, for example `MOCKGDS_PROVIDERS_ALPHA_APIKEY`.
  - `ProviderGate` takes a `RandomGenerator` bean (seeded in tests) and a `Sleeper` (`Thread::sleep` in production, recording in tests).

- [ ] **Step 1: `ProviderSettingsTest`:**
  - `failsToStartWithoutAnApiKey`
  - `rejectsALatencyMinimumAboveTheMaximum`
  - `rejectsAnErrorRateOutsideZeroToOne`
- [ ] **Step 2: `ProviderGateTest`, one test at a time,** with a test-only controller on each provider path:
  - `rejectsAMissingKey` → `401`.
  - `rejectsAnotherProvidersKey`: Beta's key on `/alpha/…` → `401`.
  - `rejectsAWrongKeyBeforeAnyLatencyOrError`: with `errorRate` 1.0 and a 5 s latency, a wrong key still gets `401` at once (the recording sleeper recorded nothing), and the response body does not contain any configured key.
  - `waitsALatencyInsideTheConfiguredRange`: a seeded generator over 1,000 draws stays inside [min, max] and its smallest and largest draws fall within 5% of the range's ends. A request sleeps one draw.
  - `failsAtTheConfiguredRate`: rate 0 → never `500`, rate 1 → always `500`, and rate 0.05 over 10,000 seeded draws gives between 4% and 6%.
  - `passesAValidKeyThrough` → the controller's `200`.
  - `leavesActuatorUngated`: `/actuator/health` needs no key.
- [ ] **Step 3: Implement `ProviderSettings`, `ProviderGate` and `WebConfig`.** The order is: key, then latency, then error. Keys are compared in constant time and never logged.
- [ ] **Step 4: `./gradlew build` green, review, commit:** `git commit -m "Gate each mock provider behind its own API key, latency and error rate"`.

### Task 3: Alpha, a flat JSON list with a cursor

**Files:**
- Create: `alpha/AlphaJson.java` (the serializer), `alpha/AlphaController.java`, `alpha/AlphaCursor.java`
- Create: `serving/IsoTimes.java`, the shared formatter, used by all three serializers
- Test: `alpha/AlphaJsonTest.java`, `alpha/AlphaCursorTest.java`, `alpha/AlphaControllerTest.java`

**Interfaces:**
- Produces: `GET /alpha/legs?cursor=<opaque>&limit=<1..1000, default 500>`, `application/json`:

```json
{"legs":[{"carrier":"IB","flightNumber":3170,"origin":"MAD","destination":"LHR","departureTime":"2026-11-20T08:15:00+01:00","arrivalTime":"2026-11-20T09:30:00+00:00","price":"129.90"}],"nextCursor":"NTAw"}
```

  - `nextCursor` is `AlphaCursor.encode(nextOffset)` (Base64URL of the decimal offset), and `null` on the last page.
  - No cursor means offset 0.

- [ ] **Step 1: `AlphaJsonTest`, one test at a time:**
  - `writesAValidLeg`: the exact JSON above (golden string) for an `IB3170 MAD-LHR` fixture leg arriving at `LHR`.
  - `writesEachDefectInItsOwnRecord`: one page with a valid leg and one leg per defect. The output is valid JSON with five elements. `MISSING_FIELD` has no `flightNumber` key, `BAD_DATE` has `"departureTime":"2026-02-30T25:61:00+01:00"`, `NEGATIVE_PRICE` has `"price":"-129.90"`, and `UNKNOWN_AIRPORT` has `"destination":"ZZZ"`. Every other record is unchanged.
  - `endsWithANullCursor`: on the last page `nextCursor` is `null`.
- [ ] **Step 2: `AlphaCursorTest`:**
  - `roundTripsAnOffset`
  - `rejectsAnUnreadableCursor`: not Base64URL, or not a non-negative integer → `IllegalArgumentException`.
- [ ] **Step 3: Implement `IsoTimes`, `AlphaJson` and `AlphaCursor`.**
- [ ] **Step 4: `AlphaControllerTest`:** with Alpha's key, latency 0 and error rate 0:
  - `servesTheFirstPageWithoutACursor`
  - `followsTheCursorToTheLastPage`: the sizes add up to the provider's record count.
  - `rejectsAnUnreadableCursor` → `400`.
  - `rejectsALimitOutOfRange`: 0 and 1001 → `400`.
- [ ] **Step 5: Implement `AlphaController`.**
- [ ] **Step 6: `./gradlew build` green, review, commit:** `git commit -m "Serve Alpha's legs as a flat JSON list with a cursor"`.

### Task 4: Beta, nested XML by page number

**Files:**
- Create: `beta/BetaXml.java`, `beta/BetaController.java`
- Test: `beta/BetaXmlTest.java`, `beta/BetaControllerTest.java`

**Interfaces:**
- Produces: `GET /beta/segments?page=<1..>&pageSize=<1..1000, default 500>`, `application/xml`:

```xml
<SegmentsResponse page="1" totalPages="9"><Segments><Segment><Carrier code="IB"/><FlightNumber>3170</FlightNumber><Departure airport="MAD">2026-11-20T08:15:00+01:00</Departure><Arrival airport="LHR">2026-11-20T09:30:00+00:00</Arrival><PriceCents>12990</PriceCents></Segment></Segments></SegmentsResponse>
```

  - `totalPages` is at least 1. A page beyond `totalPages` gets an empty `<Segments/>`.

- [ ] **Step 1: `BetaXmlTest`, one test at a time:**
  - `writesAValidSegment`: the exact XML above, as a golden string.
  - `writesEachDefectInItsOwnSegment`: the output parses as well-formed XML with five `<Segment>` elements.
    - `MISSING_FIELD` has no `<FlightNumber>`.
    - `BAD_DATE` has the invalid text inside `<Departure>`.
    - `NEGATIVE_PRICE` has `<PriceCents>-12990</PriceCents>`.
    - `UNKNOWN_AIRPORT` has `<Arrival airport="ZZZ">`.
  - `writesNoDeclarationOrNamespace`: the body has no XML declaration and no namespace, so the golden string stays stable.
- [ ] **Step 2: Implement `BetaXml`** with `XMLStreamWriter`.
- [ ] **Step 3: `BetaControllerTest`:**
  - `servesPageOneByDefault`
  - `pagesToTotalPages`: the segments of pages 1..`totalPages` add up to the provider's record count.
  - `servesAnEmptyPageBeyondTheEnd`
  - `rejectsPageZeroAndAPageSizeOutOfRange` → `400`.
- [ ] **Step 4: Implement `BetaController`.**
- [ ] **Step 5: `./gradlew build` green, review, commit:** `git commit -m "Serve Beta's segments as nested XML by page number"`.

### Task 5: Gamma, CSV with a header by offset and limit

**Files:**
- Create: `gamma/GammaCsv.java`, `gamma/GammaController.java`
- Test: `gamma/GammaCsvTest.java`, `gamma/GammaControllerTest.java`, `ProvidersEndToEndTest.java`

**Interfaces:**
- Produces: `GET /gamma/legs.csv?offset=<0..>&limit=<1..1000, default 500>`, `text/csv; charset=UTF-8`, lines ending in `\r\n`:

```csv
airline,flight_number,from,to,departs_at,arrives_at,price_eur
IB,3170,MAD,LHR,2026-11-20T08:15:00+01:00,2026-11-20T09:30:00+00:00,129.90
```

- [ ] **Step 1: `GammaCsvTest`, one test at a time:**
  - `writesTheHeaderAndAValidRow`: the exact text above, as a golden string.
  - `writesEachDefectInItsOwnRow`: one header plus five rows, each with seven cells.
    - `MISSING_FIELD` has an empty `flight_number` cell.
    - `BAD_DATE` has the invalid text in `departs_at`.
    - `NEGATIVE_PRICE` has `-129.90`.
    - `UNKNOWN_AIRPORT` has `ZZZ` in `to`.
  - `writesOnlyTheHeaderAfterTheEnd`
- [ ] **Step 2: Implement `GammaCsv`.** No field of the dataset contains a comma, a quote or a line break, and the test pins this with the fixture. If one ever could, quote it per RFC 4180.
- [ ] **Step 3: `GammaControllerTest`:**
  - `servesFromOffsetZeroByDefault`
  - `endsWithAShortPage`
  - `endsWithAHeaderOnlyPageWhenTheTotalIsAMultipleOfTheLimit`: pin it with a limit that divides the provider's record count, using a test dataset sized for that.
  - `rejectsANegativeOffsetAndALimitOutOfRange` → `400`.
- [ ] **Step 4: Implement `GammaController`.**
- [ ] **Step 5: `ProvidersEndToEndTest`:** `downloadsEveryRecordOfEveryProviderExactlyOnce`. Over HTTP with the real keys, latency 0 and error rate 0, download every provider to its end signal. Each one yields exactly its `Dataset.recordsFor(provider).size()` records, and the leg keys match the dataset's, malformed records included.
- [ ] **Step 6: `./gradlew build` green, review, commit:** `git commit -m "Serve Gamma's legs as CSV with a header by offset and limit"`.

### Task 6: The image, docker compose, CI and documentation

**Files:**
- Create: `mock-gds/Dockerfile`, `.dockerignore`, `compose.yaml`
- Modify: `.github/workflows/ci.yml` (an `images` job building the `mock-gds` image without pushing), `.github/dependabot.yml` (the `docker` ecosystem for `/mock-gds`, with the 14-day cooldown and in the existing style)
- Modify: `README.md` (how to run the mocks, and their keys and settings), `docs/architecture/README.md` (a "Mock providers" section: endpoints, the three formats with their examples, pagination and end signals, defects, keys, latency and errors)

- [ ] **Step 1: The Dockerfile.**
  - Build stage: Temurin 25 JDK. Copy the wrapper, `buildSrc`, the settings and the modules `mock-gds` needs, then run `./gradlew :mock-gds:bootJar --no-daemon`.
  - Run stage: Temurin 25 JRE, as a non-root user, running `java -jar`.
  - Pin both images by exact tag and digest, at least 14 days old, and record them.
  - `.dockerignore` excludes `**/build`, `.git`, `.gradle`, `.idea`, `.superpowers` and `node_modules`.
- [ ] **Step 2: `compose.yaml`.**
  - Service `mock-gds`, built from the repo root with `mock-gds/Dockerfile`, port `8081:8080`.
  - The three keys are `${ALPHA_API_KEY:-…}`-style demo defaults.
  - A healthcheck on `/actuator/health`, with a tool that exists in the run image (check it; if there is no `curl` or `wget`, use a shell-free alternative and say which).
- [ ] **Step 3: Prove it locally (Docker Desktop is running).**
  - `docker compose up --build -d`, then wait for `healthy`.
  - With Alpha's demo key, `curl` the first page of each provider. Without a key → `401`.
  - Then `docker compose down`.
  - Record the commands and their output in the progress report.
- [ ] **Step 4: CI and Dependabot.**
  - Add an `images` job: checkout, then `docker build -f mock-gds/Dockerfile .`, with no push. Pin its actions by SHA, like the existing job.
  - Add the `docker` ecosystem for `/mock-gds`.
  - After the push, watch the run (`CLAUDE.md`) and record its id.
- [ ] **Step 5: Docs** as listed under Files. The README states that the demo keys are committed on purpose, because the take-home must run with a single `docker compose up --build`; they protect only the local mocks, and in a real production environment they would be secrets and never committed (§6.6).
- [ ] **Step 6: `./gradlew build` green, review, commit:** `git commit -m "Run the mock providers as an image in docker compose, built in CI"`.
