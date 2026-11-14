# Flight Aggregator — Project Plan Draft (Design)

- **Date:** 2026-11-13
- **Status:** every open decision of §10 closed in the grill-me (2026-11-14)
- **Delivery:** Monday 2026-11-16, end of day — URL of the public repository `github.com/dromero001/flight-aggregator`
- **Inputs:** the take-home brief, [session 01](../ai/sessions/01-requirements-clarification.md) and the hiring team's answers received on 2026-11-13

## 1. Intent and why

This is a take-home for eDreams, followed by a live session in which the solution is extended with an AI agent on a shared screen. The goal is not only to pass the functional requirements. The repository must show how a senior backend engineer builds a production-like system with an AI agent: explicit decisions, TDD, clean architecture, resilience and a documented AI process.

Success means that:
- `docker compose up --build` starts everything, and a reviewer can search flights in the dashboard within minutes;
- every requirement in the brief is implemented, not just documented;
- `SOLUTION.md` justifies every decision and assumption, and can be defended in the interview;
- the codebase is easy to extend live (clear modules, fast tests, a versioned agent configuration).

## 2. Goals, non-goals, anti-goals

**Goals (full scope, no cut line — explicit decision of the user):**
- Three mock GDS providers (Alpha JSON, Beta XML, Gamma CSV) that serve **individual legs**, with latency of 100–5000 ms and occasional HTTP 500s.
- Ingestion: concurrent fetching, normalisation, cross-provider leg deduplication, itinerary building, validation (dead legs / invalid connections), pricing (+5% markup), storage.
- Hybrid refresh: periodic job plus background refresh of stale routes, per-provider timeout (~2–3 s), fallback to the last good data.
- Search API with optional, combinable filters (Origin, Destination, Max Price, Carrier) and keyset pagination over up to 1M rows.
- Live re-price on selection, with a "price changed" notice (§6.3).
- Frontend dashboard to search as an end customer.
- PostgreSQL + Redis, authentication, observability, CI.
- Unit, integration and end-to-end tests, written test-first. Failure tests for 1, 2 and 3 providers down.
- `SOLUTION.md`: architecture, AI usage (linking `docs/ai/sessions/`), modelling, pagination at 1M, assumptions, limitations.

**Non-goals:** load testing; date filter; round trips; currencies other than EUR; codeshare handling; more than 2 stops.

**Anti-goals (failure even if it works):**
- A big ball of mud: framework or persistence details leaking into the domain.
- Silent failures: a provider error that disappears without a log, metric or fallback.
- Features documented as "future work" instead of being built.
- Code or text copied from lastminute material.
- A repository whose AI usage is invisible or undocumented.

## 3. Constraints

- **Time with the user:** Fri 2 h, Sat 2 h, Sun 3 h, Mon 2 h (~9 h). The rest is autonomous agent work, mostly overnight (§7).
- **Git identity:** personal account `dromero001` only. Check `direnv exec . gh api user --jq .login` before any push. Commits must not carry any AI attribution.
- **Quality gates:** the full test suite is green before every commit; a thermo-nuclear code-quality review is run before the final delivery.
- **Run:** a single `docker compose up --build`, with no manual steps.

## 4. Domain rules (confirmed)

- Providers return **legs** (airline, flight number, origin, destination, departure, arrival, price). Itineraries are built by us.
- **Leg deduplication** across providers: same airline + flight number + origin/destination + departure time → the cheapest one wins.
- **Itinerary:** 1 to 3 legs (max 2 stops — our assumption). Valid connection = arrival airport equals the next departure airport, chronological order, connection time ≥ 45 min (MCT) and ≤ 24 h, and no leg already departed.
- **No airport visited twice** (decided after the dataset review): an itinerary such as MAD→LHR→MAD→BCN satisfies every connection rule but no traveller would buy it, and real search engines discard it. It is a domain rule of the itinerary builder, not a data filter.
- **Price:** sum of the leg prices, then +5% markup (EUR, rounded to cents — rounding mode left to the builder, but documented).
- **Itinerary deduplication:** with leg-level dedup in place, two itineraries with the same legs are the same itinerary by construction.
- **Carrier filter:** all legs of the itinerary are operated by that carrier (our assumption, no codeshare).
- **Filters:** Origin, Destination, Max Price and Carrier are all optional and combinable.
- **Interlining**: one itinerary may mix airlines and providers, as real OTAs do. Combining airlines that have no agreement between them (*virtual interlining*) is a known strength of eDreams. The Carrier filter still requires every leg to be operated by that carrier.
- **Realistic airline network** (decided after the dataset review): each airline concentrates its flights on its hubs (Iberia at MAD, Lufthansa at FRA, Air France at CDG…), as in reality. Target: about 40% of **direct and 1-stop** itineraries operated by a single airline, so the Carrier filter returns meaningful results. 2-stop itineraries are mostly interline, as in reality: a one-hub airline cannot fly a 2-stop trip on its own without revisiting its hub. Measuring the target over all itineraries pushed the generator to inflate point-to-point low-cost carriers, so it was moved (decided after the second dataset review). Hub routes are the most frequent ones, and each route draws its schedule from its own seed, so reordering routes does not change the data.

