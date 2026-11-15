# Progress — plan 7 (observability and delivery), 2026-11-15

## Task 1 — evidence

The telemetry backends in compose: the OpenTelemetry Collector, Prometheus, Tempo, Loki and Grafana, each configured from a file under `deploy/` mounted read-only, each file tested inside `./gradlew build` against the pinned image that runs it.

### Images (spec 6.1)

Rule applied: the newest stable GA whose release is on or before 2026-11-01 (at least 14 days before today, 2026-11-15), no release candidate; the tag's current multi-arch index digest is pinned, as plan 6 Task 9 ruled for `node` and `nginx`.

| Image | Tag | Released (GitHub release page) | Tag pushed (Docker Hub) | Multi-arch index digest | Too new |
|---|---|---|---|---|---|
| `otel/opentelemetry-collector` (core distribution) | `0.164.0` | 2026-10-26 (20 days) | 2026-10-26 23:53 UTC | `sha256:28b6d41ac51bff6b41ba5e8fe503569ffb9d6a1ea90f7a78419e86f34e44f750` | 0.165.0 (2026-11-07) |
| `prom/prometheus` | `v3.15.1` | 2026-10-21 (25 days) | 2026-10-21 14:08 UTC | `sha256:fa3302279710be2f21a8301ec17a8b4f2301fd829bd113839b3b6b02b6f191a2` | 3.16.0 (2026-11-04) |
| `grafana/tempo` | `3.1.1` | 2026-10-21 (25 days) | 2026-10-21 16:01 UTC | `sha256:64e2fbd56d9c2b3412a4b430a58ea59e91a670b1e75f920a0c702213ad145044` | none (3.1.1 is the newest) |
| `grafana/loki` | `3.7.9` | 2026-10-20 (26 days) | 2026-10-20 22:50 UTC | `sha256:0db216b630ae1f348a1584c67f3cead8b4c1fc839169a39501db664744158e28` | 3.7.10 (2026-11-13); 4.0.0 has only release candidates |
| `grafana/grafana` | `13.3.0` | 2026-10-27 (19 days) | 2026-10-27 08:42 UTC | `sha256:488984b716c0862639f91a698adbe2862677a5a07bad42da5f03f3030883b205` | 13.3.1 (2026-11-10) |

How checked: the release lists of `open-telemetry/opentelemetry-collector-releases`, `prometheus/prometheus`, `grafana/tempo`, `grafana/loki` and `grafana/grafana` (`gh api repos/<repo>/releases`: tag, `published_at`, `prerelease`); Docker Hub's tags API (`tag_last_pushed`, `digest`); `docker buildx imagetools inspect <tag>` (the digest is the index's, which lists `linux/amd64` and `linux/arm64` for all five). The Collector has no 1.x distribution: its releases are numbered 0.x and none is marked pre-release, so 0.164.0 is its stable release. The five are pinned once, in `ObservabilityImages`, and `ComposeFileTest.runsTheObservabilityImagesTheTestsRun` keeps compose on the same strings.

### What the images showed (checked before writing the files)

- **The Collector's image holds only its binary** (`/otelcol`, on `scratch`): no shell and no HTTP client. Its core distribution has the `health_check` extension, but nothing inside the container can call it, and `otelcol` has no probe command (`validate`, `components`, `print-config`, `featuregate` only). See "Pending decision" below.
- **Tempo and Loki are distroless, with no shell**, but each binary has a probe flag: `/tempo -health` and `/usr/bin/loki -health` request `/ready` and exit 1 when it does not answer (seen against a stopped container: `Health check failed: Get "http://localhost:3100/ready": ... connection refused`, exit 1). Loki's own compose example at `v3.7.9` uses the same check. Prometheus (BusyBox) and Grafana (Alpine) have `wget`.
- **Tempo 3 runs as one process with local storage and no Kafka** (3.0.0 release notes: "Single-binary mode: push distributor local ingest directly to live-store and metrics-generator without Kafka"; `example/docker-compose/single-binary/tempo.yaml` at `v3.1.1`). Its non-root user (10001) may write only under `/tmp`, so the WAL and blocks live there.
- **Prometheus's image declares `VOLUME /prometheus`**, so Docker gives the container an anonymous volume (see "Concerns").
- **Grafana 13.3.0 still honours `viewers_can_edit` (Q1-A holds; no stop needed).** The setting is deprecated: Grafana logs `[Deprecated] The viewers_can_edit configuration setting is deprecated. Please upgrade viewers to editors.` It still grants Viewers `datasources:explore` (`pkg/api/accesscontrol.go` at `v13.3.0`, `FixedRoleRegistrations`: "when true the datasources explorer role also grants Viewer"), and a Viewer's dashboard save still answers 403. `opensExploreToTheAnonymousViewer` fails on the first Grafana that stops honouring it.
- **Grafana's `/api/user` answers 401 to an anonymous visitor**, so the test reads the visitor's role from `/bootdata`, which Grafana's own pages read (`"isSignedIn":false,"orgRole":"Viewer"`).
- **Grafana's open-source edition lets a Viewer list `/api/datasources`**, so the provisioning test needs no administrator (below, "Deviations").
- **The Collector 0.164 names its OTLP exporters `otlp_grpc` and `otlp_http`** (`otelcol components`); the configuration uses those names.

### Red → green, one test at a time (Step 2)

Every test is in `bootstrap/src/test/java/com/flightaggregator/bootstrap/deploy/`, except the last one. Each configuration test copies the repository's file into the pinned image, as compose mounts it. Failure messages are quoted from the JUnit XML reports.

