# Roadmap

How the remaining work is split and ordered until the delivery on **Monday 2026-11-16, end of day**. The design is closed ([design spec](design-spec.md)); each subsystem gets its own implementation plan in [`plans/`](plans/), written just before the subsystem starts, so that it builds on the interfaces the previous plan actually produced.

Legend: 👤 = with the user (review and decisions), 🌙 = autonomous work by agents, bounded by tests. Every 🌙 run ends with small commits and a [progress report](progress/).

| # | Plan | Deliverable that works on its own | Depends on | When |
|---|---|---|---|---|
| 0 | [Mock dataset](plans/2026-11-13-mock-dataset.md) | Seeded, deterministic inventory of ~1M itineraries | — | Done |
| 1 | [Foundations](plans/2026-11-14-foundations.md) | Empty hexagonal modules with ArchUnit rules, Spotless, the JaCoCo gate, CI and Dependabot, all green | — | Sat 🌙 |
| 2 | Domain | `Leg`, `Itinerary`, `Money`, connection rules, `ItineraryBuilder`, deduplication and markup, pure Java | 1 | Sat 👤 review → 🌙 |
| 3 | `mock-gds` over HTTP | The three providers answer in their own formats, latency, 500s, API key and pagination, in docker compose | 1 | Sat 🌙 |
| 4 | Providers and ingestion | Parsers, Resilience4j, partitioned PostgreSQL, atomic publication; ingestion end to end with 1, 2 and 3 providers down | 2, 3 | Sun 🌙 |
| 5 | Search API | Keyset search with cursor and filters, Valkey cache, rate limit, re-price, OAuth2 for admin, OpenAPI | 4 | Sun 👤 review → 🌙 |
| 6 | Dashboard | The two approved screens, with Playwright and axe end to end | 5 | Sun night 🌙 |
| 7 | Observability and delivery | Grafana stack, `SOLUTION.md`, final reviews, a clean-clone check | 6 | Mon 👤 |

**Order rationale:** foundations first, so that every later commit already passes the architecture, formatting and coverage gates. Plans 2 and 3 do not depend on each other and can run in parallel. The search API waits for real ingested data, and the dashboard waits for the API contract it is generated from.

**Documentation in every plan:** each plan updates its own block of the documentation (`docs/architecture/` for what it builds, `docs/planning/progress/` for its run) and the README index, as part of its tasks.
