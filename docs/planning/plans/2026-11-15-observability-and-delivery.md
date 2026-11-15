# Observability and Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Every production change follows `superpowers:test-driven-development` and `CLAUDE.md`. Reviews follow the review protocol in `CLAUDE.md` as changed from plan 5 on: **one reviewer per task**, acting as `functional-reviewer` and then as `code-reviewer`, gives two verdicts; at the end of the plan, `functional-reviewer`, `code-reviewer` and `risk-reviewer` review the whole diff separately. Tasks marked parallel in the task map may run in their own git worktrees (`superpowers:using-git-worktrees`); each is reviewed before it reaches `main`.

**Goal:** the three signals of §6.10 leave the application and meet in one Grafana that the whole team opens without logging in (Prometheus metrics with the alert rules, Tempo traces, Loki logs with a jump from a line to its trace), all started by `docker compose up --build` and provisioned from files; then `SOLUTION.md`, the final README, the final reviews and a clean-clone check, for the delivery on Monday 2026-11-16, end of day.

**Architecture:**
- Actuator moves to a separate management port that only compose's network reaches; Prometheus scrapes it there with no token, and the API port serves no metrics. The API port keeps its public health status as plan 5 left it.
- The application gains OpenTelemetry through Spring Boot's built-in observability: spans for every HTTP request, provider request and ingestion run, with W3C `traceparent`; trace and span ids on every log line. It sends traces and logs over OTLP to the OpenTelemetry Collector, which forwards them to Tempo and Loki. The application layer stays free of Micrometer: its fan-out threads get the trace context from a JDK `ThreadFactory` that bootstrap builds.
- Grafana reads Prometheus, Tempo and Loki through datasources provisioned from `deploy/grafana/`, with one dashboard, open to anonymous Viewers. Prometheus evaluates the alert rules, with no notification channel. Every configuration file is tested inside `./gradlew build` against the pinned image that runs it; a Playwright project proves the correlation end to end against compose.

**Tech Stack:** Spring Boot 4.1.2's observability (Micrometer's Prometheus registry; Micrometer Observation and Tracing with the OpenTelemetry bridge and OTLP exporters; Micrometer context propagation; BOM versions), the OpenTelemetry Collector (core distribution), Prometheus with `promtool`, Tempo, Loki, Grafana, Testcontainers, Playwright. **No image or library version is chosen here:** each task picks its own by §6.1 and records it.

**Spec:** [design spec](../design-spec.md), sections:
- §6.10: the minimum, the approved extras (Collector, Tempo, Loki, the jump from a log line to its trace), "only if time remains" (JVM dashboards, PostgreSQL and Valkey exporters), the rejected items.
- §6.6: metrics for the whole team through Grafana without logging in, Prometheus on a management port inside compose's network, `ADMIN` for the admin actions and the provider status page (the user's decision of 2026-11-15).
- §6.5: freshness per provider, the alert after N failed ingestions in a row. §4.3: partitions ahead and rows in `DEFAULT`. §6.2: cache hit ratio. §6.4: ingestion duration against the cadence. §6.7: discarded records per provider and reason. §6.1: version policy. §3: one command, no manual step.
- §1, §2, §7 (Monday row), §8, §9: `SOLUTION.md`, the final README, the thermo-nuclear and correctness reviews, the clean clone, AI usage.

Roadmap: [plan 7](../roadmap.md), started as soon as plan 6 ends (tasks marked in the task map start earlier). It builds on what plans 4–6 built and does not repeat it: the meters `ingestion.*`, `provider.calls`, `ingestion.records.discarded`, `ingestion.provider.retries`, the breakers' `resilience4j.circuitbreaker.*` (`TaggedCircuitBreakerMetrics`) and `search.cache{result}` (plan 5 Task 5), listed in [Ingestion](../../architecture/ingestion.md); the ECS console logs with `correlation.id` in the MDC (`IngestionJob`); `SecurityConfig`, `GET /api/admin/status`, `POST /api/admin/ingestions` and the `DEGRADED` health (plan 5 Tasks 11–12); the measured figures of plan 5 Task 13; plan 6's Playwright harness and CI `e2e` job (plan 6 Tasks 9–10). Carried notes: plan 4 close-out (the alert rules' NaN and aggregation), E15 (user, plan 4's extras: "optional at the very end, only with a full re-verification").

**Status:** draft for the user's review on the evening of 2026-11-15. Not approved; the open questions below are unanswered. How Prometheus reads the metrics and who opens Grafana were decided by the user while the plan was drafted (spec §6.6, §6.10) and are written into Tasks 1 and 2.

## Global Constraints

- **Version policy (§6.1):** every image (Collector, Prometheus, Tempo, Loki, Grafana, and Task 8's exporters) is the newest stable GA at least 14 days old, read from its official release page, pinned by tag and multi-arch digest, and recorded in the progress report. Libraries take the Boot 4.1.2 BOM; one the BOM does not manage follows the same rule from Maven Central. (§6.1; CLAUDE.md)
- **Metrics access:** Actuator's endpoints are served on a management port that is reachable only inside compose's network and never published; Prometheus scrapes it with no token; the API port serves no metrics. Grafana opens to anonymous Viewers. `ADMIN` keeps the admin actions and the dashboard's provider status page. (spec §6.6, the user's decision of 2026-11-15)
- **Boot's own mechanisms first:** Boot's `management.*` and `logging.*` settings and auto-configured beans wherever Boot has one, as plan 5 replaced its hand-written decoder with Boot's resource-server settings. (plan 5 Task 11 ruling; CLAUDE.md "existing mechanism")
- **Scope (§6.10):** the minimum plus the approved Collector, Tempo and Loki; JVM dashboards and exporters only in Task 8. Never Alertmanager, SLO burn-rate alerts or a trace id in error responses. No notification channel. (§6.10)
- **Low cardinality:** no metric tag or Loki label carries an origin, destination, carrier, itinerary id, user or client address; trace, span and correlation ids are structured metadata, never labels. (§6.10)
- **Secrets:** tokens and keys are never logged (plan 5), and now never exported in a span or a log record either. No account or password is committed for Grafana. (§6.6; plan 5 Global Constraints; the user's decision on Keycloak's administrator, 2026-11-15)
- **Compose:** `docker compose up --build` starts everything with no manual step; configuration comes from files under `deploy/`, mounted read-only; every new service has a health check; ports are published on `127.0.0.1` only, and only those Q4 lists. (§3; §6.10; compose convention)
- **Telemetry never in the request path:** the app does not wait for any observability service; an exporter that cannot send drops, never blocks or fails a request or a run. (§6.5: search depends only on PostgreSQL)
- **Alert semantics (binding):** NaN or a missing gauge counts as stale; rules combine instances (max of failures in a row, min of freshness); a restarted instance's gauges read NaN until its first run. (plan 4 close-out)
- **Hexagon:** `application` and `domain` stay free of Micrometer and OpenTelemetry; ArchUnit's rules hold. (§6 ports; ArchUnit rule 2)
- **Gates:** TDD one test at a time, red → green evidence in `docs/planning/progress/<date>.md`, section "Observability and delivery run (plan 7)"; a configuration file's test is shown red before the file exists; an end-to-end test over code that already exists is shown red by mutation. Before every commit: `./gradlew build`, `npm run verify` in `frontend/` when it changes, and the thermo-nuclear self-review. Scope by `brief-scope`; extras found mid-task go to the progress report. English everywhere; no AI attribution; push only after `direnv exec . gh api user --jq .login` prints `dromero001`, then watch the CI run. (CLAUDE.md)

