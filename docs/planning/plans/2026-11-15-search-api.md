# Search API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Every production change follows `superpowers:test-driven-development` and `CLAUDE.md`. Reviews follow the review protocol in `CLAUDE.md`: `functional-reviewer` and `code-reviewer` after every task, plus `risk-reviewer` on the whole plan at the end.

**Goal:** a public search over the published inventory, with keyset pagination, the four optional filters and three sort orders, cached in Valkey and rate-limited per client IP; a live re-price on selection with its alternative flow; a background refresh of stale origins; and admin endpoints behind Keycloak, with a `DEGRADED` health and the OpenAPI contract that plan 6 generates the dashboard's types from.

**Architecture:**
- `application` gains the use cases `SearchItineraries`, `RefreshStaleRoute`, `RepriceItinerary`, `AdmitRequest`, `TriggerIngestion` and `InventoryStatus`, with their outbound ports. Search reads only PostgreSQL and Valkey, never a provider.
- `adapters/persistence` reads the active slot with keyset queries, one index per sort order, and holds the Valkey adapters (result cache, refresh coordination, rate-limit counters), where §6's module table puts Redis.
- `adapters/providers` (one package, `PagedProvider` over three page formats) adds a download by origin and a one-flight quote, behind a per-provider concurrency limit; `mock-gds` serves both (built).
- `adapters/web` holds the controllers, the cursor codec, parameter parsing, problem responses and the rate-limit interceptor. `bootstrap` adds the resource server, the background refresh, the health indicator, the wiring, and Valkey and Keycloak in compose.

**Tech Stack:** Java 25, Spring Boot 4.1.2 (Spring Framework 7.0.10: `@ConcurrencyLimit`, with `ThrottlePolicy.BLOCK | REJECT` since 7.0.3; Spring Security 7.1.2 resource server; Spring Data Redis on Lettuce 7.5.2), PostgreSQL 18, Valkey 9, Keycloak, springdoc-openapi, Testcontainers 2.0.5 with the BOM's `com.redis:testcontainers-redis` 2.2.4, WireMock 3.13.2. Versions read from the Boot 4.1.2 BOM on 2026-11-15.

**Spec:** [design spec](../design-spec.md), sections:
- §4, §4.1: filters, Carrier (every leg by that carrier), `departure > now`, the stale-route refresh, price freshness.
- §5.4: keyset, one composite index per sort order with the id as tie-breaker, opaque cursor, "Load more", no total count.
- §6.2: cache-aside with generation-versioned keys, `SET NX` refresh claims, rate-limit counters, the system working without Valkey.
- §6.3: re-price on selection, "updated X min ago", the cursor carries the generation.
- §6.4: 10 min per-origin threshold, 5 min Valkey TTL.
- §6.5: re-price alternative flow, 503 + `Retry-After`, health `DEGRADED`.
- §6.6: public search and re-price, per-IP 429, OAuth2/JWT admin with Keycloak and `ADMIN`.
- §6.8: what each result card shows; the provider-status screen and "Run ingestion now".
- §6.10: metrics of what this plan builds. §6.11: OpenAPI contract drift.

Roadmap: [plan 5](../roadmap.md). Carried risks: [progress 2026-11-14](../progress/2026-11-14.md), "Pending for later plans" (R5, R11; the `LegKey` null checks were closed by plan 4 Task 1), and plan 4's ledger notes (bulkhead, `ANALYZE`, one provider-time helper, the refresh); each task names what it closes. Plan 4 is closed: this plan builds on its `IngestionJob` (ShedLock lock `ingestion` through `LockingTaskExecutor`, run id in the MDC), `V4__shedlock.sql`, the compose `app` service, `PagedProvider` and the `CircuitBreakerRegistry` bean. Task 1 is done; the rest is rebased on the code at HEAD (after plan 4's final fix wave).

**Status:** approved by the user on 2026-11-15 (session 09), with every open question answered as recommended. Extras: E1 replaced by a confidential **client-credentials** client with a service account holding `ADMIN` (not the deprecated password grant), documented in the README's "Admin access" section and in `SOLUTION.md` so that reviewers can reproduce the admin calls without the author; E2 (Swagger UI) approved; E3 and E4 rejected. Before execution, Task 2 is rewritten on plan 4's final provider structure (one `PagedProvider`, a `CircuitBreakerRegistry`, no `WiredProvider`), and the out-of-scope note on plan 4's extras is updated.

## Global Constraints