### 4.1 Inventory, time and volume (decided after the builder check)

- **Ingestion contract:** each mock exposes its **full leg inventory, paginated**, and the job downloads all of it. Querying by origin–destination route was rejected: it never returns the legs through intermediate airports, so connections could not be built. A *stale-route refresh* re-fetches the legs departing from that origin and rebuilds every materialised itinerary that contains them.
- **Real volume:** a seeded, deterministic dataset generator produces about **1M itineraries**. Bulk insert, and keyset tests run against real volume, so the `SOLUTION.md` claims are backed by data.
- **Horizon:** the mocks generate flights in a **rolling 30-day window** relative to today, tuned to reach ~1M itineraries.
- **Time:** provider times are ISO-8601 with UTC offset (e.g. `2026-11-20T08:15:00+01:00`). They are stored as a UTC instant (`timestamptz`) plus the original offset. Justification in §4.2.
- **Past itineraries:** the itinerary table is **partitioned by departure day**, and yesterday's partition is dropped daily. Search also filters `departure > now`, to hide today's flights that have already left. Justification in §4.3.

### 4.2 Why ISO-8601 with offset

- **The connection rules need absolute instants.** MCT (≥ 45 min) and the maximum layover (≤ 24 h) subtract the arrival time of one leg from the departure time of the next, often at airports in different timezones (MAD→LHR→JFK). A local time alone (`08:15`) is not an instant: subtracting local times across zones gives wrong layovers, and some layovers would be accepted or rejected wrongly.
- **Daylight-saving transitions.** A local time can be ambiguous (it happens twice when clocks go back) or non-existent (when clocks go forward). An offset makes every value unambiguous.
- **No timezone database needed.** The alternative, local time + airport code, forces us to keep a per-airport timezone table (IANA zone per IATA code) and to resolve DST rules at ingestion. With the offset in the payload, the conversion is already done at the source.
- **The local time is not lost.** Storing the offset next to the UTC instant lets the dashboard show the airport's local wall-clock time, which is what a traveller expects to see.
- **Standard and portable.** ISO-8601 / RFC 3339 is supported natively in all three formats (JSON string, XML `xs:dateTime`, CSV field) and in Java (`OffsetDateTime`), so the three parsers share the same time handling.
- **Honest limitation** (for `SOLUTION.md`): real GDS and IATA schedule formats often send local times and rely on airport timezone data. We own the mock schemas, so we choose the unambiguous format. A real integration would need that conversion inside the adapter, and the domain would not change.

### 4.3 Why partition by departure day

- **The data expires by day.** An itinerary stops being sellable once its first leg departs, and the inventory is a rolling 30-day window. A daily partition matches the data's lifecycle exactly: one partition in, one partition out, every day.
- **Purge cost.** Dropping a partition is a metadata operation: instant, no table scan, no dead rows. A periodic `DELETE` of ~1/30 of the table (~33k rows a day) leaves dead tuples behind. Autovacuum then has to clean them, and until it does, the tables and indexes are bloated and the keyset index scans slow down.
- **Why not weekly or monthly.** Coarser partitions can only be dropped when *all* their days have passed, so up to 6 or 29 days of dead rows would stay in the indexes. Finer than daily (hourly) would mean 720 partitions, with planning overhead and no real gain.
- **Partition pruning.** The `departure > now` filter lets PostgreSQL skip past partitions entirely, if any remain before the daily drop.
- **Trade-off we accept — cross-partition sort:** keyset queries sorted by price (the default) span all ~30 partitions. PostgreSQL resolves this with a *Merge Append*: one index seek per partition, then a merge, so ~30 seeks per page (milliseconds). This is a common production pattern; planning time only becomes a concern with hundreds or thousands of partitions. If the horizon grew to a year, we would measure it and switch to coarser partitions if needed. The cost depends more on the indexes matching the filters (e.g. `(origin, destination, price, id)` for the typical search) than on the number of partitions.
- **Trade-off we accept — partitions created ahead of time:** a daily maintenance step in the ingestion job drops yesterday's partition and creates future ones (pg_partman does the same, but it adds an extension to the image for one simple job). Safeguards, so that a failed job never breaks ingestion silently:
  - partitions are created **7 days beyond the horizon**, so the job can fail for several days without impact;
  - a **DEFAULT partition** catches any row whose day has no partition, instead of failing the insert;
  - **metrics and alerts** on "days of partitions created ahead" and "rows in DEFAULT > 0".
