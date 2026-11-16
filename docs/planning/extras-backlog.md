# Extras backlog

Extras the implementers and reviewers proposed while plans 5 and 6 were built, and that are **out of the delivery** by the user's decision of 2026-11-16: "keep the extras that are not built, in case there is time later". None is approved. Each would need the user's approval before it is built; any left at the end is listed in `SOLUTION.md` as a next step.

Extras that a review turned into a finding are not here: they are fixed in the closing rounds of their plan.

## Plan 5, search API

| Extra | Proposed by | Value | Cost |
|---|---|---|---|
| Refuse unknown query parameters (a misspelt `orign=MAD` gives an unfiltered search today) | Task 9 | a client's typo is caught | one check, tests |
| Problem details for every framework error (`spring.mvc.problemdetails.enabled`) | Task 9 | one error format everywhere | a property, tests |
| An exact `ProblemDetail` schema (the contract lists a `properties` member Spring never writes) | Task 9 | a truthful contract | a springdoc customizer |
| Contract metadata: title and server (today springdoc's default and `http://localhost`) | Task 9 | a readable contract | a few lines |
| `currency` as the enum `["EUR"]` | Task 9 | a stricter contract | one annotation |
| A `oneOf` re-price contract with a discriminator on `outcome` | Task 10 | a true union type in the dashboard | a springdoc customizer, regenerated types |
| The rate limits as configuration properties instead of constants in `WebConfig` | Task 10 | tunable without a build | properties, validation |
| A startup check that every rate-limited action has a limit | Task 10 | fails at start, not on the first request | one check |
| A README sentence naming the rate limits | Task 10 | documented limits | one line |
| A `NOTHING_FRESH` refresh logged at WARN, as an empty run is | Task 7 | visible degraded refreshes | one line, one test |
| Each kept provider's failure reason in a refresh's report | Task 7 | easier diagnosis | report field, log |
| Refresh metrics (count by outcome, duration) | Task 7 | observability of refreshes | meters, tests |
| Reschedule the next run from the last run, manual or not | Task 12 | `nextIngestionAt` matches the scheduler | scheduler change |
| A health other than `UP` before the first run ends | Task 12 | honest health at start | indicator change |
| Valkey with `--save ""`, so the cache writes no snapshots (the image declares no volume) | Task 13 | no disk writes for a cache | one compose flag |
| A README line on Swagger UI's "Authorize" button | E2 | admin calls tried from the UI | one line |

## Plan 6, dashboard

| Extra | Proposed by | Value | Cost |
|---|---|---|---|
| `openapi-typescript --check` instead of `schema.test.ts` | Task 2 | one file and a tsconfig exception fewer | the generated file left unformatted |
| Focus the first new card after "Load more" | Task 5 | keyboard users carry on where new results start | a ref, one test |
| "+1" on each leg in the open card | Task 6 | next-day legs read right | helper reuse, one test |
| A caption telling the chosen legs from the alternative's | Task 6 | clearer alternative | copy, one test |
| Let `npm run dev` sign in (Vite on 3000 with `strictPort`, or the realm allowing 5173) | Task 7 | local sign-in without flags | config line |
| One shared renewal for concurrent 401s | Task 7 | one renewal instead of two | a shared promise, test |
| A "Loading…" line on the first status read | Task 8 | no blank screen | one state, one test |
| Stop polling after a 403 | Task 8 | no request every 10 s for a non-admin | query option, test |
| Hours in "Next ingestion in N min" for intervals over an hour | Task 8 | readable times | formatter, test |
| Cache Playwright's browsers in the CI `e2e` job | Task 10 | faster CI | a cache step |
| Choose the end-to-end browsers with an environment variable (`E2E_BROWSERS`), so `npm run e2e` works where a browser does not start (Firefox on macOS 27) | Task 10 | local runs on any Mac | config, docs |
| Keep screenshots and traces of failed end-to-end tests | Task 10 | easier diagnosis of CI failures | config, artefacts |
| E3, airport and airline suggestions in the filters (approved, but needs a catalogue endpoint no task builds) | plan 6 | no IATA codes to know | an endpoint, a combobox, accessibility tests |

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
| Answer 503 with `Retry-After` when no pooled database connection frees within the 1 s wait (today 500) | plan 5 closing round 1 | an overload reads as "try later", not as a bug | one exception mapping, one test |
| A counter of refreshes skipped because another one runs | plan 5 closing round 1 | visible refresh pressure | one meter, one test |
| Register `provider.calls`, `ingestion.runs` and `ingestion.records.discarded` at zero at startup, so `rate` and `increase` see the first run and the Grafana panels drop their `unless … offset 1h` term | plan 7 Task 6 (E-T6-1) | the startup run's provider calls show on the dashboard | production code in three classes, a test each |
| The log level as a Loki stream label (today only `service_name` is a label; the level is structured metadata, `severity_text` and Loki's `detected_level`, filtered with `\| detected_level="warn"`). Loki promotes only resource attributes to labels, so it needs the Collector's contrib distribution (a `transform` processor) or a resource attribute per level | plan 7 Task 5 | `{service_name="flight-aggregator", level="WARN"}` as a stream selector | a contrib Collector image or extra pipeline config, a test |
| Nest the console's trace and span ids as `"trace":{"id":…}` like `correlation`, by a `Slf4JEventListener` bean with ECS keys instead of Boot's `logging.structured.json.rename` (today flat dotted keys, `"trace.id":"…"`; ECS reads both the same) | plan 7 Task 5 | one key style in the JSON | one bean replacing Boot's, the setting removed |
| The end-to-end base URL from an environment variable (`E2E_BASE_URL`, today fixed at `http://localhost:3000` in `playwright.config.ts`), so the suite runs against a second stack on other ports, as the telemetry addresses already can (`GRAFANA_URL`, `PROMETHEUS_URL`, `KEYCLOAK_URL`). Task 7 ran its project against a throwaway stack through a scratch config that overrides `baseURL`; the admin specs would also need that stack's Keycloak address | plan 7 Task 7 | the whole suite runs beside a stack already on the default ports | one line in the config, a README line |

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
