# Roadmap

How the remaining work is split and ordered until the delivery on **Monday 2026-11-16, before 23:59** (the user's commitment, 2026-11-15). The design is closed ([design spec](design-spec.md)); each subsystem gets its own implementation plan in [`plans/`](plans/), written just before the subsystem starts, so that it builds on the interfaces the previous plan actually produced.

Legend: 👤 = with the user (review and decisions), 🌙 = autonomous work by agents, bounded by tests. Every 🌙 run ends with small commits and a [progress report](progress/).

| # | Plan | Deliverable that works on its own | Depends on | When |
|---|---|---|---|---|
| 0 | [Mock dataset](plans/2026-11-13-mock-dataset.md) | Seeded, deterministic inventory of ~1M itineraries | — | Done |
| 1 | [Foundations](plans/2026-11-14-foundations.md) | Empty hexagonal modules with ArchUnit rules, Spotless, the JaCoCo gate, CI and Dependabot, all green | — | Done |
| 2 | [Domain](plans/2026-11-14-domain.md) | `Leg`, `Itinerary`, `Money`, connection rules, `ItineraryBuilder`, deduplication and markup, pure Java | 1 | Done |
| 3 | [Mock GDS over HTTP](plans/2026-11-15-mock-gds-http.md) | The three providers answer in their own formats, latency, 500s, API key and pagination, in docker compose | 1 | Done |
| 4 | [Providers and ingestion](plans/2026-11-15-providers-and-ingestion.md) | Parsers, Resilience4j, partitioned PostgreSQL, atomic publication; ingestion end to end with 1, 2 and 3 providers down | 2, 3 | Done |
| 5 | [Search API](plans/2026-11-15-search-api.md) | Keyset search with cursor and filters, Valkey cache, rate limit, re-price, OAuth2 for admin, OpenAPI | 4 | Done: closed on 2026-11-16 after three closing rounds ([progress](progress/2026-11-15-search-api.md#end-of-plan-fix-round-3--review)) |
| 6 | [Dashboard](plans/2026-11-15-dashboard.md) | The two approved screens, with Playwright and axe end to end | 5 | Done: closed on 2026-11-16, the triple re-review approved ([progress](progress/2026-11-15-dashboard.md#end-of-plan-fix-round--re-review-verdicts)) |
| 7 | [Observability and delivery](plans/2026-11-15-observability-and-delivery.md) | Grafana stack, `SOLUTION.md`, final reviews, a clean-clone check | 6 | Mon 👤: Tasks 1–7 and 9 done, with extras E1, E4 and E5; Task 10 (delivery) in progress; the optional Tasks 8 and 11 not built ([progress](progress/2026-11-15-observability-and-delivery.md)) |

**Order rationale:** foundations first, so that every later commit already passes the architecture, formatting and coverage gates. Plans 2 and 3 do not depend on each other and can run in parallel. The search API waits for real ingested data, and the dashboard waits for the API contract it is generated from.

## Change of course on Sunday 2026-11-15, to meet the delivery date

The order above assumed one plan after another, each task reviewed by two reviewers before the next began. On Sunday at 12:30 the measured pace (50–60 minutes per task, because the reviews kept finding real defects and most tasks needed a fix round) put the end of plan 7 at the very end of Monday, with no margin. The user chose to keep the quality gates that matter and change the sequence:

- **Parallel work instead of the agreed sequence.** Tasks of plan 5 that do not depend on each other (the mock's origin filter and quote, the Valkey adapters, OAuth with Keycloak) run at the same time, each in its own git worktree, while the main chain continues; each is reviewed before it reaches `main`.
- **Plan 6 drafted while plan 5 runs,** so the user reviews it the same day and it starts as soon as plan 5's OpenAPI contract is final, instead of after plan 5 ends.
- **One reviewer per task from plan 5 on,** acting as both the functional and the code reviewer with two verdicts; the end-of-plan review keeps its three separate reviewers (`CLAUDE.md`, "Review protocol").

What did not change: TDD one test at a time, the full suite before every commit, a push and a green CI run after every task, and the user's design decisions. Why and how it was decided: [session 09](../ai/sessions/09-lost-sessions-and-recovery.md).

**The running application is tried by hand at the close of every plan** (the user, 2026-11-15: "the application also has to be tested to see that it works"), besides the tests: after plan 5, the API through `docker compose up --build` and `curl` (search with each filter, "Load more", re-price, admin status and a manual ingestion with a client-credentials token); after plan 6, the same through the dashboard in a browser; after plan 7, the clean clone of its Task 10. What was tried and what it showed goes in the progress report; a defect becomes a fix task before the plan closes.

**Documentation in every plan:** each plan updates its own block of the documentation (`docs/architecture/` for what it builds, `docs/planning/progress/` for its run) and the README index, as part of its tasks.