## Open questions for the user

**Q1. Explore for the anonymous Viewer.** The team opens Grafana as anonymous Viewers (§6.6), and the approved jump from a log line to its trace (§6.10) happens in Explore, which Grafana closes to Viewers by default.
- A. Anonymous Viewer plus Grafana's `viewers_can_edit` setting: Viewers may use Explore and change a panel on screen, but can never save; the provisioned dashboards stay read-only. The jump works as Grafana designs it.
- B. Strict Viewer, with the jump built into the dashboard: a logs panel whose trace link reloads the dashboard with that trace id, and a trace panel that shows it. Nothing beyond the Viewer role, but two more panels to build and test, and logs are searchable only through that panel.
- C. Anonymous Editor: Explore with no extra setting, but anyone who reaches Grafana can also create and save dashboards.
- **Recommendation: A.** It is the smallest step beyond the Viewer role that keeps the approved jump, and nothing a Viewer changes can be saved. If the pinned Grafana no longer honours the setting, Task 1 stops and asks. Tasks: 1, 7.

**Q2. How the logs reach Loki** (§6.10: logs searchable in Grafana, with a jump to the trace).
- A. The app exports its log records over OTLP to the Collector (OpenTelemetry's Logback appender, installed on Boot's `OpenTelemetry`), which forwards them to Loki's OTLP endpoint. The console keeps its ECS JSON for `docker compose logs`. Trace and span ids travel as fields of each record.
- B. The Collector tails the `app` container's Docker log files and parses the ECS JSON. No change in the app, but on the Mac and on Windows those files live inside Docker Desktop's VM, so the mount differs per machine.
- C. Grafana Alloy, a new service, reads every container's output through the Docker socket. It covers all containers, but it is a service the user has not approved, and the socket gives control of the host's Docker.
- **Recommendation: A.** Traces and logs take one protocol through the approved Collector; no host path or socket, so it runs the same on both machines; the trace context is structured instead of parsed from text. Cost: one library if Boot's BOM does not manage the appender (Task 5 checks). Tasks: 1, 5.

**Q3. Which alerts** (§6.5, §4.3, §6.10; the plan 4 close-out note has the rules use min freshness and max failures in a row).
- A. The three the spec names, `IngestionFailing` (§6.5) and `PartitionHorizonShort` and `RowsInDefaultPartition` (§4.3), plus `ProviderDataStale` per provider.
- B. The three the spec names only; a provider's freshness stays a metric and a dashboard panel.
- **Recommendation: A.** A run with one fresh provider resets the failure count, so with B a provider down for a whole day raises no alert, only a WARN line per run and a growing gauge; the close-out note already counts on a freshness rule. Cost: one rule and three promtool cases. Tasks: 3, 6.

**Q4. Values the spec leaves open.**

| Setting | Recommended | Why |
|---|---|---|
| Failed runs in a row before `IngestionFailing` (N, §6.5) | 3 | One failed run is a blip that the breakers and the kept offers absorb; three is 90 minutes, three times the cadence (§6.4) |
| Age for `ProviderDataStale` (Q3-A) | 90 min (5,400 s) | The same three cadences |
| `PartitionHorizonShort` | below 34 days | 37 just after a run (plan 4 R11); 34 means three days of runs missed, with four of the seven spare days left (§4.3) |
| `RowsInDefaultPartition` | above 0 | §4.3 verbatim |
| Wait before a NaN or missing gauge fires | 15 min | A normal restart reads NaN well under a minute (the first run takes ~15 s); a crash whose lock lasts up to 29 minutes (plan 4 close-out) does fire |
| Wait before a value condition fires | none (`for: 0m`) | Each condition already spans several runs or days |
| Trace sampling | 100% | Boot samples 10% by default, so the run the demo shows could go untraced; the volume is a demo's |
| Scrape and rule evaluation interval | 15 s | Panels and alerts react within a minute |
| Management port | 8079, on compose's network only | Next to the API's 8080 and free in the container |
| Published ports | Grafana `127.0.0.1:3001`, Prometheus `127.0.0.1:9090` (its alerts page); the Collector, Tempo, Loki and the management port not published | Loopback like every service; 3000 is the dashboard |
| Telemetry storage | no volumes: kept while the containers live | A demo stack; `docker compose down` resets it, as it resets Keycloak |
| Cut-off for the optional work | Task 8 starts only if Tasks 1–7 and 9 are approved by 14:00 on Monday; Task 11 only if Task 10 ends by 17:00 | Task 10's reviews, fix round and clean clone need about three hours at the measured pace (roadmap) |

- A. These values. B. Change any row. **Recommendation: A.** Tasks: 1–5, 8, 11.

## Decisions this plan takes

