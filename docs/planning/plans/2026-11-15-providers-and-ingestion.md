# Providers and Ingestion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Every production change follows `superpowers:test-driven-development` and `CLAUDE.md`. Reviews follow the review protocol in `CLAUDE.md`: `functional-reviewer` and `code-reviewer` after every task, plus `risk-reviewer` on the whole plan at the end.

**Goal:** a scheduled ingestion downloads the full inventory of the three mock providers, keeps a failed provider's last good offers, builds every itinerary with the domain, and publishes it atomically into PostgreSQL, so that the search of plan 5 always reads one complete generation.

**Architecture:**
- `application` owns the use case `IngestInventory` and its outbound ports. The service downloads the providers in parallel, replaces each provider's offers only when its download is complete, deduplicates and builds the itineraries, and streams them into the repository in push mode.
- `adapters/providers` holds one adapter per provider: an HTTP client with timeout, retry, circuit breaker and bounded pagination, and a strict parser that discards each bad record on its own.
- `adapters/persistence` keeps the offers per provider and the itineraries in **two slots** (double buffer): each run loads the inactive slot with PostgreSQL `COPY` and switches slots in the same transaction.
- `bootstrap` is the Spring Boot application: wiring, the schedule with ShedLock, logs and metrics of each run, and the image that `docker compose` runs.

**Tech Stack:** Java 25, Spring Boot 4.1.2 (JDBC, Flyway, Actuator, `RestClient`, Spring Framework 7 `RetryTemplate`), PostgreSQL 18 with the PgJDBC `CopyManager`, Jackson 3, StAX, Resilience4j 2.4.0 (circuit breaker, time limiter), ShedLock 7.10.1, Testcontainers 2.0.5, WireMock 3.13.2.

**Spec:** [design spec](../design-spec.md), sections:
- §4: domain rules (deduplication, conflicting offers, unknown airports, price freshness, providers as a closed set).
- §4.1, §4.3: full paginated inventory, ISO-8601 with offset, partitions by departure day.
- §5.1, §5.3: hybrid ingestion, itineraries materialised at ingestion.
- §6.1: version policy and stack.
- §6.2: ShedLock JDBC for the job's lock.
- §6.4: every 30 min.
- §6.5: outages: keep a failed provider's offers, atomic publication, every offer stored for re-price.
- §6.6: an API key per provider.
- §6.7: payload schemas and malformed records.
- §6.10: metrics and logs of what this plan builds.

Roadmap: [plan 4](../roadmap.md). Carried risks: [progress 2026-11-14](../progress/2026-11-14.md), "Pending for later plans" (R1, R3, R4, R7–R12 and the code review notes); this plan's tasks name the ones they close.

## Global Constraints

- **Packages:** `com.flightaggregator.application.port.in|port.out|service`, `com.flightaggregator.adapter.provider`, `com.flightaggregator.adapter.persistence`, `com.flightaggregator.bootstrap`. ArchUnit's rules hold; adapters use only ports and never each other.
- **Version policy (§6.1):** every new version is a stable GA at least **14 days old**, checked against its primary source and recorded in the progress report. Checked on 2026-11-15: Resilience4j **2.4.0** (2026-03-14); ShedLock **7.10.1** (2026-09-08; 7.11.0 is from 2026-11-07, too recent); WireMock standalone **3.13.2** (2025-11-14; 4.x is still beta). Flyway 12.4.0, PostgreSQL JDBC 42.7.14, Testcontainers 2.0.5 and Jackson 3.1.7 take the Boot 4.1.2 BOM. The `postgres:18` image tag is chosen in Task 11 by the same rule and pinned by digest.
- **Push mode (R4):** the itinerary stream is consumed only with `forEach`, never `iterator()`, `spliterator()`, `toList()` or `collect`.
- **Identity (R11):** sets, maps and comparisons of itineraries use `Itinerary.id()`, never `equals`. `price()` and `id()` are called once per itinerary when it is written.
- **Times (§4.1, R3, R12):** `timestamptz` plus the offset in seconds for every provider time. The partition day is the **UTC date of the departure instant**, defined once in `PartitionCalendar`. `quotedAt` comes from the `Clock` port when a page's response arrives, truncated to microseconds (what `timestamptz` holds).
- **Prices (R8):** parsed from text with a strict pattern into `BigDecimal`, never through `double`. Alpha and Gamma: `^[0-9]{1,9}(\.[0-9]{1,6})?$`; Beta: `^[0-9]{1,11}$`, then `BigDecimal.valueOf(cents, 2)`. A negative price fails the pattern.
- **Discarded records (§6.7):** each one goes through the `DiscardedRecords` port with its provider and a `DiscardReason`, never the exception message as a reason. Nothing is discarded silently.
- **Metrics:** low-cardinality tags only (`provider`, `reason`, `outcome`), never an airport or a carrier (§6.10).
- **Secrets (§6.6):** provider API keys and the database password come from configuration, are never logged, and a `toString()` of any settings record prints `***` for them.
- **Gates:** `./gradlew build` is green before every commit, with Spotless, ArchUnit and JaCoCo (90% lines, 80% branches); Testcontainers tests stay in the `test` suite, so the gate counts them. TDD, one test at a time. The red → green evidence goes to `docs/planning/progress/2026-11-15.md`, section "Providers and ingestion run (plan 4)".
- **Scope (`brief-scope`):** only what this plan lists. An extra found mid-task goes to the progress report under "Extras for the user to decide".
- **Repo rules:** English everywhere. No AI attribution in commits. Push only after `direnv exec . gh api user --jq .login` prints `dromero001`.

## Decisions this plan takes (for the user's review)

