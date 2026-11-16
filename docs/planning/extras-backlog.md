# Extras backlog

Extras the implementers and reviewers proposed while plans 5, 6 and 7 were built and on delivery day, and that are **out of the delivery** by the user's decision of 2026-11-16: "keep the extras that are not built, in case there is time later". None in the tables is approved. Each would need the user's approval before it is built; those left at the end stay here, and `SOLUTION.md` links to this backlog.

Extras that a review turned into a finding are not here: they are fixed in the closing rounds of their plan.

## Built on 2026-11-16 (G1)

The user decided on 2026-11-16, about 15:05, to build these before delivery; they left the tables below. Each is described where it now lives: [Search API](../architecture/search-api.md), the [OpenAPI contract](../architecture/openapi.json) and the [README](../../README.md); the red → green evidence is in [the day's progress report](progress/2026-11-15-observability-and-delivery.md#g1--evidence).

- Refuse unknown query parameters on the search: a `400` problem naming each one and listing the parameters it takes (plan 5 Task 9).
- Problem details for every error Spring MVC answers itself, `spring.mvc.problemdetails.enabled` (Task 9).
- An exact `ProblemDetail` schema: `title`, `status`, `detail` and `instance` (a URI reference), all required (Task 9).
- Contract metadata: title, version, description and one relative server, `/` (Task 9).
- `currency` as the enum `["EUR"]` (Task 9).
- The rate limits as configuration properties, `rate-limit.actions.<action>.requests` and `.window`, validated (Task 10).
- A startup check that every rate-limited action has a limit (Task 10).
- A README sentence naming the rate limits (Task 10).
- A README line on Swagger UI's "Authorize" button (E2).
- `503` with `Retry-After` when no pooled database connection frees within the wait, by the existing mapping of a read cut at 2 s (plan 5 closing round 1; the wait is 2 s since the re-review, not the row's 1 s).

Also built in G1, though not a backlog row: an anonymous `HEAD /api/itineraries` is answered as the public search it is (spec §6.6), where it was a `401`. The row "E3, airport and airline suggestions" is gone from plan 6's table: E3 was built on 2026-11-16.

## Built on 2026-11-16 (G3 and G4a)

The user decided on 2026-11-16, about 17:55, to build these after the `v1.0.0` tag (next section); they left the tables below and reached `main` through pull requests [#6](https://github.com/dromero001/flight-aggregator/pull/6) (G3) and [#7](https://github.com/dromero001/flight-aggregator/pull/7) (G4a). Each is described where it now lives; the red → green evidence is in the day's progress report, [G3](progress/2026-11-15-observability-and-delivery.md#g3--evidence) and [G4a](progress/2026-11-15-observability-and-delivery.md#g4a--evidence).

- A date on the alternative's legs when they fly on another day than the chosen itinerary (the final clean clone): [Dashboard, re-price](../architecture/dashboard.md).
- One shared renewal for concurrent `401`s (plan 6 Task 7): [Dashboard, sign-in](../architecture/dashboard.md#sign-in-and-the-admins-token).
- `openapi-typescript --check` (`npm run api:check`) instead of `schema.test.ts` (plan 6 Task 2): [Dashboard, types from the contract](../architecture/dashboard.md#types-from-the-contract).
- `E2E_BROWSERS` (plan 6 Task 10) and `E2E_BASE_URL` (plan 7 Task 7): the [README](../../README.md#use-the-dashboard) and [Dashboard, how each layer is tested](../architecture/dashboard.md#how-each-layer-is-tested).
- Screenshots and traces of failed end-to-end tests kept, and Playwright's browsers cached in CI's `e2e` job (plan 6 Task 10): the same section of [Dashboard](../architecture/dashboard.md#how-each-layer-is-tested).
- Valkey with `--save ""`, so the cache writes no snapshot (plan 5 Task 13): [Search API](../architecture/search-api.md).
- A `NOTHING_FRESH` refresh logged at `WARN`, and each kept provider's failure reason in a refresh's report (plan 5 Task 7): [Ingestion, the stale-route refresh](../architecture/ingestion.md#the-stale-route-refresh).
- Refresh metrics, `refresh.runs{outcome}` and `refresh.duration{outcome}`, with the skipped refreshes counted as `SKIPPED` in the same counter (plan 5 Task 7; plan 5 closing round 1): [Ingestion, observability](../architecture/ingestion.md#observability). No panel and no alert: none was approved.

Not built, and back in the tables with what was found: `npm run dev` signing in (plan 6), and the counters registered at zero (delivery day).

## Decided on 2026-11-16, about 17:55 (G3 and G4a, after the `v1.0.0` tag)

The user chose to build two more groups before delivery, in parallel worktrees, with **one double review (functional, then code) over the whole range** and one fix round only for a Critical or Important; the final commit gets the tag `v1.1.0` once its CI is green (`v1.0.0` stays on `dc58627`). Each row leaves its table when it reaches `main`.

- **G3, dashboard, end to end and CI:** a date on the alternative's legs; one shared renewal for concurrent 401s; `npm run dev` signs in; `openapi-typescript --check` instead of `schema.test.ts`; Playwright's browsers cached in CI; screenshots and traces of failed end-to-end tests kept; `E2E_BROWSERS`; `E2E_BASE_URL`.
- **G4a, backend logs and metrics:** Valkey with `--save ""`; a `NOTHING_FRESH` refresh at WARN; each kept provider's failure reason in a refresh's report; refresh metrics; a counter of skipped refreshes; the three counters registered at zero at startup, and Grafana's `unless … offset 1h` terms dropped.
- **Counters registered at zero, withdrawn after the review (the user's decision, about 19:35):** the double review found that the startup run starts at second 0 and Prometheus first scrapes at about 15 s, so the zeros are never scraped before the first increments; without the `unless … offset 1h` term the Discarded records panel showed nothing for the startup run until the next run, and the panels filled with flat zero lines. Reverted on `wip/g4a-extras`; the row stays below. What would make it hold: delay the startup run past the first scrape, and filter `> 0` on the panels.
- **`npm run dev` signs in, moved back to the backlog (the user's decision, about 18:55):** the controller's ruling (Vite on port 3000 with `strictPort`) does not fail when the compose dashboard holds the port: on macOS Node resolves `localhost` to `::1`, so Vite binds `[::1]:3000` beside Docker's `127.0.0.1:3000` and silently shadows the dashboard; binding `127.0.0.1` makes it fail but then advertises an address the realm refuses. Left: an IPv4 port check before Vite starts, or the realm allowing `5173`.
- **Not chosen:** G4b (health before the first run, rescheduling from the last run, awaiting a manual run on shutdown) and G5 (M1, M4); every other row stays in the backlog.

## Plan 5, search API

| Extra | Proposed by | Value | Cost |
|---|---|---|---|
| A `oneOf` re-price contract with a discriminator on `outcome` | Task 10 | a true union type in the dashboard | a springdoc customizer, regenerated types |
| Reschedule the next run from the last run, manual or not | Task 12 | `nextIngestionAt` matches the scheduler | scheduler change |
| A health other than `UP` before the first run ends | Task 12 | honest health at start | indicator change |

## Plan 6, dashboard

| Extra | Proposed by | Value | Cost |
|---|---|---|---|
| Let `npm run dev` sign in. Tried in G3 and not built: Vite on 3000 with `strictPort` does not fail beside the compose dashboard on macOS, it binds `[::1]:3000` and silently shadows it; binding `127.0.0.1` fails but advertises an address the realm refuses ([decided on 2026-11-16, about 17:55](#decided-on-2026-11-16-about-1755-g3-and-g4a-after-the-v100-tag)) | Task 7 | local sign-in without flags | an IPv4 port check before Vite starts, or the realm allowing 5173 |

**Built on 2026-11-16 (G2)**, by the user's decision of about 15:05, and so out of this table: the focus on the first new card after "Load more"; "+1" on each leg in the open card; the caption on an alternative's legs ("Alternative legs"); "Loading…" on the first status read; no polling after a 403; hours in "Next ingestion in …". The same decision added three points that were not rows here: no immediate retry of a failure with `Retry-After`, the decimal comma read only when there is no dot, and "Ingestion in progress" on the admin screen (`ingestionRunning` in `GET /api/admin/status`). What each does: [`dashboard.md`](../architecture/dashboard.md).

## Plan 7, observability and delivery

| Extra | Proposed by | Value | Cost |
|---|---|---|---|
| Measure the Valkey cache hit ratio under a realistic, synthetic search load, so `SOLUTION.md` can cite a figure instead of saying it is unmeasured. Out by the user's decision of 2026-11-16: too big a change at this stage of the project, with the delivery due the same day | Task 9 (the draft's review point 2) | evidence for Valkey's place | a load script with a realistic route mix, a measured run, the figure and its method in the docs |
| E2, database spans in traces (`datasource-micrometer`, outside Boot's BOM). Approved on 2026-11-15, but no task built it; out by the user's choice of 2026-11-16, about 11:13, which built E1, E4 and E5 instead | plan 7's extras | a search's trace shows its SQL statements and their time | one dependency chosen by §6.1's rule, its settings, one test |
| E7, telemetry of `mock-gds`. Approved on 2026-11-15, out by the same choice | plan 7's extras | traces cross into the providers, as in a real distributed system | Boot's starter and settings in `mock-gds`, one test |

## Proposed on delivery day (out by the user's standing rule of 2026-11-16: unbuilt extras wait in this backlog)

| Extra | Proposed by | Value | Cost |
|---|---|---|---|
| Order ties of the chosen sort by departure (today by id, so dates jump among equal prices or durations) | plan 6 Task 11 | results read in time order | a second key in the API's ordering, both slots' indexes, the cursor, plan 5's tests |
| Register `provider.calls`, `ingestion.runs` and `ingestion.records.discarded` at zero at startup, so `rate` and `increase` see the first run and the Grafana panels drop their `unless … offset 1h` term. Built in G4a and withdrawn after the review: the startup run starts before Prometheus's first scrape (about 15 s), so the zeros are never scraped first, and the panels filled with flat zero lines ([decided on 2026-11-16, about 17:55](#decided-on-2026-11-16-about-1755-g3-and-g4a-after-the-v100-tag)) | plan 7 Task 6 (E-T6-1) | the startup run's provider calls show on the dashboard | production code in three classes, a test each; the startup run delayed past the first scrape, and `> 0` on the panels |
| The log level as a Loki stream label (today only `service_name` is a label; the level is structured metadata, `severity_text` and Loki's `detected_level`, filtered with `\| detected_level="warn"`). Loki promotes only resource attributes to labels, so it needs the Collector's contrib distribution (a `transform` processor) or a resource attribute per level | plan 7 Task 5 | `{service_name="flight-aggregator", level="WARN"}` as a stream selector | a contrib Collector image or extra pipeline config, a test |
| Nest the console's trace and span ids as `"trace":{"id":…}` like `correlation`, by a `Slf4JEventListener` bean with ECS keys instead of Boot's `logging.structured.json.rename` (today flat dotted keys, `"trace.id":"…"`; ECS reads both the same) | plan 7 Task 5 | one key style in the JSON | one bean replacing Boot's, the setting removed |

## Minors deferred by the G3 and G4a review

The double review left these Minors unfixed, by the project's rule (a Minor opens no fix round of its own); they are in the pull requests' review comments, [#6](https://github.com/dromero001/flight-aggregator/pull/6) and [#7](https://github.com/dromero001/flight-aggregator/pull/7).

| Group | Minor |
|---|---|
| G3 | `dashboard.md` said concurrent `401`s make one `signinSilent`, though one that arrives after the shared renewal has settled starts another (corrected in the documentation audit of 2026-11-16) |
| G3 | An empty or trailing-comma `E2E_BROWSERS` stops with `unknown browser, ""` |
| G3 | Two avoidable type casts |
| G3 | CI runs `api:check` twice (its named step, then again inside `npm run verify`) |
| G3 | The concurrency tests of the shared renewal wait on a real 50 ms timer |
| G4a | `ComposeFileTest` checks Valkey's flags by sequence instead of the exact command |
| G4a | `RefreshMetrics.record` takes the whole report for its outcome, and `FAILED` is written in two classes |
| G4a | `ProviderReport.Status.of` takes an `Optional` parameter |
| G4a | `ingestion.md` did not say that a skipped refresh whose claim release throws counts as `FAILED` (corrected in the documentation audit of 2026-11-16) |
| G4a | `RefreshMetricsTest` repeats one case |

## Left by plan 5's closing round 3 (the user's decision of 2026-11-16: known limitations, not built before delivery)

All ten were graded Minor by the reviews; the reasons each was not fixed are in `docs/planning/progress/2026-11-15-search-api.md`, "End-of-plan fix round 3". M1 and M4 are named in `SOLUTION.md` as known security limits; T7-C2, T8-C2 and T8-C3 are looked at again in the final review against plan 7 Task 4's fan-out changes.

| Id | Limitation | The fix it would need |
|---|---|---|
| N2 | `port.in` and `port.out` depend on each other | eight value types into a shared package and two ArchUnit rules |
| N5 | `Retry-After: 60` and the breaker's open time have two owners | tie them through the controller's constructor or `RepriceResult.Unconfirmable` |
| N6 | the health ping goes through Valkey's breaker, and every probe runs the status use case | move the ping off the breaker; a cheaper probe |
| M1 | `X-Forwarded-For` is trusted from any private address, so a caller on the host or compose network can change its rate-limit key | trust only the proxy's address (compose would have to fix the subnet), or stop publishing the app port |
| M4 | admin tokens carry no audience check | an audience mapper on both realm clients and an `aud` check in the resource server |
| T4-C1 | single-statement reads open a `REPEATABLE READ` transaction (it carries the 2 s bound) | a `READ COMMITTED` transaction or JDBC's query timeout |
| T7-C2 | a refresh that ends after a run can write pre-run offers (narrow window; the next refresh or run corrects it). Since the final review's I1 it also stores its own fresh offers of the origin, downloaded before that run's, in the same transaction; the two writes to `provider_offer` are serialized, so no offer is duplicated | `replaceThrough` checks the `published_at` read before the offers and answers "superseded", which rolls the stored offers back with it |
| T8-C2 | the virtual-thread fan-out is written twice | one fan-out shared by `ProviderDownloads` and `RepriceItineraryService` |
| T8-C3 | a spent time budget reads as "not sold" | a `Deadline` value in the fan-out |
| T12-F-m2 | shutdown does not wait for a run started by hand | await the run on shutdown |