| Decision | Why | Source |
|---|---|---|
| Every endpoint on the management port is open: health, metrics and the Prometheus scrape | Metrics are for the team, and the unpublished port is the boundary; nothing else is exposed, and none of the three holds a secret | (spec §6.6, the user's decision of 2026-11-15) |
| The API port keeps `/actuator/health` through a health group's additional path on the server | Plan 5's health, compose's health check and the orchestrator's probe stay as they are | (spec §6.6; plan 5 Tasks 11–12) |
| Prometheus finds the app's replicas through compose's DNS | Each replica is a target with its own `instance`, which the rules combine | (plan 4 close-out; §6.2 several instances) |
| Grafana's login form and basic authentication are off, and no Grafana account is committed | Provisioning from files needs no account; Grafana's built-in administrator exists but cannot sign in, as Keycloak's console account was removed | (the user's decision on Keycloak's administrator, 2026-11-15; spec §6.6) |
| Grafana's tests give their own container a random administrator password | Listing datasources needs an administrator; the password lives only inside the test | (implied by Task 1's provisioning test) |
| Configuration under `deploy/<tool>/`, mounted read-only | As `deploy/keycloak/` | (repository convention) |
| Each new image is pinned once, in `ObservabilityImages`, which `ComposeFileTest` compares with `compose.yaml` | The tests run what compose runs, as for PostgreSQL | (§6.1; plan 4 R10) |
| The deployment files are tested in `./gradlew build` against their pinned images; bootstrap's test task declares `deploy/**` as inputs | The gate before every commit covers them, and a change to one file alone reruns them | (CLAUDE.md "Before every commit"; plan 4 R10) |
| The Collector's core distribution | OTLP in, and OTLP out to Tempo and Loki, is all it needs | (minimum for §6.10's approved extras) |
| Only the application's telemetry is collected | The exporters are "only if time remains"; the mocks, nginx and Keycloak are not named | (§6.10) |
| The fan-out threads (ingestion, refresh, re-price, page downloads) are built from a `ThreadFactory` that carries the caller's context | The application may not use Micrometer, and a run must stay one trace | (§6 ports, ArchUnit rule 2; §6.10) |
| Provider requests are built from Boot's auto-configured `RestClient.Builder` | It already carries the observation registry and W3C propagation | (CLAUDE.md "existing mechanism"; §6.10) |
| Rules and panels combine instances the same way: max of failures and partition gauges, min of freshness | One meaning per signal | (plan 4 close-out) |
| Alerts are read in Prometheus's alerts page and Grafana's alert list (data-source rules, read-only) | No notification channel | (§6.10) |
| End-to-end checks are a Playwright project in plan 6's harness, run by CI's `e2e` job, before `rate-limit`; they read Tempo and Loki through Grafana's API as an anonymous Viewer | End to end means against compose; the rate-limit test spends the window; only Grafana and Prometheus are published | (§8; §6.11 step 5; plan 6 decisions; Q4) |
| `SOLUTION.md` summarises and links; it takes no new decision | The spec is the source of truth | (CLAUDE.md "Source of truth"; §1) |

## Out of scope