1. **`CollectorConfigTest.validatesTheCollectorConfiguration`** (the image's `validate --config` exits 0; Testcontainers' `OneShotStartupCheckStrategy` fails `start()` otherwise). Red before `deploy/otel-collector/config.yaml` existed: `ContainerLaunchException: Container startup failed`, container log `Error: empty configuration file`. Green: the file (OTLP receivers on gRPC 4317 and HTTP 4318; traces to Tempo over OTLP gRPC, logs to Loki's `/otlp` over OTLP HTTP). Check that `validate` reads the pipelines: a mutated reference (`otlp_grpc/tempoo`) failed with `references exporter "otlp_grpc/tempoo" which is not configured`; restored.
2. **`PrometheusConfigTest.checksThePrometheusConfiguration`** (`promtool check config` of the same image; the whole `deploy/prometheus/` directory is copied, as compose mounts it, so Task 3's `rules/` will be checked too). Red before the file existed: `promtool: error: stat /etc/prometheus/prometheus.yaml: not a directory`. Green: `deploy/prometheus/prometheus.yaml` (15 s scrape and evaluation interval, Q4; `rule_files: [rules/*.yaml]`); promtool: `SUCCESS: /etc/prometheus/prometheus.yaml is valid prometheus config file syntax`.
3. **`TempoConfigTest.startsWithTheRepositorysConfiguration`** (`/ready` answers 200). Red before the file existed: the container exited 1 with `failed to create store: unknown backend`. Green: `deploy/tempo/tempo.yaml`; ready in 2.5 s.
4. **`LokiConfigTest.startsWithTheRepositorysConfiguration`** (`/ready` answers 200). Red before the file existed: the container exited 2 with `panic: runtime error: index out of range [0] with length 0`. Green: `deploy/loki/loki.yaml` (single process, filesystem storage under the image's `/loki`, TSDB schema v13); ready in 17 s.
5. **`GrafanaProvisioningTest.opensToAnAnonymousViewer`** (`/bootdata` without credentials gives the role `Viewer`; a dashboard save answers 403). The test starts Grafana with the environment of compose's `grafana` service, read from `compose.yaml`, so it runs compose's settings. Red before the service existed: `[service grafana in compose.yaml] Expecting actual not to be null`. Green: the `grafana` service with `GF_AUTH_ANONYMOUS_ENABLED` and `GF_AUTH_ANONYMOUS_ORG_ROLE: Viewer`. Labelled mutation (Q1's option C, an anonymous Editor): `expected: "Viewer" but was: "Editor"`; restored.
6. **`GrafanaProvisioningTest.opensExploreToTheAnonymousViewer`** (the anonymous visitor's permissions, `/api/access-control/user/permissions`, include `datasources:explore`; Q1-A). Red: the 30 permissions listed, without `datasources:explore`. Green: `GF_USERS_VIEWERS_CAN_EDIT: "true"`; `opensToAnAnonymousViewer` still sees the save refused with 403.
7. **`GrafanaProvisioningTest.letsNoOneSignIn`** (the built-in administrator, which keeps Grafana's default password because no account is committed, cannot sign in: basic authentication answers 401, the login form a 4xx). Red: `expected: 401 but was: 200` (`admin:admin` over basic authentication signed in). With only `GF_AUTH_BASIC_ENABLED: "false"`: `Expecting actual: 200 to be between: [400, 499]` (the login form signed in). Green: `GF_AUTH_DISABLE_LOGIN_FORM: "true"` (the form answers 400, `auth.client.notConfigured`).
8. **`GrafanaProvisioningTest.provisionsTheThreeDatasources`** (`/api/datasources` lists exactly the uids `prometheus`, `tempo`, `loki`, all read-only). Red before the file existed: `Expecting actual: [] to contain exactly in any order: ["prometheus", "tempo", "loki"]`. Green: `deploy/grafana/provisioning/datasources/datasources.yaml`. Labelled mutation (Loki `editable: true`): `Expecting ArrayList: [false, true, true] to contain only: [true]`; restored.
9. **`ComposeFileTest.runsTheObservabilityImagesTheTestsRun`** (compose's `otel-collector`, `prometheus`, `tempo`, `loki` and `grafana` run the images of `ObservabilityImages`). Red: `could not find the following map entries: ["prometheus"=..., "otel-collector"=..., "loki"=..., "tempo"=...]` (Grafana was already there from test 5). Green: the four services, each with its file mounted read-only, a health check (except the Collector, below), and no port published except Grafana's `127.0.0.1:3001` and Prometheus's `127.0.0.1:9090` (Q4). `app` depends on none of them.

### The inputs (Step 3)

- Red: after a green `./gradlew build`, a mutation of `datasources.yaml` alone (`uid: loki` to `uid: loki-mutated`) left `:bootstrap:test UP-TO-DATE` and the build green (`BUILD SUCCESSFUL in 1s`): the test task declared `compose.yaml` and the Keycloak realm file as inputs, not the rest of `deploy/`. Restored.
- Green: `bootstrap/build.gradle.kts` declares the whole `deploy/` directory as an input of the test task (it replaces the realm file's own line, which the directory covers). After a green build, the same mutation alone failed a plain `./gradlew build`, without `--rerun-tasks`: `GrafanaProvisioningTest > provisionsTheThreeDatasources() FAILED`, `Expecting actual: ["loki-mutated", "prometheus", "tempo"] to contain exactly in any order: ["prometheus", "tempo", "loki"]`. Restored.

### The run (Step 4)

`docker compose -p plan7-task1 up --build -d --wait`, the whole stack: exit 0, every service with a health check `healthy` (app, frontend, grafana, keycloak, loki, mock-gds, postgres, prometheus, tempo); `otel-collector` `Up`, with no health state (below). Published: Grafana `127.0.0.1:3001`, Prometheus `127.0.0.1:9090`; the Collector (4317, 4318), Tempo (3200) and Loki (3100) are not reachable from the host. Every configuration is a read-only bind mount.

- `http://127.0.0.1:3001/` answers 200 with no login; `/bootdata` gives `{"isSignedIn":false,"orgRole":"Viewer"}`.
- Each datasource's health check, as the anonymous Viewer (`/api/datasources/uid/<uid>/health`): Prometheus `Successfully queried the Prometheus API.`, Tempo `Data source is working`, Loki `Data source successfully connected.` (all `"status":"OK"`).
- Prometheus's alerts page (`/alerts`) answers 200.
- Smoke check of the two pipelines (not a test; Tasks 4, 5 and 7 test them): one OTLP span and one log record with the same trace id, posted to `otel-collector:4318` from inside the compose network, were found in Tempo (`/api/v2/traces/<id>`, span `probe`) and in Loki (`{service_name="plan7-probe"}`). Loki's only index label was `service_name`; `trace_id` and `span_id` were structured metadata.
- Logs: no error from Prometheus, the Collector or Grafana's provisioning; Tempo and Loki log only start-up noise (`metrics-generator is not configured`, `empty ring`, the backend scheduler's `no jobs found`).
- After a change to the Prometheus and Grafana health checks (from `127.0.0.1` to `localhost`: both listen on `[::]`), the five telemetry services were started again alone and were all healthy, with the three datasources `OK`.
- Stopped with `docker compose -p plan7-task1 down -v` (the project's own volumes, including Prometheus's anonymous one).

### Deviations from the plan's wording

- **`opensToAnAnonymousViewer` reads `/bootdata`, not `/api/user`:** Grafana 13.3.0 answers `/api/user` with 401 to an anonymous visitor (`{"message":"Unauthorized"}`).
- **`provisionsTheThreeDatasources` lists the datasources as the anonymous Viewer, not as an administrator, and no test sets an administrator password.** The plan's "Decisions" row ("Grafana's tests give their own container a random administrator password") was marked "implied by Task 1's provisioning test"; it is not needed, because Grafana's open-source edition lets a Viewer list datasources. It would also have needed basic authentication on in the test's container, unlike compose's. The Grafana test runs compose's own `grafana` environment, read from `compose.yaml`.
- **Two tests the plan does not name, both for lines of Task 1's own interface:** `opensExploreToTheAnonymousViewer` (Q1-A's "if the pinned Grafana no longer honours the setting, Task 1 stops and asks" made a check) and `letsNoOneSignIn` ("login form and basic authentication off"; the built-in administrator keeps Grafana's default password, `admin`, since no account is committed).

### Pending decision for the user: the Collector's health check

The plan asks for a health check on every new service (Global Constraints, Task 1's interfaces), and for the Collector's core distribution ("Decisions this plan takes"). The core image holds only `/otelcol` on `scratch`: no shell, no `wget` or `curl`, and no probe command, so compose has nothing to run inside it. Built for now as option A, with the reason in a comment in `compose.yaml`.

- **A. No health check for the Collector (as built).** Nothing waits on it: the app never depends on it (telemetry is never in the request path), and its exporters drop when it is down. `docker compose ps` shows it `Up` with no health state; `--wait` treats it as started.
- **B. A small image built by compose,** `FROM` the pinned Collector, adding a static probe binary (for example BusyBox's `wget`, from another pinned image) and the `health_check` extension (port 13133). Cost: one Dockerfile under `deploy/otel-collector/`, one more image pinned by §6.1, and `ComposeFileTest` comparing the Dockerfile's `FROM` instead of compose's `image:`.
- **C. Another distribution:** the contrib image is also `scratch`, so it does not help.
- Recommendation: **A**, since no service or check reads the Collector's health, and B adds a built image and a second pinned image only for that.

### Concerns

- **Prometheus's anonymous volume.** Its image declares `VOLUME /prometheus`, so Docker attaches an anonymous volume. The behaviour matches Q4 (a new container after `docker compose down` starts empty), but the old volume stays behind until `docker compose down -v` or `docker volume prune`; `compose.yaml` says so. Moving the data onto a `tmpfs` would leave no volume, but would also lose it on every container restart.
- **`viewers_can_edit` is deprecated** (Grafana 13.3.0 logs a deprecation notice). It works on the pinned version and `opensExploreToTheAnonymousViewer` fails on the first version that drops it; Q1-B (the jump built into the dashboard) is then the fallback the plan already describes.
- **The configuration tests take time:** `TempoConfigTest` and `LokiConfigTest` wait up to two minutes when a configuration is broken (Testcontainers keeps polling `/ready` after the container has exited). Green, the five classes add about 30 s to the suite.

### Extras for the user to decide

- **Turn off the usage reports to Grafana Labs** (Tempo's `usage_report.reporting_enabled: false`, Loki's `analytics.reporting_enabled: false`, Grafana's `GF_ANALYTICS_REPORTING_ENABLED` and `GF_ANALYTICS_CHECK_FOR_UPDATES` set to `false`). Value: the demo stack sends no anonymous statistics from a reviewer's machine. Cost: four lines, no test. Left out unless you approve.
- **A test that compose publishes none of the Collector's, Tempo's or Loki's ports, and Grafana's and Prometheus's only on `127.0.0.1`** (Q4). Value: a later compose edit cannot publish them unnoticed, as Task 2's `neverPublishesTheManagementPort` does for the management port. Cost: one test in `ComposeFileTest`. Left out unless you approve.

### Gates

- Self-review (thermo-nuclear) of the diff: the existing mechanisms are reused (`ComposeFileTest` and the `PostgresImage`/`KeycloakImage` pattern for the pins; Testcontainers' `OneShotStartupCheckStrategy` and HTTP wait strategy; each image's own probe, `-health` or `wget`; Gradle's task inputs, as plan 4 R10). Nothing passes silently when a file is missing: each test was seen failing without its file. Findings fixed: a comment in `compose.yaml` claimed Prometheus listened on IPv4 only (it listens on `[::]`), so both `wget` checks went back to `localhost`, like the repository's other checks. Kept as they are: each test class keeps its own `../deploy/...` path, as `RealmClientCredentialsTest` does, rather than a shared helper for one line; `GrafanaProvisioningTest` parses `compose.yaml` itself, as each `ComposeFileTest` test does.
- `./gradlew build`: `BUILD SUCCESSFUL` (exit 0), 724 tests across the modules, none failed or skipped; the new ones: `CollectorConfigTest` 1, `PrometheusConfigTest` 1, `TempoConfigTest` 1, `LokiConfigTest` 1, `GrafanaProvisioningTest` 4, `ComposeFileTest` 4 (one new). `frontend/` is not touched.
- Not built, and not assigned to Task 1 by the plan's text: the approved extras E1–E5 and E7–E10. E8 (every compose image pinned by tag and digest) would sit next to `runsTheObservabilityImagesTheTestsRun`, but no task names it.

## Task 1 — user decisions and E8

The user's decisions of 2026-11-15 on Task 1's open points (plan 7, Task 1, "Decided by the user on 2026-11-15, after the implementation"), and the controller's ruling that the approved E8 is built in this task.

### 1. The Collector's health check: A

The Collector stays without a health check, the one exception to the Global Constraints' rule. The comment in `compose.yaml` now says why, as decided: the core image is `/otelcol` on `scratch`, with no shell, HTTP client or probe command; nothing waits on the Collector; an exporter that cannot send to it drops what it holds. No test (a comment).

### Refactor first (green to green)

`ComposeFile` (bootstrap test package) now reads `compose.yaml` for every test that needs it: `ComposeFileTest`'s tests and `GrafanaProvisioningTest`'s compose environment, which had each parsed the file on their own. `TempoConfigTest` and `LokiConfigTest` now start their container once in `@BeforeAll`, so that a second test can share it; `startsWithTheRepositorysConfiguration` asserts that `/ready` answers 200. All green before and after.

### 2. E8: every compose image pinned by tag and digest

- **`ComposeFileTest.pinsEveryImageByTagAndDigest`**: every service's image matches `name:tag@sha256:<64 hex>`. For a service compose builds (app, frontend, mock-gds), the images are the `FROM` lines of its Dockerfile, except those naming an earlier stage; each service must yield at least one image. Passed on its first run (every image was already pinned, and none was repinned). Shown red by labelled mutations, each restored:
  - Tempo's digest removed in `compose.yaml`: `[image of tempo] Expecting actual: "grafana/tempo:3.1.1" to match pattern: "[^\s@]+:[^\s@:/]+@sha256:[0-9a-f]{64}"`.
  - nginx's digest removed in `frontend/Dockerfile`: `[image of frontend] Expecting actual: "nginx:1.30.5-alpine3.24" to match pattern ...`. The node stage, `AS build`, still matched.
- **The Dockerfiles as test inputs** (the same lesson as Step 3 and plan 4 R10).
  - Red: after a green `./gradlew build`, the nginx mutation alone left `:bootstrap:test UP-TO-DATE` and the build green (`BUILD SUCCESSFUL in 531ms`).
  - Green: `bootstrap/build.gradle.kts` declares `bootstrap/Dockerfile`, `mock-gds/Dockerfile` and `frontend/Dockerfile` as test inputs, with a comment that a service built from a new Dockerfile adds it there. After a green build, the same mutation alone failed a plain build: `ComposeFileTest > pinsEveryImageByTagAndDigest() FAILED`. Restored.
- The test checks the form only: that each digest is the multi-arch index's is checked when an image is pinned (`docker buildx imagetools inspect`), as recorded in each progress report.

### 3. No usage reports (extra approved)

Each setting is the one the pinned version documents, checked against the setting the running service reports wherever the service exposes it.

- **Tempo.** Tempo 3.1.1's configuration reference, section "Usage-report" (`docs/sources/tempo/configuration/_index.md` at `v3.1.1`): "By default, Tempo reports anonymous usage data ... to Grafana Labs", disabled with `usage_report: reporting_enabled: false`.
  - `TempoConfigTest.sendsNoUsageReport` reads the configuration Tempo runs with from `/status/config`, which answers its request line and then the configuration as a second YAML document.
  - Red: `["reporting_enabled"=true (expected: false)]`. Green: the setting in `deploy/tempo/tempo.yaml`.
  - A first run failed for the wrong reason (`expected a single document in the stream`); the test now reads the last YAML document.
- **Loki.** Loki 3.7.9's documentation, "Usage statistics" (`docs/sources/configure/usage-statistics.md` at `v3.7.9`): "Loki sends anonymous usage statistics to Grafana Labs ... To disable reporting in the Loki config file, set `analytics.reporting_enabled` to `false`".
  - `LokiConfigTest.sendsNoUsageReport` reads `/config`.
  - Red: `["reporting_enabled"=true (expected: false)]`. Green: the setting in `deploy/loki/loki.yaml`.
- **Grafana.** Grafana 13.3's "Configure Grafana" (`docs/sources/setup-grafana/configure-grafana/_index.md` at `v13.3.0`), `[analytics] reporting_enabled`: "When enabled Grafana sends anonymous usage statistics to `stats.grafana.org`". The same page documents environment overrides as `GF_<SECTION>_<KEY>`, so the variable is `GF_ANALYTICS_REPORTING_ENABLED`.
  - Grafana shows this setting only to an administrator: `/api/frontend/settings` and `/bootdata` do not follow it, as checked with and without the variable. Nobody can sign in as an administrator. So `GrafanaProvisioningTest.sendsNoUsageReport` checks compose's environment for `GF_ANALYTICS_REPORTING_ENABLED: "false"`.
  - A first version read Grafana's start-up log (`Config overridden from Environment variable`). Its red was `to contain: "var="GF_ANALYTICS_REPORTING_ENABLED=false""`, and it went green with the variable. It was replaced because Grafana logs every `GF_` variable it is given, even one it does not know (seen with a misspelled `GF_ANALYTICS_REPORTING_ENABLE`). The log therefore proved no more than compose's environment.
  - The replacement passed on its first run. Labelled mutation (the variable removed): `could not find the following map entries: ["GF_ANALYTICS_REPORTING_ENABLED"="false"]`; restored.
- Not included: Grafana's `check_for_updates`, a version check against grafana.com, not a usage report. The round-1 extra had listed it; the user's decision names the usage reports only.

### 4. Published ports (extra approved)

- **`ComposeFileTest.publishesOnlyGrafanaAndPrometheusOfTheTelemetryOnTheLoopback`**: Grafana publishes exactly `127.0.0.1:3001:3000`, Prometheus exactly `127.0.0.1:9090:9090`, and the Collector, Tempo and Loki publish nothing (Q4). Passed on its first run; shown red by labelled mutations, each restored:
  - Tempo published on `127.0.0.1:3200:3200`: `["tempo"=["127.0.0.1:3200:3200"] (expected: null)]`.
  - Grafana published on every interface (`3001:3000`): `["grafana"=["3001:3000"] (expected: ["127.0.0.1:3001:3000"])]`.

### Run and gates

- The five telemetry services were started again alone with the new settings (`docker compose -p plan7-task1 up -d --wait`). Four were healthy and the Collector `Up`; the three datasources were `OK`. Stopped with `down -v`.
- `./gradlew build`: `BUILD SUCCESSFUL` (exit 0), 729 tests across the modules, none failed or skipped. That is five more than round 1: `pinsEveryImageByTagAndDigest`, `publishesOnlyGrafanaAndPrometheusOfTheTelemetryOnTheLoopback` and the three `sendsNoUsageReport`.
- Self-review (thermo-nuclear):
  - One reader of `compose.yaml` (`ComposeFile`) replaces five copies of the parse.
  - The Dockerfile reader is about 15 lines in `ComposeFileTest`, its only user.
  - The ports test compares compose's short syntax literally, so a switch to the long syntax would need the test updated.
  - Kept: `TempoConfigTest` and `LokiConfigTest` stay two small classes with the same shape, as the plan names them, rather than a shared base class.
  - Fixed: the Grafana log check, replaced as above because it proved nothing more.
  - Nothing passes silently: every new test was seen red.

## Task 1 — round 3 (review fixes)

The task review (one reviewer, functional then code) answered: functional CHANGES REQUESTED (F1 Important; F2–F4 Minor), code APPROVED (C1–C6 Minor). The user decided F2–F4 (plan 7, Task 1, "Decided by the user on 2026-11-15, after the task's review"). C3–C5 are deferred to the end-of-plan review, as the controller ruled.

### F1: nobody signs in to Grafana, not even with the right password

- **`GrafanaProvisioningTest.letsNoOneSignIn` now uses a password that works.**
  - Before: the test tried `admin:admin`. Under the reviewer's scenario, a committed `GF_SECURITY_ADMIN_PASSWORD: demo` in place of `GF_AUTH_DISABLE_LOGIN_FORM` (so the form is back on), all five Grafana tests stayed green (shown on the old test).
  - Now: the test's own container gets a random administrator password (`GF_SECURITY_ADMIN_PASSWORD`, set after compose's environment, so it also replaces any committed one). Basic authentication and the form must both refuse that password.
  - Red under the reviewer's scenario: `Expecting actual: 200 to be between: [400, 499]` (the form signed in). Green with compose restored.
  - Second labelled mutation (`GF_AUTH_BASIC_ENABLED` removed): `expected: 401 but was: 200`; restored.
- **`ComposeFileTest.givesGrafanaNoAdministratorAccount`** sits beside Keycloak's check: the `grafana` service sets no `GF_SECURITY_ADMIN_*` variable, which covers the `__FILE` forms. Passed on its first run. Labelled mutation (`GF_SECURITY_ADMIN_PASSWORD__FILE` added): `Expecting no elements of: [..., "GF_SECURITY_ADMIN_PASSWORD__FILE", ...] to match given predicate`; restored.

### F2: Explore's query history off (the user's decision)

- Name checked on 13.3.0: `defaults.ini`'s `[query_history] enabled = true`, documented in "Configure Grafana", `[query_history]`. So the variable is `GF_QUERY_HISTORY_ENABLED`.
- **`GrafanaProvisioningTest.savesNoQueryHistory`**: an anonymous `POST /api/query-history` is refused.
  - Red: `Expecting actual: 200 to be between: [400, 499]`.
  - Green: `GF_QUERY_HISTORY_ENABLED: "false"`. Grafana now answers `404 {"message":"Not found"}` to both the save and the list.
  - Explore itself stays open: `opensExploreToTheAnonymousViewer` is green.

### F3: Prometheus on tmpfs (the user's decision)

- A first compose check (`keepsPrometheusDataOnTmpfs`: a `tmpfs` entry `/prometheus`) went red, then green with `tmpfs: [/prometheus]`. But the stack then showed Prometheus restarting with `open /prometheus/queries.active: permission denied`: the tmpfs is root's, and the image runs as `nobody`.
- The compose check proved only the file's shape, so a test that runs compose's settings replaced it:
  - **`PrometheusConfigTest.storesItsDataOnComposesTmpfsWithNoVolume`** starts the pinned Prometheus with compose's own `command` and `tmpfs` entries, read from `compose.yaml`. It expects `/-/ready` to answer within 30 s and no mount at `/prometheus`.
  - Red with the plain tmpfs: `Wait strategy failed. Container exited with code 1`; Prometheus's log: `create mmap-ed active query log: open /prometheus/queries.active: permission denied`.
  - Green: `/prometheus:uid=65534,gid=65534`, a tmpfs owned by the image's user.
  - Labelled mutation (the `tmpfs` entry removed): `Expecting no elements of: [InspectContainerResponse.Mount(name=0216…, source=/var/lib/docker/volumes/…` (the anonymous volume came back); restored.
- In compose (`-p plan7-task1`): Prometheus was healthy, and its only mount was the read-only `/etc/prometheus` bind. `HostConfig.Tmpfs` was `{"/prometheus":"uid=65534,gid=65534"}`, and inside the container `tmpfs on /prometheus ... uid=65534,gid=65534` held `chunks_head`, `wal` and `queries.active`. After `docker compose down` without `-v`, the project left no volume.
- The compose comment now says that the data lives in memory, that no volume is left behind, and that a restart loses the history.

### F4: Grafana's update checks off (the user's decision)

- **`GrafanaProvisioningTest.checksForNoUpdates`**: compose sets `GF_ANALYTICS_CHECK_FOR_UPDATES` and `GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES` to `"false"`. These are "Configure Grafana" settings `[analytics] check_for_updates` and `check_for_plugin_updates`. They are checked in compose for the same reason as `sendsNoUsageReport`: only an administrator can read them.
  - Red: `could not find the following map entries: ["GF_ANALYTICS_CHECK_FOR_UPDATES"="false"]`.
  - With only that variable added: `["GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES"="false"]`.
  - Green with both.

### C1: rule files, not their promtool tests

- **`PrometheusConfigTest.loadsTheRuleFilesButNotTheirPromtoolTests`** copies the directory, plus an `example.rules.yaml` (`groups: []`) and a promtool unit test, `example.rules.test.yaml`, into `rules/`. `promtool check config` must exit 0, and its output must say `SUCCESS: 1 rule files found` and `Checking /etc/prometheus/rules/example.rules.yaml`.
  - Red with `rules/*.yaml`: `Checking /etc/prometheus/rules/example.rules.test.yaml FAILED: ... field rule_files not found in type rulefmt.RuleGroups`.
  - Green: `rule_files: [rules/*.rules.yaml]`, with a comment in `prometheus.yaml`. This narrows the plan's literal `rules/*.yaml`, as the controller asked; Task 3's brief should name its files `*.rules.yaml` and `*.rules.test.yaml`.
  - Labelled mutation (`rules/*.rule.yaml`, a glob that matches nothing): `to contain: "SUCCESS: 1 rule files found"`; restored. So a glob that silently drops the rules fails too.
- The two promtool tests now share one builder, `promtoolCheckConfig()`.

### C2: the Dockerfile reader and continuation lines

- Red by labelled mutation: `bootstrap/Dockerfile`'s `RUN` line split so that its last continuation line holds only `--no-daemon`. `pinsEveryImageByTagAndDigest` failed with `java.util.NoSuchElementException`.
- Green: the reader keeps only lines that start with a `FROM` instruction (`\s*FROM\s`, any case) before it drops the flags. With the same mutation the test passes; with an unpinned nginx `FROM` it still fails (`[images of frontend]`). Both mutations restored.

### C6: Dependabot's comments

`.github/dependabot.yml` now says that compose pins every image it pulls by tag and digest, and that `PostgresImage`, `KeycloakImage` and `ObservabilityImages` must each follow an update, or `ComposeFileTest` fails. These are comments only, so there is no test.

### Run and gates

- The five telemetry services under `-p plan7-task1` came up healthy, the Collector `Up`, and the three datasources `OK`.
  - From the host: `POST /api/query-history` answered 404, and the anonymous visitor still had `datasources:explore`.
  - Sign-in was refused: basic `admin:admin` answered 401, and the form answered `400 auth.client.notConfigured`.
  - Stopped; no container or volume of the project is left.
- `./gradlew build`: `BUILD SUCCESSFUL` (exit 0), 734 tests, none failed or skipped. That is five more than round 2: `givesGrafanaNoAdministratorAccount`, `savesNoQueryHistory`, `checksForNoUpdates`, `loadsTheRuleFilesButNotTheirPromtoolTests` and `storesItsDataOnComposesTmpfsWithNoVolume`. `letsNoOneSignIn` was strengthened; `keepsPrometheusDataOnTmpfs` was replaced.
- Self-review (thermo-nuclear):
  - Fixed: the duplicated promtool container became one builder.
  - Fixed: the compose-only tmpfs check was replaced by a test that runs compose's settings, because the shape check passed while Prometheus could not write.
  - Kept, as deferred (C4): `givesGrafanaNoAdministratorAccount` casts compose's environment inline, as `givesKeycloakNoAdministratorAccount` does. A `ComposeFile.environment(service)` would remove both casts and `GrafanaProvisioningTest`'s own copy.
  - Nothing new passes silently: every new or changed test was seen red, by mutation where the state already held.

## Task 3 — evidence

The alert rules of Q3-A with Q4's values, in `deploy/prometheus/rules/ingestion.rules.yaml`, and promtool's unit tests for them beside it, in `ingestion.rules.test.yaml`. `AlertRulesTest.passesEveryRuleTest` runs `promtool check rules` and `promtool test rules` on them inside `./gradlew build`, in `ObservabilityImages.PROMETHEUS`, and fails with promtool's output.

### The meter names (read before writing a rule)

- On `main`, `IngestionMetrics` registers four gauges, none with a base unit: `ingestion.consecutive.failures`, `ingestion.partitions.days.ahead`, `ingestion.partitions.default.rows`, and `ingestion.provider.last.fresh.seconds` with the tag `provider` (the enum's name: `ALPHA`, `BETA`, `GAMMA`).
- Micrometer's Prometheus registry is not on `main` yet (Task 2 adds it). Its naming was read from the sources at the BOM's versions (Boot 4.1.2: Micrometer 1.17.2, Prometheus client 1.7.0):
  - `PrometheusNamingConvention.name` turns every dot into an underscore (`PrometheusNaming.prometheusName`, underscore escaping);
  - it adds a suffix only for a base unit (none here) or a timer;
  - `sanitizeMetricName` strips only `_total`, `_created`, `_bucket` and `_info`.
- So the app exports `ingestion_consecutive_failures`, `ingestion_partitions_days_ahead`, `ingestion_partitions_default_rows` and `ingestion_provider_last_fresh_seconds{provider}`. These are the names Task 2's interface fixes.
- No tag is added to every meter (`management.metrics.tags` is not set). A series carries its own tags plus Prometheus's `job` and `instance`, as the test series do.
- Printing a real scrape from the registry was refused (it meant running a downloaded jar), so the names rest on the source. Task 2's Step 4 records them as Prometheus stores them.

### Red → green, one test at a time

Failure messages are AssertJ's, which quote promtool's output (`exp` is the expected alerts, `got` the firing ones). Every mutation below was restored.

**Step 1.** `AlertRulesTest.passesEveryRuleTest`:
- Red: `[promtool check rules /etc/prometheus/rules/*.rules.yaml` / `promtool: error: path '/etc/prometheus/rules/*.rules.yaml' does not exist, try --help] expected: 0 but was: 1`.
- Green: a rule file with the group `ingestion` and no rules, and a test file with no tests.

**Task 1's `loadsTheRuleFilesButNotTheirPromtoolTests` (R1).**
- With the real rule file in `rules/`, it went red as the review predicted: `Expecting actual: "... SUCCESS: 2 rule files found ..." to contain: "SUCCESS: 1 rule files found"`.
- Changed: the count assertion is dropped. The test keeps its own example pair, so it does not depend on the repository's rule files.
- The glob check is not weakened, shown by the same two mutations of `prometheus.yaml` as round 3:
  - `rules/*.yaml`: red. promtool failed on both test files: `field rule_files not found in type rulefmt.RuleGroups`. Now that a real test file sits beside the rules, `checksThePrometheusConfiguration` and `storesItsDataOnComposesTmpfsWithNoVolume` fail on it too.
  - `rules/*.rule.yaml` (matches nothing): red, `Expecting actual: "Checking /etc/prometheus/prometheus.yaml SUCCESS: ... valid prometheus config file syntax" to contain: "Checking /etc/prometheus/rules/example.rules.yaml"`.

**Step 2: the promtool cases.** Each is one test group in `ingestion.rules.test.yaml`, added on its own. Unless noted, the series are one instance at `172.18.0.7:8079`, one value a minute, and the rules are evaluated every 15 s, as Prometheus does (Q4).

`IngestionFailing`
1. **Fires at three failed runs in a row, and not at two** (2 until minute 5, 3 from minute 6).
   - Red: `alertname: IngestionFailing, time: 6m, exp:[{alertname="IngestionFailing"}, summary="Ingestion has failed 3 runs in a row"], got:[]`.
   - Green: `max(ingestion_consecutive_failures) >= 3`, `for: 0m`.
   - Mutations: `>= 4` gives `time: 6m ... got:[]`; `>= 2` gives `time: 5m, exp:[], got:[... "Ingestion has failed 2 runs in a row"]`; `for: 1m` gives `time: 6m ... got:[]`.
2. **Fires with one instance at 3 and another at 0.**
   - Passed on its first run (`max` was there).
   - Mutation `min`: `time: 5m ... got:[]`.
3. **Fires after 15 minutes with no instance reporting it, not after 14** (no series).
   - Red: `time: 15m ... got:[]`.
   - Green: a second rule, `absent(ingestion_consecutive_failures)`, `for: 15m`.
   - Mutations: `for: 14m` gives `time: 14m, exp:[], got:[...]`; `for: 16m` gives `time: 15m ... got:[]`.

`PartitionHorizonShort`

4. **Quiet at 37 and at 34, fires at 33** (37, then 34 from minute 5, then 33 from minute 10).
   - Red: `time: 10m ... got:[]`.
   - Green: `max(ingestion_partitions_days_ahead) < 34`, `for: 0m`.
   - Mutations: `< 35` and `<= 34` give `time: 9m, exp:[], got:[... "The partitions reach only 34 days ahead"]`; `< 33` gives `time: 10m ... got:[]`; `> 34` gives `time: 4m ... "only 37 days ahead"`; `for: 1m` gives `time: 10m ... got:[]`.
5. **Fires after 15 minutes of NaN on two instances, not after 14.**
   - First run: a fixture error, `unexpected identifier "NaNx20" in series values, expected number or "stale"`. promtool repeats a NaN only as `NaN+0x20` (checked on a scratch file first).
   - Red: `time: 15m ... got:[]`.
   - Green: `absent(ingestion_partitions_days_ahead == ingestion_partitions_days_ahead)`, `for: 15m`. `x == x` keeps only the series holding a number.
   - Mutations: `for: 14m` and `for: 16m`, red at 14 and 15 minutes.
6. **Quiet with one instance at 37 and a restarted one at NaN,** evaluated at minute 20.
   - Passed on its first run.
   - Mutation, NaN on any instance (`group(ingestion_partitions_days_ahead != ingestion_partitions_days_ahead)`): `time: 20m, exp:[], got:[... "The partition horizon has been unknown on every instance for 15 minutes"]`.
7. **Fires after 15 minutes with no instance reporting it, not after 14.**
   - Passed on its first run.
   - Mutation, NaN only (`max(...) != max(...)`, which misses an absent gauge): `time: 15m ... got:[]`.

`RowsInDefaultPartition`

8. **Fires at one row on any instance, and not at none** (one instance at 0; another at 0, then 1 from minute 5).
   - Red: `time: 5m ... got:[]`.
   - Green: `max(ingestion_partitions_default_rows) > 0`, `for: 0m`.
   - Mutations: `>= 0` gives `time: 4m ... "Rows in the DEFAULT partition: 0"`; `> 1`, `min` and `for: 1m` give `time: 5m ... got:[]`.

`ProviderDataStale`

9. **Fires for BETA only** (ALPHA at 5,400 s, the threshold itself; BETA at 5,400, then 5,401 from minute 5; GAMMA at 60).
   - Red: `time: 5m, exp:[{alertname="ProviderDataStale", provider="BETA"}, summary="Provider BETA's newest data is 1h 30m 1s old"], got:[]`.
   - Green: `min by (provider) (ingestion_provider_last_fresh_seconds) > 5400`, `for: 0m`.
   - Mutations: `> 5401`, `min` without `by (provider)` and `for: 1m` give `time: 5m ... got:[]`; `>= 5400` gives `time: 4m ... got:[ALPHA "1h 30m 0s", BETA "1h 30m 0s"]`.
10. **Quiet when another instance has BETA at 60 s** (the first at 5,401).
    - Passed on its first run.
    - Mutation `max by (provider)`: `time: 5m, exp:[], got:[BETA "1h 30m 1s"]`.
11. **Fires for GAMMA after 15 minutes of NaN on two instances, not after 14** (ALPHA at 60 on both).
    - Red: `time: 15m ... got:[]`.
    - Green: `group by (provider) (x) unless on (provider) (x == x)`, `for: 15m`: the providers listed, unless an instance holds a number for them.
    - Mutations: `for: 14m` and `for: 16m`, red at 14 and 15 minutes.
12. **Quiet with GAMMA at 60 s on one instance and NaN on a restarted one,** at minute 20.
    - Passed on its first run.
    - Mutation, NaN on any instance (`group by (provider) (x != x)`): `time: 20m, exp:[], got:[GAMMA "... unknown on every instance for 15 minutes"]`.
13. **Fires after 15 minutes with no instance reporting any provider, not after 14.**
    - Red: `time: 15m, exp:[{alertname="ProviderDataStale"}, summary="No instance has reported any provider's data age for 15 minutes"], got:[]`.
    - Green: `absent(ingestion_provider_last_fresh_seconds)`, `for: 15m`.
    - Mutations: `for: 14m` and `for: 16m`, red at 14 and 15 minutes.

**Refactor, staying green.** The rule file's header says how the rules combine instances, why the value rules fire at once and the stale ones after 15 minutes, and what `x == x` does. The test file's header explains promtool's value notation. `AlertRulesTest` and `PrometheusConfigTest` stayed green.

### Deviations from the plan's wording

- **Each alert whose gauge can be unknown has two rules under one name:** one for its value (`for: 0m`) and one for the stale gauge (`for: 15m`). A rule has a single `for`, and Q4 gives the two conditions different waits.
- **The 15 minutes are a `for`, not a range such as `absent_over_time(x[15m])`.** A range reads the time before Prometheus started as missing, so every `docker compose up` would fire the stale alerts before the first scrape. A `for` needs 15 minutes of evaluations. Prometheus keeps its data on tmpfs, so a Prometheus restart also starts the wait again.
- **`IngestionFailing`'s stale rule covers a missing gauge only,** as its interface says. The count is an `AtomicInteger` and reads 0 from the start, never NaN.
- **`ProviderDataStale` with no series at all carries no `provider` label,** because with no series no provider can be named. Its summary says "any provider". Naming each provider would mean one rule per provider, which the rules would have to be changed for whenever a provider is added. Cost if wrong: the alert list shows one `ProviderDataStale` without a provider when the app is not scraped, beside `IngestionFailing`'s.
- **Three cases the plan's list does not name,** each for its interface or Review Focus 2:
  - case 7, `PartitionHorizonShort` with no instance reporting ("or missing", in its interface line);
  - case 12, GAMMA's NaN on a restarted instance beside a healthy one (Review Focus 2: one restarted instance beside a healthy one must not fire);
  - case 13, `ProviderDataStale` with no instance reporting ("or missing", in its interface line).
- **The order of two cases:** `PartitionHorizonShort`'s "NaN on every instance" (5) came before "one at 37 and a restarted one at NaN" (6). Written first, case 6 would have passed before its rule existed. Written after, it was shown red by a mutation of that rule.

### Pending decision for the user: promtool's duplicate-rules lint

`promtool check rules` and `promtool check config` now print `FAILED: lint error 3 duplicate rule(s) found. Metric: IngestionFailing ... PartitionHorizonShort ... ProviderDataStale ... Might cause inconsistency while recording expressions`, and still exit 0. The lint flags rules with the same name and the same labels. The default lint only warns; `--lint-fatal` would make it fail.

The two rules of an alert never hold at once: one needs a number, the other none. So no two alerts with the same labels exist at the same time, and the warning is about recording rules. Built as A for now, with a line in the rule file saying so.
- **A. Leave it (as built).** Cost: promtool's output says `FAILED` on a valid file, which a reader has to know to ignore.
- **B. A label on the four stale rules** (for example `gauge: unknown`). The lint passes, and the alert list can filter the stale cases. `AlertRulesTest` could then run with `--lint-fatal`. Cost: one line per rule and one per expected alert in the tests; the stale alerts' labels change.
- **C. Separate names for the stale rules** (for example `IngestionMetricsMissing`). Cost: alerts that Q3-A does not name.
- Recommendation: **B.** It keeps Q3-A's four names and makes promtool's output clean.

### The run (not a step of the plan)

Prometheus alone, `docker compose -p plan7-task3 up -d --wait --no-deps prometheus`, healthy:
- `/api/v1/rules` listed the group `ingestion` from `/etc/prometheus/rules/ingestion.rules.yaml` (compose's read-only mount): eight rules, all `health: ok`, evaluated every 15 s.
- With no scrape job yet (Task 2), the three rules for a missing gauge were `pending`, labels `{alertname: IngestionFailing}`, `{alertname: PartitionHorizonShort}` and `{alertname: ProviderDataStale}`. The five others were `inactive`.
- Stopped with `docker compose -p plan7-task3 down -v`; no container, network or volume of the project is left.

### Concerns

- **Until Task 2 adds the scrape job, compose's Prometheus fires three alerts 15 minutes after it starts:** `IngestionFailing`, `PartitionHorizonShort` and `ProviderDataStale`, each for a missing gauge. That is correct, since nothing is scraped. It ends when Task 2 reaches `main`.
- **When the app is down, those three alerts fire together for one cause.** Each alert's interface asks for it; the summaries say that no instance reports.
- **After a crash, `IngestionFailing` stays quiet while the dead run's lock lasts** (up to 29 minutes, plan 4 close-out), because the restarted instance's count reads 0. The gap is caught instead by `PartitionHorizonShort` and `ProviderDataStale`, whose gauges read NaN until the first run and fire after 15 minutes. This matches the "Alert semantics" constraint.

### Extras for the user to decide

- **`PrometheusConfigTest` showing promtool's output when it fails,** as `AlertRulesTest` does (run promtool in a kept-alive container and put its output in the assertion). Its failures today say only `Container did not start correctly`, and promtool's error is in the test's log, not the report's message (seen in R1's mutations). Value: a broken configuration names its error. Cost: Task 1's helper changes, three tests unchanged in what they check. Left out unless you approve.

### Gates

- `./gradlew build`: `BUILD SUCCESSFUL` (exit 0), 826 tests across the modules (bootstrap 131), none failed or skipped. The new test is `AlertRulesTest.passesEveryRuleTest`; its 13 promtool cases run inside it. `loadsTheRuleFilesButNotTheirPromtoolTests` changed as R1 asked. `frontend/` is not touched.
- Self-review (thermo-nuclear) of the diff:
  - Existing mechanisms reused: the image's own `promtool` and the `ObservabilityImages` pin; the directory copied as compose mounts it, as `PrometheusConfigTest` does; promtool's unit-test format rather than a Java harness around the rules.
  - `AlertRulesTest` runs promtool with `execInContainer` in a kept-alive container rather than with `PrometheusConfigTest`'s one-shot builder. The one-shot start loses promtool's output (its failure says only `Container did not start correctly`), and the plan asks for that output. The extra above would give `PrometheusConfigTest` the same.
  - One rule per condition, not one expression per alert. The value and stale conditions have different waits, and a range in place of `for` would fire at every start (see "Deviations").
  - Silent passes: a quiet expectation (`exp_alerts: []`) under a misspelled alert name would pass. Every quiet expectation is either in a case that also expects the alert to fire under the same name, or was shown red by a mutation (cases 6, 10 and 12). A test file that matches no rule file fails promtool, and no rule file fails `AlertRulesTest` (Step 1).
  - Sizes: the rule file has 77 lines, its tests 218 and `AlertRulesTest` 47.

### The lint decision: B (the user's decision, after the first commit)

The user chose B for the pending decision above. The stale-gauge rules carry the label `gauge: unknown`, so promtool's duplicate-rules lint passes. The rule checks run with `--lint-fatal`, so a later lint error fails the build. The extra (`PrometheusConfigTest` showing promtool's output) stays out.

**Red, against the rules of `2260bd7`:**
- `AlertRulesTest` with `promtool check rules --lint-fatal`: `lint error 3 duplicate rule(s) found. Metric: IngestionFailing ... PartitionHorizonShort ... ProviderDataStale ... expected: 0 but was: 3`.
- `PrometheusConfigTest`'s builder with `promtool check config --lint-fatal`: `checksThePrometheusConfiguration` and `loadsTheRuleFilesButNotTheirPromtoolTests` red. The container exits with 3, and its log shows `lint error 3 duplicate rule(s) found`.
- The five expectations of a stale alert (cases 3, 5, 7, 11 and 13) now expect `gauge: unknown` beside the alert's name. Run directly with the image's `promtool test rules`, before the label existed, each one failed: for example `exp: Labels:{alertname="IngestionFailing", gauge="unknown"}`, `got: Labels:{alertname="IngestionFailing"}`.
  - The cases still select by alert name.
  - The quiet cases (`exp_alerts: []`) do not change.

**Green, in two steps:**
1. The label on the four stale rules: `IngestionFailing`'s, `PartitionHorizonShort`'s and `ProviderDataStale`'s two. The lint then still failed: `lint error 1 duplicate rule(s) found. Metric: ProviderDataStale Label(s): gauge: unknown`. The lint compares a rule's own labels, and `ProviderDataStale`'s two stale rules (NaN per provider, and no series at all) had the same ones. My report's option B had counted four rules and missed this.
2. Those two rules became one, with the same wait and label: `(group by (provider) (x) unless on (provider) (x == x)) or absent(x)`, `for: 15m`.
   - Its summary keeps both texts: `{{ with $labels.provider }}Provider {{ . }}'s data age has been unknown on every instance for 15 minutes{{ else }}No instance has reported any provider's data age for 15 minutes{{ end }}`.
   - Every alert now has one value rule and at most one stale rule: seven rules in all.
   - No alert changes its labels (beyond `gauge`), its summary or its timing: cases 11, 12 and 13 pass unchanged except for the label.
   - `AlertRulesTest` and the three `PrometheusConfigTest` tests are green.

**Labelled mutations, each restored:**
- The label removed from `PartitionHorizonShort`'s stale rule. `AlertRulesTest` failed with `lint error 1 duplicate rule(s) found. Metric: PartitionHorizonShort ... expected: 0 but was: 3`, and both `check config` tests failed on the same lint error.
- `or absent(...)` removed from the merged rule: case 13 failed, `time: 15m, exp: Labels:{alertname="ProviderDataStale", gauge="unknown"} ... "No instance has reported any provider's data age for 15 minutes"`.
- The per-provider half removed (only `absent(...)` left): case 11 failed, `time: 15m, exp: Labels:{alertname="ProviderDataStale", gauge="unknown", provider="GAMMA"}, got:[]`.
- NaN on any instance (`group by (provider) (x != x)`) in place of the per-provider half: case 12 failed, `time: 20m, exp:[], got: Labels:{alertname="ProviderDataStale", gauge="unknown", provider="GAMMA"}`.

**The run.** Prometheus alone, `docker compose -p plan7-task3 up -d --wait --no-deps prometheus`:
- It loaded the group `ingestion` with seven rules, all `health: ok`, the three stale ones with the label `gauge: unknown`.
- With no scrape job yet, the stale alerts were `pending`, as `{alertname: IngestionFailing, gauge: unknown}`, `{alertname: PartitionHorizonShort, gauge: unknown}` and `{alertname: ProviderDataStale, gauge: unknown}`.
- Stopped with `down -v`; nothing of the project is left.

**Self-review (thermo-nuclear).**
- Merging `ProviderDataStale`'s two stale rules deletes a rule and leaves every alert with the same shape. The cost is one `with`/`else` in its summary, the template language's own way to handle a label that may be absent.
- `--lint-fatal` is promtool's own switch. It is set once in each place that runs a check: `AlertRulesTest`'s `check rules`, and `PrometheusConfigTest`'s one builder for `check config`.
- Nothing passes silently that failed before: a lint error now fails the build (shown above).

**Gates.** `./gradlew build`: `BUILD SUCCESSFUL` (exit 0). `bootstrap:test` and the coverage verification ran again: 826 tests, none failed or skipped. The test count is unchanged, since the label and the merge are checked inside `AlertRulesTest`'s promtool run.
