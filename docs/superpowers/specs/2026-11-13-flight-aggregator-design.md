# Flight Aggregator — Project Plan Draft (Design)

- **Date:** 2026-11-13
- **Status:** draft, pending the grill-me session on the open decisions (§10)
- **Delivery:** Monday 2026-11-16, end of day — URL of the public repository `github.com/dromero001/flight-aggregator`
- **Inputs:** the take-home brief, [session 01](../../ai-sessions/01-requirements-clarification.md) and the hiring team's answers received on 2026-11-13

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
- Frontend dashboard to search as an end customer.
- PostgreSQL + Redis, authentication, observability, CI.
- Unit, integration and end-to-end tests, written test-first. Failure tests for 1, 2 and 3 providers down.
- `SOLUTION.md`: architecture, AI usage (linking `docs/ai-sessions/`), modelling, pagination at 1M, assumptions, limitations.

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
- **Price:** sum of the leg prices, then +5% markup (EUR, rounded to cents — rounding mode left to the builder, but documented).
- **Itinerary deduplication:** with leg-level dedup in place, two itineraries with the same legs are the same itinerary by construction.
- **Carrier filter:** all legs of the itinerary are operated by that carrier (our assumption, no codeshare).
- **Filters:** Origin, Destination, Max Price and Carrier are all optional and combinable.
- **Interlining** (agent's call): one itinerary may mix airlines and providers, as real OTAs do. The Carrier filter still requires every leg to be operated by that carrier.

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

### 5.3 Where itineraries are built — proposal, to confirm in the grill-me

| Approach | Trade-off |
|---|---|
| **A. Materialised at ingestion (proposed):** the job builds every valid itinerary up to 2 stops and stores it with its final price | Keyset pagination and indexes work over a real table of up to 1M rows; search is a single indexed query. Cost: the combinatorics happen at ingestion time and must be bounded |
| B. Built at query time from stored legs | Less storage, but every page needs a graph search, and keyset pagination over computed rows is fragile |

Recommendation: **A**, because the brief's "pagination up to 1,000,000 rows" only makes sense over a materialised result set.

### 5.4 Pagination

Keyset (seek) pagination with one composite index per supported sort order — price (default), departure time, duration — and the itinerary id as tie-breaker. The cursor is opaque (encoded sort key + id). Offset pagination is rejected because its cost grows with the page depth. Pending: check how eDreams' own website paginates (load more vs page numbers), to justify the UX choice in `SOLUTION.md`.

## 6. Architecture (hexagonal / DDD) — proposal

Proposed stack (pending the grill-me, Q1): **Java 21 + Spring Boot 3 + virtual threads + Resilience4j + PostgreSQL + Redis + Gradle (multi-module) + Testcontainers**. The frontend stack is still open.

Modules (Gradle subprojects; the dependency direction is enforced with ArchUnit):

| Module | Content | Depends on |
|---|---|---|
| `domain` | Leg, Itinerary, Money, Airport, Carrier; `ItineraryBuilder`, `ConnectionRules`, `LegDeduplicator`, `PricingPolicy` (markup). Pure Java, no frameworks | — |
| `application` | Use cases: `IngestInventory`, `RefreshStaleRoute`, `SearchItineraries`. Ports: `FlightProvider`, `ItineraryRepository`, `SearchCache`, `Clock` | `domain` |
| `adapters/providers` | Alpha (JSON), Beta (XML), Gamma (CSV) HTTP clients + parsers + mapping to `Leg`; Resilience4j (timeout, retry, circuit breaker, bulkhead) | `application` |
| `adapters/persistence` | PostgreSQL (Flyway, keyset queries, indexes), Redis (cache) | `application` |
| `adapters/web` | REST search API, cursor encoding, validation, auth, OpenAPI | `application` |
| `bootstrap` | Spring Boot app, wiring, scheduler, configuration, observability | all |
| `mock-gds` | One deployable serving the three mock providers, with a seeded dataset, configurable latency and 500 rate | — |
| `frontend` | Dashboard | web API |

## 7. Phases and definition of done

Legend: 👤 = with the user (design decisions), 🌙 = autonomous overnight work (tedious, verifiable by tests). Every night ends with small commits and a `docs/progress/<date>.md` report. If a decision blocks the night's work, it is written down as pending, and the agent moves to another task instead of improvising.

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
- **Traceability:** each relevant session documented in `docs/ai-sessions/` (only the prompts that add value); the agent configuration (`CLAUDE.md`, skills, subagents) versioned in the repo; `SOLUTION.md` links to both.

## 10. Open decisions (for the grill-me)

1. **Stack** — proposed: Java 21 + Spring Boot 3 + virtual threads + Resilience4j + PostgreSQL + Redis + Gradle + Testcontainers.
2. **Where itineraries are built** (§5.3) — proposed: materialised at ingestion.
3. **Role of Redis** — e.g. cache of search pages vs freshness markers per route vs distributed lock for the job.
4. **Job frequency and TTL / staleness threshold** per route.
5. **All three providers down** — serve stale data with a freshness flag, or fail?
6. **Authentication** — API key, JWT (OAuth2 resource server), or none for search with auth only on admin endpoints.
7. **Payload schemas** for Alpha, Beta and Gamma (Gamma with or without headers), designed to cover every case and to be defended in the interview.
8. **Dashboard** — design and frontend stack.
9. **Observability** — metrics, traces, logs; which stack in docker compose.
10. **CI** — GitHub Actions scope (build, tests, image build).
11. **Pagination UX** — load more vs page numbers, after checking eDreams' website.

## 11. Left to the builder

- Package names, class names below the module level, and the internal layout of each module.
- Money rounding mode (documented in `SOLUTION.md`).
- Concrete dataset contents, as long as every case in §4 is covered by at least one route.
- Retry/backoff parameters within the ~2–3 s per-provider budget.