Alertmanager, SLO burn-rate alerts and a trace id in error responses (rejected, §6.10); tracing in the browser; load testing (§2 non-goal); publishing images (§6.11, rejected); resuming Dependabot (the user's step after the delivery, Task 10).

## Review Focus

1. **The Collector, Tempo or Loki down or slow** must leave the app starting, searching and ingesting at its normal speed; only telemetry is lost. Pinned by Task 4 `servesAndIngestsWithTheCollectorDown`.
2. **A gauge reading NaN after a restart, or missing because the app is down,** must not keep an alert quiet, while one restarted instance beside a healthy one must not fire it, and two instances are combined (max failures, min freshness). Pinned by Task 3's NaN, missing and two-instance cases.
3. **Metrics reachable from outside compose's network:** the management port published by a later compose edit, or the API port serving a metrics endpoint to an admin token. Pinned by Task 2 `neverServesMetricsOnTheApiPort` and `ComposeFileTest.neverPublishesTheManagementPort`.
4. **A trace, span or correlation id as a Loki label** would create a stream per run or request. Pinned by Task 7 `'keeps ids out of Loki's labels'`.
5. **A provider key or a bearer token in an exported span or log record,** now that telemetry leaves the process. Pinned by Task 4 `exportsNoSecretInAnySpan` and Task 5 `exportsNoSecretInAnyLogRecord`.

## Task map

| Task | Depends on | May run in parallel with | Starts before plan 6 ends |
|---|---|---|---|
| 1. Telemetry backends in compose | — | 2, 3, 4, 9 | yes |
| 2. Metrics on the management port | plan 5 closed (`SecurityConfig`, Task 12's health and admin endpoints) | 1, 3, 4, 9 | yes, once plan 5 is closed |
| 3. Alert rules (Q3, Q4) | 1's Prometheus image; 2's names (fixed below) | 2, 4, 5, 6, 9 | yes |
| 4. Traces | plan 5 closed (its fan-out services) | 1, 2, 3, 9 | yes, once plan 5 is closed |
| 5. Logs in Loki and the jump (Q2) | 1, 4 | 3, 6, 9 | yes |
| 6. The Grafana dashboard | 1, 2 | 3, 4, 5, 9 | yes |
| 7. End to end, and the observability docs | 1–6; plan 6 Task 10 | 9 | no |
| 8. Only if time remains (optional) | 7 | 9 | no |
| 9. `SOLUTION.md` 👤 | — to draft; plan 5 Task 13 and Task 7 for its figures | 1–8 | yes (draft) |
| 10. Delivery: reviews, README, clean clone 👤 | all | — | no |
| 11. A smaller image, E15 (optional) | 10 | — | no |

**Shared files, merged by hand** (a parallel task rebases on `main` before its review): `compose.yaml` (1, 2, 4, 5, 8; plans 5 and 6 edit it too), `application.yaml` (2, 4, 5), `gradle/libs.versions.toml` and `bootstrap/build.gradle.kts` (1, 2, 4, 5), `deploy/prometheus/prometheus.yaml` (1, 2, 3, 8), `deploy/grafana/provisioning/datasources/datasources.yaml` (1, 5), `GrafanaProvisioningTest` (1, 5, 6, 8), `ObservabilityImages` and `ComposeFileTest` (1, 2, 8), `README.md` (2, 7, 10), `frontend/playwright.config.ts` (7, after plan 6).

---

### Task 1: The telemetry backends in compose

**Files:**
- Create: `deploy/otel-collector/config.yaml`, `deploy/prometheus/prometheus.yaml`, `deploy/tempo/tempo.yaml`, `deploy/loki/loki.yaml`, `deploy/grafana/provisioning/datasources/datasources.yaml`; in `bootstrap/src/test/java/com/flightaggregator/bootstrap/deploy/`: `ObservabilityImages.java`, `CollectorConfigTest.java`, `PrometheusConfigTest.java`, `TempoConfigTest.java`, `LokiConfigTest.java`, `GrafanaProvisioningTest.java`
- Modify: `compose.yaml` (services `otel-collector`, `prometheus`, `tempo`, `loki`, `grafana`), `bootstrap/build.gradle.kts` (test inputs `deploy/**`), `bootstrap/src/test/.../ComposeFileTest.java`

**Interfaces:**
- Collector: OTLP receivers (gRPC 4317, HTTP 4318) on the compose network; pipelines traces → Tempo and logs → Loki's OTLP endpoint. (§6.10)
- Prometheus: `rule_files: [rules/*.yaml]` for Task 3 and the evaluation interval of Q4; Task 2 adds the scrape job. Tempo and Loki: single process, local storage, no volume (Q4). (§6.10)
- Grafana: datasources Prometheus, Tempo and Loki with the fixed uids `prometheus`, `tempo`, `loki`, provisioned from files and not editable; anonymous access with the Viewer role, as Q1 settles Explore; login form and basic authentication off; `127.0.0.1:3001`. (spec §6.6, the user's decision of 2026-11-15; §6.10; Q1; Q4)
- `ObservabilityImages`: one `String` constant per image (`COLLECTOR`, `PROMETHEUS`, `TEMPO`, `LOKI`, `GRAFANA`), tag and digest, as `PostgresImage.NAME`. (plan 4 R10)
- Every new service has a health check, and `app` does not depend on any of them. (§3; Global Constraints)

- [ ] **Step 1: images.** Pick the five by §6.1, record name, tag, digest and release date in the progress report. (§6.1)
- [ ] **Step 2: one test at a time, each red before its file exists:** `CollectorConfigTest.validatesTheCollectorConfiguration` (the image's `validate --config` exits 0); `PrometheusConfigTest.checksThePrometheusConfiguration` (`promtool check config`); `TempoConfigTest.startsWithTheRepositorysConfiguration` and `LokiConfigTest.startsWithTheRepositorysConfiguration` (each `/ready` answers 200); `GrafanaProvisioningTest.provisionsTheThreeDatasources` (`/api/datasources`, as the test's administrator, lists exactly the three uids); `GrafanaProvisioningTest.opensToAnAnonymousViewer` (`/api/user` without credentials answers the Viewer role; a dashboard save answers 403); `ComposeFileTest.runsTheObservabilityImagesTheTestsRun`. (§6.10; §6.6; §6.1)
- [ ] **Step 3: the inputs.** A mutation of `datasources.yaml` alone makes `GrafanaProvisioningTest` fail on a plain `./gradlew build`, without `--rerun-tasks`; restore. (plan 4 R10)
- [ ] **Step 4: run.** `docker compose up --build -d --wait`: every service healthy; `http://localhost:3001` opens with no login, and each datasource's health check passes. Record. (§3; §6.6)
- [ ] **Step 5: gates green, review, commit:** `git commit -m "Run the OpenTelemetry Collector, Prometheus, Tempo, Loki and Grafana in compose, provisioned from files"`. (CLAUDE.md)

### Task 2: Metrics on the management port, scraped by Prometheus (spec §6.6, the user's decision of 2026-11-15)

**Files:**
- Modify: `gradle/libs.versions.toml`, `bootstrap/build.gradle.kts` (`micrometer-registry-prometheus`, BOM); `application.yaml` (`management.server.port` per Q4; exposure `health,metrics,prometheus`; a health group whose additional path `server:/actuator/health` keeps the API port's health); `bootstrap/.../security/SecurityConfig.java`; `compose.yaml` (the management port never in `ports`); `deploy/prometheus/prometheus.yaml` (scrape job `app`); `README.md` ("Admin access": metrics are no longer on the API port); `security/SecurityTest.java`, `FlightAggregatorApplicationTest.java`, `ComposeFileTest.java`
- Create: `bootstrap/src/test/.../security/ManagementPortTest.java` (real sockets, `RANDOM_PORT` and management port 0, because MockMvc does not see the management port)

**Interfaces:**
- Management port: `/actuator/health`, `/actuator/metrics` and `/actuator/prometheus`, with no token; `SecurityConfig` permits every request that arrives on that port and keeps the API port's rules unchanged. (spec §6.6, the user's decision of 2026-11-15)
- API port: `/actuator/health` exactly as plan 5 left it (status for anyone, components for `ADMIN`, `DEGRADED` answering 200); no other Actuator endpoint; `/api/admin/**` keeps `ADMIN`. If Boot's additional path cannot keep it exactly, the task stops and asks. (spec §6.6; plan 5 Tasks 11–12)
- Prometheus: job `app` through `dns_sd_configs` on `app`, port 8079, every 15 s (Q4). (plan 4 close-out; Q4)
- **Produces (Tasks 3 and 6 rely on them):** the gauges `ingestion_consecutive_failures`, `ingestion_partitions_days_ahead`, `ingestion_partitions_default_rows` and `ingestion_provider_last_fresh_seconds{provider}`, each with `job` and `instance`; the counters and timers under Micrometer's Prometheus names, recorded by Step 4 and written into Task 6's test. (§6.10)

- [ ] **Step 1: `ManagementPortTest`, one test at a time:** `scrapesWithoutATokenOnTheManagementPort` (200, the body holds `ingestion_consecutive_failures`; red: not exposed); `neverServesMetricsOnTheApiPort` (with an `ADMIN` token, `/actuator/prometheus` and `/actuator/metrics` on the API port answer 404); `keepsTheApiPortsHealthAsPlanFiveLeftIt` (anonymous: status only; `ADMIN`: the components). (spec §6.6; Review Focus 3)
- [ ] **Step 2:** plan 5's MockMvc tests of `/actuator/metrics` move: their role cases are plan 5 Task 12's `adminEndpointsNeedTheAdminRole` already, so they are removed; the health cases move to `ManagementPortTest`. `FlightAggregatorApplicationTest` pins the exposure and the port; `ComposeFileTest.neverPublishesTheManagementPort`. (spec §6.6; Review Focus 3)
- [ ] **Step 3:** README "Admin access": the admin endpoints are `/api/admin/**`; metrics are read in Grafana. (CLAUDE.md documentation)
- [ ] **Step 4: run.** `docker compose up --build -d --wait`: Prometheus's target `app` is up; after the first run `/api/v1/series` returns the four gauges with `job` and `instance`; from the host, port 8079 refuses the connection and `http://localhost:8080/actuator/prometheus` answers 401. Record every meter name as stored. (spec §6.6; §6.10)
- [ ] **Step 5: gates green, review, commit:** `git commit -m "Serve the metrics on a management port inside compose and let Prometheus scrape them"`. (CLAUDE.md)

### Task 3: The alert rules, tested with promtool (as Q3-A and Q4)

**Files:**
- Create: `deploy/prometheus/rules/ingestion.rules.yaml`, `deploy/prometheus/rules/ingestion.rules.test.yaml`, `bootstrap/src/test/.../deploy/AlertRulesTest.java`

**Interfaces** (group `ingestion`; each alert's English summary names what is wrong and, where it applies, the provider):
- `IngestionFailing`: the max over instances of `ingestion_consecutive_failures` is at least N, or the gauge is missing everywhere for the stale wait. (§6.5; §6.10; Q4; plan 4 close-out)
- `PartitionHorizonShort`: the max over instances of `ingestion_partitions_days_ahead` is below the threshold, or it is NaN or missing on every instance for the stale wait. (§4.3; Q4)
- `RowsInDefaultPartition`: the max over instances of `ingestion_partitions_default_rows` is above 0; an unknown count is `PartitionHorizonShort`'s, since both gauges read the same publication. (§4.3)
- `ProviderDataStale{provider}`: the min over instances of `ingestion_provider_last_fresh_seconds` for that provider is above the threshold, or it is NaN or missing on every instance for the stale wait. (Q3-A; plan 4 close-out)
- `AlertRulesTest.passesEveryRuleTest` runs `promtool check rules` and `promtool test rules` in `ObservabilityImages.PROMETHEUS` and fails with promtool's output. (§6.10; CLAUDE.md gate)

- [ ] **Step 1: `AlertRulesTest`** red: no rule files.
- [ ] **Step 2: promtool cases, one at a time, each red before the rule or branch that passes it:**
  - `IngestionFailing`: quiet at 2, fires at 3; fires with two instances at 3 and 0; quiet after 14 min with no series, fires after 15.
  - `PartitionHorizonShort`: quiet at 37 and at 34, fires at 33; quiet with one instance at 37 and one reading NaN (restarted); fires after 15 min of NaN on every instance.
  - `RowsInDefaultPartition`: quiet at 0, fires at 1 on any instance.
  - `ProviderDataStale`: Beta at 5,401 s fires for Beta only; quiet when another instance reads 60 s for Beta; fires after 15 min of NaN for Gamma on every instance. (Review Focus 2)
- [ ] **Step 3: gates green, review, commit:** `git commit -m "Alert on failed ingestions, the partition horizon, the DEFAULT partition and stale providers"`. (CLAUDE.md)

### Task 4: Traces: every search, re-price and ingestion run as one trace

**Files:**
- Modify: `application/.../service/IngestInventoryService.java`, `RefreshStaleRouteService.java`, `RepriceItineraryService.java` and their tests; `adapters/providers/.../ProviderHttp.java`, `PagedDownload.java`, `PagedProvider.java` and their tests; `bootstrap/.../config/ProvidersConfig.java`, `IngestionConfig.java`, and plan 5's refresh and re-price configuration; `bootstrap/.../ingestion/IngestionJob.java`; `application.yaml` (Q4's sampling, the OTLP traces endpoint); `compose.yaml` (`app`); `gradle/libs.versions.toml`, `bootstrap/build.gradle.kts` (Boot 4's OpenTelemetry starter and `io.micrometer:context-propagation`; tests: OpenTelemetry's SDK testing module; BOM)
- Create: `bootstrap/.../observability/ContextThreads.java`; tests `ContextThreadsTest.java`, `observability/TracingEndToEndTest.java` (on `IngestionEndToEndTest`'s PostgreSQL container and WireMock providers), `observability/TelemetryUnavailableTest.java`

**Interfaces:**
- `final class ContextThreads { static ThreadFactory virtual(ContextSnapshotFactory snapshots); }`: `newThread(r)` captures the calling thread's context and returns an unstarted virtual thread that runs `r` inside it. A thread-per-task executor calls `newThread` on the submitting thread, so each download or quote runs in the context of the code that submitted it. (§6.10 one run = one trace)
- `IngestInventoryService`, `RefreshStaleRouteService` and `RepriceItineraryService` take a `java.util.concurrent.ThreadFactory` and fan out through `Executors.newThreadPerTaskExecutor(threads)`; their tests pass `Thread.ofVirtual().factory()`. (ArchUnit rule 2)
- `ProviderHttp(ProviderEndpoint, ResilienceSettings, CircuitBreakerRegistry, RetryListener, Clock, RestClient.Builder http)`: `http` is Boot's auto-configured builder, cloned per provider, so each request is a client span and sends `traceparent`; its request factory, timeout and key header stay. `PagedDownload(Provider, ResilienceSettings, ThreadFactory pages)` runs its page loop on a thread of `pages`. (§6.10 W3C)
- A scheduled run is observed by Spring (`@Scheduled`); `IngestionJob`'s one entry for scheduled and manual runs (plan 5 Task 12) opens an `Observation` named `ingestion.run`, so a manual run is a trace too. Its timer is denied by a `MeterFilter`: `ingestion.duration` already times runs. (§6.10; §6.4)
- Metrics keep one path: if the starter brings Micrometer's OTLP registry, Boot's setting turns its export off, since Prometheus scrapes. (spec §6.6, the user's decision of 2026-11-15)
- A side effect, not a requirement: the breakers' transition lines, logged on a download's thread, now carry the run's trace id; the run id on them stays out. (user, plan 4 final fix wave extras)

- [ ] **Step 1: `ContextThreadsTest.runsTheTaskInTheContextOfTheThreadThatCreatedIt`** (an observation opened on the test thread is current inside the new thread; red with `Thread.ofVirtual().factory()`). (§6.10)
- [ ] **Step 2: one test at a time:** `IngestInventoryServiceTest.downloadsOnThreadsOfItsFactory` (a counting factory, one thread per provider); `RefreshStaleRouteServiceTest.downloadsOnThreadsOfItsFactory`; `RepriceItineraryServiceTest.quotesOnThreadsOfItsFactory`; `PagedDownloadTest.fetchesPagesOnAThreadOfItsFactory`. (§6.10)
- [ ] **Step 3: `TracingEndToEndTest` (tracing on, an in-memory span exporter), one test at a time:** `anIngestionRunIsOneTrace` (every WireMock request carries a `traceparent` of one trace id, the `ingestion.run` span's; red: `RestClient.builder()` sends none); `aManualRunIsOneTraceToo`; `aSearchContinuesTheCallersTrace` (`traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01` → the server span's trace id is `4bf92f3577b34da6a3ce929d0e0e4736`); `aRepriceIsOneTraceWithItsQuotes`; `exportsNoSecretInAnySpan` (no attribute holds a provider key or the bearer token of an admin call); `pushesNoMetrics` (no meter registry exports over OTLP). (§6.10; §6.6; Review Focus 5)
- [ ] **Step 4: `TelemetryUnavailableTest.servesAndIngestsWithTheCollectorDown`** (every OTLP endpoint on a closed port: the context starts, a run publishes, a search answers 200 within 1 s). (Review Focus 1)
- [ ] **Step 5: run.** In compose, Grafana's Tempo datasource shows the startup run as one trace with a client span per provider page. Record. (§6.10)
- [ ] **Step 6: gates green, review, commit:** `git commit -m "Trace every search, re-price and ingestion run end to end with W3C trace context"`. (CLAUDE.md)

### Task 5: Logs in Loki, with the jump from a line to its trace (as Q2-A)

**Files:**
- Modify: `gradle/libs.versions.toml`, `bootstrap/build.gradle.kts` (OpenTelemetry's Logback appender, by §6.1 if the BOM does not manage it), `application.yaml` (the OTLP logs endpoint), `compose.yaml` (`app`), `deploy/grafana/provisioning/datasources/datasources.yaml` (Loki's derived field), `GrafanaProvisioningTest.java`
- Create: `bootstrap/.../observability/LogExport.java` only if Boot does not install the appender itself; tests `observability/StructuredLogsTest.java`, `observability/LogExportTest.java`

**Interfaces:**
- Each console line (ECS JSON, format unchanged) and each exported record carries `trace.id` and `span.id` when written inside a span, and `correlation.id` inside a run; level, logger, message and key-value pairs are kept, so a provider failure or a discarded record still names its provider and reason. (§6.10; plan 4)
- Loki's stream labels are the service name and the level only; trace, span and correlation ids are structured metadata. (Global Constraints; Review Focus 4)
- Grafana's Loki datasource has a derived field from the `trace_id` metadata to the datasource uid `tempo`. (§6.10 approved extra: the jump)

- [ ] **Step 1: `StructuredLogsTest.everyLineOfARunCarriesItsTraceSpanAndCorrelationIds`** (console output captured during a run, each JSON line parsed). (§6.10)
- [ ] **Step 2: `LogExportTest` (an in-memory log record exporter), one test at a time:** `exportsARunsLinesWithTheirTraceContext` (the run's INFO line carries the `ingestion.run` span's trace id and the `correlation.id` attribute; red: no appender); `exportsNoSecretInAnyLogRecord`. (§6.10; Review Focus 5)
- [ ] **Step 3: `GrafanaProvisioningTest.linksALogLineToItsTrace`** (the Loki datasource's derived field targets uid `tempo`). (§6.10)
- [ ] **Step 4: run.** In Grafana, as the anonymous Viewer, `{service_name="flight-aggregator"}` shows the run's lines, and the trace link on one opens the run's trace. Record. (§6.10; Q1)
- [ ] **Step 5: gates green, review, commit:** `git commit -m "Send the application's logs to Loki with their trace context and link each line to its trace"`. (CLAUDE.md)

### Task 6: The Grafana dashboard

**Files:**
- Create: `deploy/grafana/provisioning/dashboards/dashboards.yaml` (a file provider, `allowUiUpdates: false`), `deploy/grafana/dashboards/flight-aggregator.json`
- Modify: `GrafanaProvisioningTest.java`

**Interfaces:** one dashboard, uid `flight-aggregator`, title "Flight aggregator", read-only, with these panels and names from Task 2:

| Panel | Shows | Source |
|---|---|---|
| Provider calls | rate of `provider.calls` by provider and outcome | (§6.5; §6.10) |
| Circuit breakers | each provider's breaker state | (§6.5) |
| Data freshness | min over instances of each provider's age | (§6.5; plan 4 close-out) |
| Ingestion | runs by outcome, duration against the 30-minute cadence, failures in a row (max) | (§6.4; §6.5) |
| Cache hit ratio | hits over all lookups of `search.cache`, errors included, since an error is served from PostgreSQL like a miss | (§6.2; plan 5 Task 5) |
| Discarded records | increase by provider and reason | (§6.7) |
| Partitions | days ahead (max) and rows in `DEFAULT` (max) | (§4.3) |

- [ ] **Step 1: `GrafanaProvisioningTest.loadsTheDashboardWithItsSevenPanels`** (titles exactly as above; red: no dashboard). (§6.10)
- [ ] **Step 2: `everyPanelQueriesAKnownMetric`** (each expression names a metric of Task 2's recorded list, kept as a constant in the test); red by mutation (one metric misspelt), restored. (§6.10)
- [ ] **Step 3: run.** After two identical searches and a run, every panel shows data to the anonymous Viewer. Record, with a screenshot for the user kept out of git. (§6.10; §6.6)
- [ ] **Step 4: gates green, review, commit:** `git commit -m "Provision one Grafana dashboard with every metric the spec promises"`. (CLAUDE.md)

### Task 7: The observability proven end to end, and its documentation

**Files:**
- Create: `frontend/e2e/observability.spec.ts`, `frontend/e2e/telemetry.ts`, `docs/architecture/observability.md`
- Modify: `frontend/playwright.config.ts` (project `observability`, after `admin` and before `rate-limit`), `README.md` (section "Observability"; the index), `docs/architecture/README.md`, `docs/planning/design-spec.md` (§6.10 records the answers to Q1 and Q2)

**Interfaces:** `telemetry.ts`: `adminToken(): Promise<string>` (the README's client-credentials request), `grafanaQuery(datasourceUid: string, query: object)`, `tempoTrace(traceId: string)`, `lokiLines(query: string)`, each through Grafana's API with no credentials and polling up to 60 s. (spec §6.6; Q4)

- [ ] **Step 1: one test at a time, each shown red once by mutation:** `'shows a search as one trace'` (a `traceparent` the test chooses is found in Tempo; mutation: sampling 0); `'shows a manual run as one trace with every provider request'` (`POST /api/admin/ingestions`, then the run's INFO line in Loki gives the trace id, whose trace in Tempo has spans of Alpha, Beta and Gamma requests); `'jumps from a log line to its trace in Grafana'` (browser with no login: Loki's lines, expand the run's line, follow the Tempo link, the trace view shows `ingestion.run`; mutation: the derived field's uid); `'keeps ids out of Loki's labels'` (Loki's label list holds none of `trace_id`, `span_id`, `correlation_id`); `'loads the alert rules, none firing on a healthy stack'` (Prometheus `/api/v1/rules`); `'feeds every dashboard panel'` (each panel's query returns a series after two identical searches). (§6.10; §6.6; §8; Review Focus 4)
- [ ] **Step 2: docs.** `observability.md`: the pipeline (a Mermaid diagram, as the architecture overview uses), each signal and its path, the management port and who reads what (spec §6.6), the names as Prometheus stores them (linking the meter table in `ingestion.md`, not copying it), the rules with their NaN, missing and multi-instance meaning, the dashboard, the jump, what is not collected, and how each part is tested. README "Observability": Grafana's and Prometheus's addresses and three steps to see a run as a trace. Spec §6.10 and the indexes. (CLAUDE.md documentation blocks)
- [ ] **Step 3: gates green (`npm run verify`, `./gradlew build`), review, commit:** `git commit -m "Prove traces, logs, alerts and the dashboard end to end and document the observability"`; push and watch CI's `e2e` job, recording its duration against its 30-minute limit. (CLAUDE.md; §6.11)

### Task 8: Only if time remains: JVM dashboard, PostgreSQL and Valkey exporters (optional)

**Starts only by Q4's cut-off; each part is its own commit, in this order, and the task stops at the cut-off.** (§6.10 "only if time remains"; Q4)

**Files:** `deploy/grafana/dashboards/jvm.json`; `compose.yaml` (the exporters, unpublished); `deploy/prometheus/prometheus.yaml` (their scrape jobs); `ObservabilityImages.java`, `ComposeFileTest.java`, `GrafanaProvisioningTest.java`, `frontend/e2e/observability.spec.ts`

- [ ] **Step 1: JVM dashboard** from the `jvm.*` meters Boot already binds: `GrafanaProvisioningTest.loadsTheJvmDashboard`; the e2e `'feeds every dashboard panel'` covers it. Commit. (§6.10)
- [ ] **Step 2: PostgreSQL exporter** (image by §6.1, the database's existing user and overridable password), with its panels on the JVM dashboard; the same two checks. Commit. (§6.10; §6.6 demo defaults)
- [ ] **Step 3: Valkey exporter,** likewise. Commit. (§6.10)

### Task 9: `SOLUTION.md` 👤

**Files:** Create `SOLUTION.md`. (§2; §1)

**Interfaces** (one section per item; each paragraph gives the decision, why, the alternative rejected and a link to its spec section, and each figure cites its progress report):
- Architecture: modules, ports, flows, linking `docs/architecture/`. (§2)
- Modelling: legs and offers, deduplication, itinerary identity, price and its half-up rounding, freshness, the airport catalogue, the closed provider set. (§2; §4; §11)
- Ingestion and freshness: the hybrid model, double-buffered slots, daily partitions, the stale-route refresh, the cadence, with plan 4's measured run. (§4.1; §4.3; §5.1; §6.4)
- Pagination at 1M: keyset, one index per sort order, the cross-partition merge, the cursor and its generation, with plan 5 Task 13's `EXPLAIN` figures. (§2; §4.3; §5.4; §6.3)
- Caching, outages, re-price, security, observability: each decision in a paragraph, with the measured hit ratio, the honest note on Valkey, and who reads the metrics. (§6.2; §6.3; §6.5; §6.6; §6.10)
- Testing and quality gates: TDD, ArchUnit, the coverage and formatting gates, end to end, CI. (§8; §6.11)
- Assumptions (at most 2 stops, Carrier on every leg, ISO-8601 with offset, the rolling 30-day window, and those of sessions 01–02) and limitations (local times from real GDSs, §4.2; the mock dataset fixed at startup, plan 3; committed demo secrets, §6.6; unversioned `provider_offer`, plan 4; the restart gap and per-instance counters, plan 4 close-out; metrics open to whoever reaches Grafana, which production would put behind the company's sign-in, §6.6). (§2)
- AI usage: the process (brainstorming, grill-me, `writing-plans`, subagents with reviewers, the change of course), `CLAUDE.md`, skills, hooks and the sessions, linking `docs/ai/` and `docs/ai/sessions/`; what the reviews caught, such as the outdated first stack. (§2; §9)

- [ ] **Step 1: draft** with the `writing-clearly-and-concisely` skill (as `docs/ai/README.md` lists it for this file). (§1)
- [ ] **Step 2: check.** Every §2 item has its section; every relative link resolves (each `](…)` target exists); each figure matches its progress report. Record. (§1 "justifies every decision")
- [ ] **Step 3: the user reviews it (👤);** fixes; gates green, review, commit: `git commit -m "Write SOLUTION.md: architecture, modelling, pagination at 1M, assumptions, limitations and AI usage"`. (§7 Monday)

### Task 10: Delivery: the final reviews, the README and the clean-clone check 👤

**Files:** Modify `README.md` (final), `docs/planning/roadmap.md` (plan 7 done), `docs/planning/progress/<date>.md` (plan 7's close-out), `docs/ai/sessions/` and the README's sessions index (a record for the work since session 09, if none exists yet). (CLAUDE.md; §9)

- [ ] **Step 1: end of plan 7.** The three reviewers on the plan's whole diff; findings fixed or accepted by the user, recorded with the verdicts. (CLAUDE.md review protocol)
- [ ] **Step 2: delivery review of the whole repository:** the `thermo-nuclear-code-quality-review` skill and a correctness review (`/code-review`); findings fixed, or accepted by the user (👤), recorded. (§3; §9)
- [ ] **Step 3: README final:** the "Work in progress" note replaced by a link to `SOLUTION.md`; every published service with its address (dashboard 3000, API 8080, Keycloak 8180, Grafana 3001, Prometheus 9090, mocks 8081); admin access; observability; the index complete (this plan, `observability.md`, `SOLUTION.md`, every progress report and session). (§7 Monday; CLAUDE.md)
- [ ] **Step 4: full verification:** `./gradlew build --rerun-tasks`, `npm run verify`, `docker compose down -v`, `docker compose up --build -d --wait`, `npm run e2e`; record test counts and coverage. (§3; §8)
- [ ] **Step 5:** push after the login check; the CI run of the final head is green, and it is the second architecture (a fresh checkout on amd64 Linux). (CLAUDE.md; §6.11)
- [ ] **Step 6: clean clone on the Mac.** Stop the working copy's stack (`docker compose down`, keeping its volumes); `git clone https://github.com/dromero001/flight-aggregator.git` into a new empty directory (its own compose project, so no volume is reused); `docker compose build --no-cache`, so no cached layer hides a missing file; then exactly the README's `docker compose up --build`, and the README's steps as written: a search in the dashboard within minutes, the admin sign-in, an admin call with a client-credentials token, Grafana's dashboard with no login and a jump from a log line to its trace; `npm ci`, `npx playwright install chromium` and `npm run e2e` from the clone; `docker compose down -v`. Anything the clone lacks is a defect fixed before delivery. Record. (§1; §7 "everything working from a clean clone")
- [ ] **Step 7 (👤):** the repository is public (`gh repo view dromero001/flight-aggregator --json visibility`; changing it is the user's step); the user's demo walkthrough on the clean clone; the user sends the URL. (§7 Monday "demo, push"; the spec's delivery line)

**Notes:** Dependabot stays paused through the delivery; resuming it afterwards (`open-pull-requests-limit` back to its designed value) is the user's step, not a task. (§6.11)

### Task 11: Optional, at the very end: a smaller application image (E15)

**Only with time for the whole re-verification before Q4's cut-off.** (user, plan 4's extras: "optional at the very end, only with a full re-verification")

**Files:** Modify `bootstrap/Dockerfile`.

**Interfaces:** the same behaviour: user 10001, ports 8080 and 8079, the heap from compose, compose's health check unchanged; a runtime built with `jlink` from the modules `jdeps` reports for the jar, or the layered jar if `jlink` cannot serve it. (E15: "jlink or the layered jar could shrink it")

- [ ] **Step 1:** record today's image size (`docker image ls`). (E15)
- [ ] **Step 2:** the change; record the new size. It stays only if smaller and Step 3 passes. (E15)
- [ ] **Step 3: full re-verification:** Task 10 Steps 4–6 again (suite, compose and e2e, push and CI, clean clone). (user, plan 4's extras)
- [ ] **Step 4:** a failure not fixed before the cut-off reverts the commit, so the delivered image is never half changed; otherwise gates green, review, commit: `git commit -m "Shrink the application image with a jlink runtime"`. (CLAUDE.md; Global Constraints)

## ⚠ Extras — your call

- **E1. A link from a trace to its logs** (Tempo's datasource pointing at Loki). Value: the reverse jump, from a slow span to its lines. Cost: a few provisioning lines and one e2e step. Left out unless you approve.
- **E2. Database spans in traces** (`datasource-micrometer`, outside Boot's BOM). Value: a search's trace shows its SQL and its time. Cost: one dependency by §6.1, its settings and one test. Left out unless you approve.
- **E3. The background refresh as one trace** (an observation on its thread). Value: a refresh's provider requests are grouped instead of one trace per request. Cost: a few lines and one test. Left out unless you approve.
- **E4. A logs panel and an alert list on the dashboard.** Value: the logs and the alerts one click from the dashboard. Cost: two panels and their e2e checks. Left out unless you approve.
- **E5. An HTTP panel** (search and re-price rate, errors and latency, from `http.server.requests`). Value: the API's behaviour in the demo. Cost: one panel. Left out unless you approve.
- **E6. Exemplars** (from a point on the duration panel to its trace). Value: metrics → traces, the third jump. Cost: Prometheus's exemplar storage, histogram settings, a datasource link. Left out unless you approve.
- **E7. Telemetry of `mock-gds`.** Value: traces cross into the providers, as in a real distributed system. Cost: the starter and settings in `mock-gds`, one test. Left out unless you approve.
- **E8. A test that every compose image is pinned by tag and digest.** Value: the build enforces §6.1 for every image, not only the observability ones. Cost: one test in `ComposeFileTest`. Left out unless you approve.
- **E9. The clean-clone check on the Windows PC too.** Value: proves Docker Desktop on Windows, the second machine. Cost: 30–45 minutes of your Monday. Left out unless you approve.
- **E10. A tag on the delivered commit** (`v1.0.0`). Value: the reviewer sees exactly what was delivered, whatever is pushed later. Cost: one command. Left out unless you approve.