- **Rejected alternatives:** a periodic `DELETE` (dead rows, see above); no purge at all (the table grows without limit and the "~1M rows" figure stops being true); TTL in Redis only (PostgreSQL is the source of truth for keyset pagination).

## 5. Approaches

### 5.1 Ingestion model

| Approach | Trade-off | Decision |
|---|---|---|
| On-demand fan-out per search | Always fresh, but each search is as slow as the slowest provider (up to 5 s), and paginating 1M rows makes no sense | Rejected |
| Pure pre-computed inventory | Fast reads, but stale data and no answer to provider failure | Rejected |
| **Hybrid: periodic job + background refresh of stale routes + fallback to last good data** | Millisecond search and resilient; prices can be slightly stale (documented limitation) | **Chosen (session 01)** |

### 5.2 Provider payload model

| Approach | Trade-off | Decision |
|---|---|---|
| Providers return ready-made itineraries | Simpler, but not how GDSs work, and the dead-leg rules become simple filters | Rejected |
| Mixed (some legs, some itineraries) | Two code paths for the same concept | Rejected |
| **All three return legs; a single itinerary builder** | One domain service owns the connection rules; matches the industry pattern recommended by eDreams | **Chosen (2026-11-13)** |

### 5.3 Where itineraries are built — decided (grill-me, Q2): materialised at ingestion

| Approach | Trade-off |
|---|---|
| **A. Materialised at ingestion (proposed):** the job builds every valid itinerary up to 2 stops and stores it with its final price | Keyset pagination and indexes work over a real table of up to 1M rows; search is a single indexed query. Cost: the combinatorics happen at ingestion time and must be bounded |
| B. Built at query time from stored legs | Less storage, but every page needs a graph search, and keyset pagination over computed rows is fragile |

Recommendation: **A**, because the brief's "pagination up to 1,000,000 rows" only makes sense over a materialised result set.

### 5.4 Pagination

Keyset (seek) pagination with one composite index per supported sort order — price (default), departure time, duration — and the itinerary id as tie-breaker. The cursor is opaque (encoded sort key + id). Offset pagination is rejected because its cost grows with the page depth.