| Decision | Why | Source |
|---|---|---|
| **User:** double buffer. Two partitioned tables, `itinerary_a` and `itinerary_b`, and a one-row `inventory_publication` (active slot, generation). One transaction per run: `TRUNCATE` the inactive slot, drop its partitions before today, create today's to today + 37, `COPY` the itineraries, then switch the active slot and increment the generation | The publication is atomic: a failure anywhere rolls the whole run back and search never sees a half-loaded slot. All DDL touches only the inactive slot, so it never locks what search reads. A slot rebuilt each run needs no `DELETE`, so no dead rows | (user, session 09; §6.5 atomic publication) |
| **User:** `JdbcClient` plus `COPY` for persistence, no JPA | Bulk load, DDL and keyset are SQL either way; `COPY` streams 1M rows without holding them in memory | (user, session 09) |
| **User:** at most **2,000,000 itineraries** per run (`ingestion.max-itineraries`) | R7: past it the run stops without publishing and is logged and counted; about twice the expected volume | (user, session 09; R7) |
| **User:** the stale-route refresh is plan 5's | It is triggered by search, so it comes with the search that detects it. This plan adds no origin filter to the mock | (user, session 09) |
| Offers in `provider_offer`, one row per provider offer (before deduplication), with its download position. A provider's offers are replaced in one transaction, only after every page arrived | R1 and §6.5: a failed provider keeps its last good offers whole, and every provider's offer of a flight is stored for plan 5's re-price alternative. The position keeps page order (R10) | (§6.5; R1, R10) |
| Each run rebuilds every itinerary from the stored offers of all providers, fresh and kept | No merge of old and new itineraries; a provider down only means its offers are older (§4, price freshness) | (§5.3, §6.5) |
| A run with no stored offers at all publishes nothing | All providers down on the first run must not publish an empty inventory | (§6.5: never replace good data with nothing) |
| Partitioned by range on `departure` itself, with bounds at UTC midnights, plus a `DEFAULT` partition per slot | One definition of the day (R12), no extra column, and `departure > now` prunes partitions directly | (§4.3; R12) |
| The legs of an itinerary are a `jsonb` column of the itinerary row | Search shows them and re-price re-quotes them, always with their itinerary; a leg table would add ~2.3M rows per slot | (left to the builder, §11) |
| The airport catalogue is a table seeded by a Flyway migration | Versioned reference data, as §4 asks; a new airport is a new migration | (§4, unknown airports) |
| **User:** two time bounds. The HTTP client's timeout, **2.5 s** per page request, plus a Resilience4j **time limiter over each provider's whole download, 60 s** (`providers.download-timeout`). Retry with Spring 7's `RetryTemplate` (3 attempts, 200 ms doubling, only on 5xx, timeouts and I/O errors); a Resilience4j circuit breaker per provider (window of 10 calls, opens at 50% failures, 60 s open, 3 half-open calls) | Only the client can cut a hung connection, so each request keeps its own timeout. Per request alone, a provider that is slow but never fails (every page 2.4 s) stretches its download to about 8 min over 200 pages, and to about 27 min when every page uses its three attempts; the run waits for the slowest provider, close to ShedLock's 29 min and the 30 min cadence, and the circuit breaker never opens because nothing fails. The time limiter bounds the run to about 60 s plus the build. With the mock's defaults (10% of requests take 1–5 s, 5% fail) a page fails all three attempts about 0.14% of the time | (§6.1, §6.5, §11; user, session 09: option B over a client-only timeout) |
| Pages of 1,000 records (the mock's maximum), at most **200 pages** per provider, and an 8 MB limit per response body | Fewer requests, so fewer chances to fail. A pagination that never ends, a cursor or page that repeats, or a huge body fails that provider's download instead of hanging or exhausting memory | (R1: bound the pagination; the body limit: user, session 09, approved extra) |
| The three providers download in parallel on virtual threads; each provider's pages are sequential | The slowest provider sets the duration, not the sum | (§6.1, virtual threads) |
| The service reports each run (`IngestionReport`); `bootstrap` logs it and records the metrics | The application layer may not use Micrometer or SLF4J (ArchUnit rule 2); one place turns runs into signals | (ArchUnit rule 2; §6.10) |
| Run at startup, then with a fixed delay of 30 min; ShedLock `lockAtMostFor` 29 min | Compose has data a minute after starting; two instances, or a schedule and plan 5's manual trigger, never ingest at once | (§6.4; §6.2, ShedLock) |
| Domain visibility rule: `public` means called by the application or an adapter; everything else is package-private | The code review's open note; recorded in `docs/architecture/` | (code review note, progress 2026-11-14) |

## ⚠ Extras — your call

Extras: none. The one proposed, an 8 MB limit on each provider response body, was approved by the user (session 09) and is now in the decisions above.

## Out of scope (carried to later plans)

- The per-provider concurrency limit (bulkhead), with Spring 7's `@ConcurrencyLimit` as §6.1 says, when re-price and the refresh call a provider while ingestion runs (user, session 09). In this plan each provider gets one download at a time, with sequential pages.
- Search, its indexes, the keyset queries, Valkey, the stale-route refresh with the mock's origin filter, re-price, the health `DEGRADED` state, admin endpoints and their authentication (plan 5).
- Grafana, Prometheus, the alert on N failed runs, OTel (plan 7). This plan registers the meters; exposing them over HTTP waits for admin authentication.

## Review Focus

1. **A provider that fails on page k of n keeps its previous offers whole,** and its itineraries stay in the next generation with their old `quotedAt`. Pinned by Task 2 `keepsAFailedProvidersOffersWhole` and Task 10 `ingestsWithOneProviderDown`.
2. **A failure in the middle of the load leaves search on the previous generation.** Pinned by Task 4 `aFailureMidLoadKeepsThePreviousGeneration` (the writer throws after N rows).
3. **A pagination that never ends** (a repeated cursor, a page number or offset that does not advance, more than 200 pages) fails only that provider. Pinned by Task 6 `failsADownloadThatRepeatsAPosition` and `failsADownloadPastTheMaximumPages`.
4. **A departure just after midnight at a non-zero offset** (`2026-11-20T00:30:00+01:00`) goes to the partition of 2026-11-19 UTC. Pinned by Task 4 `partitionsByTheUtcDayOfTheDeparture`.
5. **Two runs at once ingest once.** Pinned by Task 10 `aSecondRunWhileOneHoldsTheLockIsSkipped`.
6. **A provider that is slow but never fails** cannot stretch the run: its download stops at its 60 s budget and its previous offers are kept. Pinned by Task 6 `failsADownloadPastItsTimeBudget` and `requestsNoPageAfterTheBudget`.

---

### Task 1: The ingestion ports and the provider downloads

**Files:**
- Modify: `domain/.../LegKey.java` (`requireNonNull` on every component), `architecture-tests/.../HexagonalArchitectureTest.java` (rule 2 leaves `LENIENT_RULES`), `application/build.gradle.kts`
- Create in `application/src/main/java/com/flightaggregator/application/`: `port/in/IngestInventory.java`, `port/in/IngestionReport.java`, `port/in/ProviderReport.java`; `port/out/Clock.java`, `FlightProvider.java`, `ProviderDownload.java`, `DiscardedRecord.java`, `DiscardReason.java`, `ProviderUnavailableException.java`, `FailureReason.java`, `OfferStore.java`, `AirportCatalogue.java`, `DiscardedRecords.java`; `service/IngestInventoryService.java`
- Create: `docs/architecture/domain-visibility.md` (the visibility rule), linked from the README index
- Test: `domain/.../LegKeyTest.java`, `application/src/test/.../service/IngestInventoryServiceTest.java` with in-memory fakes in `application/src/test/.../service/fakes/`

**Interfaces:**
- Produces (`port.out`):
  - `interface Clock { Instant now(); }`
  - `interface FlightProvider { Provider provider(); ProviderDownload download(); }`. `download()` returns only when every page arrived, else throws `ProviderUnavailableException(Provider provider, FailureReason reason, Throwable cause)`.
  - `enum FailureReason { HTTP_ERROR, TIMEOUT, UNAUTHORIZED, CIRCUIT_OPEN, MALFORMED_PAGE, PAGINATION_LIMIT, UNEXPECTED }`
  - `record ProviderDownload(List<Leg> legs, List<DiscardedRecord> discarded)`, legs in page order.
  - `record DiscardedRecord(DiscardReason reason, String excerpt)`: the excerpt is the record as received (Alpha: the JSON object; Beta: the segment's values as `name=value` pairs; Gamma: the line), at most 200 characters.
  - `enum DiscardReason { SHAPE, CARRIER, FLIGHT_NUMBER, AIRPORT, TIME, PRICE, LEG, UNKNOWN_AIRPORT, CONFLICTING }`
  - `interface DiscardedRecords { void discarded(Provider provider, DiscardedRecord record); }`
  - `interface OfferStore { void replace(Provider provider, List<Leg> offers); List<Leg> offers(Provider provider); }`, `offers` in stored page order.
  - `interface AirportCatalogue { Set<Airport> airports(); }`
- Produces (`port.in`):
  - `interface IngestInventory { IngestionReport ingest(); }`
  - `record ProviderReport(Provider provider, Status status, Optional<FailureReason> failure, int offersFetched, Map<DiscardReason, Integer> discarded, int offersUsed, int lostToDeduplication)` with `enum Status { FRESH, KEPT }`. `offersUsed` counts the stored offers (fresh or kept) that took part in the run.
  - `record IngestionReport(Instant startedAt, Duration duration, Outcome outcome, long generation, long itineraries, List<ProviderReport> providers)` with `enum Outcome { PUBLISHED, NO_OFFERS, CEILING_EXCEEDED }`; providers in `Provider` order.
- `IngestInventoryService(List<FlightProvider>, OfferStore, AirportCatalogue, DiscardedRecords, Clock)`; this task's service ends after storing the offers, and Task 2 adds the build and the publication.

- [ ] **Step 1: `LegKeyTest.rejectsANullComponent`** (code review note): each null component throws `NullPointerException` naming it.
- [ ] **Step 2: `IngestInventoryServiceTest`, one test at a time,** with fakes for every port and a fixed `Clock`:
  - `storesACompleteDownload`: Alpha returns 3 legs → `OfferStore.replace(ALPHA, those 3)`, report `FRESH`, `offersFetched` 3.
  - `keepsAFailedProvidersOffersWhole` (R1): Beta throws `ProviderUnavailableException(BETA, TIMEOUT, …)` → `replace` is never called for Beta, its stored offers are untouched, report `KEPT` with failure `TIMEOUT`.
  - `treatsAnUnexpectedExceptionAsAFailedProvider`: a provider throwing `IllegalStateException` → `KEPT`, failure `UNEXPECTED`, and the other providers are still stored.
  - `downloadsTheProvidersInParallel`: three fakes that each wait on a shared `CountDownLatch(3)` with a 5 s timeout all complete.
  - `discardsLegsWithAnAirportOutsideTheCatalogue`: a leg to `ZZZ` is not stored and goes to `DiscardedRecords` with `UNKNOWN_AIRPORT`.
  - `reportsEveryDiscardedRecordOfTheDownload`: two `DiscardedRecord`s from the download reach `DiscardedRecords` with their provider and are counted by reason in the report.
- [ ] **Step 3: Implement.** Downloads run in `Executors.newVirtualThreadPerTaskExecutor()` (try-with-resources); results are read in `Provider` order. The catalogue is read once per run.
- [ ] **Step 4:** move rule 2 out of `LENIENT_RULES`; write `domain-visibility.md` and make `LegDeduplicator`, `ItineraryBuilder`, `ItineraryId.of` follow it (public only if this plan calls it).
- [ ] **Step 5: `./gradlew build` green, review, commit:** `git commit -m "Download every provider in parallel and keep a failed provider's last good offers"`.

### Task 2: Building and publishing the itineraries

**Files:**
- Create: `application/.../port/out/ItineraryRepository.java`, `port/out/ItineraryWriter.java`, `port/out/Publication.java`, `service/IngestionCeiling.java` (package-private exception)
- Modify: `service/IngestInventoryService.java`, its test and fakes

**Interfaces:**
- Consumes: Task 1's ports; `LegDeduplicator.deduplicate(Collection<Leg>)`, `ItineraryBuilder.build(Deduplication, Instant)`.
- Produces:
  - `@FunctionalInterface interface ItineraryWriter { void writeTo(Consumer<Itinerary> sink); }`
  - `interface ItineraryRepository { Publication replaceInventory(Instant now, ItineraryWriter writer); }`: writes everything the writer pushes into a generation search cannot see, and publishes it only if `writeTo` returns normally. If `writeTo` throws, nothing changes and the exception propagates.
  - `record Publication(long generation, long itineraries, long rowsInDefaultPartition, LocalDate lastPartitionDay)`
  - `IngestInventoryService(List<FlightProvider>, OfferStore, AirportCatalogue, DiscardedRecords, ItineraryRepository, Clock, long maxItineraries)`. It is a plain class; Task 9 declares it as a bean.
- Push mode (R4) has no unit test here: a buffering consumer and a pushing one look the same to a fake. It is a global constraint for the code review, and Task 11 proves it on the real volume with a bounded heap.

- [ ] **Step 1: one test at a time:**
  - `publishesTheItinerariesOfAllStoredOffers`: Alpha fresh with MAD→LHR, Beta down with a stored LHR→JFK from an earlier run → the writer pushes the direct legs and MAD→LHR→JFK; report `PUBLISHED` with the fake's generation; the itinerary through Beta's leg has Beta's stored `quotedAt` (R3).
  - `buildsWithTheRunsStartInstant`: a stored leg departing before `Clock.now()` produces no itinerary.
  - `reportsConflictingOffersAsDiscarded`: Gamma gives one flight two arrivals → both offers go to `DiscardedRecords` with `CONFLICTING`; `lostToDeduplication` counts an offer beaten by a cheaper one of another provider.
  - `publishesNothingWithoutOffers`: every provider down and nothing stored → `replaceInventory` is never called, outcome `NO_OFFERS`, generation 0.
  - `stopsWithoutPublishingPastTheCeiling` (R7): ceiling 2 and four itineraries → the fake repository sees the writer throw and publishes nothing; outcome `CEILING_EXCEEDED`.
  - `propagatesARepositoryFailure`: the repository throws `IllegalStateException` → `ingest()` throws it; the offers already stored stay stored.
- [ ] **Step 2: Implement.** Offers are read per provider in `Provider` order (R10), deduplicated once, then `build(...).forEach(sink)` inside the writer, counting against the ceiling and throwing `IngestionCeiling` past it.
- [ ] **Step 3: `./gradlew build` green, review, commit:** `git commit -m "Build every itinerary from the stored offers and publish it in one generation"`.

### Task 3: The offer store and the airport catalogue in PostgreSQL

**Files:**
- Modify: `gradle/libs.versions.toml`, `adapters/persistence/build.gradle.kts`, `HexagonalArchitectureTest.java` (rules 3 and 4 leave `LENIENT_RULES`)
- Create in `adapters/persistence/src/main/`: `resources/db/migration/V1__provider_offer.sql`, `V2__airport_catalogue.sql`; `java/.../adapter/persistence/JdbcOfferStore.java`, `JdbcAirportCatalogue.java`, `CopyRows.java` (text-format `COPY` writer with escaping)
- Test: `JdbcOfferStoreTest.java`, `JdbcAirportCatalogueTest.java`, `CopyRowsTest.java`; `src/test/.../support/PostgresTestDatabase.java`

**Interfaces:**
- Consumes: `OfferStore`, `AirportCatalogue` (Task 1).
- Produces:
  - Table `provider_offer(provider text, position int, carrier char(2), flight_number int, origin char(3), destination char(3), departure timestamptz, departure_offset int, arrival timestamptz, arrival_offset int, price numeric(11,2), quoted_at timestamptz, primary key (provider, position))`; offsets in seconds.
  - Table `airport(code char(3) primary key)`, seeded with the 33 airports of the mock's `StandardCatalogue` (copied as data; the persistence module does not depend on `mock-gds`).
  - `JdbcOfferStore(DataSource, TransactionTemplate)`, `JdbcAirportCatalogue(JdbcClient)`.
  - `final class CopyRows implements AutoCloseable`: `static CopyRows open(Connection connection, String copySql)`, `void row(String... cells)` (null → `\N`; backslash, tab, newline and carriage return escaped), `long finish()`. It runs on the connection of the current Spring transaction (`DataSourceUtils.getConnection`), unwrapped to `PGConnection`.
  - `PostgresTestDatabase`: one Testcontainers `PostgreSQLContainer` per JVM (same image and digest as Task 11), migrated with Flyway once, giving a `DataSource`; each test class truncates what it uses.

- [ ] **Step 1: catalog and build.** Main: `spring-jdbc`, `postgresql`, `jackson-core` (used in Task 4). Tests: `flyway-core`, `flyway-database-postgresql`, Testcontainers `testcontainers-postgresql` and `testcontainers-junit-jupiter`. All through the Boot BOM; verify the artifacts exist at those versions and record it. The migrations are resources of this module; Task 9's application runs them with Boot's Flyway.
- [ ] **Step 2: `CopyRowsTest`, one test at a time:** `escapesTheTextFormatsSpecialCharacters` (a cell `a\tb\\c\nd` reads back equal through a `COPY` into a temp table and a `SELECT`); `writesNullAsNull`.
- [ ] **Step 3: `JdbcOfferStoreTest`, one test at a time:**
  - `roundTripsALegExactly` (R3): a leg with a negative offset (`-05:00`), a quote with microseconds and a price of `0.01` comes back `equals` the original, with an equal `key()`.
  - `returnsOffersInStoredOrder`.
  - `replacesOnlyThatProvidersOffers`: replacing Alpha leaves Beta's rows.
  - `replacesAProvidersOffersAtomically`: a replacement with a `List<Leg>` whose `get(1)` throws (a test `AbstractList`) propagates the exception and leaves the provider's old offers.
- [ ] **Step 4: `JdbcAirportCatalogueTest.loadsTheSeededCatalogue`:** 33 airports, `MAD` in, `ZZZ` out.
- [ ] **Step 5:** move rules 3 and 4 out of `LENIENT_RULES` (the list becomes empty; delete it and its test if nothing uses it).
- [ ] **Step 6: `./gradlew build` green, review, commit:** `git commit -m "Store each provider's offers in PostgreSQL and read the airport catalogue"`.

### Task 4: The itinerary slots, the load with COPY and the atomic switch

**Files:**
- Create: `db/migration/V3__itinerary_slots.sql`; `adapter/persistence/JdbcItineraryRepository.java`, `Slot.java` (enum `A`, `B` with their table names), `PartitionCalendar.java`, `ItineraryRow.java` (an itinerary to its `COPY` cells, `jsonb` legs written with Jackson's streaming generator)
- Test: `JdbcItineraryRepositoryTest.java`, `PartitionCalendarTest.java`, `ItineraryRowTest.java`

**Interfaces:**
- Consumes: `ItineraryRepository`, `ItineraryWriter`, `Publication` (Task 2); `CopyRows`, `PostgresTestDatabase` (Task 3).
- Produces:
  - Tables `itinerary_a`, `itinerary_b`, identical, `PARTITION BY RANGE (departure)`, each with a `DEFAULT` partition: `id uuid, departure timestamptz, departure_offset int, arrival timestamptz, arrival_offset int, origin char(3), destination char(3), duration_seconds int, stops smallint, single_carrier char(2) null, price numeric(11,2), quoted_at timestamptz, legs jsonb, primary key (id, departure)`. Day partitions are named `itinerary_<slot>_<yyyymmdd>`.
  - Table `inventory_publication(id smallint primary key check (id = 1), active_slot char(1) not null check (active_slot in ('a','b')), generation bigint not null, published_at timestamptz)`, seeded `(1, 'a', 0, null)`.
  - `JdbcItineraryRepository(DataSource, TransactionTemplate, JsonFactory, int partitionDaysAhead)` with `partitionDaysAhead` = 37 in production.
  - `PartitionCalendar`: `static LocalDate dayOf(Instant departure)` (UTC date), `static Instant start(LocalDate day)`.
  - Leg JSON, pinned by `ItineraryRowTest`: `[{"carrier":"IB","flightNumber":3166,"origin":"MAD","destination":"LHR","departure":"2026-11-20T08:15:00+01:00","arrival":"2026-11-20T09:30:00+00:00","price":"129.90","provider":"ALPHA","quotedAt":"2026-11-15T10:00:00.123456Z"}]`.

- [ ] **Step 1: `PartitionCalendarTest.partitionsByTheUtcDayOfTheDeparture`:** `2026-11-20T00:30:00+01:00` → `2026-11-19`; `2026-11-19T23:30:00-05:00` → `2026-11-20`.
- [ ] **Step 2: `ItineraryRowTest`, one test at a time:** `writesTheCellsOfATwoLegItinerary` (every cell, `single_carrier` null for an interline trip, `stops` 1, the price with the markup); `writesTheLegsAsJson` (the string above). `ItineraryRow.of(Itinerary)` calls `price()` and `id()` once each and keeps the results.
- [ ] **Step 3: `JdbcItineraryRepositoryTest`, one test at a time:**
  - `publishesIntoTheInactiveSlotAndSwitches`: on a fresh database, two itineraries → `Publication(1, 2, 0, today + 37)`; `inventory_publication` is `('b', 1)`; `itinerary_b` has 2 rows.
  - `rebuildsTheOtherSlotOnTheNextRun`: a second run with one itinerary → active `a`, generation 2, `itinerary_a` has exactly 1 row, `itinerary_b` still has its 2 (search of generation 1 can finish).
  - `aFailureMidLoadKeepsThePreviousGeneration` (Review Focus 2): a writer that throws after 1,000 rows → the exception propagates, `inventory_publication` is unchanged, and the inactive slot's previous rows are still there (the `TRUNCATE` rolled back).
  - `dropsPartitionsBeforeTodayAndCreatesThemAhead`: with `now` on 2026-11-20, the loaded slot has partitions 2026-11-20 to 2026-12-27 and none before.
  - `putsADepartureBeyondThePartitionsInTheDefaultPartition`: a departure on today + 40 → `rowsInDefaultPartition` 1, and the load still publishes.
  - `loadsAThousandItinerariesThroughCopy`: 1,000 generated itineraries, count and one row read back by id.
- [ ] **Step 4: Implement `replaceInventory`.** One `TransactionTemplate` transaction: `TRUNCATE` the inactive slot, `DROP TABLE` its day partitions before today, `CREATE TABLE IF NOT EXISTS … PARTITION OF` for today to today + `partitionDaysAhead`, open `CopyRows`, run the writer, `finish`, count the `DEFAULT` partition, `UPDATE inventory_publication SET active_slot = ?, generation = generation + 1, published_at = ?  RETURNING generation`. Table names come only from `Slot` and `PartitionCalendar`, never from input. A `lock_timeout` of 5 s on the transaction.
- [ ] **Step 5: `./gradlew build` green, review, commit:** `git commit -m "Load the itineraries with COPY into the inactive slot and switch slots atomically"`.

### Task 5: The shared provider client: timeout, retry, circuit breaker and record parsing

**Files:**
- Modify: `gradle/libs.versions.toml`, `adapters/providers/build.gradle.kts` (`spring-web`, `jackson-databind`, Resilience4j circuit breaker; WireMock standalone for tests)
- Create in `adapters/providers/src/main/java/.../adapter/provider/`: `ProviderEndpoint.java`, `ProviderHttp.java`, `ResilienceSettings.java`, `RecordFields.java`, `RecordRejected.java`
- Test: `ProviderHttpTest.java` (WireMock), `RecordFieldsTest.java`

**Interfaces:**
- Consumes: `Clock`, `FailureReason`, `ProviderUnavailableException`, `DiscardReason` (Task 1).
- Produces:
  - `record ProviderEndpoint(Provider provider, URI baseUrl, String apiKey)`; `toString()` prints `apiKey=***`.
  - `record ResilienceSettings(Duration requestTimeout, Duration downloadTimeout, int maxAttempts, Duration firstBackoff, int pageSize, int maxPages, int maxBodyBytes)`; production defaults 2.5 s, 60 s, 3, 200 ms, 1000, 200, 8 MB.
  - `final class ProviderHttp`, built from `(ProviderEndpoint, ResilienceSettings, CircuitBreaker, Clock)`, with `FetchedPage get(String pathAndQuery)` and `record FetchedPage(String body, Instant receivedAt)`. It sends `X-Api-Key`, retries through `RetryTemplate`, runs each attempt inside the circuit breaker, and maps failures to `ProviderUnavailableException`: 401/403 → `UNAUTHORIZED` (no retry), other 4xx → `HTTP_ERROR` (no retry), 5xx after the attempts → `HTTP_ERROR`, timeout → `TIMEOUT`, open breaker → `CIRCUIT_OPEN`, body over the limit → `MALFORMED_PAGE`.
  - `final class RecordFields` (static, strict): `Carrier carrier(String)`, `int flightNumber(String)` (`^[0-9]{1,4}$`), `Airport airport(String)`, `OffsetDateTime time(String)` (ISO-8601 with offset, seconds required), `Money decimalPrice(String)`, `Money centsPrice(String)`. Each throws `RecordRejected(DiscardReason)`.
  - `static Leg RecordFields.leg(...)`: builds the `Leg`, turning the domain's `IllegalArgumentException` into `RecordRejected(LEG)`.

- [ ] **Step 1: `RecordFieldsTest`, one test at a time:** `parsesAValidDecimalPrice` (`"129.90"`, `"7"`, `"10.005"` → `10.01`); `rejectsAnExponentANegativeAndAnOverlongPrice` (`"1e3"`, `"-129.90"`, `"1234567890.00"` → `PRICE`); `parsesCents` (`"12990"` → `129.90`); `rejectsATimeWithoutOffsetOrSeconds` (`"2026-11-20T08:15"`, `"2026-11-20T08:15:00"`, `Defect.INVALID_DATE_TEXT`'s value `2026-02-30T25:61:00+01:00` → `TIME`); `rejectsABadFlightNumberCarrierOrAirport`; `rejectsALegTheDomainRejects` (arrival before departure → `LEG`).
- [ ] **Step 2: `ProviderHttpTest`, one test at a time,** against WireMock with a fixed `Clock`:
  - `sendsTheApiKeyAndReturnsTheBody`, with `receivedAt` from the clock truncated to microseconds.
  - `retriesAServerErrorThenSucceeds`: 500 then 200 → the body, 2 requests.
  - `failsAfterTheLastAttempt`: three 500s → `HTTP_ERROR`, 3 requests.
  - `doesNotRetryAWrongKey`: 401 → `UNAUTHORIZED`, 1 request.
  - `timesOutASlowResponse`: a fixed delay of 3 s with a 500 ms timeout → `TIMEOUT`.
  - `failsFastWithAnOpenBreaker`: after the breaker opens, the next call makes no request → `CIRCUIT_OPEN`.
  - `rejectsABodyOverTheLimit`: a body of `maxBodyBytes + 1` → `MALFORMED_PAGE`, read no further than the limit.
  - `neverLogsTheApiKey`: `ProviderEndpoint.toString()` does not contain the key.
- [ ] **Step 3: `./gradlew build` green, review, commit:** `git commit -m "Call a provider with a timeout, bounded retries and a circuit breaker"`.

### Task 6: Alpha: JSON with a cursor

**Files:**
- Create: `adapter/provider/alpha/AlphaProvider.java` (implements `FlightProvider`), `alpha/AlphaPageParser.java`; `adapter/provider/PagedDownload.java` (shared bounds)
- Test: `alpha/AlphaPageParserTest.java`, `alpha/AlphaProviderTest.java` (WireMock), `PagedDownloadTest.java`

**Interfaces:**
- Consumes: `ProviderHttp`, `RecordFields` (Task 5).
- Produces:
  - `record ParsedPage(List<Leg> legs, List<DiscardedRecord> discarded, Optional<String> next)`: shared by the three parsers (create it in this task, in `adapter.provider`).
  - `AlphaPageParser.parse(String body, Instant quotedAt) → ParsedPage`: the format `{"legs":[{"carrier","flightNumber","origin","destination","departureTime","arrivalTime","price"}],"nextCursor":…}` as `mock-gds/.../alpha/AlphaJson.java` writes it; `price` must be a JSON string, `flightNumber` a JSON integer.
  - `final class PagedDownload`, built from `(Provider, ResilienceSettings, TimeLimiter)`: drives `next` positions and throws `ProviderUnavailableException(PAGINATION_LIMIT)` when a position repeats or `maxPages` is passed. It runs the whole page loop inside the provider's Resilience4j `TimeLimiter` (`timeoutDuration` = `downloadTimeout`, `cancelRunningFuture` true), on a virtual thread; past the budget it throws `ProviderUnavailableException(TIMEOUT)`, and the loop checks the thread's interrupt flag before each page, so a cancelled download requests no further page. The page in flight is bounded by its own request timeout.
  - `AlphaProvider(ProviderHttp, ResilienceSettings)`: `GET /alpha/legs?limit=<pageSize>[&cursor=…]` until `nextCursor` is `null`.

- [ ] **Step 1: `AlphaPageParserTest`, one test at a time,** on golden bodies copied from the mock's `AlphaJsonTest`: `parsesAValidLeg`; `discardsEachOfTheMocksDefects` (`MISSING_FIELD` → `FLIGHT_NUMBER`, `BAD_DATE` → `TIME`, `NEGATIVE_PRICE` → `PRICE`; `UNKNOWN_AIRPORT` parses, since only the catalogue catches it) with the valid neighbours kept; `discardsANumericPrice` (`"price":129.9` → `PRICE`); `discardsANonObjectRecord` (`SHAPE`); `failsAPageThatIsNotJson` (`MALFORMED_PAGE`); `readsTheNextCursorAndTheEnd`.
- [ ] **Step 2: `PagedDownloadTest`, one test at a time:** `failsADownloadThatRepeatsAPosition`, `failsADownloadPastTheMaximumPages` (Review Focus 3); `failsADownloadPastItsTimeBudget` (a fake page source that takes 100 ms per page, a 250 ms budget and 10 pages → `TIMEOUT`, raised within 400 ms); `requestsNoPageAfterTheBudget` (same setup: the fake records at most 3 page requests, and none after the exception, checked 300 ms later).
- [ ] **Step 3: `AlphaProviderTest.downloadsEveryPageInOrder`:** three WireMock pages → the legs in page order, `quotedAt` of each page's arrival; a 500 on page 2 after retries → `ProviderUnavailableException(ALPHA, HTTP_ERROR)`, never a partial download.
- [ ] **Step 4: `./gradlew build` green, review, commit:** `git commit -m "Download Alpha's legs page by page through its cursor"`.

### Task 7: Beta: nested XML by page number

**Files:**
- Create: `adapter/provider/beta/BetaProvider.java`, `beta/BetaPageParser.java`
- Test: `beta/BetaPageParserTest.java`, `beta/BetaProviderTest.java`

**Interfaces:**
- Consumes: `ProviderHttp`, `RecordFields`, `ParsedPage`, `PagedDownload` (Tasks 5–6).
- Produces: `BetaPageParser.parse(String body, Instant quotedAt) → ParsedPage` for `mock-gds/.../beta/BetaXml.java`'s format; `next` is `page + 1` while `page < totalPages`. `BetaProvider`: `GET /beta/segments?page=<n>&pageSize=<pageSize>` from 1.

- [ ] **Step 1: `BetaPageParserTest`, one test at a time,** on golden bodies from the mock's `BetaXmlTest`: `parsesAValidSegment`; `discardsEachOfTheMocksDefects` (as Alpha, prices in cents); `refusesADoctypeAndExternalEntities` (a body with `<!DOCTYPE … SYSTEM "file:///etc/passwd">` → `MALFORMED_PAGE`, nothing read: StAX with `SUPPORT_DTD` false and `IS_SUPPORTING_EXTERNAL_ENTITIES` false); `failsAPageThatIsNotWellFormed`; `readsThePageCount`.
- [ ] **Step 2: `BetaProviderTest.downloadsEveryPageInOrder`** and `failsWhenTotalPagesExceedsTheMaximum` (`PAGINATION_LIMIT` before page 2).
- [ ] **Step 3: `./gradlew build` green, review, commit:** `git commit -m "Download Beta's segments page by page from nested XML"`.

### Task 8: Gamma: CSV with a header by offset and limit

**Files:**
- Create: `adapter/provider/gamma/GammaProvider.java`, `gamma/GammaPageParser.java`
- Test: `gamma/GammaPageParserTest.java`, `gamma/GammaProviderTest.java`

**Interfaces:**
- Consumes: as Task 7.
- Produces: `GammaPageParser.parse(String body, Instant quotedAt, int limit) → ParsedPage` for `mock-gds/.../gamma/GammaCsv.java`'s format; columns are found by header name; `next` is `offset + limit` when the page has `limit` rows. `GammaProvider`: `GET /gamma/legs.csv?offset=<n>&limit=<pageSize>`.

- [ ] **Step 1: `GammaPageParserTest`, one test at a time,** on golden bodies from the mock's `GammaCsvTest`: `parsesAValidRow`; `readsColumnsByHeaderName` (reordered columns parse the same); `discardsEachOfTheMocksDefects`; `discardsARowWithTheWrongCellCount` (`SHAPE`); `failsAPageWithoutARequiredColumn` (`MALFORMED_PAGE`); `endsOnAShortPageAndOnAHeaderOnlyPage`.
- [ ] **Step 2: `GammaProviderTest.downloadsEveryPageInOrder`.**
- [ ] **Step 3: `./gradlew build` green, review, commit:** `git commit -m "Download Gamma's legs page by page from CSV read by header"`.

### Task 9: The Spring Boot application and its wiring

**Files:**
- Modify: `bootstrap/build.gradle.kts` (Boot plugin, starters: JDBC, Flyway with `flyway-database-postgresql`, Actuator, Jackson; `spring-boot-testcontainers`), `coverage/build.gradle.kts` (exclude `**/bootstrap/FlightAggregatorApplication.class` by name)
- Create in `bootstrap/src/main/java/com/flightaggregator/bootstrap/`: `FlightAggregatorApplication.java`; `config/ProvidersConfig.java` (the three adapters, one breaker each), `config/ProvidersProperties.java`, `config/PersistenceConfig.java`, `config/IngestionConfig.java` (the service), `config/SystemClock.java`; `observability/LoggedDiscardedRecords.java`; `src/main/resources/application.yaml`
- Test: `FlightAggregatorApplicationTest.java`, `observability/LoggedDiscardedRecordsTest.java`

**Interfaces:**
- Consumes: every adapter and the service.
- Produces:
  - Properties: `providers.{alpha,beta,gamma}.{base-url,api-key}`, `providers.request-timeout` (2.5 s), `providers.download-timeout` (60 s), `providers.max-attempts` (3), `providers.page-size` (1000), `providers.max-pages` (200); `ingestion.max-itineraries` (2000000), `ingestion.interval` (PT30M), `ingestion.schedule.enabled` (true; false in tests); `spring.datasource.*`. Startup fails without an API key or base URL.
  - `LoggedDiscardedRecords implements DiscardedRecords`: one `WARN` line per record with `provider`, `reason` and the excerpt as structured fields, and the counter `ingestion.records.discarded{provider,reason}`.
  - Virtual threads on; Actuator exposes only `health`.

- [ ] **Step 1: `LoggedDiscardedRecordsTest.countsAndLogsEachDiscardedRecord`** (a `SimpleMeterRegistry` and an output capture).
- [ ] **Step 2: `FlightAggregatorApplicationTest`, one test at a time:** `startsAgainstPostgresAndMigrates` (Testcontainers `@ServiceConnection`, health `UP`, Flyway applied V1–V3); `failsToStartWithoutAProviderApiKey`.
- [ ] **Step 3: `./gradlew build` green, review, commit:** `git commit -m "Wire the providers, the stores and the ingestion into a Spring Boot application"`.

### Task 10: The scheduled ingestion end to end, with 1, 2 and 3 providers down

**Files:**
- Modify: `gradle/libs.versions.toml` (ShedLock), `bootstrap/build.gradle.kts`
- Create: `db/migration/V4__shedlock.sql` (in `adapters/persistence`, ShedLock's documented PostgreSQL table); `bootstrap/.../ingestion/IngestionJob.java`, `ingestion/IngestionMetrics.java`, `config/SchedulingConfig.java`
- Test: `bootstrap/src/test/.../ingestion/IngestionEndToEndTest.java` (Testcontainers + one WireMock server serving the three providers), `ingestion/IngestionJobTest.java`, `ingestion/IngestionMetricsTest.java`

**Interfaces:**
- Consumes: `IngestInventory`, `IngestionReport`.
- Produces:
  - `IngestionJob.run()`: `@Scheduled(initialDelay = 0, fixedDelayString = "${ingestion.interval}")`, `@SchedulerLock(name = "ingestion", lockAtMostFor = "PT29M", lockAtLeastFor = "PT1M")`. It logs one `INFO` line per run (outcome, generation, itineraries, duration, and per provider status, failure, fetched, discarded, used, lost to deduplication), records the metrics, and catches any exception so the schedule continues (logged at `ERROR`, counted as a failed run).
  - Meters (`IngestionMetrics`): timer `ingestion.duration{outcome}`; counter `ingestion.runs{outcome}` (with `outcome=FAILED` for an exception); gauges `ingestion.itineraries`, `ingestion.consecutive.failures` (any outcome but `PUBLISHED` counts), `ingestion.provider.last.fresh.seconds{provider}` (age of the provider's last `FRESH` download), `ingestion.partitions.days.ahead`, `ingestion.partitions.default.rows`; counter `ingestion.provider.downloads{provider,status}`.

- [ ] **Step 1: `IngestionMetricsTest`, one test at a time:** `recordsAPublishedRun`; `countsConsecutiveFailuresUntilAPublishedRun` (two `CEILING_EXCEEDED`, one exception, then `PUBLISHED` → 3 then 0).
- [ ] **Step 2: `IngestionJobTest.keepsSchedulingAfterAnException`.**
- [ ] **Step 3: `IngestionEndToEndTest`, one test at a time,** with a small fixed network on WireMock (MAD→LHR on Alpha, LHR→JFK on Beta, MAD→JFK and a conflicting pair on Gamma, one malformed record per provider):
  - `ingestsAllThreeProviders`: generation 1, the expected itineraries in the active slot, every provider `FRESH`, discarded counters per reason.
  - `ingestsWithOneProviderDown`: after a good run, Beta answers 500 → generation 2; MAD→LHR→JFK is still published with Beta's previous `quotedAt`.
  - `ingestsWithTwoProvidersDown`.
  - `keepsTheLastGoodInventoryWithAllThreeDown`: generation 3, the same itineraries, every provider `KEPT`.
  - `publishesNothingWhenAllThreeAreDownOnTheFirstRun`: generation 0, outcome `NO_OFFERS`.
  - `aSecondRunWhileOneHoldsTheLockIsSkipped` (Review Focus 5): with ShedLock's lock held through `LockProvider` for `ingestion`, `IngestionJob.run()` returns without ingesting (generation unchanged).
- [ ] **Step 4: `./gradlew build` green, review, commit:** `git commit -m "Ingest on a schedule under a database lock, and prove it with providers down"`.

### Task 11: The image, PostgreSQL in compose, and the real ingestion measured

No production Java, so no TDD: the proof is the local Docker run, recorded in the progress report.

**Files:**
- Create: `bootstrap/Dockerfile` (same pattern as `mock-gds/Dockerfile`: multi-stage, Temurin 25 pinned by tag and digest, non-root, `bash` `/dev/tcp` health check)
- Modify: `compose.yaml` (services `postgres` and `app`), `.github/workflows/ci.yml` (build the `app` image without pushing), `.github/dependabot.yml` (`docker` for `/bootstrap`, `docker-compose` for `/`), `README.md`, `docs/architecture/` (an ingestion page: the run, the slots, the failure behaviour), `docs/planning/roadmap.md` (plan 4 done)

- [ ] **Step 1:** pick the `postgres:18` tag by §6.1 (exact tag, 14+ days old, digest of the multi-arch index) and use the same one in `PostgresTestDatabase`. Record tag, push date and digest.
- [ ] **Step 2: compose.** `postgres` with a committed demo password as an overridable default (`${POSTGRES_PASSWORD:-…}`, the same deliberate exception as the provider keys, said in the comment and the README), a `pg_isready` health check and a named volume; `app` built from `bootstrap/Dockerfile`, depending on `postgres` and `mock-gds` being healthy, with the provider URLs and keys from the same variables as the mock, published on `127.0.0.1:8080`.
- [ ] **Step 3: run and measure.** The `app` container runs with `-Xmx512m` (`JAVA_TOOL_OPTIONS`), which proves push mode (R4) on the real volume: buffering one first leg's itineraries ran out of memory at 256 MB in plan 2's probe. `docker compose up --build -d --wait`, then from the `app` log: the first run's outcome, duration, itineraries and per-provider counts; `SELECT count(*)` on the active slot. Record the numbers (this replaces the 5–15 s estimate given to the user). Then make one provider fail every request with an override file and show the next run keeping its offers. `docker compose down`.
- [ ] **Step 4: `./gradlew build` green, review, commit:** `git commit -m "Run the aggregator and PostgreSQL in docker compose, ingesting the mock providers"`.