- **Packages:** `com.flightaggregator.application.port.in|port.out|service`, `com.flightaggregator.adapter.provider|persistence|web`, `com.flightaggregator.bootstrap`. ArchUnit's rules hold; the web adapter uses only inbound ports.
- **Version policy (§6.1):** Spring Data Redis, Spring Security, the OAuth2 resource server, `spring-security-test` and `testcontainers-redis` take the Boot 4.1.2 BOM. springdoc-openapi and the `valkey/valkey:9` and Keycloak images are chosen at their task by the 14-day rule, pinned (images by tag and multi-arch digest) and recorded. If no springdoc GA at least 14 days old supports Boot 4.1, that task stops and asks.
- **Identity (R11):** keyset, cache, re-price and test oracles key on `Itinerary.id()`. No ordering by `UUID` in Java: PostgreSQL orders `uuid` by unsigned bytes, so oracles come from SQL. Prices compare with `Money.compareTo`.
- **API content:** a result carries what §6.8's card shows and nothing more: no provider, no leg price. Times are ISO-8601 with the airport's offset (§4.2); prices are decimal strings in EUR.
- **Values from the spec:** freshness threshold 10 min, result TTL 5 min (§6.4), Valkey `allkeys-lru` with `maxmemory` (§6.2), all configurable (`search.freshness-threshold`, `search.cache-ttl`). Values the spec leaves open: Q9.
- **Copy fixed by §6.5:** *"We couldn't confirm this flight. This is the best alternative we found."* and *"We can't confirm the price right now. Please try again in a few minutes."*
- **Errors:** RFC 9457 problem details, English `detail` naming the parameter. A domain `IllegalArgumentException` is caught where the parameter is parsed, never mapped to 400 globally.
- **Stores (E6):** every new store (the itinerary reader, the provider-status writer, the re-price update, the refresh's slot rewrite) is built from Boot's one transaction manager, as `PersistenceConfig` builds `JdbcOfferStore` and `JdbcItineraryRepository` (a `DataSourceTransactionManager`, which is a `PlatformTransactionManager`): connections and `TransactionTemplate` come from it, a store never takes a `DataSource` or a `JdbcClient` of its own, and `PersistenceConfig` is where each is declared.
- **Metrics (§6.10 minimum, low cardinality):** `search.cache{result=hit|miss|error}` (hit ratio, §6.2) and `provider.calls{provider,operation,outcome}` for every provider call. Never an airport, carrier or client tag. Tokens and keys are never logged.
- **Gates:** as plan 4: `./gradlew build` green before every commit, TDD one test at a time, red → green evidence in `docs/planning/progress/<date>.md`, section "Search API run (plan 5)". Scope by `brief-scope`; extras found mid-task go to the progress report. English everywhere; no AI attribution; push only after `direnv exec . gh api user --jq .login` prints `dromero001`.

## Open questions for the user

**Answered by the user on 2026-11-15 (session 09):** Q1 A, Q2 B, Q3 A, Q4 A, Q5 A, Q6 B, Q7 B, Q8 A, Q9 A (every recommendation). Extras: E2 (Swagger UI) approved, E3 (signed cursors) rejected; E1 (password grant) and E4 (rate-limit headers) still open — the user asked how an admin logs in without E1 (through the dashboard's Keycloak login, Authorization Code with PKCE; a client-credentials client is the non-deprecated way to get a token from the command line) and why E4 helps. The plan is not approved yet: the user wants to read it first.


**Q1. Cursors across generations (§6.3).** The double buffer keeps the previous generation whole in the inactive slot until the next run truncates it.
- A. Always flag: a cursor of another generation seeks from its position on the active generation, and the page says `inventoryChanged: true` ("results updated, reload").
- B. Keep paging its own generation while a slot still holds it: `inventory_publication` records each slot's generation; a cursor of the previous one reads the inactive slot with `SET LOCAL lock_timeout` of about 100 ms, so it never waits on (or queues behind) the next run's `TRUNCATE`, then rereads that slot's generation in the same transaction after the page query and flags if it changed. It flags only when no slot holds its generation.
- C. Always flag and return no rows, forcing a reload.
- **Recommendation: A.** With Q2-B the active slot changes in place on every refresh, so B would keep a cursor alive only across a full run (every 30 min), for a migration, a second lock path and a recheck on every old-slot page. A gives rows plus the notice, the trade-off §6.3 accepted. Tasks: 4 (B adds the slot to read), 6, 9.

**Q2. The stale-route refresh and the double buffer (§4.1, §6.4).**
- A. Rebuild the whole inactive slot: download the origin from every provider, swap those offers into the stored set, publish through `replaceInventory`. One publication path, no dead rows, `ANALYZE` as on every load. But each refresh is a full build and `COPY` of about 1M rows, the cost for which §6.4 runs the full ingestion only every 30 min, and each one bumps the generation (whole cache invalidated, every cursor flagged).
- B. Rewrite the active slot in place for that origin: build from the stored offers with the origin's fresh ones swapped in, keep the itineraries with a leg departing from it, and in one transaction delete those rows from the active slot, `COPY` the new ones and bump the generation (versioned cache keys and cursors follow the one rule). Costs: dead rows (autovacuum analyzes leaf partitions, so no explicit `ANALYZE`); a scan of the active slot for the legs (no index; a GIN index on `legs` if Task 13 measures it slow); a full in-memory build, filtered; the refresh and the run's switch serialize on the `inventory_publication` row. Fresh offers are not stored (the next run downloads everything).
- C. Store only the origin's fresh offers and ask for an early full run: A's cost, later.
- **Recommendation: B.** A refresh exists to keep a searched origin within 10 min without a full rebuild (§6.4), and R1 (C2) asked it to "replace their itineraries in one transaction". Tasks: 7.

**Q3. Max Price and the result cache (§6.2).** Each user types a different amount.
- A. Cache only first pages (no cursor) of searches without Max Price.
- B. Cache every page of every search, Max Price in the key.
- C. Cache first pages of every search, Max Price in the key.
- **Recommendation: A.** §6.2 justifies the cache by "a few popular routes and the first page" (session 03: "the first page of popular searches"); typed amounts rarely repeat, so caching them fills memory and dilutes the hit ratio §6.2 uses as evidence. Tasks: 6.

**Q4. The re-price contract (§6.3, §6.5, §6.8).**
- A. `POST /api/itineraries/{id}/reprice` answers `200` with `outcome` `CONFIRMED`, `PRICE_CHANGED` (with `previousPrice`) or `ALTERNATIVE` (with the §6.5 notice and the alternative); `503` problem with `Retry-After` and the §6.5 message when nothing is confirmable; `404` for an id not in the active generation. A departed choice (R5's "no longer available") takes the alternative flow.
- B. One status per outcome: `200` confirmed, `409` price changed, `200` alternative, `410` departed, `503`.
- C. As A, but a departed choice answers `410 Gone`.
- **Recommendation: A.** The three outcomes are answers the dashboard renders as states (§6.8), one body with a discriminator becomes one generated TypeScript union, and only "nothing confirmable" is a failure to retry. Tasks: 8, 10.

**Q5. Re-price's in-place update, the generation and the cache (§6.3: "the stored itinerary is updated").**
- A. Update the row in the active slot (price, legs, `quoted_at`) by id, generation unchanged: cached pages keep the old price up to their 5 min TTL, and a paging user may see the row move.
- B. Update and bump the generation: every cached page and cursor is invalidated on each re-price.
- C. Do not update the stored itinerary (against §6.3).
- **Recommendation: A.** Re-prices happen on card clicks; a bump per click would flush the cache and flag every user's pagination continuously, defeating §6.2. Tasks: 8.

**Q6. Indexes per slot (§5.4, §4.3).**
- A. One per sort order, `(price, id)`, `(departure, id)`, `(duration_seconds, id)`, plus the spec's `(origin, destination, price, id)`: 4 per slot.
- B. A, plus `(origin, destination, departure, id)` and `(origin, destination, duration_seconds, id)`: 6 per slot.
- C. A, adding indexes only where Task 13's `EXPLAIN` shows a slow combination.
- **Recommendation: B.** Origin + Destination is the typical search (§4.3) and the dashboard offers all three sorts on it; without the prefix, a rare route sorted by departure walks the departure index of ~30 partitions discarding most rows. Each index slows every `COPY`; Task 13 measures the load. Tasks: 3, 13.

**Q7. Where the provider-status screen's data lives (§6.8, screen 2).**
- A. In memory: bootstrap keeps this instance's last `IngestionReport`; empty after a restart or on an instance that did not run.
- B. A table, one row per provider (status, failure, offers used, discarded by reason, last fresh download, run end), written at the end of every run through a port; any instance reads it.
- C. Derived from `provider_offer` (count, newest `quoted_at`) and this instance's discarded counters.
- **Recommendation: B.** §6.2 reasons about several API instances, and an admin must see the same status on any of them and after a restart: one migration, one port, one adapter. The breaker state is per instance anyway and is read live. Tasks: 12.

**Q8. When health is `DEGRADED` (§6.5).**
- A. A provider is `DOWN` when its last run was `KEPT` or its breaker is open, `DEGRADED` when half-open, else `UP`. The service is `DEGRADED` when any provider is not `UP` or Valkey is unavailable; `DOWN` only when PostgreSQL is.
- B. `DEGRADED` only when every provider is failing.
- C. A, but Valkey down stays `UP` (visible in metrics only).
- **Recommendation: A.** Serving kept data is degraded service, and so is a slower search without Valkey (§6.2); `DEGRADED` answers HTTP 200, so the orchestrator does not restart the API. Tasks: 12.

**Q9. Values the spec leaves open.**

| Setting | Recommended | Why |
|---|---|---|
| Rate limit, search | 60 requests per minute per IP, fixed window | A person paging and changing filters stays well below |
| Rate limit, re-price | 10 per minute per IP, its own counter | Each re-price calls the providers (look-to-book cost, §6.2) |
| `Retry-After` | 429: seconds left in the window; re-price 503: 60 s | 60 s is the breaker's open time (plan 4) |
| Page size | default 20, maximum 100 | A page per "Load more" |
| Concurrency limit | 4 calls per provider, `REJECT` (a rejected call counts as that provider unavailable for that call; a run whose download is rejected keeps that provider's offers) | Ingestion holds one; with `BLOCK` a re-price would wait without bound |
| Re-price | at most 3 alternatives; no new quote after 10 s | A quote with its 3 attempts can take about 8 s (plan 4) |
| Refresh claim (`SET NX`) TTL | 2 min | Longer than one refresh |
| A run's switch waiting for a refresh's write (Q2-B) | 60 s, the rest of the load keeping its 5 s | A refresh's delete and `COPY` take seconds; 5 s could fail a 1M-row load at its last statement |
| Valkey client | 250 ms command, 1 s connect, commands rejected while disconnected | A Valkey outage costs a search at most ~250 ms ("only slower", §6.2) |

- A. These values. B. Change any row. **Recommendation: A.** Tasks: 2, 5, 8, 9, 10.

## Decisions this plan takes

| Decision | Why | Source |
|---|---|---|
| **User:** the stale-route refresh is this plan's | Search detects a stale origin | (user, session 09) |
| **User:** a per-provider concurrency limit with Spring 7's `@ConcurrencyLimit`, shared by ingestion, the refresh and re-price | They call one provider at once | (user, session 09; §6.1) |
| `ANALYZE` the loaded slot before the switch | Autovacuum never analyzes the partitioned parent, and a fresh slot has no statistics | (plan 4 Task 4 note) |
| One provider-time encoding (instant + offset seconds) for the offer store, the row writer and the readers | It exists twice today | (plan 4 Task 4 note) |
| The mock's quote is its listing filtered to one flight, in each provider's own format; the listing also takes `origin` (built, Task 1) | The page formats are reused; the schemas are ours | (§6.7; §6.3; §4.1) |
| The rate limit is an inbound port over a Valkey counter | Driving adapters use only inbound ports; counters live in Valkey | (§6 ports; §6.6) |
| The client IP is the address Tomcat's `RemoteIpValve` resolves (`server.forward-headers-strategy: native`): `X-Forwarded-For` counts only from a private-range proxy | Otherwise any client picks its own counter | (§6.6 per client IP; §6.8 nginx) |
| Valkey fails open: an error is a cache miss, an unclaimed refresh and an admitted request, logged on the first failure after a success and on recovery | The system keeps working, only slower, without Valkey | (§6.2) |
| `/actuator/health` public with status only; `metrics` and everything else under `ADMIN` | The orchestrator probes health; actuator is admin | (§6.5, §6.6, §6.10) |
| JWT signatures checked against Keycloak's internal JWKS URL, the issuer against the public one (`KC_HOSTNAME`) | A browser token names `localhost` while the app reaches `keycloak` | (implied by Keycloak in compose, §6.6) |
| OpenAPI code-first with springdoc; the served contract is committed and a test fails on drift | The contract is generated, and CI fails on drift | (§6.8; §6.11) |
| Re-price refuses a departed trip through `Itinerary.isSellable(now)` and matches re-quoted offers by `LegKey` | R5 | (progress 2026-11-14, R5) |
| Provider call outcomes counted by a bootstrap decorator around each `FlightProvider` (outside its concurrency proxy, so a rejection is counted) | The application cannot use Micrometer (ArchUnit rule 2) | (§6.10 minimum) |

## ⚠ Extras — your call

- **E1. Password grant on a demo Keycloak client,** so the admin endpoints can be called with `curl` and tested against a real Keycloak before plan 6's browser login. Value: proves the real token path (issuer, realm roles) now. Cost: one client in the realm, one Testcontainers test, a deprecated grant in a demo realm. Left out unless you approve.
- **E2. Swagger UI** (`springdoc-openapi-starter-webmvc-ui`). Value: browse and try the API in the demo. Cost: one dependency, one more public path. Left out unless you approve.
- **E3. Signed (HMAC) cursors.** Value: a client cannot forge a seek position. Cost: a key in configuration and ~30 lines; low, since a forged cursor only seeks within public data and is validated. Left out unless you approve.
- **E4. Rate-limit headers on every response** (remaining quota). Value: clients can pace themselves. Cost: ~20 lines and a test. Left out unless you approve.

## Out of scope

- The dashboard, its Keycloak client and login, and the TypeScript types (plan 6). Prometheus, Grafana, alerts and OTel (plan 7). Plan 4's extras are decided (E4–E8, E11, E13 and E14 built in plan 4; the rest rejected).

## Review Focus

1. **Many itineraries at the same price across a page boundary** must neither repeat nor skip a row. Pinned by Task 4 `pagesThroughTiesWithoutRepeatsOrGaps`.
2. **A cursor from a replaced generation, or a forged or garbage one,** gives a flagged page or a 400, never a 500 or a silently wrong page. Pinned by Task 6 `flagsACursorOfAnotherGeneration` and Task 9 `rejectsGarbageTamperedAndOverlongCursors`.
3. **Valkey down** must leave search answering at its normal speed plus at most the command timeout, not Lettuce's 60 s default. Pinned by Task 5 `failsOpenWhenValkeyIsDown` and Task 13's run.
4. **Re-price with all three providers down** answers 503 within its budget and changes nothing stored. Pinned by Task 8 `answersUnconfirmableWithAllProvidersDown` and Task 10 `answers503WithRetryAfterAndTheMessage`.
5. **A client forging `X-Forwarded-For`** is still counted under its own address. Pinned by Task 10 `countsTheSocketAddressOfANonProxyClient`.

---

### Task 1: The mock providers: an origin filter and a one-flight quote — DONE

**Done:** `eb2c1eb` (*Let the mock providers filter by origin and quote one flight*) and `e8b6b87` (*Validate the mock providers' origin and quote parameters, bound by name*). Built: `serving/ListingParams.java` (the parameters, validated), `RecordFilter.java`, `ProviderInventory.page(Provider, RecordFilter, offset, limit)`; the three controllers; tests in `ProviderInventoryTest`, the three controller tests and `EndpointAccessTest`. **Depends on:** nothing. **Parallel:** n/a.

**Decided details that later tasks rely on (Task 2's formats and WireMock stubs follow them):**
- Every listing takes `origin=<IATA>`, filtered before paging.
- A quote is the listing with five parameters, all present: `carrier`, `flightNumber`, `origin`, `destination` and `departure`, a UTC instant ending in `Z` (`2026-12-01T08:00:00Z`). It returns at most one record, in the listing's own format, ending its pagination (Alpha `"nextCursor":null`, Beta `totalPages` 1, Gamma a short page). Prices drift as on the listing (§6.3).
- An unknown flight answers the listing's own empty page with `200`, never `404`.
- A partial flight key or a `departure` that is not a UTC instant → `400`. The quote needs the API key like the listing (no key → `401`).

### Task 2: The provider adapter: download by origin, quote, concurrency limit, call counter

**Depends on:** Task 1 (done). **Parallel:** yes, with 3 and 11 (its own worktree). It changes `FlightProvider` and `PagedProvider`, which 7, 8 and 12 build on, so they wait for it.

**Files:**
- Modify: `application/.../port/out/FlightProvider.java`, `application/src/test/.../fakes/FakeFlightProvider.java`; in `adapters/providers/.../adapter/provider/` (one package): `PageFormat.java`, `PageRequest.java`, `AlphaPage.java`, `BetaPage.java`, `GammaPage.java`, `PagedProvider.java`; `bootstrap/.../config/ProvidersConfig.java`, `ProvidersProperties.java`, `application.yaml`
- Create: `bootstrap/.../observability/CountedFlightProvider.java`, and the post-processor that wraps each `FlightProvider` bean with it (`CountedProviders.java`, same package)
- Test: `AlphaPageTest.java`, `BetaPageTest.java`, `GammaPageTest.java`, `AlphaProviderTest.java`, `BetaProviderTest.java`, `GammaProviderTest.java` (each drives `PagedProvider` with that provider's endpoint), `ProvidersConfigTest.java`, `ProvidersPropertiesTest.java`, `CountedFlightProviderTest.java`

**Interfaces:**
- `FlightProvider` gains `ProviderDownload downloadFrom(Airport origin)` (whole or `ProviderUnavailableException`, as `download()`) and `Optional<Leg> quote(LegKey flight)`: empty when the flight is no longer sold; a record of another flight, more than one record, or a discarded (malformed) one throws `ProviderUnavailableException(provider, MALFORMED_PAGE, …)`.
- `PageFormat` stops being a lambda: `PageRequest at(Optional<Airport> origin, Optional<String> position, int pageSize)` (the origin joins the listing's query; `Optional.empty()` is today's whole download) and `PageRequest quoting(LegKey flight)` (the five-parameter listing of Task 1, `departure` as a UTC instant). `AlphaPage`, `BetaPage` and `GammaPage` implement both; `PagedProvider.formatOf` returns the provider's format as today. Escaping of anything provider-controlled in a message is `ProviderText` (`application.port.out`).
- `PagedProvider.download()` and `downloadFrom(origin)` both go through the same `PagedDownload.download(PageSource)` (its own `TimeLimiter`, page bounds, `PageMalformed` → `MALFORMED_PAGE`), so the origin download has the same bounds as the whole one. `quote(flight)` is one request through `ProviderHttp.get` (its breaker, retries and request timeout, so about 8 s at worst, Q9's budget) and `PageRequest.parse`; a `PageMalformed` becomes `MALFORMED_PAGE` as in `PagedDownload`; `quotedAt` comes from the response's arrival (`FetchedPage.receivedAt`).
- The concurrency limit sits on `PagedProvider`, one class and one limit per provider: `@ConcurrencyLimit(limitString = "${providers.concurrency-limit}", policy = <Q9>)` on its three operations (not on `provider()`, which a rejection must never fail), shared by them. `ProvidersConfig` is `@EnableResilientMethods`; each `PagedProvider` bean built there is proxied on its own, so each provider has its own limit; the breakers stay the registry's, one per provider. `ProvidersProperties` gains `@DefaultValue("4") int concurrencyLimit` (it has `ignoreUnknownFields = false`, so the property must be declared there). A rejected call surfaces as `InvocationRejectedException`, a `RejectedExecutionException`. If Spring 7.0.10 counts the limit per method rather than per instance, the test of Step 2 shows it and the task stops and asks before choosing another seam.
- `CountedFlightProvider implements FlightProvider` wraps each limited provider (applied after the proxy, so a rejected call is counted; `ProvidersConfigTest` pins the order) and counts `provider.calls{provider, operation=download|origin-download|quote, outcome=OK|NOT_SOLD|<FailureReason>|REJECTED}`.

- [ ] **Step 1: page formats, one test at a time, per provider:** `AlphaPageTest.requestsOnlyAnOriginsLegs` (the path carries `origin=MAD`, the cursor still encoded), `buildsTheQuotePath` (the five parameters, `departure` ending in `Z`); the same two for Beta and Gamma.
- [ ] **Step 2: `PagedProvider`, against WireMock, one test at a time, per provider:** `downloadsOnlyTheOriginsLegs` (every page carries `origin=MAD`), `quotesOneFlight` (`quotedAt` from the response's arrival), `quotesNothingForAFlightNoLongerSold` (the empty `200` page of Task 1), `failsAQuoteThatAnswersAnotherFlight`, `failsAQuoteWithAMalformedRecord`. Then `ProvidersConfigTest.limitsConcurrentCallsPerProvider`: limit 2, Alpha delayed 500 ms, a download, an origin download and a quote at once → at most 2 requests in flight in WireMock's journal and the third handled per Q9; a Beta quote meanwhile is not held back; `provider()` answers meanwhile. `ProvidersPropertiesTest.readsTheConcurrencyLimit`.
- [ ] **Step 3: `CountedFlightProviderTest.countsEachOutcome`** (OK, not sold, `TIMEOUT`, rejected); `ProvidersConfigTest.countsARejectedCall`.
- [ ] **Step 4: `./gradlew build` green, review, commit:** `git commit -m "Download one origin and quote one flight from each provider, under a per-provider limit"`.

### Task 3: Persistence groundwork: one time encoding, legs read back, ANALYZE, sort indexes

**Depends on:** nothing (plan 4's repository). **Parallel:** yes, with 2 and 11. It edits `JdbcOfferStore`, `ItineraryRow` and `JdbcItineraryRepository`, which 7 and 8 extend, so they wait for it.

**Files:**
- Create: `adapters/persistence/.../ProviderTime.java`, `LegsJson.java`; `db/migration/V5__itinerary_sort_indexes.sql`
- Modify: `JdbcOfferStore.java`, `ItineraryRow.java` (its leg JSON moves to `LegsJson`), `JdbcItineraryRepository.java`
- Test: `ProviderTimeTest.java`, `LegsJsonTest.java`, `JdbcItineraryRepositoryTest.java`

**Interfaces:**
- `final class ProviderTime`: `static String instantCell(OffsetDateTime)`, `static String offsetCell(OffsetDateTime)`, `static OffsetDateTime read(ResultSet, String instantColumn, String offsetColumn)`.
- `final class LegsJson`: `static String write(List<Leg>, JsonFactory)` (the format `ItineraryRowTest` pins, unchanged) and `static List<Leg> read(String, JsonFactory)`; a document of another shape throws `IllegalStateException`.
- V5 creates Q6's indexes on `itinerary_a` and `itinerary_b` (the parents, so present and future partitions inherit them). `JdbcItineraryRepository.load` runs `ANALYZE <slot>` after the `COPY` and the default-partition count, before `switchTo`; `Publication` (with its `partitionedUntil`), the publication-row lock and the constructor taking Boot's one transaction manager stay as they are.

- [ ] **Step 1: `LegsJsonTest`, one test at a time:** `roundTripsLegsExactly` (a `-05:00` offset, a microsecond quote, `0.01` → equal legs and an equal `Itinerary.id()`); `failsOnADocumentOfAnotherShape`.
- [ ] **Step 2: `ProviderTimeTest.roundTripsAnInstantAndItsOffset`** through a temporary table; then move `JdbcOfferStore` and `ItineraryRow` onto both helpers with their tests unchanged and green.
- [ ] **Step 3: `JdbcItineraryRepositoryTest`, one test at a time:** `analyzesTheLoadedSlotBeforeTheSwitch` (a loaded day partition's `pg_class.reltuples` equals its rows, not -1); `indexesEverySortOrderOnBothSlots` (`pg_indexes` of both parents and of a day partition created by a run).
- [ ] **Step 4: `./gradlew build` green, review, commit:** `git commit -m "Share the provider-time encoding, read legs back, analyze each slot and index its sort orders"`.

### Task 4: The itinerary reader: keyset pages over the active slot

**Extras approved by the user on 2026-11-15, after the task reached `main`:** a statement timeout of **2 s** on every search read (`SET LOCAL`, so a stuck read fails instead of holding a connection and its lock on the slot), and defensive copies of the lists in `StoredPage` and `SearchPage`. The guard on `limit` in the port stays out (the endpoint validates it).

**Depends on:** 3 (`LegsJson`, V5's indexes). **Parallel:** not with 3; after it, 2 and 11 may still be running. Its `port/in` types are what 5, 6, 8 and 9 compile against, so they start from its commit.

**Files:**
- Create in `application/.../port/in/`: `SortOrder.java`, `SearchCriteria.java`, `SeekPosition.java`, `PageCursor.java`, `SearchPage.java`; in `port/out/`: `ItineraryReader.java`, `InventoryState.java`, `StoredPage.java`; `adapters/persistence/.../JdbcItineraryReader.java`, `KeysetQuery.java`
- Modify: `bootstrap/.../config/PersistenceConfig.java` (declares the reader)
- Test: `KeysetQueryTest.java`, `JdbcItineraryReaderTest.java`

**Interfaces:**
- `enum SortOrder { PRICE, DEPARTURE, DURATION }`; `record SearchCriteria(Optional<Airport> origin, Optional<Airport> destination, Optional<Money> maxPrice, Optional<Carrier> carrier, SortOrder sort)`.
- `record SeekPosition(Money price, Instant departure, Duration duration, ItineraryId id)` with `static SeekPosition of(Itinerary)`; `record PageCursor(long generation, SortOrder sort, SeekPosition position)`; `record SearchPage(List<Itinerary> itineraries, Optional<PageCursor> next, boolean inventoryChanged)`.
- `interface ItineraryReader { InventoryState state(); StoredPage page(SearchCriteria criteria, Optional<SeekPosition> after, int limit, Instant now); Optional<Itinerary> byId(ItineraryId id); }`; `byId` reads the active slot whatever the departure, so re-price can still find a departed choice and offer its alternative (Q4); `record InventoryState(long generation, Optional<Instant> publishedAt)`; `record StoredPage(long generation, List<Itinerary> itineraries)`, generation and rows read in one read-only transaction.
- `JdbcItineraryReader(DataSourceTransactionManager transactions, JsonFactory json)`, built in `PersistenceConfig` like the other stores (E6): its reads use a read-only `TransactionTemplate` of that manager, so the generation and the rows of a page are one snapshot.
- SQL from fixed fragments: `… FROM <active slot> WHERE departure > ? [AND origin = ?] [AND destination = ?] [AND price <= ?] [AND single_carrier = ?] [AND (<key>, id) > (?, ?)] ORDER BY <key>, id LIMIT ?`, `<key>` one of `price`, `departure`, `duration_seconds`; the table from `Slot`, every value a parameter. Max Price is inclusive; Carrier matches `single_carrier` (§4). A row becomes `new Itinerary(LegsJson.read(legs))`.

- [ ] **Step 1: `KeysetQueryTest.buildsTheQueryOfEachSortWithEveryFilter`** (text and parameters).
- [ ] **Step 2: `JdbcItineraryReaderTest`, one test at a time:** `returnsNothingBeforeTheFirstPublication`; `readsAPageInPriceOrder`; `pagesThroughTiesWithoutRepeatsOrGaps` (25 at one price, pages of 10 → 25 distinct ids in SQL's `ORDER BY price, id`); `ordersByDepartureAndByDuration`; `appliesEachFilterAndTheirCombination` (a price equal to Max Price is in; an interline trip with an IB leg is out for Carrier IB); `hidesDepartedItineraries`; `readsTheActiveSlotWithItsGeneration`; `findsAnItineraryByIdEvenOnceDeparted`; `findsNothingForAnUnknownId`.
- [ ] **Step 3: `./gradlew build` green, review, commit:** `git commit -m "Read keyset pages of the active slot with every filter and sort order"`.

### Task 5: Valkey: result cache, refresh coordination, request counters

**Extras approved by the user on 2026-11-15, after fix round 1:** the state of Valkey's circuit breaker as a meter, and fewer WARN lines when many cached values fail to parse at once (as after a format change). A meter for unreadable refresh marks stays out. All seven of Resilience4j's breaker meters are kept, under `valkey.circuitbreaker.*` (the user, after round 2). **Decided by the user on 2026-11-15, after the re-review:** a probe that throws anything (`Throwable`, a JVM `Error` included) releases the breaker's permission and rethrows, so a bug neither closes the breaker with a false recovery nor leaves it half-open for good; this replaces the earlier "recovery after a JVM `Error` stays out".

**Depends on:** 4 (`SearchPage`, `SearchCriteria`, `LegsJson` from 3). **Parallel:** with 8 (after 4) and 11; it edits `libs.versions.toml`, `bootstrap/build.gradle.kts` and `application.yaml`, shared with 11 and 13, so those merges are by hand.

**Files:**
- Modify: `gradle/libs.versions.toml`, `adapters/persistence/build.gradle.kts` (`spring-data-redis`, `micrometer-core`; tests `com.redis:testcontainers-redis`), `bootstrap/build.gradle.kts` (`spring-boot-starter-data-redis`), `application.yaml`
- Create: `port/out/SearchCache.java`, `SearchKey.java`, `RefreshCoordination.java`, `RequestCounter.java`, `WindowCount.java`; in `adapters/persistence`: `ValkeySearchCache.java`, `ValkeyRefreshCoordination.java`, `ValkeyRequestCounter.java`, `ValkeyFailures.java`, `testFixtures/.../support/ValkeyImage.java`; `bootstrap/.../config/ValkeyConfig.java`
- Test: `ValkeySearchCacheTest.java`, `ValkeyRefreshCoordinationTest.java`, `ValkeyRequestCounterTest.java`, `support/ValkeyTestServer.java`

**Interfaces:**
- **Changed by the user on 2026-11-15, after the task's review:** a failed Valkey command opens a Resilience4j circuit breaker (the project's own mechanism, as for the providers), so callers skip Valkey for **10 seconds** and fail open at once, then one call probes it; this bounds a hung Valkey to one command timeout per 10 s instead of up to five per search, including before the first connection. A cached value that does not parse is a counted miss, not an error.
- `interface SearchCache { Optional<SearchPage> get(SearchKey key); void put(SearchKey key, SearchPage page); }`, TTL `search.cache-ttl`. `record SearchKey(long generation, SearchCriteria criteria, int limit)` with `String value()`: `search:<generation>:<sort>:<origin|->:<destination|->:<carrier|->:<maxPrice|->:<limit>`. Values are JSON: itineraries through `LegsJson`, the cursor's fields.
- `interface RefreshCoordination { Optional<Instant> refreshedAt(Airport origin); boolean claim(Airport origin); void refreshed(Airport origin, Instant at); }`: `claim` is `SET refresh:<origin> NX EX <claim TTL>` (§6.2); `refreshed` writes `fresh:<origin>` with the threshold as TTL.
- `interface RequestCounter { WindowCount increment(String bucket, Duration window); }`, `record WindowCount(long count, Duration resetsIn)` with `static WindowCount unavailable()`: a fixed window, `INCR` then `EXPIRE … NX`.
- Fail open through `ValkeyFailures`: `get` empty, `put` dropped, `claim` false, `increment` unavailable; one WARN on the first failure after a success, one INFO on recovery. `ValkeyConfig` sets Q9's Lettuce timeouts and `REJECT_COMMANDS`, and `management.health.redis.enabled: false` (Task 12 reports Valkey as `DEGRADED`, never `DOWN`).

- [ ] **Step 1:** pick the `valkey/valkey:9` tag by §6.1, pin tag and digest in `ValkeyImage` (shared with bootstrap tests like `PostgresImage`), run it as a `RedisContainer`; record it.
- [ ] **Step 2: one test at a time:** `ValkeySearchCacheTest.roundTripsAPage`, `expiresAfterItsTtl` (`TTL` ≤ 300 s), `keysDifferByGeneration`; `ValkeyRefreshCoordinationTest.claimsOncePerOriginUntilTheClaimExpires`, `remembersWhenAnOriginWasRefreshed`; `ValkeyRequestCounterTest.countsWithinAWindowAndResets`.
- [ ] **Step 3: `failsOpenWhenValkeyIsDown`** (Review Focus 3): with the container paused, each operation returns its fail-open value within 500 ms, `search.cache{result=error}` counts the cache's, and one WARN line is written, not one per call.
- [ ] **Step 4: `./gradlew build` green, review, commit:** `git commit -m "Cache search pages, coordinate refreshes and count requests in Valkey, failing open"`.

### Task 6: The search use case

**Decided by the user on 2026-11-15, after the implementation:** `SearchConfig` is wired in this task with a provisional `RefreshScheduler` (one WARN per request, nothing else), so the application starts and Tasks 7 and 9 stay parallel; Task 7 replaces it, and both beans together stop startup, so it cannot linger. No refresh is requested before the first publication (no `publishedAt`; the first run loads every origin).

**Depends on:** 4, 5. **Parallel:** with 8; 7 and 9 start from its commit.

**Files:**
- Create: `port/in/SearchItineraries.java`, `SearchQuery.java`, `InvalidCursorException.java`; `port/out/RefreshScheduler.java`; `service/SearchItinerariesService.java`; `bootstrap/.../config/SearchConfig.java`
- Test: `SearchItinerariesServiceTest.java` with fakes

**Interfaces:**
- `interface SearchItineraries { SearchPage search(SearchQuery query); }`; `record SearchQuery(SearchCriteria criteria, int limit, Optional<PageCursor> cursor)`; `interface RefreshScheduler { void request(Airport origin); }` (Task 7 implements it).
- `SearchItinerariesService(ItineraryReader, SearchCache, RefreshCoordination, RefreshScheduler, Clock, Duration freshnessThreshold)`: reads `limit + 1` rows and returns `next` only when the extra row exists, at the last returned row, with the page's generation. A cursor of another sort order throws `InvalidCursorException`. Q1: a cursor of another generation sets `inventoryChanged`. Q3: only the cached pages are read from and written to the cache, under `state().generation`; a page whose `StoredPage.generation` differs is served but not cached. With an origin in the query, if `now − max(publishedAt, refreshedAt(origin)) > threshold` and `claim(origin)` succeeds, it calls `request(origin)` (§6.4), never delaying or failing the search.

- [ ] **Step 1: one test at a time:** `returnsAPageWithACursorWhenMoreRowsExist`; `returnsNoCursorOnTheLastPage`; `flagsACursorOfAnotherGeneration`; `rejectsACursorOfAnotherSortOrder`; `servesACachedFirstPageWithoutReading`; `cachesOnlyFirstPagesWithoutMaxPrice`; `doesNotCacheAPageOfAnotherGeneration`; `requestsOneRefreshForAStaleOrigin` (11 min old: requested once; a second search finds the claim taken); `doesNotRefreshAFreshOriginOrASearchWithoutOrigin`.
- [ ] **Step 2: `./gradlew build` green, review, commit:** `git commit -m "Search the inventory page by page, cached by generation, asking to refresh stale origins"`.

### Task 7: The stale-route refresh (as Q2-B; with Q2-A, the service publishes through `replaceInventory` and the repository is unchanged)

**Depends on:** 2 (`downloadFrom`), 3, 5 (`RefreshCoordination`), 6 (`RefreshScheduler`). **Parallel:** with 9. With 8 only in a second worktree whose merge is by hand: both add a method to `ItineraryRepository`, `JdbcItineraryRepository` and its test.

**Files:**
- Create: `port/in/RefreshStaleRoute.java`, `RefreshReport.java`; `service/RefreshStaleRouteService.java`; `bootstrap/.../refresh/BackgroundRefresh.java`
- Modify: `port/out/ItineraryRepository.java`, `JdbcItineraryRepository.java` (the rewrite is a method of the repository, so it is built from the same one transaction manager and reuses its `Slot`, `CopyRows` and `ItineraryRow`); `bootstrap/.../config/IngestionConfig.java` or a new `RefreshConfig.java` for the wiring
- Test: `RefreshStaleRouteServiceTest.java`, `JdbcItineraryRepositoryTest.java`, `BackgroundRefreshTest.java`

**Interfaces:**
- `interface RefreshStaleRoute { RefreshReport refresh(Airport origin); }`; `record RefreshReport(Airport origin, Outcome outcome, long generation, long itineraries, Map<Provider, ProviderReport.Status> providers)`, `enum Outcome { REFRESHED, NOTHING_FRESH }`.
- `ItineraryRepository.replaceThrough(Airport origin, Instant now, ItineraryWriter writer) → Publication`, one transaction: `SELECT … FROM inventory_publication FOR UPDATE`; delete from the active slot the rows with `legs @> jsonb_build_array(jsonb_build_object('origin', ?))`; `COPY` the writer's itineraries in; `generation + 1`, `published_at` unchanged (it stays the last full run's). `Publication` returned carries the active slot's `partitionedUntil`, unchanged. `replaceInventory`'s `switchTo` (its `activeSlotLocked` already takes the row with `FOR UPDATE`) waits for that row as long as Q9 says, instead of the 5 s `LOCK_TIMEOUT` of the rest of the load.
- `RefreshStaleRouteService(List<FlightProvider>, OfferStore, AirportCatalogue, DiscardedRecords, ItineraryRepository, RefreshCoordination, Clock)`: `downloadFrom(origin)` from every provider in parallel (a rejected call, `InvocationRejectedException`, is that provider failing, so its stored offers stay); each fresh provider's stored offers departing from the origin are replaced in memory (catalogue and discarded records as in ingestion; a download with no usable leg is `EMPTY_DOWNLOAD`, the provider kept as in `IngestInventoryService`, which owns that rule: the build and the checks it shares with the run are lifted out of it, not copied); deduplicate, build with `now`, push only the itineraries with a leg departing from the origin; then `refreshed(origin, now)`. `provider_offer` is not written. Every provider failing → `NOTHING_FRESH`, nothing written, origin not marked.
- `BackgroundRefresh implements RefreshScheduler`: runs `refresh` on a virtual thread with its own correlation id in the MDC (`correlation.id`, as `IngestionJob` does, removed in the `finally`), logs the report (INFO) or the exception (ERROR), never throws to the caller.

- [ ] **Step 1: service, one test at a time:** `replacesTheItinerariesThroughTheOrigin` (fresh Alpha MAD→LHR → the writer pushes MAD→LHR, MAD→LHR→JFK and BCN→MAD→LHR, not BCN→MAD); `keepsAFailedProvidersStoredOffersOfTheOrigin`; `writesNothingWhenEveryProviderFails`; `marksTheOriginRefreshedOnlyAfterTheWrite`.
- [ ] **Step 2: repository, one test at a time:** `replacesOnlyTheItinerariesThroughTheOrigin` (other rows untouched, generation + 1, `published_at` unchanged); `aFailedRefreshLeavesTheActiveSlotWhole`; `aRunSwitchesAfterARefreshReleasesThePublicationRow` (two threads).
- [ ] **Step 3: `BackgroundRefreshTest.logsAFailureWithoutThrowing`.**
- [ ] **Step 4: `./gradlew build` green, review, commit:** `git commit -m "Refresh a stale origin in the background and rewrite the itineraries through it"`.

### Task 8: Re-price on selection, with the alternative flow

**Extra approved by the user on 2026-11-15, after fix round 1:** a test that pins two re-prices of the same itinerary at once (the second answers `GONE`, the store keeps the first's quote).

**Depends on:** 2 (`quote`), 3, 4 (`ItineraryReader`). **Parallel:** with 5 and 6 (it needs neither), and with 7 only by hand-merging the repository files (see Task 7). `V6` is reserved for it (3 takes V5, 12 takes V7), so the numbers never collide.

**Files:**
- Modify: `domain/.../Itinerary.java`; `port/out/OfferStore.java`, `ItineraryRepository.java`; `JdbcOfferStore.java`, `JdbcItineraryRepository.java`
- Create: `port/in/RepriceItinerary.java`, `RepriceResult.java`, `RepriceSettings.java`; `service/RepriceItineraryService.java`; `db/migration/V6__provider_offer_flight_index.sql`; `bootstrap/.../config/RepriceConfig.java`
- Test: `ItineraryTest.java`, `RepriceItineraryServiceTest.java`, `JdbcOfferStoreTest.java`, `JdbcItineraryRepositoryTest.java`

**Interfaces:**
- **Changed by the user on 2026-11-15, after the implementation:** when `updateInPlace` cannot store a live-confirmed price because a run holds the publication row (the shared lock is refused at once, `55P03`), the re-price still answers with the live result; the store is skipped, logged at WARN and counted. The next run rebuilds every itinerary from the providers.
- Domain (R5): `public boolean isSellable(Instant now)` on `Itinerary`, delegating to `ConnectionRules.isSellable` for the first leg.
- `interface RepriceItinerary { RepriceResult reprice(ItineraryId id); }`; `sealed interface RepriceResult` of `Confirmed(Itinerary itinerary)`, `PriceChanged(Itinerary itinerary, Money previousPrice)`, `Alternative(Itinerary itinerary)`, `Unconfirmable()`, `NotFound()`.
- `OfferStore.offersOf(LegKey flight) → List<Leg>`: every provider's stored offer of the flight, cheapest first; V6 indexes `provider_offer (carrier, flight_number, origin, departure)`.
- `ItineraryRepository.updateInPlace(Itinerary repriced) → StoreOutcome` (`STORED`, `GONE`, `BUSY`; Q5, as built in fix round 1): a method of `JdbcItineraryRepository`, so in its `TransactionTemplate` of the one transaction manager (E6), its statements bounded by the stores' `StatementTimeout`. It takes the publication row `FOR SHARE NOWAIT`: shared among re-prices, refused at once while a run holds it `FOR UPDATE`, which answers `BUSY` and writes nothing; any other failure to read the row throws. It then deletes the row of that id in the active slot by `(id, departure)` (the id fixes the departure) and copies it back through the load's own `copy()` and `ItineraryRow`, so every column derived from the legs follows the re-quote (N1), generation unchanged; `GONE` when the row no longer exists. A run that starts during an update waits for it, so the write lands in the slot that stays active until the update commits. The WARN for `BUSY` and the counter `reprice.store{outcome}` live in the bootstrap decorator `CountedItineraryRepository`, as `CountedFlightProvider` counts provider calls; the service answers the live result for every outcome.
- `RepriceItineraryService(ItineraryReader, OfferStore, List<FlightProvider>, ItineraryRepository, Clock, RepriceSettings)`, `record RepriceSettings(int maxAlternatives, Duration budget)` (Q9). To confirm an itinerary: it must be `isSellable(now)`; every leg is quoted from its provider (`FlightProvider.quote`, found by `provider()`) in parallel; a leg not sold, or whose provider fails or rejects the call, is quoted from the other providers' stored offers of the flight, cheapest first; offers match by `LegKey`; `new Itinerary(quoted)` throwing means not confirmable (R5). Same price → `Confirmed`, else `PriceChanged`; both `updateInPlace`. If the choice cannot be confirmed, up to `maxAlternatives` itineraries of the same origin and destination by price (`ItineraryReader.page`, the choice excluded) are tried in turn: the first confirmed → `Alternative`, also updated; none → `Unconfirmable`. No quote starts after `budget`; one in flight is bounded by its own timeouts.

- [ ] **Step 1: `ItineraryTest.isSellableUntilItsFirstLegDeparts`** (at the departure instant it is not).
- [ ] **Step 2: service, one test at a time:** `answersNotFoundForAnUnknownId`; `confirmsAnUnchangedPrice`; `reportsAChangedPriceAndStoresIt`; `confirmsALegWithAnotherProvidersOfferOfTheSameFlight` (Beta down, Gamma sells it); `treatsRequotedLegsThatNoLongerConnectAsUnconfirmed` (a re-quoted arrival leaves 30 min to connect); `offersTheNextCheapestConfirmableItinerary`; `aDepartedChoiceGoesToTheAlternative` (Q4); `answersUnconfirmableWithAllProvidersDown` (Review Focus 4: nothing updated, at most `1 + maxAlternatives` itineraries tried); `startsNoQuoteAfterItsBudget` (quotes of 300 ms, budget 500 ms).
- [ ] **Step 3: persistence, one test at a time:** `JdbcOfferStoreTest.findsEveryProvidersOfferOfAFlight`; `JdbcItineraryRepositoryTest.updatesAnItineraryInPlaceWithoutChangingTheGeneration`.
- [ ] **Step 4: `./gradlew build` green, review, commit:** `git commit -m "Re-price a chosen itinerary live, falling back to another offer or the next confirmable itinerary"`.

### Task 9: The search endpoint

**Depends on:** 6 (`SearchItineraries`, `InvalidCursorException`, with 4's types). **Parallel:** yes, with 7, 8, 11; 10 and 12 build on its `ProblemResponses` and response records.

**Files:**
- Modify: `adapters/web/build.gradle.kts` (`spring-webmvc`, `jackson-databind`, `springdoc-openapi-starter-webmvc-api` by §6.1; tests `spring-boot-starter-webmvc-test`), `gradle/libs.versions.toml`, `application.yaml` (`springdoc.writer-with-order-by-keys: true`)
- Create in `adapters/web/.../adapter/web/`: `SearchController.java`, `SearchParameters.java`, `CursorCodec.java`, `ItineraryResponse.java`, `SearchResponse.java`, `ProblemResponses.java`; test `WebTestApplication.java`; `docs/architecture/openapi.json`, `bootstrap/src/test/.../OpenApiContractTest.java`
- Test: `CursorCodecTest.java`, `SearchParametersTest.java`, `SearchControllerTest.java`, `OpenApiContractTest.java`
- **Moved here from Task 13 (the user's decision of 2026-11-15, to start plan 6 about two hours earlier):** the OpenAPI contract and its drift test. Every later task that adds or changes an endpoint (10, 12) regenerates the committed file with `-Dopenapi.write=true` in its own commit, and the drift test fails CI otherwise.

**Interfaces:**
- `GET /api/itineraries` with `origin`, `destination`, `maxPrice`, `carrier`, `sort` (`price` default, `departure`, `duration`), `limit` (Q9) and `cursor`, all optional. Codes are upper-cased; `maxPrice` matches `^[0-9]{1,9}(\.[0-9]{1,2})?$` and is above zero; each bad value → 400 naming it.
- 200: `{"itineraries":[{"id","origin","destination","departure","arrival","durationMinutes","stops","price":{"amount":"129.90","currency":"EUR"},"quotedAt","legs":[{"carrier","flightNumber","origin","destination","departure","arrival"}]}],"nextCursor":"…"|null,"inventoryChanged":false}`.
- `CursorCodec`: `String encode(PageCursor)`, `PageCursor decode(String)`, base64url without padding of `1|<generation>|<sort>|<price>|<departure instant>|<duration seconds>|<id>`; anything else, or over 200 characters → `InvalidCursorException` → 400.

- [ ] **Step 1: `CursorCodecTest`, one test at a time:** `roundTrips`; `rejectsGarbageTamperedAndOverlongCursors` (Review Focus 2).
- [ ] **Step 2: `SearchParametersTest`, one test at a time:** `normalisesCodesAndAmounts` (`mad` → `MAD`; `100` and `100.00` equal); `rejectsEachInvalidParameter` (parameterised: `MADR`, `1e3`, `-1`, `0`, `IBX`, `best`, `limit` 0 and above the maximum).
- [ ] **Step 3: `SearchControllerTest` (`@WebMvcTest`, a fake `SearchItineraries`), one test at a time:** `searchesWithNoFilters` (price, 20); `writesLocalTimesAndThePriceButNeverTheProvider` (`+01:00` offsets; no `provider` and no leg price anywhere in the body); `flagsAChangedInventory`; `answersAProblemForABadCursor`.
- [ ] **Step 4: `OpenApiContractTest.servedContractMatchesTheCommittedOne`** (red: no file yet); `-Dopenapi.write=true` writes it. CI's `./gradlew build` then fails on drift (§6.11 step 3, the backend half; plan 6 adds the types).
- [ ] **Step 5: `./gradlew build` green, review, commit:** `git commit -m "Serve the search over HTTP with an opaque cursor and strict parameters"`.

### Task 10: The re-price endpoint and the rate limit

**Depends on:** 5 (`RequestCounter`), 8 (`RepriceItinerary`), 9 (`ItineraryResponse`, `ProblemResponses`, the web module). **Parallel:** with 12 and 13's compose step (not the same files: 10 and 12 each add a controller and a bean config; `application.yaml` is shared, merged by hand), not with 9.

**Files:**
- Modify: `docs/architecture/openapi.json`, regenerated with `-Dopenapi.write=true` so that Task 9's `OpenApiContractTest` passes (the user's decision of 2026-11-15)
- Create: `port/in/AdmitRequest.java`, `Admission.java`, `RateLimitedAction.java`, `RateLimit.java`; `service/RateLimitService.java`; in `adapters/web`: `RepriceController.java`, `RepriceResponse.java`, `RateLimitInterceptor.java`; `bootstrap/.../config/WebConfig.java`
- Modify: `application.yaml` (`server.forward-headers-strategy: native`)
- Test: `RateLimitServiceTest.java`, `RepriceControllerTest.java`, `RateLimitInterceptorTest.java`, `bootstrap/.../ClientAddressTest.java`

**Interfaces:**
- `POST /api/itineraries/{id}/reprice`, per Q4: `Confirmed`, `PriceChanged` (with `previousPrice`) and `Alternative` (with `notice`, the §6.5 text) → 200 with `outcome` and Task 9's itinerary; `Unconfirmable` → 503 problem, `Retry-After` (Q9), `detail` the §6.5 text; `NotFound` → 404; a malformed id → 400.
- `interface AdmitRequest { Admission admit(String clientAddress, RateLimitedAction action); }`; `enum RateLimitedAction { SEARCH, REPRICE }`; `sealed interface Admission` of `Admitted()` and `Rejected(Duration retryAfter)`; `record RateLimit(int requests, Duration window)`; `RateLimitService(RequestCounter, Map<RateLimitedAction, RateLimit>)`; an unavailable counter admits (§6.2).
- `RateLimitInterceptor` on `/api/itineraries/**` picks the action by method and path, uses `request.getRemoteAddr()`, and answers 429 problem with `Retry-After` in whole seconds.

- [ ] **Step 1: `RateLimitServiceTest`, one test at a time:** `admitsUpToTheLimitInAWindow`; `rejectsPastItWithTheTimeLeft`; `countsSearchAndRepriceApart`; `admitsWhenTheCounterIsUnavailable`.
- [ ] **Step 2: one test at a time:** `RepriceControllerTest.answersEachOutcome` (parameterised over the three 200s, notice text exact), `answers503WithRetryAfterAndTheMessage`, `answers404ForAnUnknownId`, `answers400ForAMalformedId`; `RateLimitInterceptorTest.answers429WithRetryAfter`.
- [ ] **Step 3: `ClientAddressTest` (real port), one test at a time:** `countsTheSocketAddressOfANonProxyClient` (Review Focus 5: with `server.tomcat.remoteip.internal-proxies` excluding loopback, `X-Forwarded-For: 203.0.113.9` is ignored); `honoursForwardedForFromAnInternalProxy`.
- [ ] **Step 4: `./gradlew build` green, review, commit:** `git commit -m "Serve re-price over HTTP and limit search and re-price per client address"`.

### Task 11: OAuth2 for the admin side, and Keycloak in compose

**Depends on:** nothing (it secures paths by name, and its `SecurityTest` uses Actuator's `metrics`). **Parallel:** yes, from the start, with 2, 3 and everything else; it shares `libs.versions.toml`, `bootstrap/build.gradle.kts`, `application.yaml` and `compose.yaml` with 5, 10, 12 and 13, merged by hand. 12 needs it merged first for its admin tests.

**Files:**
- Modify: `gradle/libs.versions.toml`, `bootstrap/build.gradle.kts` (`spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`; tests `spring-security-test`), `application.yaml`, `compose.yaml` (service `keycloak`), `README.md` (the demo admin user)
- Create: `bootstrap/.../security/SecurityConfig.java`; `deploy/keycloak/flight-aggregator-realm.json`
- Test: `security/SecurityTest.java`, `security/PublicAccessOverHttpTest.java`, `security/RealmClientCredentialsTest.java`

**Interfaces:**
- Public: `GET /api/itineraries`, `POST /api/itineraries/*/reprice`, `GET /actuator/health` (status only; details for `ADMIN`), `GET /v3/api-docs`. Everything else, `/api/admin/**` and the other Actuator endpoints, needs `ROLE_ADMIN`; Actuator exposes `health` and `metrics`. Stateless; CSRF off (bearer tokens only, no cookies).
- **Changed in the fix round (controller's ruling, spec §6.6):** no hand-written decoder or role converter. Spring Boot's own resource-server settings do it, so that Keycloak can be swapped through configuration only: `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (in compose `http://keycloak:8080/realms/flight-aggregator/protocol/openid-connect/certs`, set as `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI`), `issuer-uri` (`http://localhost:8180/realms/flight-aggregator`, fixed by `KC_HOSTNAME`, checked on each token with no lookup at startup), `authorities-claim-expressions` `[realm_access][roles]` with `authority-prefix` `ROLE_`. The internal forward to `/error` is permitted (`DispatcherType.ERROR`), so a public path's error reaches an anonymous client with its own status.
- Keycloak image by §6.1 (tag and digest), `start-dev --import-realm`, health enabled; realm `flight-aggregator` with realm role `ADMIN` and the user `admin` holding it, its password a committed demo default like the provider keys (§6.6). Keycloak has no administrator account of its own: no bootstrap admin, no console login (the user's decision of 2026-11-15); the admin token comes from the client-credentials client `admin-service`. The dashboard's client comes with plan 6.

- [ ] **Step 1: `SecurityTest` maps the realm roles to authorities** (no claim → none) through Boot's settings.
- [ ] **Step 2: `SecurityTest` (`jwt()` from `spring-security-test`), one test at a time:** `searchAndRepriceNeedNoToken`; `healthNeedsNoTokenAndShowsNoDetails`; `metricsAnswer401WithoutAToken`; `metricsAnswer403WithoutTheAdminRole`; `metricsAnswer200WithTheAdminRealmRole`; `rejectsATokenOfAnotherIssuer`.
- [ ] **Step 3:** `docker compose up keycloak --wait`; the realm's `.well-known/openid-configuration` names the public issuer. Record it.
- [ ] **Step 4: `./gradlew build` green, review, commit:** `git commit -m "Protect the admin side with Keycloak tokens and the ADMIN role"`.

### Task 12: Admin status, manual ingestion and the DEGRADED health (as Q7-B and Q8-A)

**Depends on:** 2 (the `CircuitBreakerRegistry` bean and `FlightProvider` as built there), 5 (Valkey's ping), 9 (the web module), 11 (the admin role); V7 is reserved for it. **Parallel:** with 10 (see its note) and with 7 once 2 is merged; not before 11.

**Files:**
- Modify: `docs/architecture/openapi.json`, regenerated with `-Dopenapi.write=true` so that Task 9's `OpenApiContractTest` passes (the user's decision of 2026-11-15)
- Create: `port/in/InventoryStatus.java`, `StatusReport.java`, `ProviderStatus.java`, `TriggerIngestion.java`; `port/out/IngestionStatusStore.java`, `ProviderRun.java`, `ProviderBreakers.java`, `IngestionLauncher.java`; `service/InventoryStatusService.java`, `TriggerIngestionService.java`; `adapters/persistence/.../JdbcIngestionStatusStore.java`, `db/migration/V7__ingestion_status.sql`; `adapters/web/.../AdminController.java`; `bootstrap/.../observability/InventoryHealthIndicator.java`, `RegistryProviderBreakers.java`, `config/AdminConfig.java`
- Modify: `IngestInventoryService.java`, `bootstrap/.../ingestion/IngestionJob.java` (implements `IngestionLauncher`), `bootstrap/.../config/PersistenceConfig.java` (declares the status store), `application.yaml`, `compose.yaml` (the `app` health check accepts `UP` or `DEGRADED`), `README.md` ("Admin access": replace the temporary `/actuator/metrics` example with `/api/admin/*`, which Task 11 left for this task)
- Test: `IngestInventoryServiceTest.java`, `JdbcIngestionStatusStoreTest.java`, `InventoryStatusServiceTest.java`, `IngestionJobTest.java`, `AdminControllerTest.java`, `SecurityTest.java`, `InventoryHealthIndicatorTest.java`, `bootstrap/src/test/.../SearchWithoutValkeyTest.java`

**Interfaces:**
- `interface IngestionStatusStore { void record(List<ProviderRun> runs, Instant finishedAt); List<ProviderRun> latest(); Optional<Instant> lastFinishedAt(); }`; `record ProviderRun(Provider provider, ProviderReport.Status status, Optional<FailureReason> failure, int offersUsed, Map<DiscardReason, Integer> discarded, Optional<Instant> newestQuotedAt)`, built from a `ProviderReport` (its status follows from `failure`, so the two never disagree; `newestQuotedAt` is the report's, which survives a restart). `JdbcIngestionStatusStore(DataSourceTransactionManager)`, built in `PersistenceConfig` (E6), writes the rows in one transaction. `IngestInventoryService` records each `PUBLISHED` or `NOTHING_TO_PUBLISH` run's providers from its `IngestionReport` (a `CEILING_EXCEEDED` run too: its providers' statuses are still the last known), through the port; a failure to record is logged and never fails the run.
- `interface ProviderBreakers { BreakerState state(Provider provider); }`, `enum BreakerState { CLOSED, OPEN, HALF_OPEN }`; bootstrap's `RegistryProviderBreakers` reads `CircuitBreakerRegistry.circuitBreaker(provider.name())` (the registry plan 4 declared; there is no `ProviderBreakers` class in the adapters any more).
- `interface InventoryStatus { StatusReport status(); }`; `record StatusReport(long generation, Optional<Instant> publishedAt, Optional<Instant> nextIngestionAt, List<ProviderStatus> providers)`; `record ProviderStatus(Provider provider, Health health, BreakerState breaker, Optional<Instant> newestQuotedAt, int legs, Map<DiscardReason, Integer> discarded, boolean stale)`, `enum Health { UP, DEGRADED, DOWN }` per Q8; `stale` when the last run kept its offers; `nextIngestionAt` = last run's end + `ingestion.interval`.
- `interface TriggerIngestion { TriggerOutcome trigger(); }`, `enum TriggerOutcome { STARTED, ALREADY_RUNNING }`; `IngestionLauncher.launch() → boolean`. `IngestionJob` gets one entry for the scheduled run and the manual one so both take ShedLock's `ingestion` lock through the same `LockingTaskExecutor` (`executeWithLock` with the same `LockConfiguration`, inside the same `try`, with a run id in the MDC): `launch` starts the run on a virtual thread, returns `false` at once when the lock is held (the executor's `TaskResult` says it was not executed), and the thread releases the lock at the end. The run's body (`ingestOnce`, logging, metrics, a failed run counted and logged at `ERROR`) is not duplicated.
- `GET /api/admin/status` → `StatusReport`; `POST /api/admin/ingestions` → 202, or 409 *"An ingestion is already running."*.
- `InventoryHealthIndicator` → custom `DEGRADED` (HTTP 200; order `DOWN, OUT_OF_SERVICE, DEGRADED, UP, UNKNOWN`), Valkey checked with a ping; PostgreSQL down stays Boot's `DOWN`.

- [ ] **Step 1: one test at a time:** `IngestInventoryServiceTest.recordsEveryRunsProviderStatus` (a `KEPT` provider with its failure and `newestQuotedAt`; `NOTHING_TO_PUBLISH` included); `JdbcIngestionStatusStoreTest.keepsTheLatestRunPerProvider`.
- [ ] **Step 2: `InventoryStatusServiceTest`, one test at a time:** `reportsEachProvidersHealthAndStaleness`; `computesTheNextIngestion`; `reportsNothingBeforeTheFirstRun`.
- [ ] **Step 3: one test at a time:** `IngestionJobTest.launchesARunUnderTheLock` and `refusesToLaunchWhileTheLockIsHeld`; `AdminControllerTest.startsAnIngestion`, `refusesASecondRunWhileOneHoldsTheLock`; `SecurityTest.adminEndpointsNeedTheAdminRole` (401, 403).
- [ ] **Step 4: `InventoryHealthIndicatorTest`, one test at a time:** `isUpWithEveryProviderFresh`; `isDegradedWith200WhenAProviderIsKept`; `isDegradedWhenValkeyIsDown`; then `SearchWithoutValkeyTest.searchesAndRepricesWhileValkeyIsDown` (§6.2's integration test: PostgreSQL and Valkey containers, Valkey paused → search answers 200 from PostgreSQL, a request past the rate limit is admitted, health is `DEGRADED` with 200).
- [ ] **Step 5: `./gradlew build` green, review, commit:** `git commit -m "Report each provider's status, run an ingestion on demand and degrade health without failing"`.

### Task 13: Compose, the complete OpenAPI contract, and the search measured on the real volume

**Depends on:** every other task (the contract documents all endpoints; the measures need the whole stack). **Parallel:** no; it runs last, alone. Its compose step (`valkey`) may start earlier, after 5.

**Files:**
- Modify: `compose.yaml` (`valkey`; `app` with the Valkey host, Task 11's resource-server settings (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_*`), depending on `valkey` and `keycloak` healthy), `README.md`, `docs/architecture/README.md`, `docs/planning/roadmap.md`
- Create: `docs/architecture/search-api.md`; modify `docs/architecture/openapi.json` and `OpenApiContractTest.java` (Task 9's) only if a check below needs it

**Interfaces:** `/v3/api-docs` documents every endpoint, its parameters and bodies, the 400/404/409/429/503 problem responses with `Retry-After`, and the admin bearer scheme.

- [ ] **Step 1: the contract is complete:** a test in `OpenApiContractTest` that every endpoint of Tasks 9, 10 and 12 is documented with its parameters, bodies, the problem responses and the admin bearer scheme (red if any is missing); the committed file regenerated if needed.
- [ ] **Step 2: compose:** `valkey` with Task 5's tag and digest, `--maxmemory 256mb --maxmemory-policy allkeys-lru` (§6.2) and a `valkey-cli ping` health check.
- [ ] **Step 3: run and measure** (no production Java; recorded in the progress report): `docker compose up --build -d --wait`; after the first run, `EXPLAIN (ANALYZE, BUFFERS)` of the first and the fiftieth page of each sort × {no filter, origin, origin + destination, carrier} (Q6), and the load time against plan 4's; a stale-origin search triggering one refresh, and its duration (Q2); a re-price confirmed, then with Beta stopped and with all three stopped (503 within the budget); search with `valkey` stopped; 61 searches in a minute → 429.
- [ ] **Step 4: docs:** `search-api.md` (endpoints, cursor and generation, cache, refresh, re-price, auth, rate limit); the README index (this plan, `search-api.md`, `openapi.json`); roadmap plan 5 done.
- [ ] **Step 5: `./gradlew build` green, review, commit:** `git commit -m "Publish the OpenAPI contract and run the search API with Valkey and Keycloak in compose"`.