**UX (decided in the grill-me, Q11): a "Load more" button**, with no page numbers and no infinite scroll.
- **It is what eDreams does:** a "Load more deals" button at the bottom of the results, with sort tabs ([travel blog](https://travelwithbender.com/travel-thoughts/travel-tips/cheap-flights-edreams), [eDreams help centre](https://help.edreams.co.uk/hc/en-gb/articles/34070010833938-Ranking-Criteria-and-Information-on-Prices)). eDreams also asks the user to reload expired results, like our "results updated, reload" notice (§6.3).
- **It fits keyset:** the cursor only knows "the next page". Page numbers would need offset and a count over ~1M rows.
- **Over infinite scroll:** the user decides when to load more, the footer stays reachable, and it works with the keyboard and screen readers (axe, §6.8).
- **No total count:** the header shows "Showing N flights" (the rows loaded so far), because an exact total would need an expensive count.
- **Not approved:** a "Best" sort order (an eDreams default that blends price and duration), which would need a scoring formula and another keyset index.

## 6. Architecture (hexagonal / DDD)

### 6.1 Technology stack (decided in the grill-me, Q1)

**Version policy:** the latest **stable GA** of every component; no milestones, release candidates or `.0` releases published days before the delivery. With a fixed deadline, a framework bug that is not ours is a risk we cannot absorb. Exact patch versions are verified in Maven Central and on the official sites when the skeleton is created, and are pinned. Libraries managed by the Spring Boot BOM (Flyway, Testcontainers, Jackson, Micrometer, drivers) take the BOM version, so they are known to work together.

| Component | Choice | Why |
|---|---|---|
| Language | **Java 25 (LTS)** | Current LTS. Virtual threads no longer pin carrier threads inside `synchronized` (JEP 491), which matters for concurrent calls to three slow providers. Scoped Values are final, and carry the ingestion context (correlation id). Preview features (e.g. structured concurrency) are deliberately not used |
| Framework | **Spring Boot 4.1.x** | Latest stable line (Spring Framework 7, Jakarta EE 11, JSpecify null-safety, Jackson 3, OpenTelemetry-based observability). 3.5 reached end of OSS support on 2026-06-30. 4.2 is excluded while it is a release candidate or a fresh `.0` |
| Retry and concurrency limits | **Spring Framework 7 core** (`@Retryable`, `@ConcurrencyLimit`, `RetryTemplate`) | Built into the framework since 7.0 (it supersedes Spring Retry), so we use the canonical mechanism instead of adding a library for it |
| Circuit breaker and time limiter | **Resilience4j core modules** | Spring 7 has no circuit breaker. Resilience4j has no official Boot 4 starter yet, but its core is framework-agnostic; we wire it ourselves and bind its metrics to Micrometer |
| Database | **PostgreSQL 18** | Latest GA. PostgreSQL 19 is a release candidate with GA planned after our delivery date. Native declarative partitioning covers §4.3 |
| Cache | **Valkey 9** | BSD-licensed, Linux Foundation fork of Redis, protocol-compatible and the default engine in AWS ElastiCache. Redis 8 (AGPL) was considered; its native JSON and search modules are not needed here. Spring Data Redis works with both, so the choice is reversible |
| Build | **Gradle 9.x (latest stable)**, multi-module, Kotlin DSL | Supports Java 25; modules enforce the hexagonal boundaries at compile time |
| Tests | JUnit 5, AssertJ, **Testcontainers**, WireMock, ArchUnit | Real PostgreSQL and Valkey in integration tests instead of in-memory fakes |

The frontend stack is still open (Q8).

How the stack was chosen is part of the AI story: the agent's first proposal (Java 21 + Spring Boot 3) was outdated, and the review caught it. See [session 03](../ai/sessions/03-grill-me-design-decisions.md).

### 6.2 Caching strategy (decided in the grill-me, Q3)

There are two cache levels, each with its own justification.

**Level 1 — PostgreSQL as a cache of the providers' inventory**
- **Pattern:** a pre-computed inventory with *refresh-ahead* (the periodic job refreshes it before it expires) and *stale-while-revalidate* (a stale origin is served as is and refreshed in the background).
- **Why:** providers answer in 100–5000 ms and fail with HTTP 500s. Search traffic in the travel industry outnumbers bookings by orders of magnitude (look-to-book ratio), and in real life every GDS query has a cost. Without this level, every search would be as slow as the slowest provider and would fail with it.
- **Cost:** displayed prices can be stale. This is mitigated by the freshness indicator and the re-price on selection (§6.3).

**Level 2 — Valkey as a result cache**
- **Pattern:** *cache-aside* with **versioned keys** (normalised filters + inventory **generation**) and a short TTL. The ingestion job increments the generation when it finishes, which invalidates everything at once with no key scan or delete; old keys expire through their TTL. Eviction: `allkeys-lru` with `maxmemory`.
- **Why:** traffic concentrates on a few popular routes and on the first page. Each price-sorted search spans ~30 partitions (§4.3), and the cache avoids repeating that work for the hottest queries, which takes load off the database.
- **Second role, coordination:** per-origin freshness markers, plus `SET NX` with a TTL to trigger a background refresh, so that a thousand searches detecting the same stale origin trigger one refresh, not a thousand.
- **Why distributed and not local (Caffeine):** with several API instances, a local cache would serve different generations depending on the instance, duplicate memory, and could not coordinate refreshes.
- **Rejected:** *write-through* (users do not write); Valkey as source of truth (PostgreSQL is); a two-level Caffeine + Valkey cache (complexity with no gain at this volume); the ingestion job's lock in Valkey (it goes in PostgreSQL with ShedLock JDBC, so that exclusion does not depend on two systems).
- **Honest note:** at ~1M rows, a well-indexed PostgreSQL would cope without Valkey. Valkey is justified by horizontal scaling and coordination, not by the demo's volume. To back this with evidence instead of opinion:
  - the **cache hit ratio** is exposed as a metric;
  - an integration test proves that the system keeps working (only slower) when Valkey is down.

### 6.3 Price volatility (decided in the grill-me, Q3)

Fares change at any moment. The design accepts that search shows an *indicative* price and confirms it before selling, as real OTAs do.
- **Freshness shown to the user:** each result carries the time its price was last updated ("updated X min ago").
- **Re-price on selection:** an endpoint re-quotes the itinerary's legs live from their providers, with the same timeout and resilience policies, and returns either the confirmed price or "price changed: was X, now Y". The stored itinerary is updated with the new price.
- **Mocks with price drift:** mock prices change over time, deterministically from the seed, so the behaviour can be shown in the demo and asserted in tests.
- **Pagination while prices change:** an in-place price update can move an itinerary across pages, so a user paging by price could see it twice or never. The cursor carries the inventory generation. When the generation changes mid-pagination, the API flags it and the dashboard offers "results updated, reload". This is an accepted trade-off: keeping full inventory snapshots for perfectly stable pagination would duplicate ~1M rows on every ingestion.
- **Maximum staleness:** bounded by the job frequency and the per-origin refresh threshold (§6.4).

### 6.4 Refresh cadence and staleness bounds (decided in the grill-me, Q4)

All values are configurable, and will be tuned by measuring how long the real ingestion takes.

| Setting | Value | Why |
|---|---|---|
| Full ingestion | every **30 min** | Rebuilding ~1M itineraries takes minutes; running it more often would keep the database permanently busy |
| Per-origin freshness threshold | **10 min** | A search on an origin older than this triggers a background refresh of that origin's legs (deduplicated with `SET NX`, §6.2) |
| Valkey result TTL | **5 min** | Below the threshold, so the cache never extends staleness |

Resulting guarantees: origins that people search are at most ~10 min old; the rest at most ~30 min. The re-price on selection (§6.3) protects the moment of purchase regardless of these bounds. The ingestion duration is exposed as a metric, so that the 30-minute cadence can be checked against it.

### 6.5 Provider outages (decided in the grill-me, Q5)

Covers 1, 2 or all 3 providers down.

- **Search keeps working** on the last good inventory. It reads only from PostgreSQL, so it does not depend on the providers. Every result shows its freshness ("updated X min ago"). No error is returned.
- **Ingestion never replaces good data with nothing.** When a provider fails, its previously ingested legs are **kept**, not deleted, so its itineraries do not vanish all at once. Each ingestion is published atomically (the generation is incremented at the end), so a run that fails halfway never leaves a mixed state.
- **Re-price on selection with providers down:** we never sell at an unconfirmed price. The endpoint answers `503 Service Unavailable` with `Retry-After`, and the dashboard shows *"We can't confirm the price right now. Please try again in a few minutes."*
- **No silent failure:**
  - circuit breakers open per provider and probe again periodically (half-open);
  - the health check reports **DEGRADED, not DOWN**: search still works, and the orchestrator must not restart the API;
  - a freshness metric per provider, plus an alert after N consecutive failed ingestions.
- **Tests:** ingestion with 1, 2 and 3 providers down; re-price with all providers down; search served from the last good inventory.

### 6.6 Authentication and access control (decided in the grill-me, Q6)

Starting point: on a real OTA, **searching does not require logging in**. Requiring it would hurt the business, so authentication goes where it adds value.

- **Search and re-price: public**, with **rate limiting per client IP** against scraping, a real problem in the travel industry. The counters live in Valkey, so the limit holds across API instances. Over the limit: `429 Too Many Requests` with `Retry-After`.
- **Admin endpoints: OAuth2 with JWT.** These are manual ingestion trigger, provider and circuit-breaker status, and actuator. The API is a Spring Security *resource server* and requires the `ADMIN` role.
  - Token issuer: **Keycloak** in docker compose, an open-source identity provider and the de facto standard. Its realm is imported at startup, with a demo admin user (documented in the README). Spring integrates with it out of the box through standard OIDC, so it could be swapped for any other provider (Auth0, Cognito, Okta) through configuration only.
  - Rejected: a static API key for admin (not how it would be done in production); authentication on search (against the business).
- **Credentials towards the providers:** each mock requires its own API key in a header, as a real GDS does. The adapters send it from configuration, injected through environment variables and never committed.
- **Tests:** public search without a token; admin endpoints returning 401 without a token and 403 without the `ADMIN` role; rate-limit 429; a provider rejecting a wrong API key.

### 6.7 Provider payload schemas (decided in the grill-me, Q7)

The hiring team left the schemas to us. Each provider is **deliberately different**, as real legacy systems are, so that each adapter performs a real translation into our model instead of copying fields.

| | Alpha (JSON) | Beta (XML) | Gamma (CSV) |
|---|---|---|---|
| Structure | flat list of legs, `camelCase` names | nested (`<Segments><Segment><Carrier code="IB"/>…`), mixing attributes and elements | one line per leg, **with a header row** |
| Price | decimal as a string, `"129.90"` | integer in minor units (cents), `12990` | decimal with a dot |
| Pagination | cursor (`nextCursor`) | page number | `offset` / `limit` |
| Times | ISO-8601 with offset in all three (§4.2) | | |
| Auth | API key in a header, different per provider (§6.6) | | |

- **Why Gamma has a header row:** columns are read by name, not by position. Reordering columns does not break the parser, and the file describes itself. Without a header, adding a column would silently corrupt the parsing.
- **Malformed records:** only the malformed line or element is discarded. It is counted in a metric and logged with the provider and the reason. A batch is never lost because of one bad record, and nothing is discarded silently.
- **Why it matters:** these differences are what justify the **adapter / anti-corruption layer** pattern. No external format crosses into the domain, and adding a fourth provider means writing one adapter, without touching the domain or the itinerary builder.
- **Tests:** a parser contract test per provider, covering valid payloads, every malformed case and pagination to the last page.

### 6.8 Frontend dashboard (decided in the grill-me, Q8)

**Decision: React + TypeScript + Vite**, in its own container, built to look as close as possible to a real production frontend. Thymeleaf + htmx was considered: it has fewer moving parts and stays entirely in Java, but the HTML would be generated by the backend, so the dashboard could not prove that the public API stands on its own.

Production-like choices (agent's calls, made to that goal):
- **Deployment:** a multi-stage Docker build serves the static bundle from **nginx**. nginx also reverse-proxies `/api` to the backend, so the browser talks to a single origin and production needs no CORS. In development, the Vite dev server proxies the same way.
- **API contract:** the backend publishes an OpenAPI specification, and the TypeScript types are **generated from it**, so frontend and backend cannot drift apart silently. CI fails if the generated types are out of date.
- **Server state:** TanStack Query handles caching, retries, and loading and error states. Its infinite queries map naturally onto keyset "load more" (see Q11). There is no global state manager, because none is needed.
- **The URL holds the state:** filters and sort order live in the query string, so a search can be shared or bookmarked, and the browser's back button works.
- **Every state designed:** loading, empty results, errors, price freshness ("updated X min ago"), "results updated, reload" (generation change), "price changed" on re-price, provider outage (503) and rate limit (429). All texts are in English.
- **Accessibility:** semantic HTML, keyboard navigation and labelled controls, checked automatically with axe in the end-to-end tests.
- **Quality:** TypeScript in strict mode, ESLint + Prettier, Vitest + React Testing Library + Mock Service Worker for components, and Playwright for end-to-end tests against docker compose. All of them run in CI.
- **Dependencies:** the latest stable versions, verified like the backend's (§6.1); light styling with no heavy component library.

**Visual design (approved in the grill-me, Q8.1–Q8.3).** Two screens:

1. **Search (public).** A top bar with exactly the four filters of the brief (From, To, Airline, Max price), all optional, and the sort order (price, departure, duration). No side panel, no date or stops filter. Each result card shows the departure date, local departure and arrival times, total duration, number of stops with the connecting airports and layover times, the airlines and flight numbers, and the final price with the markup. Mixed-airline itineraries carry a "Multiple airlines" notice. The provider is never shown. Clicking a card expands its legs and re-prices it: unchanged price → a "Select" button that goes no further (booking is out of scope); "Price changed" with the new price; or "Provider unavailable" on a 503. Results end with "Load more" and the header shows "Showing N flights" (§5.4). On a phone the filters stack.
2. **Provider status (admin, Keycloak login).** The active inventory generation and when it was published, the time to the next ingestion, and one row per provider with its health (UP / DEGRADED / DOWN), circuit-breaker state, last successful ingestion, legs and discarded records, marking a provider whose legs are kept from the last good data as stale. A **"Run ingestion now"** button calls the manual ingestion trigger of §6.6 (approved as an extra, so the live session can show the system reacting without waiting 30 minutes).

### 6.9 Language

All user-facing text is in **English**: API error messages, dashboard labels, filters, notices and empty states. The same applies to code, logs and documentation.

### 6.10 Observability (decided in the grill-me, Q9)

**Minimum (required by §2 and the metrics the other sections already promise):**
- **Metrics:** Micrometer, exposed through Actuator (admin only, §6.6): provider call outcomes and circuit-breaker state (§6.5), data freshness per provider (§6.5), ingestion duration (§6.4), cache hit ratio (§6.2), discarded records per provider and reason (§6.7), and partition health (§4.3). Low-cardinality tags only: never origin, destination or user.
- **Alert:** N consecutive failed ingestions (§6.5), evaluated as a Prometheus rule with no notification channel.
- **Logs:** structured JSON (Boot's built-in structured logging), with trace id, span id and the correlation id; every provider failure and discarded record logs its provider and reason.
- **Traces:** OpenTelemetry through Boot's built-in observability, W3C `traceparent`.
- **Compose:** Prometheus plus Grafana, with datasources and one dashboard provisioned from files in the repository, so `docker compose up --build` needs no manual step.

**Approved extras:** the **OpenTelemetry Collector and Tempo** (one search or ingestion run visible as a single trace) and **Loki** (logs searchable in Grafana, with a jump from a log line to its trace). Together with Prometheus they give the three signals, correlated in one UI, as in production.

**Only if time remains:** JVM dashboards and PostgreSQL / Valkey exporters. **Rejected:** Alertmanager, SLO burn-rate alerts and a trace id in every error response.

### 6.11 Continuous integration (decided in the grill-me, Q10)

GitHub Actions on every push and pull request to `main`:
1. **Backend:** Java 25 and Gradle with its cache, running `./gradlew build`: the full suite (unit, ArchUnit, WireMock and Testcontainers integration, failure tests with 1, 2 and 3 providers down).
2. **Frontend:** TypeScript in strict mode, ESLint, Prettier and Vitest (§6.8).
3. **Contract drift:** regenerate the OpenAPI specification and the TypeScript types, and fail if they differ from the committed ones (§6.8).
4. **Images:** build the backend, `mock-gds` and frontend images, without pushing them.
5. **End-to-end:** `docker compose up --build`, wait for health, then Playwright with axe against the stack (§6.8).
6. Test reports are uploaded as artefacts when a job fails.

**Approved extras:**
- **Superseded runs are cancelled** (a concurrency group), and the README shows a build status badge.
- **Dependabot** for Gradle, npm, GitHub Actions and Docker: weekly, grouped per ecosystem, with a **14-day cooldown** before proposing a fresh release (it matches the "no recent releases" rule of §6.1).
- **Coverage gate with JaCoCo:** at least **90% of lines and 80% of branches**, over unit and integration tests of every backend module together. The Spring Boot entry point, configuration classes and generated code are excluded. Branches get a lower bar because 90% would force tests with no value for defensive and compiler-generated branches.
- **Formatting gate with Spotless and google-java-format** (Google Java Style: 2-space indentation, 100 columns). `spotlessCheck` runs inside `./gradlew build`, so the gate also applies locally; `./gradlew spotlessApply` fixes it. The existing code is reformatted once, in a commit of its own.

**Rejected:** publishing images to GHCR, CodeQL / Trivy scans, branch protection (it would block the agents' direct pushes to `main`). **Not approved:** frontend coverage gate, mutation testing with PIT.

Modules (Gradle subprojects; the dependency direction is enforced with ArchUnit):

**Ports (decided before the plans):** both sides of the hexagon are explicit. Every use case is an inbound port, an interface in `application.port.in`, implemented by a service in `application.service`; every external dependency is an outbound port in `application.port.out`. Driving adapters (web, the scheduler) depend only on inbound ports, driven adapters (providers, persistence) only implement outbound ports, adapters never depend on each other, and the domain depends on nothing but the JDK. The application layer may use Spring (`@Service`, `@Transactional`, Spring's retry): the user chose idiomatic, modern Spring Boot over a framework-free core, so services are plain annotated classes and need no hand-written wiring. ArchUnit checks each of these rules. The cost, one interface per use case with a single implementation, is accepted because it makes the hexagon visible and every rule checkable.

| Module | Content | Depends on |
|---|---|---|
| `domain` | Leg, Itinerary, Money, Airport, Carrier; `ItineraryBuilder`, `ConnectionRules`, `LegDeduplicator`, `PricingPolicy` (markup). Pure Java, no frameworks | — |
| `application` | Inbound ports (`port.in`, one interface per use case): `IngestInventory`, `RefreshStaleRoute`, `SearchItineraries`, `RepriceItinerary`. Outbound ports (`port.out`): `FlightProvider`, `ItineraryRepository`, `SearchCache`, `Clock`. Services (`service`) implement the inbound ports | `domain` |
| `adapters/providers` | Alpha (JSON), Beta (XML), Gamma (CSV) HTTP clients + parsers + mapping to `Leg`; Resilience4j (timeout, retry, circuit breaker, bulkhead) | `application` |
| `adapters/persistence` | PostgreSQL (Flyway, keyset queries, indexes), Redis (cache) | `application` |
| `adapters/web` | REST search API, cursor encoding, validation, auth, OpenAPI | `application` |
| `bootstrap` | Spring Boot app, wiring, scheduler, configuration, observability | all |
| `mock-gds` | One deployable serving the three mock providers, with a seeded dataset, configurable latency and 500 rate | — |
| `frontend` | Dashboard | web API |

## 7. Phases and definition of done

Legend: 👤 = with the user (design decisions), 🌙 = autonomous overnight work (tedious, verifiable by tests). Every night ends with small commits and a `docs/planning/progress/<date>.md` report. If a decision blocks the night's work, it is written down as pending, and the agent moves to another task instead of improvising.

| When | Work | Definition of done |
|---|---|---|
| Fri 👤 | This plan draft, short grill-me on the open decisions, detailed plan (`writing-plans`) | Spec and plan committed; sessions 02–03 documented |
| Fri night 🌙 | Gradle skeleton, ArchUnit rules, CI (GitHub Actions), docker compose skeleton, `mock-gds` with seeded dataset covering every case | `./gradlew build` and CI green; `docker compose up --build` starts the mocks |
| Sat 👤 | Review of the domain model, `ItineraryBuilder` and the mock dataset | Domain tests green and approved |
| Sat night 🌙 | Provider adapters + parsers, Resilience4j, ingestion job, PostgreSQL + Flyway, Redis; failure tests for 1, 2 and 3 providers down | Integration tests (Testcontainers + WireMock) green |
| Sun 👤 | Review of the search API with keyset, refresh strategy and the 3-providers-down behaviour; dashboard sketch | API contract and sketch approved |
| Sun night 🌙 | Dashboard from the approved sketch, auth, observability, e2e for the approved flows | e2e green against `docker compose` |
| Mon 👤 | Thermo-nuclear review, full suite green, `SOLUTION.md` and `README` final, demo, push | Public repo with everything working from a clean clone |

## 8. Testing strategy

- **TDD everywhere.** Red → green → refactor; no production code without a failing test first.
- **Unit (domain/application):** connection rules (every dead-leg and invalid-connection case), deduplication, pricing and rounding, itinerary building up to 2 stops, cursor encoding. Fast, no Spring.
- **Integration:** each provider adapter against WireMock (happy path, malformed payload, timeout, 500); persistence against PostgreSQL/Redis with Testcontainers (keyset correctness at page boundaries, ties, filters); ingestion with 1, 2 and 3 providers failing.
- **Architecture:** ArchUnit rules for the dependency direction.
- **End-to-end:** the full stack via docker compose; search flows through the API and the dashboard (tool to be decided with the frontend stack).
- **Gate:** the full suite runs before every commit and in CI.

## 9. AI usage per phase

- **Requirements and design:** Claude Code with persistent memory; brainstorming → grill-me (one question at a time, always with a recommendation) → `writing-plans`.
- **Implementation:** TDD driven by the agent; subagents for independent tasks; overnight autonomous runs bounded by tests.
- **Review:** thermo-nuclear code-quality review plus a correctness code review before the delivery.
- **Traceability:** each relevant session documented in `docs/ai/sessions/` (only the prompts that add value); the agent configuration (`CLAUDE.md`, skills, subagents) versioned in the repo; `SOLUTION.md` links to both.

## 10. Open decisions (for the grill-me)

1. ~~**Stack**~~ — **decided**, see §6.1.
2. ~~**Where itineraries are built**~~ — **decided**: materialised at ingestion (§5.3).
3. ~~**Role of the cache**~~ — **decided**, see §6.2 and §6.3.
4. ~~**Job frequency and TTL / staleness threshold**~~ — **decided**, see §6.4.
5. ~~**All three providers down**~~ — **decided**, see §6.5.
6. ~~**Authentication**~~ — **decided**, see §6.6.
7. ~~**Payload schemas**~~ — **decided**, see §6.7.
8. ~~**Dashboard**~~ — **decided**: stack and visual design, see §6.8.
9. ~~**Observability**~~ — **decided**, see §6.10.
10. ~~**CI**~~ — **decided**, see §6.11.
11. ~~**Pagination UX**~~ — **decided**: "Load more", see §5.4.

## 11. Left to the builder

- Package names, class names below the module level, and the internal layout of each module.
- Money rounding mode (documented in `SOLUTION.md`).
- Concrete dataset contents, as long as every case in §4 is covered by at least one route.
- Retry/backoff parameters within the ~2–3 s per-provider budget.
