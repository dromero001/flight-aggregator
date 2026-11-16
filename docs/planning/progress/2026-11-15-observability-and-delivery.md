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
- **`ProviderDataStale` with no series at all carries no `provider` label,** because with no series no provider can be named. Its summary says "any provider". (Reason corrected in fix round 1.) One rule could still name each provider, `absent(x{provider="ALPHA"}) or absent(x{provider="BETA"}) or ...`, but it would have to list the provider enum. Nothing is lost without it: `IngestionMetrics` registers every provider's gauge together, so one provider's series is never missing alone, and naming each would turn one cause into three more alerts. Cost if wrong: the alert list shows one `ProviderDataStale` without a provider when the app is not scraped, beside `IngestionFailing`'s.
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

## Task 3 — fix round 1

The double review answered: functional CHANGES REQUESTED (F1 Important; F2, F3 Minor), code APPROVED (C1–C3 Minor). The controller asked for F1, F2 (as ruled) and C1. C2 joins Task 1's deferred C4. C3 stays, as the plan requires. F3 is a note for the user and the end-of-plan review, with no change here.

### F1: the value rules' combining of instances is pinned

- **The gap.** The reviewer's sweep left six aggregation mutations alive: `PartitionHorizonShort` `max` to `min`, `avg` or `sum`; `IngestionFailing` and `RowsInDefaultPartition` `max` to `sum`; `ProviderDataStale` `min by` to `avg by`.
  - No case put two numbers on either side of a threshold for the horizon.
  - No case put a NaN beside a value past a threshold. That is the one input that separates max and min, which skip a NaN, from sum and avg, which return NaN and go quiet.
- **Five cases, added one at a time.** Each passed on its first run, since the rules were right, and was then shown red by the mutation it targets, restored after each run:
  1. **`IngestionFailing` stays quiet with one instance at 2 failed runs in a row and another at 1.** `sum`: `time: 5m, exp:[], got:[{alertname="IngestionFailing"} "Ingestion has failed 3 runs in a row"]`.
  2. **`PartitionHorizonShort` stays quiet with one instance at 37 and another at 33.** `min`: `time: 5m, exp:[], got:[{alertname="PartitionHorizonShort"} "The partitions reach only 33 days ahead"]`.
  3. **`PartitionHorizonShort` fires at 33 on one instance beside a restarted one at NaN.** `avg` and `sum`: `time: 5m, exp:[{alertname="PartitionHorizonShort"} ...], got:[]`.
  4. **`RowsInDefaultPartition` fires at one row on one instance beside a restarted one at NaN.** `sum`: `time: 5m, exp:[{alertname="RowsInDefaultPartition"} ...], got:[]`.
  5. **`ProviderDataStale` fires for BETA at 5,401 s on one instance beside a restarted one at NaN.** `avg by (provider)`: `time: 5m, exp:[{alertname="ProviderDataStale", provider="BETA"} ...], got:[]`.
- **The whole sweep, run again after the five cases:** each value rule's aggregation replaced by every other one, 12 mutations, one run each. All 12 failed:

| Rule | Mutation | Case that failed |
|---|---|---|
| `IngestionFailing` | `min`, `avg` | fires with one instance at 3 and another at 0 |
| `IngestionFailing` | `sum` | quiet at 2 and 1 (new) |
| `PartitionHorizonShort` | `min` | quiet at 37 and 33 (new) |
| `PartitionHorizonShort` | `avg`, `sum` | fires at 33 beside NaN (new) |
| `RowsInDefaultPartition` | `min` | fires at one row on any instance |
| `RowsInDefaultPartition` | `avg` | fires at one row on any instance, and fires at one row beside NaN (new) |
| `RowsInDefaultPartition` | `sum` | fires at one row beside NaN (new) |
| `ProviderDataStale` | `max by`, `sum by` | quiet when another instance has BETA at 60 s (`sum by` also: BETA beside NaN) |
| `ProviderDataStale` | `avg by` | BETA beside NaN (new) |

- **Correction.** The implementer's report said that every aggregation went red under a mutation. That was not true before this round: `PartitionHorizonShort` had no aggregation mutation, and the six above survived. The report now says so, and the table above is the record.

### C1: `IngestionFailing`'s stale rule treats NaN like the others

- **`IngestionFailing` fires after 15 minutes of NaN on every instance, not after 14** (two instances at NaN; expected `{alertname="IngestionFailing", gauge="unknown"}`). Red with `absent(ingestion_consecutive_failures)`: `time: 15m, exp:[{alertname="IngestionFailing", gauge="unknown"}], got:[]`.
- Green: `absent(ingestion_consecutive_failures == ingestion_consecutive_failures)`, the same shape as `PartitionHorizonShort`'s.
  - The special-case comment ("the count is never NaN") is gone.
  - The rule file's header, "a gauge that reads NaN or is missing counts as stale", now holds for every alert.
  - All other cases stayed green, and so did `PrometheusConfigTest`'s three tests (`check config --lint-fatal`).
- Today's count reads 0 from the start, so nothing changes now. If a later change published it as NaN until an instance's first run, both of `IngestionFailing`'s rules would have stayed quiet (the reviewer's S4).

### F2: the reason for `ProviderDataStale`'s unlabelled no-series alert, corrected

- The controller's ruling: keep the alert without a `provider` label.
- The reason recorded under "Deviations" was inaccurate ("one rule per provider"). One rule, `absent(x{provider="ALPHA"}) or absent(x{provider="BETA"}) or ...`, could name each provider, but it would have to list the provider enum. The real reason the outcome is sound:
  - `IngestionMetrics` registers every provider's gauge together, so one provider's series is never missing alone;
  - naming each provider would turn one cause into three more alerts.
- The deviation's line is corrected in place, marked "corrected in fix round 1". The rule's comment says the same.

### Gates

- `./gradlew build`: `BUILD SUCCESSFUL` (exit 0). `bootstrap:test` and the coverage verification ran again: 826 tests, none failed or skipped. The new promtool cases run inside `AlertRulesTest`.
- Self-review (thermo-nuclear):
  - The rule file changes by one expression and two comments.
  - The test file grows from 13 to 19 cases (312 lines). Each new case is the smallest input that separates one aggregation from another, and the cases sit with their alert's group.
  - No new mechanism.
  - Nothing passes silently that the review found: every aggregation and the NaN failure count now fail a case when changed.

## Task 2 — evidence

Actuator serves `health`, `metrics` and `prometheus` on its own port, 8079, open there with no token and never published by compose; Prometheus scrapes it as job `app`. The API port serves no Actuator endpoint and keeps plan 5's public health at `/health`.

The task ran in two sessions. The first implementer stopped on the interface's condition, then built Steps 1 to 4 with nothing committed before the network dropped at 06:42 and the Mac rebooted. A second implementer reviewed that work, committed it step by step, re-ran Step 4 and the gates. The red → green lines below come from the first implementer's work log (its scratch notes were wiped by the reboot). The lines marked "recorded after the fact by mutation" are reds that log does not show, re-established on `d73f5a9`.

### The stop, and the user's decision

- Boot 4.1.2's `AdditionalHealthEndpointPath.from`, called against the 4.1.2 jars: `server:/healthz -> accepted`, `server:/actuator/health -> rejected: java.lang.IllegalArgumentException: 'value' must contain only one segment.` So once `management.server.port` is set, nothing on the API port can answer `/actuator/health` through Boot's health groups.
- The user chose `/health` (Task 2's "Decided by the user on 2026-11-16"). A health group that sets nothing but its additional path inherits the root health's `show-details`, roles, status order and HTTP mapping (read in `AutoConfiguredHealthEndpointGroups`, spring-boot-health 4.1.2).

### Red → green, one test at a time (Step 1)

`ManagementPortTest` starts the application on real sockets with `RANDOM_PORT`: Boot then gives the management server a random port too, since `application.yaml` sets one that differs from the server's, and the test reads it with `@LocalManagementPort`.

1. **`scrapesWithoutATokenOnTheManagementPort`** (200, and the body holds `ingestion_consecutive_failures`).
   - Red: `expected: 200 but was: 401`. With no management port, the request reached the API's server, where everything not public needs `ADMIN`.
   - `management.server.port: 8079`: still `expected: 200 but was: 401`. The test's log showed two servers (`Tomcat started on port 54254` and `on port 54266`): Spring Security's filter chain runs on the management server too.
   - `SecurityConfig` permits every request served by the management server's context: `expected: 200 but was: 404`, since there was no `/actuator/prometheus` yet.
   - Green: `micrometer-registry-prometheus` (`runtimeOnly`, the BOM's version) and the exposure `health,metrics,prometheus`.
2. **`neverServesMetricsOnTheApiPort`** (`/actuator/prometheus` and `/actuator/metrics` on the API port, with an `ADMIN` token, answer 404; Review Focus 3).
   - Passed on its first run: the management port had already taken Actuator off the API port.
   - Mutation, `management.server.port: 8080`: still green, so it proved nothing and is not counted.
   - Mutation, no `management.server` at all: red, `[1] path = "/actuator/prometheus"` and `[2] path = "/actuator/metrics"`, each `expected: 404 but was: 200`, and `scrapesWithoutATokenOnTheManagementPort` `expected: 200 but was: 401`. Restored.
3. **`keepsTheApiPortsHealthAsPlanFiveLeftIt`** (anonymous `/health`: 200, `DEGRADED` since no Valkey runs in the test, no `components`; with the `ADMIN` token: `components.db.status` is `UP`).
   - Red: `expected: 200 but was: 401`. Nothing served `/health`, so it fell to the `ADMIN` rule.
   - `SecurityConfig` permits `EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, HealthEndpoint.class)` in place of `GET /actuator/health`: still `expected: 200 but was: 401`, as no additional path existed yet.
   - Green: the health group `api` with `additional-path: server:/health`, and nothing else.
   - Mutations of the group, to show it inherits plan 5's settings: `show-details: always` gives `Expecting value to be false but was true` (an anonymous client saw the components); `show-details: never` gives `expected: "UP" but was: ""` (the administrator saw none). Restored.

The test's own `JwtDecoder` bean turns one fixed token into a JWT with the `ADMIN` realm role, so Boot's role mapping applies without Keycloak. `SecurityTest` keeps the real decoder.

### Plan 5's tests that read Actuator on the API port (Step 2)

- After Step 1, the bootstrap suite had four red classes:
  - `FlightAggregatorApplicationTest.configuresTheProductionIngestionAndExposesOnlyHealthAndMetrics`: `Expecting actual's toString() to return: "health,metrics" but was: "health,me…"` (cut in the log);
  - `SearchWithoutValkeyTest.searchesAndRepricesWhileValkeyIsDown`, `RealmClientCredentialsTest` and `SecurityTest`, whose messages the log cuts off.
- What moved, as the user's decision lists:
  - `SecurityTest`: `healthNeedsNoTokenAndShowsNoDetails` and `healthShowsItsDetailsToTheAdmin` are now `ManagementPortTest.keepsTheApiPortsHealthAsPlanFiveLeftIt`. `metricsAnswer401WithoutAToken`, `metricsAnswer403WithoutTheAdminRole` and `metricsAnswer200WithTheAdminRealmRole` are removed: `adminEndpointsNeedTheAdminRole` and `adminStatusAnswers200WithTheAdminRealmRole` cover the same rules on the admin endpoints. The five token-decoder tests now call `/api/admin/status`.
  - `SearchWithoutValkeyTest`: both health reads call `/health`.
  - `RealmClientCredentialsTest.acceptsTheClientCredentialsTokenOfTheRealmsAdminClient`: `/api/admin/status`.
  - `PublicAccessOverHttpTest.anAnonymousAdminCallIsStillAskedForAToken`: `GET /api/admin/ingestions`. It had not failed: an anonymous request to any path that is not public answers 401.
  - `FlightAggregatorApplicationTest.configuresTheProductionIngestionAndTheManagementPort` (renamed): it pins the exposure `health,metrics,prometheus` and `management.server.port` 8079.
- Green: the bootstrap suite, exit 0.
- `FlightAggregatorApplicationTest`'s port pin, mutation `port: 8078`: `Expecting actual's toString() to return: "8079" but was: "8078"`. Restored.
- **`ComposeFileTest.neverPublishesTheManagementPort`** reads the port from `application.yaml` and the app's published ports from `compose.yaml`, in the short and the long syntax. Passed on its first run; mutation, `"127.0.0.1:18079:8079"` added to the app's ports: `Expecting ["8080", "8079"] not to contain ["8079"] but found ["8079"]`. Restored.

Recorded after the fact by mutation (on `d73f5a9`, each restored with `git checkout`):
- **M1, `SecurityConfig` with a decoder that accepts any token as `ADMIN`:** `rejectsATokenOfAnotherIssuer`, `rejectsAnExpiredToken` and `rejectsATokenSignedWithAnotherKey` give `Status expected:<401> but was:<200>`; `answers403ToAValidTokenWithoutTheAdminRealmRole` gives `Status expected:<403> but was:<200>`. 4 of `SecurityTest`'s 9 failed.
- **M2, `anyRequest().hasRole("NOBODY")`:** `acceptsASignedTokenOfTheIssuerWithTheAdminRealmRole`, `adminStatusAnswers200WithTheAdminRealmRole` and `RealmClientCredentialsTest.acceptsTheClientCredentialsTokenOfTheRealmsAdminClient` give `Status expected:<200> but was:<403>`.
- **M3, `anyRequest().permitAll()`:** `anAnonymousAdminCallIsStillAskedForAToken` gives `expected: 401 UNAUTHORIZED but was: 405 METHOD_NOT_ALLOWED` (and `swaggerUiOpensNothingElse` gives `but was: 200 OK` for both paths).
- **M4, the group's additional path `server:/healthz`:** `searchesAndRepricesWhileValkeyIsDown` gives `Expecting actual: "" to contain: ""status":"UP""` (an anonymous `/health` answered 401, with no body).
- **M5, the exposure `health,metrics`:** `configuresTheProductionIngestionAndTheManagementPort` gives `Expecting actual's toString() to return: "health,metrics,prometheus" but was: "health,metrics"`.

### Documentation (Step 3)

- README: the run section's health URL is `http://localhost:8080/health`; "Admin access" lists `GET /health` as public and the admin endpoints `/api/admin/**` as needing `ADMIN`, and says the metrics are on the unpublished management port, read in Grafana.
- `docs/architecture/search-api.md`: the endpoint table's `GET /health`, and a paragraph on the management port and the health group. `docs/architecture/ingestion.md`: the web server serves `/health`.
- Plan 7's Decisions row and Task 2's Files and Interfaces lines name `/health`.

### The run (Step 4)

Both runs used the compose project `p7t2`, with every published port moved to `127.0.0.1:37xxx` by a scratch override file, because other stacks share the host. So the API port is `37080` where the plan says 8080. Each was taken down with `down -v`, leaving no container, volume or network.

**First run (the first implementer, before the scrape job existed):** `/api/v1/targets` listed no target. The API port's `/health` answered 200 `{"status":"UP"}`, and `/actuator/health` and `/actuator/prometheus` answered 401; from the host, port 8079 gave `curl exit 7`. With the scrape job added and Prometheus restarted, target `app` at `172.20.0.11:8079` went from `unknown` to `up`. Before the first run ended, the three gauges set by a run read `NaN`. After it (`Ingestion run ended`, `PUBLISHED`), the four gauges held numbers with `job` and `instance`. From inside compose's network, `app:8079/actuator/health` answered `{"groups":["api","liveness","readiness"],"status":"UP"}` and `/actuator/metrics` its names. The log cut the list of stored names short, so the run was repeated.

**Second run (on `6ae5454`, rebased on `main` with its locked Gradle cache mounts):** `docker compose -p p7t2 … up --build -d --wait` exit 0 in 41 s, every service `Healthy`, `app` included (its health check reads `/health`).
- Prometheus target: `('app', '172.20.0.11:8079', 'up', '', 'http://172.20.0.11:8079/actuator/prometheus')`.
- `/api/v1/series` after the first run:
  - `ingestion_consecutive_failures{instance="172.20.0.11:8079", job="app"}` 0;
  - `ingestion_partitions_days_ahead{…}` 37;
  - `ingestion_partitions_default_rows{…}` 0;
  - `ingestion_provider_last_fresh_seconds{…, provider="ALPHA"|"BETA"|"GAMMA"}` 33.9, 30.1 and 32.9.
- API port: `/health` 200 `{"status":"UP"}`; `/actuator/health`, `/actuator/metrics` and `/actuator/prometheus` 401.
- Host: `curl http://127.0.0.1:8079/actuator/prometheus` and `http://localhost:8079/…` exit 7 (connection refused); nothing listens on 8079; `docker port p7t2-app-1` lists only `8080/tcp -> 127.0.0.1:37080`.
- Inside compose's network (`wget` from the `prometheus` container): `app:8079/actuator/health`, `/actuator/metrics` and `/actuator/prometheus` each `HTTP/1.1 200`. The app's log: `Tomcat started on port 8080` and `Tomcat started on port 8079`.

**Every name Prometheus stored for job `app`** (167, of which `up`, `scrape_duration_seconds`, `scrape_samples_post_metric_relabeling`, `scrape_samples_scraped` and `scrape_series_added` are Prometheus's own). Labels beyond `job` and `instance` are given for the application's meters. Timers have `_count`, `_sum` and `_max`, with no buckets; counters end in `_total`.
- Ingestion: `ingestion_consecutive_failures`, `ingestion_partitions_days_ahead`, `ingestion_partitions_default_rows`, `ingestion_itineraries` (no labels); `ingestion_provider_last_fresh_seconds{provider}`; `ingestion_runs_total{outcome}`; `ingestion_duration_seconds_count`, `_sum` and `_max` `{outcome}`; `ingestion_provider_downloads_total{provider, reason, status}`; `ingestion_provider_retries_total{provider}`; `ingestion_records_discarded_total{provider, reason}`; `provider_calls_total{operation, outcome, provider}`.
- Search: `search_cache_total{result}`.
- Providers' breakers (`{name}`, plus `kind` or `state` where shown): `resilience4j_circuitbreaker_state{name, state}`, `resilience4j_circuitbreaker_buffered_calls{kind, name}`, `resilience4j_circuitbreaker_calls_seconds_count`, `_sum` and `_max` `{kind, name}`, `resilience4j_circuitbreaker_failure_rate{name}`, `resilience4j_circuitbreaker_not_permitted_calls_total{kind, name}`, `resilience4j_circuitbreaker_slow_call_rate{name}`, `resilience4j_circuitbreaker_slow_calls{kind, name}`.
- Valkey's breaker: the same nine names under `valkey_circuitbreaker_`, with the same labels.
- HTTP: `http_server_requests_seconds_count`, `_sum` and `_max` `{error, exception, method, outcome, status, uri}`; `http_server_requests_active_seconds_count`, `_sum` and `_max`.
- Boot, JVM and libraries: `application_ready_time_seconds`, `application_started_time_seconds`, `disk_free_bytes`, `disk_total_bytes`; `hikaricp_connections`, `hikaricp_connections_acquire_seconds_count|_max|_sum`, `hikaricp_connections_active`, `hikaricp_connections_creation_seconds_count|_max|_sum`, `hikaricp_connections_idle`, `hikaricp_connections_max`, `hikaricp_connections_min`, `hikaricp_connections_pending`, `hikaricp_connections_timeout_total`, `hikaricp_connections_usage_seconds_count|_max|_sum`; `jdbc_connections_active|idle|max|min`; `jvm_buffer_count_buffers`, `jvm_buffer_memory_used_bytes`, `jvm_buffer_total_capacity_bytes`, `jvm_classes_loaded_classes`, `jvm_classes_loaded_count_classes_total`, `jvm_classes_unloaded_classes_total`, `jvm_compilation_time_ms_total`, `jvm_gc_concurrent_phase_time_seconds_count|_max|_sum`, `jvm_gc_live_data_size_bytes`, `jvm_gc_max_data_size_bytes`, `jvm_gc_memory_allocated_bytes_total`, `jvm_gc_memory_promoted_bytes_total`, `jvm_gc_overhead`, `jvm_gc_pause_seconds_count|_max|_sum`, `jvm_info`, `jvm_memory_committed_bytes`, `jvm_memory_max_bytes`, `jvm_memory_usage_after_gc`, `jvm_memory_used_bytes`, `jvm_threads_daemon_threads`, `jvm_threads_live_threads`, `jvm_threads_peak_threads`, `jvm_threads_started_threads_total`, `jvm_threads_states_threads`; `lettuce_active_seconds_count|_max|_sum`, `lettuce_seconds_count|_max|_sum`; `logback_events_total`; `process_cpu_time_ns_total`, `process_cpu_usage`, `process_files_max_files`, `process_files_open_files`, `process_start_time_seconds`, `process_uptime_seconds`; `system_cpu_count`, `system_cpu_usage`, `system_load_average_1m`; `tasks_scheduled_execution_seconds_count|_max|_sum`, `tasks_scheduled_execution_active_seconds_count|_max|_sum`; `tomcat_sessions_active_current_sessions`, `tomcat_sessions_active_max_sessions`, `tomcat_sessions_alive_max_seconds`, `tomcat_sessions_created_sessions_total`, `tomcat_sessions_expired_sessions_total`, `tomcat_sessions_rejected_sessions_total`.
- Spring Security's observations: `spring_security_authorizations_seconds_count|_max|_sum`, `spring_security_authorizations_active_seconds_count|_max|_sum`, `spring_security_filterchains_seconds_count|_max|_sum`, `spring_security_filterchains_active_seconds_count|_max|_sum`, `spring_security_http_secured_requests_seconds_count|_max|_sum`, `spring_security_http_secured_requests_active_seconds_count|_max|_sum`, and one `spring_security_filterchains_<filter>_before_total` and `_after_total` pair per filter: `OAuth2ProtectedResourceMetadataFilter`, `access_exceptions`, `authentication_anonymous`, `authentication_bearer`, `authorization`, `context_async`, `context_holder`, `context_servlet`, `header`, `logout`, `requestcache`, `session_management`, `session_urlencoding`.

### Deviations from the plan's wording

- **The API port's health is at `/health`, not `/actuator/health`:** the user's decision, after the stop above.
- **`SecurityConfig` recognises the management server by the namespace of the context serving the request, not with Boot's `EndpointRequest.toAnyEndpoint()`.** Both match the same requests while the ports differ. But `toAnyEndpoint()` skips the API's server only when the port type is `DIFFERENT` (`ignoreApplicationContext`, spring-boot-security 4.1.2). If `management.server.port` were ever removed or set to the server's port, it would match `/actuator/**` on the API port and open every endpoint to anyone. The namespace check fails closed instead: the endpoints would then need `ADMIN`. The method's Javadoc says why.
- **Tests moved that the plan's list does not name:** `RealmClientCredentialsTest` (it read `/actuator/metrics` with the realm's client token) and `SearchWithoutValkeyTest` (it reads the health).
- **Two mentions beyond the plan's file list** now say `/health`: the README's run section and `docs/architecture/ingestion.md`.
- **Step 4 used the API port 37080,** not 8080, for the reason given there.

### Concerns

- **Plan 5 closing round 1 reads `/actuator/health` on the API port.** Its `HealthWithoutPostgresTest` (`dbb2015`, on `wip/plan5-close-1`) calls `http://127.0.0.1:<server port>/actuator/health`. With this task on `main` that answers 401. Whichever of the two reaches `main` second moves the test to `/health`.
  - Moved, it is also the test that shows `DOWN` answering 503 on `/health`. This task's tests show the group's `DEGRADED` 200 and its details by role. `DOWN` → 503 is the root health's mapping, which the group inherits; `FlightAggregatorApplicationTest.ranksDegradedBetweenDownAndUpAndAnswersIt200` pins the root mapping.
  - Round 1 also changes `application.yaml` (Hikari's connection wait), to be merged by hand.
- **Plan 7 Task 4** shares `application.yaml`, `gradle/libs.versions.toml`, `bootstrap/build.gradle.kts` and this report with this task.
- **Minor: `/health` is permitted for every method,** because Boot's additional-path matcher is used without a method. Plan 5 permitted `GET /actuator/health` only. The health endpoint serves only reads, so another method reaches no data.
- **`bootstrap/Dockerfile` has `EXPOSE 8080` only.** Compose's network reaches 8079 without it; plan 7 Task 11 describes the image with "ports 8080 and 8079".

### Extras for the user to decide

None.

### Gates

- Between commits, bootstrap's tests: on `d73f5a9`'s files alone, `:bootstrap:spotlessCheck :bootstrap:test` exit 0, 205 tests, none failed; `ComposeFileTest` for `57cd88f` (12 tests) and the `deploy` tests for `6ae5454` (16 tests, `PrometheusConfigTest`'s `promtool check config` among them), exit 0.
- `./gradlew build` on `6ae5454`: `BUILD SUCCESSFUL` (exit 0), 1,035 tests across the modules (bootstrap 206, which ran; the modules this task does not touch came from Gradle's build cache), none failed or skipped. `frontend/` is not touched.
- Self-review (thermo-nuclear) of the whole diff:
  - Boot's mechanisms used: `management.server.port`, a health group's additional path, `EndpointRequest.toAdditionalPaths`, Micrometer's Prometheus registry at the BOM's version, `@LocalManagementPort`. The one hand-written piece, the management-server matcher, is justified under "Deviations" and in its Javadoc.
  - Nothing fails silently: `ComposeFileTest` fails if the app publishes no port at all, so an empty list cannot pass it; the mutations above show each moved test failing for its own reason.
  - Sizes: `SecurityConfig` 84 lines, `ManagementPortTest` 127, `ComposeFileTest` 263, `FlightAggregatorApplicationTest` 384; `SecurityTest` lost 39 lines. No file crosses 1,000 lines.

## Task 2 — review

One double reviewer (functional, then code), 2026-11-16 about 08:00, on `2e4839c..b1c56d5`. **Functional: APPROVED. Code: APPROVED.** No Critical or Important finding; the Minors below wait for plan 7's closing round. The TDD evidence holds, including the five mutations recorded after the reboot (each would fail its test).

| Id | Where | Finding | Suggested fix |
|---|---|---|---|
| F1 | `SecurityConfig.java:51-54` | `/health` is permitted for every HTTP method, where plan 5 allowed GET only; an anonymous `POST /health` gets 405 instead of 401 (no data exposed) | `.withHttpMethod(HttpMethod.GET)` on Boot's matcher |
| F2 | tests | No test on this task proves `DOWN` answers 503 on `/health`; covered only through the root status mapping the group inherits | Plan 5 closing round 1's `HealthWithoutPostgresTest` covers it once moved to `/health` on the API port |
| C1 | `ComposeFileTest.java:157-179` | Compose's port syntax parsed by hand; a range such as `127.0.0.1:8070-8090:8070-8090` would publish 8079 and pass | Pin the app's exact port list, like the test next to it, and delete the parser |
| C2 | `deploy/prometheus/prometheus.yaml:22` | The scrape target's 8079 copies `application.yaml:87`; no test ties them | A small assertion, or Task 7's end-to-end run owns it |

The implementer's points, as judged: the hand-written management-server check in `SecurityConfig` is kept (Boot's `toAnyEndpoint()` would open `/actuator/**` on the API port if both servers ever shared a port); `EXPOSE 8080` stays as is (an exposed 8079 would be published by `docker run -P`); plan 5 closing round 1's `HealthWithoutPostgresTest` moves to `/health` on the API port (not the management port), and its Hikari setting in `application.yaml` is merged by hand, whichever lands second.

## Task 4 — evidence

Traces: every search, re-price and ingestion run is one trace, carried to the providers as W3C `traceparent` and sent over OTLP to the Collector in compose. Libraries, all from Boot 4.1.2's BOM: `spring-boot-starter-opentelemetry`, `spring-boot-starter-restclient`, `io.micrometer:context-propagation`; for the tests `spring-boot-micrometer-tracing-test` and `io.opentelemetry:opentelemetry-sdk-testing`.

The first implementer was cut by the network at 06:42 and the Mac's reboot at 07:05, after `6e98ce5` (now `fedbcf9` after rebasing) and in the middle of `pushesNoMetrics`; a second one took the task over on the same worktree. The red → green messages up to 06:42 are copied from the first implementer's work log (its tool calls and their output), since its scratch notes were wiped by the reboot. Every red step below was seen; none had to be recorded after the fact.

### Red → green, one test at a time

**Step 1.** `ContextThreadsTest.runsTheTaskInTheContextOfTheThreadThatCreatedIt` (an observation opened on the test thread is current inside the new thread).
- Red, with `ContextThreads.virtual` a stub returning `Thread.ofVirtual().factory()`: `Expecting actual: null and: {name=run(null), ...} to refer to the same object`. (A first run, with a registry without handlers, compared against a no-op observation; a handler was added to the test's registry.)
- Green: `ContextThreads.virtual` wraps each task in `snapshots.captureAll().wrap(task)` on a virtual thread; `TracingConfig` makes it the `contextThreads` bean.

**Step 2.** Each with a recording factory (`RecordingThreads`); each red with the constructor already taking the factory but not using it.
- `IngestInventoryServiceTest.downloadsOnThreadsOfItsFactory`. Red: `Expecting actual: [VirtualThread[#72]/terminated, VirtualThread[#73]/terminated, VirtualThread[#74]/terminated] to contain exactly in any order: [] but the following elements were unexpected`. Green: `ProviderDownloads` takes the factory and fans out through `Executors.newThreadPerTaskExecutor(threads)`.
- `RefreshStaleRouteServiceTest.downloadsOnThreadsOfItsFactory`. Red: the same message with three other threads. Green: the factory passed on to `ProviderDownloads`.
- `RepriceItineraryServiceTest.quotesOnThreadsOfItsFactory`. Red: the same message with two threads. Green: `Executors.newThreadPerTaskExecutor(threads)` for the quotes.
- `PagedDownloadTest.fetchesPagesOnAThreadOfItsFactory`. Red: `Expected size: 1 but was: 0 in: []`. Green: `pages.newThread(task).start()` instead of `Thread.ofVirtual().start(task)`.

**Step 3.** `TracingEndToEndTest`: Boot's tracing on (`@AutoConfigureTracing`), spans in an `InMemorySpanExporter`, PostgreSQL from `PostgresContainers`, one WireMock server for the three providers (`OneFlightProviders`).
- `anIngestionRunIsOneTrace` (every provider request carries a `traceparent` of the `ingestion.run` span's trace).
  - Red: `Expecting actual: [null, null, null] not to contain null elements` (`RestClient.builder()` sends no `traceparent`). A first run had failed on `No span named ingestion.run` because the span was asserted first; the headers are asserted first now.
  - Green: `ProviderHttp` built from Boot's `RestClient.Builder` (cloned per provider; its request factory, timeout and key header kept), `PagedProvider` given the builder and the context threads for its page loop (`ProvidersConfig`); `IngestionJob`'s one entry for scheduled and manual runs opens the `ingestion.run` observation; `management.tracing.sampling.probability: 1.0` (Q4). The three `*ConfigTest` runners then lacked the `ThreadFactory` bean (`UnsatisfiedDependencyException`) and import `TracingConfig`.
- `aManualRunIsOneTraceToo`: passed on its first run. Mutation M1, `launch()` calling the run without its observation: `AssertionError: No span named ingestion.run`.
- `aSearchContinuesTheCallersTrace`: passed on its first run (Boot's server observation). Mutation M2, the test property `management.tracing.propagation.consume=b3`: `Expecting actual: ["7df72106f2b573606c3646b3400d512a"] to contain exactly (and in same order): ["4bf92f3577b34da6a3ce929d0e0e4736"]`.
- `aRepriceIsOneTraceWithItsQuotes`: passed on its first run. Mutation M3, `RepriceConfig` passing `Thread.ofVirtual().factory()`: `Expecting ArrayList: ["e1b227253922e3c77a06577d12e19f32"] to contain only: ["beaf9409d0c5663cc8c62d4b8b47e964"]`.
- `exportsNoSecretInAnySpan` (no attribute of a run's spans or of an admin call's holds a provider key or the bearer token): passed on its first run. Mutation M4, `ProviderHttp` appending `&apiKey=<provider>-key` to the URI: `Expecting all elements of: [..., "http://localhost:49986/beta/segments?page=1&pageSize=1000&apiKey=beta-key", ...]` (the key in the client span's URL attribute).
- `pushesNoMetrics` (no meter registry is Micrometer's OTLP one; metrics export on, as in production). Red, with `@AutoConfigureMetrics` at the time: `Expecting ["io.micrometer.registry.otlp.OtlpMeterRegistry"] not to contain ["io.micrometer.registry.otlp.OtlpMeterRegistry"]`. Green: `management.otlp.metrics.export.enabled: false`.
- `timesARunOnlyAsIngestionDuration` (after a run, the meters hold `ingestion.duration` and `ingestion.runs` and neither `ingestion.run` nor `ingestion.run.active`). Red: `... not to contain ["ingestion.run", "ingestion.run.active"] but found ["ingestion.run", "ingestion.run.active"]`. Green: Boot's filter, `management.metrics.enable.ingestion.run: false`; it matches whole name segments, so `ingestion.runs` stays.

Every mutation was restored with `git checkout` and the test was green again.

**Step 4.** `TelemetryUnavailableTest.servesAndIngestsWithTheCollectorDown`: tracing on; the OTLP traces and logs endpoints and the OTLP metrics URL on `http://127.0.0.1:1`, where nothing listens. It checks that the OTLP span exporter is built, runs ingestion (generation 1 published), flushes the run's spans so that their export is failing while the search runs, and times a search after the context's first one: 200 within 1 s.
- Passed on its first run (Boot's batch span processor exports off the request path).
- Mutation M5, a `SpanProcessor` bean in `TracingConfig` that waits up to 10 s for each ended span's export: `Expecting actual: 40.179236416S to be less than: 1S`.
- Probes, not mutations: the timed search took `0.019770958S` (the context's first, untimed, 0.365 s); with a 20 s wait at the end, the test's output held `HttpExporter`'s `Failed to export spans. The request could not be executed.` twice, so the exports do fail.

**Step 5: the run.** Compose under `-p p7t4`, with a local override file (not committed) that remapped the published ports, because plan 6 Task 11's stack held them: Grafana on `127.0.0.1:13001`. `app`, the Collector, Tempo, Loki, Prometheus and Grafana healthy.
- Red, before the endpoint: the startup run ended `PUBLISHED` (1,030,286 itineraries, 31.7 s), and Tempo, searched through Grafana's datasource proxy as the anonymous Viewer, held no trace: `{"traces":[],...}`.
- Green: `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT: http://otel-collector:4318/v1/traces` in `app`'s environment. The startup run (43.3 s, `traceId 4b7bbe34cd863eb89d32bc4e36e271d1` on its log line) is that trace in Tempo: `task ingestionJob.run` (Spring's `@Scheduled` observation) → `ingestion.run` → 13 `http get` client spans, children of `ingestion.run`: Alpha 4 pages (no cursor, then three cursors), Gamma 4 (offsets 0–3000), Beta 4 (pages 1–4) plus a retry of page 1 (`ResourceAccessException ... Request cancelled`, then 200). One client span per provider request, each page and each retry. The client spans hold the URL template and the full URL, never the key header.
- Stopped with `down -v`; nothing of the project left.

**After rebasing on plan 7 Task 2.** The first `./gradlew build` failed 10 bootstrap tests:
- `ManagementPortTest.scrapesWithoutATokenOnTheManagementPort`: `expected: 200 but was: 404`. Boot's `spring-boot-starter-opentelemetry-test`, first chosen for the tests, also brings the metrics test module, whose customizer turns metrics export off in every test without `@AutoConfigureMetrics`. Fixed by depending on `spring-boot-micrometer-tracing-test` (it holds `@AutoConfigureTracing`) instead.
- Nine `SecurityTest` cases: `FATAL: sorry, too many clients already` on the shared PostgreSQL, since every cached context keeps a pool of 10. Fixed with `@DirtiesContext` on `TracingEndToEndTest` and `TelemetryUnavailableTest`, whose contexts no other class shares.
- `pushesNoMetrics` was shown red again without the metrics test module, by mutation M6 (`management.otlp.metrics.export.enabled: true`): `Expecting ["io.micrometer.registry.otlp.OtlpMeterRegistry", "io.micrometer.prometheusmetrics.PrometheusMeterRegistry", ...] not to contain ["io.micrometer.registry.otlp.OtlpMeterRegistry"]`.

### Deviations from the plan's wording

- **The OTLP traces endpoint is set in compose only, not in `application.yaml`.** Boot builds the OTLP span exporter only when `management.opentelemetry.tracing.export.otlp.endpoint` is set. A default in `application.yaml` would make the application run outside compose, where the Collector is not published (Q4), and every test with tracing on, retry and log a failed export every few seconds. `application.yaml` says where the endpoint comes from, as it does for the providers' URLs.
- **The run's timer is denied by Boot's `management.metrics.enable` setting,** which Boot implements as a `MeterFilter` (`PropertiesMeterFilter`), not by a hand-written one (Global Constraints: Boot's own mechanisms first). Pinned by a test the plan's Step 3 does not list, `timesARunOnlyAsIngestionDuration`.
- **`TelemetryUnavailableTest` times the context's second search,** after a first one asserted 200: the first request also starts the servlet (0.365 s here), which would make the 1 s bound flaky on CI's runners; mutation M5 makes every search slow, the second included.
- **A test helper, `OneFlightProviders`,** holds the WireMock providers that `TracingEndToEndTest` and `TelemetryUnavailableTest` share (refactored green to green).

### Concerns

- **Plan 5 closing round 1 has not landed:** it changes the same fan-outs (one refresh at a time; the run's download out of the per-provider limit). Each fan-out here changed by one constructor parameter and one executor line, so its rebase should be small; `RefreshStaleRouteServiceTest.downloadsOnThreadsOfItsFactory` and `aRepriceIsOneTraceWithItsQuotes` pin the behaviour across it.
- **`service.name` is `unknown_service`** in Tempo: the application sets no `spring.application.name`. Task 5's Loki query `{service_name="flight-aggregator"}` needs one; it belongs to Task 5 (or this task, if the user prefers), since no line of Task 4 names the service.
- **Spans carry provider URLs with their query** (`http.url`, an origin filter included). Spans are not metrics, so §6.10's cardinality rule is not broken; no key or token is in them (`exportsNoSecretInAnySpan`).
- **Boot also records `http.client.requests`** for the provider calls now that they go through its builder; its `uri` tag is the template (`/beta/segments?page={page}&pageSize={pageSize}`), low cardinality, and Task 6 may use it or ignore it.

### Extras for the user to decide

- None found.

### Gates

- `./gradlew build` on the branch rebased on `main` at `1a6d5bd` (plan 7 Task 2): `BUILD SUCCESSFUL` (exit 0); bootstrap 215 tests, none failed or skipped. `frontend/` is not touched.
- Module tests between the task's commits (application, providers, bootstrap), each green before its commit.
- Self-review (thermo-nuclear) before each commit:
  - Existing mechanisms used: Boot's `RestClient.Builder` (observed, W3C propagation), Boot's sampling, OTLP and `management.metrics.enable` settings, Micrometer's context propagation for the fan-out threads; no hand-written filter, propagator or exporter.
  - The application layer stays free of Micrometer: it takes a JDK `ThreadFactory`; each fan-out changed by one constructor parameter and one executor line.
  - Silent failure: an export that cannot reach the Collector is dropped and logged by OpenTelemetry's exporter (`Failed to export spans`), never blocking or failing a request or a run (Step 4).
  - Test fixtures shared rather than copied (`OneFlightProviders`); `TracingEndToEndTest` has 268 lines.

## Task 4 — fix round 1

The double review (functional and code) asked for one Important change, F-I1/C-I1: a refresh that a search asks for started on a plain `Thread.ofVirtual()` in `BackgroundRefresh`, so it carried no trace context. Each of its provider requests, and its Valkey `set`, became a root trace of its own. The Minors (F-M1, C-M1–C-M4) wait for plan 7's closing round.

### Rebase on plan 5 closing round 1

The branch was rebased onto round 1's branch (`231e451`, on `main` `2fd74a7`), which rewrites `BackgroundRefresh` (one refresh at a time, ShedLock's `refresh` row), `RefreshConfig`, `PagedProvider` and `application.yaml`. One textual conflict: `RefreshConfig`'s imports, where both were kept. The other files the review listed merged on their own. The module tests (application, providers, bootstrap, with `spotlessCheck`) were green after it: exit 0, bootstrap 223 tests.

### Red → green

`RefreshTraceTest.aRefreshContinuesTheTraceOfTheSearchThatAskedForIt`. It runs PostgreSQL and Valkey containers with tracing on and `search.freshness-threshold=PT0.001S`. After a run, it sends `GET /api/itineraries?origin=MAD` with `traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`, then waits until the refresh gives ShedLock's `refresh` row back. It asserts that the refresh's three provider requests carry the caller's trace id, and that so does every `set` span (the origin's claim and its refreshed mark).
- Red: `Expecting ArrayList: ["80ef71d6c697b53e858717ddc54c366e", "2e898a14af2cb0ef6fa351302ca01420", "b757128a61dabcc620de2e35dab48ab5"] to contain only: ["4bf92f3577b34da6a3ce929d0e0e4736"]`. Each provider request was a root trace, as the reviewer's probe showed.
- Green: `BackgroundRefresh` takes the `contextThreads` `ThreadFactory` from `RefreshConfig`. It starts each refresh on a thread of that factory, created on the search's thread, and keeps the thread's name `refresh-<origin>`. Round 1's lock logic is unchanged. `BackgroundRefreshTest` passes `Thread.ofVirtual().factory()`.
- As C-I1 suggested, a comment at `IngestionJob.launch` says why the manual run's thread stays plain: a run is a trace of its own.
- No refresh observation was added: the refresh's spans hang under the search's server span, which is the smallest change that closes F-I1.

### Gates

- `./gradlew build` on the final state: `BUILD SUCCESSFUL`, exit 0; bootstrap 224 tests, none failed or skipped. The first build of this round failed two `ValkeySearchCacheTest` cases in `adapters/persistence` (`QueryTimeoutException: Redis command timed out`). This round does not touch that module. The host was running several builds at once, and the module passed whole (126 tests) on the next build.
- Self-review (thermo-nuclear): the refresh now uses the canonical `contextThreads` factory, the one other spawn (`IngestionJob.launch`) says why it stays plain, and the WireMock `traceparent` reader moved into `OneFlightProviders`, so the two tracing tests do not copy it.

## Task 4 — review

- **First review:** Functional CHANGES REQUESTED (F-I1: a refresh a search asks for was not one trace; each provider request became its own) and Code CHANGES REQUESTED (C-I1, the same defect: `BackgroundRefresh` started its thread with a raw `Thread.ofVirtual()`, skipping `contextThreads`).
- **Fix round 1** (above) closed both; **re-review: Functional APPROVED, Code APPROVED**.
- Minors for plan 7's closing round: F-M1 `service.name` is `unknown_service` (no `spring.application.name`; goes to Task 5), C-M1 failed and skipped runs look like successful runs in Tempo, C-M2 (older than this task) every new context class can exhaust the shared PostgreSQL in tests, C-M3 `ContextThreads` is correct only under a thread-per-task executor, C-M4 the provider bean methods keep growing in parallel.
- **Integration:** rebased onto `main` `064fb78` after plans 5 and 6 closed, with `git rebase --onto main 231e451` (the branch still carried old copies of plan 5's closing round 1). Every conflict was in the first commit and kept both sides: plan 5 round 3's ceiling (`maxItineraries`), `REJECTED`, simplified `runUnderTheLock` and `eachQuote` test helper, with Task 4's `ThreadFactory` and the `ingestion.run` observation now wrapping the simplified method. One test adaptation: `RefreshStaleRouteServiceTest`'s helpers delegate to one full `service(...)`; no test body changed or dropped. The controller checked the `IngestionJob` resolution. Gates: `./gradlew build` (1,094 tests) and `npm run verify` (140) green.

## Task 6 — evidence

One dashboard, uid `flight-aggregator`, title "Flight aggregator", provisioned from `deploy/grafana/dashboards/flight-aggregator.json` by a file provider (`deploy/grafana/provisioning/dashboards/dashboards.yaml`, `allowUiUpdates: false`, `disableDeletion: true`). Its seven panels read Prometheus (datasource uid `prometheus`) with the names Task 2 recorded, and combine instances as the alert rules do.

| Panel | Query | Display |
|---|---|---|
| Provider calls | `sum by (provider, outcome) (rate(provider_calls_total[$__rate_interval]))` | time series, calls per second |
| Circuit breakers | `max by (name, state) (resilience4j_circuitbreaker_state) == 1` | one tile per provider and current state; green closed, orange half open, red open or forced open |
| Data freshness | `min by (provider) (ingestion_provider_last_fresh_seconds)` | one tile per provider; red from 5,400 s (`ProviderDataStale`, Q4) |
| Ingestion | runs in the last hour by outcome; `max by (outcome) (ingestion_duration_seconds_max)`; `max(ingestion_consecutive_failures)` | time series: the duration on the left axis with a red line at 1,800 s (the 30-minute cadence, §6.4); runs and failures in a row on the right |
| Cache hit ratio | `sum(increase(search_cache_total{result="hit"}[1h])) / sum(increase(search_cache_total[1h]))` | time series, hits over hits, misses and errors in the last hour |
| Discarded records | records discarded in the last hour by provider and reason | time series |
| Partitions | `max(ingestion_partitions_days_ahead)`, `max(ingestion_partitions_default_rows)` | two tiles; red below 34 days and from 1 row, as the alerts fire (Q4) |

### Red → green, one test at a time

1. **`GrafanaProvisioningTest.loadsTheDashboardWithItsSevenPanels`** (`/api/dashboards/uid/flight-aggregator` as the anonymous Viewer: title "Flight aggregator", the seven titles in the plan's order).
   - Red: `[{"message":"Dashboard not found","title":"Not found"}] expected: 200 but was: 404`.
   - Green: the provider file, the dashboard file, the test's container gets `deploy/grafana/dashboards` at `/etc/grafana/dashboards` as compose mounts it, and compose mounts it read-only. `GrafanaProvisioningTest`: 8 tests, none failed.
2. **`everyPanelQueriesAKnownMetric`** (every panel has a query; each expression names at least one metric, and only names of Task 2's recorded list, held in `StoredMetrics.NAMES`: 167 names, 167 distinct).
   - Passed on its first run.
   - Mutation M1, `rate(provider_call_total…`: `[metrics of Provider calls that Prometheus does not store: sum by (provider, outcome) (rate(provider_call_total[$__rate_interval]))] Expecting empty but was: ["provider_call_total"]`. Restored.
   - Mutation M2, the name after `unless`, before `offset 1h`, misspelt (`ingestion_records_discard_total`): `Expecting empty but was: ["ingestion_records_discard_total"]`. Restored.
   - Refactor (green to green): both tests read the dashboard through one helper; the recorded names and the PromQL reading moved to `StoredMetrics` beside the test. Mutation M3 after it, `max(ingestion_partition_days_ahead)`: `[metrics of Partitions that Prometheus does not store: max(ingestion_partition_days_ahead)] Expecting empty but was: ["ingestion_partition_days_ahead"]`. Restored; 9 tests, none failed.

### The run (Step 3)

Compose project `p7t6`, every published port moved to `127.0.0.1:38xxx` by a scratch override file (`ports: !override`), since other stacks share the host; Grafana on `38301`, the API on `38080`.
- `docker compose -p p7t6 … up --build -d --wait`: exit 0 in 46 s, every service healthy. The startup run ended at 08:02:58.
- As the anonymous Viewer, the dashboard's API answers `provisioned: true`, `canSave: false`, `canEdit: true` (Q1-A's `viewers_can_edit`: a change on screen, never saved), seven panels.
- Two identical searches, `GET /api/itineraries?origin=MAD&destination=JFK`: 200 in 0.215 s, then 200 in 0.016 s; the scrape shows `search_cache_total` miss 1, hit 1, error 0.
- A manual run with the README's client-credentials token: `POST /api/admin/ingestions` answered 409 at 08:03:08, as designed (the startup run holds the lock for at least one minute, `IngestionJob.SCHEDULED_LOCK_AT_LEAST_FOR`), then 202 at 08:05:17; it ended within a minute, `ingestion_duration_seconds_max{outcome="PUBLISHED"}` 56.3 s.
- Each panel's queries through Grafana's `/api/ds/query` with no credentials, over the last hour: **every one returned data.** Provider calls: three series, peaking at 0.022 calls/s during the manual run. Circuit breakers: `ALPHA: closed`, `BETA: closed`, `GAMMA: closed`. Data freshness: about 95 s for each provider. Ingestion: runs `PUBLISHED` 2, duration 56.3 s, failed runs in a row 0. Cache hit ratio: 0.5. Discarded records: 12 series (3 providers × 4 reasons), each twice one run's count (for example Alpha `PRICE` 26 = 2 × 13). Partitions: 37 days ahead, 0 rows in `DEFAULT`.
- A screenshot for the user, kept out of git: `.superpowers/sdd/2026-11-15-observability-and-delivery/task-6-screens/dashboard.png` (headless Chrome with a throwaway profile, kiosk mode, the last 30 minutes).
- The names Prometheus stored this time: 173, Task 2's 167 plus six `spring_security_authentications_*` (an authenticated admin call, which Task 2's run did not make). None of the 167 was missing.
- `down -v`: exit 0; no container, volume or network of `p7t6` left.

### Deviations from the plan's wording

- **`compose.yaml` gains one line** (not in the task's file list): Grafana mounts `./deploy/grafana/dashboards` at `/etc/grafana/dashboards`, read-only, where the provider reads it. The dashboard cannot load without it.
- **The recorded names are a constant of `StoredMetrics`, beside the test,** not of `GrafanaProvisioningTest` itself: 160 lines of names and the PromQL reading are one concept of their own, and `GrafanaProvisioningTest` is shared with Tasks 1, 5 and 8, so it keeps only the two tests and the mount. Task 8's JVM dashboard can use the same check.
- **Runs and discarded records count a new counter in full.** `ingestion.runs` and `ingestion.records.discarded` are created on their first increment, and `increase` ignores what a series held when Prometheus first saw it, so the startup run would never count. Their panels add `(x unless x offset 1h)`, the series that did not exist an hour ago, at their full value; the run's "runs 2" shows the startup run counted.
- **The duration is `ingestion_duration_seconds_max`:** the timers have no buckets, and Micrometer's max holds a run's duration for its rolling window (about two minutes), so each run is a spike of its own length against the 1,800 s line.
- **Colours follow Q4's alert values** (freshness, days ahead, `DEFAULT` rows) instead of Grafana's default thresholds, which turn anything above 80 red, a healthy 95-second freshness included.

### Concerns

- **Provider calls use `rate`, which misses a counter's first increment** for the same reason: `provider.calls` is created on first use, so the startup run's calls do not show; from the second run on they do. Counting from zero would mean registering the meters at zero at startup, in production code (see Extras).
- **The recorded list will drift.** Tasks 4 and 5 add tracing and log export; a panel that later uses a name they add needs `StoredMetrics.NAMES` updated from a new run, or the test fails (closed, never silently).
- **The PromQL reading is a set of regular expressions,** not Prometheus's parser; it errs towards reading more names, so a construct it does not know fails the test rather than passing it.

### Extras for the user to decide

- **E-T6-1. Register the counters at zero at startup** (`provider.calls`, `ingestion.runs`, `ingestion.records.discarded`, for every provider, outcome and reason). Value: `rate` and `increase` see the first run, the dashboard's panels need no `unless … offset` term, and Task 7's e2e reads a series even before a run. Cost: production code in `CountedFlightProvider`, `IngestionMetrics` and `LoggedDiscardedRecords`, and a test each. Left out unless you approve.

### Gates

- Between steps, `GrafanaProvisioningTest` alone (exit 0, 9 tests after Step 2).
- `./gradlew build` on the final files: `BUILD SUCCESSFUL` (exit 0) in 2 min 25 s, 1,037 tests across the modules (Task 2's 1,035 plus this task's two), none failed or skipped. `frontend/` is not touched.
- Self-review (thermo-nuclear) of the whole diff:
  - Grafana's own mechanism: a file provider, read-only, as the datasources are; the test mounts the files where compose does, like Task 1's.
  - Structure: the recorded names and the PromQL reading moved out of the shared `GrafanaProvisioningTest` into `StoredMetrics` (the test went from 416 lines to 258; `StoredMetrics` 172).
  - Nothing passes silently: a panel without a query, an expression with no metric name, or a word the reading cannot place each fails the check; Mutations M1 to M3 show it failing on a misspelt name.

## Task 6 — fix round

The review (one double reviewer): functional APPROVED, code CHANGES REQUESTED for one Important finding, I1: the Data freshness and Partitions stat tiles were range queries reduced with `lastNotNull`, so a gauge gone missing or reading NaN kept its last healthy value in green while its alert counts it as stale and fires.

### The fix (red → green)

- **Test: `GrafanaProvisioningTest.showsAnUnknownGaugeAsUnknownNeverAsItsLastValue`.** For each gauge tile (Data freshness A, Partitions A and B): its query is instant, the panel's `noValue` is "unknown", and its `null+nan` value mapping reads "unknown" in the colour of the alert that fires on it: red for freshness and days ahead (`ProviderDataStale`, `PartitionHorizonShort`), orange for rows in `DEFAULT`, whose unknown is `PartitionHorizonShort`'s.
- Red: `[Partitions/A is instant] Expecting value to be true but was false`.
- Green: both panels' targets `instant: true, range: false`; `noValue: "unknown"` and a `special` `null+nan` mapping in each panel's defaults, overridden to orange for the rows; freshness drops its sparkline (no range to draw); both descriptions now say what "unknown" means and when the alert fires on it. 10 tests, none failed.
- Mutations, each red and restored: no `noValue`; the rows' unknown red instead of orange; `range` left on; `nan` instead of `null+nan`. Refactor: the tiles became a `GaugeTile` record; a mutation after it was red, restored green.

### Checked against the running stack

The compose stack was not stopped or restarted, and its Grafana lets no one sign in, so a throwaway Grafana 13.3.0 joined its network with this branch's provisioning, reading the running Prometheus, plus a scratch dashboard with copies of the two panels whose expressions read NaN or a missing metric. In headless Chrome: NaN shows each provider, days ahead and rows as "unknown" (red, red, orange); a missing gauge shows one red "unknown" tile per panel; the real dashboard shows healthy values in green as before. The container was removed. Screenshots kept out of git (`.superpowers/…/task-6-screens/fix-*.png`).

### Minors left for the plan's closing round

M1 (Provider calls' description misses the startup-run caveat), M2 (no test pins the dashboard's read-only provisioning), M3 (`_max` duration spikes fall between steps on long ranges), M4 (the `unless … offset 1h` term also counts in full after a Prometheus restart), M5 (`everyPanelQueriesAKnownMetric`'s Javadoc promises renames; optional `parse_query`), M6 (Discarded records legend clipped). Not fixed here, per CLAUDE.md.

## Task 6 — fix round re-review

Re-review of `7d45a2c..27e7866`: **Functional APPROVED, Code APPROVED** (thermo-nuclear applied). I1 closed: instant queries, `noValue: "unknown"`, a `null+nan` mapping; freshness and days ahead unknown red (their `gauge: unknown` rules), rows in DEFAULT unknown orange (its unknown is PartitionHorizonShort's, as the rule file says). The reviewer's own mutation (Partitions B not instant) turned `showsAnUnknownGaugeAsUnknownNeverAsItsLastValue` red. Minors for the closing round, with the first review's M1–M6: F-m1 the freshness description (`flight-aggregator.json:74`) leaves out "a provider with nothing stored" among the NaN causes; F-m2 `GrafanaProvisioningTest.java:228` a renamed panel gives a bare NullPointerException; F-m3 no test shows `noValue` renders in the mapping's colour (the screenshot does).

## Task 5 — evidence

Logs in Loki, with the jump from a line to its trace (Q2-A), plus Task 4's review Minor F-M1 (`service.name` was `unknown_service`). Branch `worktree-agent-a1def00b5149bfa77`, rebased on `main` `051a20e`.

### Library (spec 6.1)

Boot 4.1.2 builds the OpenTelemetry SDK's logger provider and its OTLP log exporter (`management.opentelemetry.logging.export.*`), but not the Logback appender that feeds it, and its BOM does not manage it. `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0` on Maven Central: newest `2.33.1-alpha` (2026-10-26), whose POM needs `opentelemetry-api` 1.67.0, above the BOM's 1.62.0 (Gradle would take the higher API and leave the SDK at 1.62.0). The appender's POMs: 2.27.0 → API 1.61.0, **2.28.0 and 2.28.1 → 1.62.0**, 2.29.0 → 1.63.0. Pinned: **`2.28.1-alpha`**, published 2026-05-20, the newest release on the BOM's OpenTelemetry; resolved `opentelemetry-api:1.62.0` everywhere. Every release of the artifact carries the `-alpha` suffix (the instrumentation project's marker for that module's API stability, not a pre-release of the project): see "For the user" below.

### Red → green, one test at a time

**F-M1.** `TracingEndToEndTest.namesTheServiceInEverySpan` (after a run, every span's resource `service.name` is `flight-aggregator`).
- Red: `Expecting ArrayList: ["unknown_service", "unknown_service", "unknown_service", "unknown_service"] to contain only: ["flight-aggregator"]`.
- Green: `spring.application.name: flight-aggregator`. Boot builds the resource's `service.name` from it, which Loki turns into its `service_name` label; the console's ECS `service.name` gained it too.

**Step 1.** `StructuredLogsTest.everyLineOfARunCarriesItsTraceSpanAndCorrelationIds` (Boot's `OutputCaptureExtension`; every JSON line of a `com.flightaggregator` logger written during `job.run()` has `trace.id` (32 hex), `span.id` (16 hex) and `correlation.id`, one trace id across the run, and the "Ingestion run ended" line is among them).
- Red: `[trace.id in {... "message":"Ingestion run ended","traceId":"e2e77d8dbb25d4e3fbf95f34bb48e8de","spanId":"d113c52cfce7b995","correlation":{"id":"..."} ...}] Expected size: 32 but was: 0`. Micrometer Tracing's `Slf4JEventListener` (Boot's bean) already put the ids in the MDC, as `traceId` and `spanId`, not under their ECS names.
- Boot's `logging.structured.json.rename` writes the renamed member as a flat dotted key (`"trace.id":"..."`), not nested like `correlation`; the test reads the key as written and was shown red again without the setting (`Expected size: 32 but was: 0 in: ""`, on the "Kept the last good offers" line).
- Green: `logging.structured.json.rename` `[traceId]: trace.id`, `[spanId]: span.id` (Boot's setting, no code).

**Step 2.** `LogExportTest` (`InMemoryLogRecordExporter` and `InMemorySpanExporter` beans, tracing on).
- `exportsARunsLinesWithTheirTraceContext` (the "Ingestion run ended" record has the `ingestion.run` span's trace id and a `correlation.id` attribute). Red: `java.lang.AssertionError: No record of the run's report line`. Green: the appender (above); `logback-spring.xml` with Boot's `defaults.xml` and `structured-console-appender.xml` plus the `OTEL` appender (`captureMdcAttributes=correlation.id`); `LogExport`, an `InitializingBean` that calls `OpenTelemetryAppender.install(openTelemetry)` with Boot's SDK, declared in `LogExportConfig`. A first declaration in `TracingConfig` failed 18 `ProvidersConfigTest`, `RefreshConfigTest` and `RepriceConfigTest` cases, whose context runners import `TracingConfig` without an `OpenTelemetry` bean; hence its own configuration class.
- `exportsTheKeyValuePairsOfALine`, not in the plan's list; it pins the interface's "key-value pairs are kept, so a provider failure still names its provider and reason" (Beta's "Kept the last good offers" record has `provider=BETA`, `reason=EMPTY_DOWNLOAD`). Red: `Expecting actual: [] to contain exactly (and in same order): ["EMPTY_DOWNLOAD"]`. Green: `captureKeyValuePairAttributes=true`.
- `exportsNoSecretInAnyLogRecord` (after a run and a `GET /api/admin/status` with a bearer token, no record's body or attribute holds a provider key or the token). Passed on its first run. Mutation M1, a request interceptor in `ProviderHttp` that logs each request's `X-Api-Key` at INFO: `Expecting all elements of: ["Tomcat started on port 52894 (http) ...", ...] ... but found: ["alpha-key"]`. Restored; green.
- Seen in passing: the startup lines (Tomcat's) are exported too: the appender keeps up to 1,000 events written before `LogExport` installs the SDK.

**Step 3.** `GrafanaProvisioningTest.linksALogLineToItsTrace` (the Loki datasource, read as the anonymous Viewer, has a derived field `matcherType: label`, `matcherRegex: trace_id`, `datasourceUid: tempo`).
- Red: `Expecting any element of: [] to satisfy the given assertions requirements but none did`.
- Green: the derived field `TraceID` in `datasources.yaml`.
- Then its URL, asserted to be `${__value.raw}`, was red: `expected: "${__value.raw}" but was: ""`. Grafana's provisioning expands `${...}` as an environment variable, so the link would have opened an empty Tempo query. Green: `$${__value.raw}`, Grafana's escape for a literal `$`.

**Review Focus 1 with logs.** `TelemetryUnavailableTest.servesAndIngestsWithTheCollectorDown` (Task 4) already points the logs endpoint at a closed port; now that the appender feeds records, it stays green: a failing log export does not slow a search or a run.

### The run (Step 4)

The main checkout's stack was left running; a throwaway project `p7t5` ran `app` and its dependencies, Grafana, Loki, Tempo and the Collector from this branch, with an uncommitted override file that moved the published ports (Grafana on `127.0.0.1:13001`).
- Red, before compose set the logs endpoint: the startup run ended (its console line with `service.name`, `trace.id`, `span.id`, `correlation.id`); through Grafana's datasource proxy, as the anonymous Viewer, Loki had no label at all and `{service_name="flight-aggregator"}` returned no stream.
- Green: `MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT: http://otel-collector:4318/v1/logs` in `app`'s environment, `app` recreated. Loki's labels: `["service_name"]` only. `{service_name="flight-aggregator"} |= "Ingestion run ended"` returned the run's line, with structured metadata `trace_id 6192501160b9fe4dd0d1c48e0ce59097`, `span_id 65132dc5fe7561cb`, `correlation_id`, `severity_text INFO`, `detected_level info`, `scope_name` (the logger) and each key-value pair (`outcome`, `generation`, `alpha_status`, ...).
- Tempo, through Grafana as the anonymous Viewer: trace `6192501160b9fe4dd0d1c48e0ce59097` is that run, `task ingestionJob.run` → `ingestion.run` (span `65132dc5fe7561cb`, the record's) → 15 `http get`, resource `service.name=flight-aggregator`.
- The jump in a browser (headless Chromium, a throwaway Playwright script): Explore on Loki as the anonymous Viewer shows the line; its details show "View trace"; following it opens a Tempo pane with trace `6192501160b9fe4dd0d1c48e0ce59097`, "flight-aggregator task ingestionJob.run", 17 spans, `ingestion.run` and its `http get` children. Screenshots in the worktree's `.superpowers/sdd/2026-11-15-observability-and-delivery/task-5-screens/` (untracked).
- Stopped with `down -v`; nothing of the project left, the main stack untouched.

### Deviations from the plan's wording

- **The level is not a Loki label.** The plan's interface says the labels are the service name and the level; Loki 3.7's OTLP ingestion makes only `service_name` a label (as Task 1's smoke check saw), and keeps the level as structured metadata (`severity_text`, and Loki's `detected_level`, which Explore uses for its level colours and filters). Loki promotes resource attributes only, and the core Collector has no processor to turn the severity into one. Fewer labels than the plan says, never more: the ids stay out of the labels, as Review Focus 4 asks. Making the level a label is in the extras backlog.
- **The console's ids are flat ECS keys, `"trace.id"` and `"span.id"`,** while `correlation.id` stays nested: Boot's rename setting writes the new name as one key. ECS reads both forms as the same field. Nesting them would replace Boot's `Slf4JEventListener` bean with one of ours: in the extras backlog.
- **`logback-spring.xml` replaces Boot's default Logback configuration:** the OTel appender needs a place in Logback's configuration. It includes Boot's own console pieces, so the console's ECS lines are unchanged, but `logging.file.*` no longer writes a file (none was configured).
- **A test the plan does not list, `LogExportTest.exportsTheKeyValuePairsOfALine`,** pins the interface's key-value pairs.
- **One sentence in `docs/architecture/ingestion.md`** says what a log line now carries; the observability document itself is Task 7's.

### For the user

- **The appender's version carries `-alpha`.** `CLAUDE.md` asks for stable GA releases with no milestones or release candidates. `opentelemetry-logback-appender-1.0` has never been published without the suffix: each release of OpenTelemetry's Java instrumentation (2.28.1 here, a GA release of that project) publishes this module as `-alpha`, the marker of a module whose API may change. Q2-A, decided by the user, needs this appender, and Boot's reference documentation installs the same one. The alternative is a hand-written Logback-to-OpenTelemetry appender. Kept as built; the user may decide otherwise.

### Concerns

- Every new test context (`StructuredLogsTest`, `LogExportTest`) repeats the provider keys and the WireMock setup of `TracingEndToEndTest`; a shared helper in `OneFlightProviders` would remove it (Minor, for the closing round with Task 4's C-M2).

### Gates

- Module tests between commits (bootstrap), green before each commit.
- `./gradlew build` on the final state, rebased on `main` `051a20e`: `BUILD SUCCESSFUL` (exit 0); 1,104 tests across the modules, none failed or skipped. `frontend/` is not touched.
- Self-review (thermo-nuclear) of `main...HEAD`: Boot's own mechanisms first (`spring.application.name`, `logging.structured.json.rename`, Boot's logger provider and OTLP exporter, Boot's Logback includes); the one piece Boot leaves to the application, installing the appender, is one `InitializingBean` in its own configuration class, so the context runners that import `TracingConfig` need no `OpenTelemetry`; no secret leaves in a record (mutation M1); an export that fails is dropped by the SDK's batch processor (`TelemetryUnavailableTest`). Found and fixed in the review: the `logback-spring.xml` comment now says that it replaces Boot's default configuration and that no log file is written. Left as a Minor: the duplicated test setup (Concerns).

## Task 5 — the user's answer

The appender's version ("For the user" above): **accepted** by the user on 2026-11-16, `2.28.1-alpha`, recorded as an exception in the plan's Global Constraints.

## Task 7 — evidence

The observability proven end to end against compose, and documented. Branch `worktree-agent-ad7b69291fd00b999`, built on `main` `d27071f` and rebased on `eac8515` (append-only conflicts in this report and the extras backlog, both sides kept, `main`'s first). No per-task review (the user's decision of 2026-11-16, about 10:02): the group review covers it.

- **Files:** `frontend/e2e/observability.spec.ts` (six tests) and `frontend/e2e/telemetry.ts` (reads Tempo, Loki and dashboard queries through Grafana's API with no credentials, Prometheus's rules on its own API, and the admin token from Keycloak; each read polls up to 60 s); `frontend/playwright.config.ts` gains the project `observability`, once, in Chromium, after every browser's admin project and before `rate-limit`. Docs: `docs/architecture/observability.md` (new), README "Observability" and its index, `docs/architecture/README.md`, one sentence in `dashboard.md` (the projects' order), spec §6.10 (Q1-A and Q2-A with their rejected options).
- **The live stack:** a throwaway compose project `p7t7` from this worktree, every published port moved by a scratch override (`ports: !override`; Grafana `33001`, Prometheus `39090`, Keycloak `38180`, frontend `33000`), so the user's running stack was never stopped, restarted or rebuilt. The specs ran against it through a scratch Playwright config that imports the repository's and changes only `baseURL` and paths, plus `GRAFANA_URL`, `PROMETHEUS_URL`, `KEYCLOAK_URL`; `--project observability --no-deps`. Each mutation below changed `app`'s environment through a second override, or a file of this worktree that `p7t7` mounts (never the main checkout's), and was undone with `git checkout` and a restart or recreation.

### Red by mutation, one test at a time

Every test runs over code that already exists, so each was written, run green, then shown red by a mutation of the running stack and green again once restored (plan 7 Global Constraints).

1. **`'shows a search as one trace'`** (a search through nginx with a `traceparent` the test draws at random; Tempo holds the server span `http get /api/itineraries` of `flight-aggregator`, its parent the test's span id).
   - The plan's mutation, sampling 0 (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0`), **left it green**: Boot's sampler is parent-based, so a caller's sampled `traceparent` is recorded whatever the ratio.
   - Mutation M1b, B3 propagation only (`MANAGEMENT_TRACING_PROPAGATION_TYPE=B3`): red, `Error: No trace 32387cbb4ee8deef69c5c5b7bb85f406 in Tempo after 60 s`. Restored: green.
2. **`'shows a manual run as one trace with every provider request'`** (`POST /api/admin/ingestions` with the README's client-credentials token, waiting out a 409; the status's generation + 1 is the run's; Loki's `{service_name="flight-aggregator"} |= "Ingestion run ended" | generation = "<n>"` gives the line's `trace_id`; Tempo's trace holds `ingestion.run` and `http get` spans whose `uri` starts with `/alpha/`, `/beta/` and `/gamma/`).
   - Mutation M1, sampling 0: the run's own trace is not sampled; its line reaches Loki with a trace id Tempo never receives. Red: `Error: No trace 59bbe955087c73110768f5adaa2709d7 in Tempo after 60 s`. Restored: green.
   - Two failures on the way, neither about the behaviour: recreating `app` mid-run left its ShedLock row locked for 29 minutes (`LOCK_AT_MOST_FOR`, as designed; released in the throwaway database, and `app` recreated only with no run in flight from then on); and a run took 2 min 36 s on this Mac with two stacks up, so the test waits up to 240 s for a run (CI runs one stack, about half a minute).
   - A real flaw found and fixed: the trace was read before its parent span arrived (`Expected value: "ingestion.run"`, `Received array: ["http get", …]`); spans reach Tempo in batches as they end. `tempoTrace(traceId, holding)` polls until the trace holds the named span, which ends after the spans under it.
3. **`'jumps from a log line to its trace in Grafana'`** (Chromium, no sign-in: Explore on Loki, the run's line, its "View trace" link, the trace view shows `ingestion.run`). Mutation M2, the plan's: the derived field's `datasourceUid` → `no-such-tempo`, Grafana restarted. Red: `expect(locator).toBeVisible() failed`, `getByText('ingestion.run')`, `element(s) not found`. Restored: green.
4. **`'keeps ids out of Loki's labels'`** (Loki's labels, read once `service_name` is among them, hold none of `trace_id`, `span_id`, `correlation_id`). Mutation M3: Loki's `limits_config.otlp_config.log_attributes` indexes `correlation.id` as a label; after a manual run the labels were `["correlation_id","service_name"]`. Red: `+ Array [ "correlation_id" ]`. Restored, Loki recreated (its index kept the label for the stored logs): labels `["service_name"]`, green.
5. **`'loads the alert rules, none firing on a healthy stack'`** (the rule names are exactly `IngestionFailing`, `PartitionHorizonShort`, `ProviderDataStale`, `RowsInDefaultPartition`; every health `ok`; none `firing`, `pending` allowed). Mutation M4: `PartitionHorizonShort`'s `< 34` → `< 340`, Prometheus restarted. Red: `+ "name": "PartitionHorizonShort"`, `+ "state": "firing"`.
   - Found by re-running M4 right after Prometheus's restart: **green with the mutation in place**, because each rule had been evaluated once before Prometheus scraped the app. The test now waits for the app's gauge through Grafana, then `alertRules(since)` polls until every rule's `lastEvaluation` is after that moment. M4 again, run at once after the restart: red (`"state": "firing"`). Restored: green.
6. **`'feeds every dashboard panel'`** (two identical searches; every target of every panel of `flight-aggregator`, run through Grafana's `/api/ds/query` over the last hour, all at once, each polled until it returns a series with a value). Mutation M5: the Cache hit ratio's `{result="hit"}` → `{result="hits"}` (a stored name, which `GrafanaProvisioningTest` cannot see; only the data can), Grafana restarted. Red after the minute: `+ Array [ "Cache hit ratio/A" ]`. Restored: green.

The six together on the restored stack, on the final files: `6 passed (1.1m)` (search 6.2 s, manual run 43.9 s, jump 2.2 s, labels 5 ms, rules 14.1 s waiting for a fresh evaluation, panels 222 ms).

### Deviations from the plan's wording

- **Test 1's mutation is B3 propagation, not sampling 0**, which cannot turn a caller-sampled trace red (above); sampling 0 is test 2's mutation instead, where the app starts the trace.
- **`telemetry.ts` has more than the four functions the plan names:** `lokiLabels`, `lokiExploreUrl`, `alertRules` and `dashboardPanels` serve tests 3–6; `tempoTrace` takes the span to wait for (above). Prometheus's rules are read on its own API (the plan's "Prometheus `/api/v1/rules`"), the rest through Grafana.
- **The addresses have environment overrides** (`GRAFANA_URL`, `PROMETHEUS_URL`, `KEYCLOAK_URL`, and `ADMIN_SERVICE_CLIENT_SECRET` as compose reads it), defaulting to compose's, so the project ran against `p7t7` here. `baseURL` stays fixed in the config; making it an environment variable is in the extras backlog.
- **The whole suite was not run here:** on `p7t7` the admin specs would sign in through `/config.json`'s Keycloak, `localhost:8180`, the user's stack. CI's `e2e` job runs the whole order (admin ×3, observability, rate-limit) on the push.

### Concerns

- **The approved extras E1 (trace to logs), E2 (database spans), E4 (logs panel and alert list on the dashboard), E5 (HTTP panel) and E7 (`mock-gds` telemetry) are not built by any task**, Task 7 included (its text names none of them). For the controller or the user: build them, or move them to the backlog.
- The jump test reads the newest run line of the last hour; on a stack whose last run is older (none in CI, where the suite runs minutes after startup) it would wait out its minute and fail.

### Gates

- `npm run verify` in `frontend/`: exit 0 (typecheck, lint, format check, 151 component tests); again after the rebase on `eac8515`, exit 0, 155 component tests (main's airline suggestions added four).
- `./gradlew build`: exit 0, `BUILD SUCCESSFUL`; 1,104 tests, none failed or skipped (no backend change; the test tasks came from the build cache); again after the rebase, exit 0, every task up to date (main's six commits touched no backend file). After the last end-to-end run Prettier reflowed one line of `telemetry.ts` (format only).
- Self-review (thermo-nuclear) of the diff: `telemetry.ts` regrouped by backend; one `json()` request helper (10 s limit, a non-2xx answer an error naming the URL) instead of three copies; an unused `spanId` removed; a custom `poll` kept because Playwright's `expect.poll` does not return the value it waited for. No existing helper for client-credentials tokens or telemetry reads in `frontend/e2e/`. Nothing fails silently: every read that finds nothing within its minute throws, naming what it waited for; a Grafana query error throws at once.
- Throwaway stack: `docker compose -p p7t7 … down -v`, exit 0; no container, volume or network of `p7t7` left; the user's `flight-aggregator` stack still running, 11 services, untouched.

## Review group 1

One reviewer, functional then code, over `3a57b73..9377e02` on `main` (plan 5's C1/F3/F5/F6; the airport and airline suggestions, the decimal comma, the WebKit keyboard e2e; plan 7 Tasks 5 and 7; CI concurrency), by the user's decision of reviews by group: **Functional APPROVED, Code APPROVED**; nothing Critical or Important. Ran `./gradlew build --no-build-cache --rerun-tasks` (1,104 tests), `npm ci && npm run verify` (155), read CI 43847065593 (green; e2e 10 min 8 s, 40 passed, the 6 observability tests among them) and eight mutations, each red (C1 lookup back in the `try`; name conversion off; airline conversion off; the decimal comma off; "Vueling" renamed in the mock; the Loki derived field's `$$` escape; `captureKeyValuePairAttributes` off; the ECS `traceId` rename removed). Its escalation (E1, E2, E4, E5, E7 unbuilt) was already put to the user, who chose E1, E4, E5.

Minors, for Task 10's final review:

| Id | Finding |
|---|---|
| M1 | `dashboard.md:30`: with the datalists, WebKit does not submit on Enter from From/To/Airline (Safari needs a second Enter while suggestions match; WebKit on Linux never); "Search" still works by keyboard. Told to the user |
| M2 | `searchParams.ts:49`: "1.234,5" becomes "1.234.5" in the field and URL, next to the error: input rewritten into something never typed |
| M3 | `FilterBar.tsx:62`, `searchParams.ts:49`: two normalisation sites; a shared `?maxPrice=55,5` is accepted while `?origin=madrid` errors (documented) |
| M4 | `eslint.config.js:34`, `tsconfig.app.json:26`, `tsconfig.node.json:21-27`: each Node-environment test listed by hand in three files |
| M5 | `observability.spec.ts:145`: the generation is read after the 202; a run publishing before that read makes the test wait 240 s and fail |
| M6 | `progress/2026-11-15-dashboard.md:1025-1028,1046`: pre-rebase hashes; on `main` they are `48a066b`, `141b7cc`, `e759970`, `7223931` |
| M7 | `continuation.md:7`: "Last updated" had grown stale and self-contradictory (rewritten by the controller at this checkpoint) |
| M8 | `LogExportTest`, `StructuredLogsTest` repeat `TracingEndToEndTest`'s provider keys and WireMock setup |
| M9 | `RepriceItineraryService.java:200-203`: a provider without adapter surfaces only at its first re-price; a startup check would fail earlier (optional) |

## Task 9 — draft

`SOLUTION.md` drafted at the repository root: Steps 1 and 2 of plan 7 Task 9. Step 3, the user's review, is still to come. Written from `main` at `72bce35`, with the `writing-clearly-and-concisely` skill applied to every paragraph, and from the sources the task names: spec §1, §2, §4–§6, §8, §9 and §11, `docs/architecture/`, the roadmap, plans 0–7, the progress reports, `docs/ai/README.md` and sessions 01–09. It takes no design decision of its own (plan 7, "Decisions this plan takes"): every paragraph restates a decision of the spec, a plan or a session, with the reason and the alternative those record.

### Step 2 — check

**Every item of Task 9's Interfaces list has its section:**

| Interfaces item | Section of `SOLUTION.md` |
|---|---|
| Architecture: modules, ports, flows, linking `docs/architecture/` | 1. Architecture (modules and ports, a runtime diagram, four flows, the stack, the adapters) |
| Modelling: legs and offers, deduplication, itinerary identity, price and half-up, freshness, the airport catalogue, the closed provider set | 2. Modelling, one paragraph each (the catalogue and the provider set share "Reference data the domain controls"), plus connections and the Carrier filter |
| Ingestion and freshness: hybrid model, double-buffered slots, daily partitions, stale-route refresh, cadence, plan 4's measured run | 3. Ingestion and freshness, one paragraph each, plus the two time bounds of a download |
| Pagination at 1M: keyset, one index per sort order, the cross-partition merge, the cursor and its generation, plan 5 Task 13's `EXPLAIN` figures | 4. Pagination at 1M, one paragraph each, plus "Load more"; the `EXPLAIN` figures are a placeholder |
| Caching, outages, re-price, security, observability, with the hit ratio, the honest note on Valkey, who reads the metrics | 5., one paragraph each; the honest note has its own paragraph; the hit ratio is a placeholder |
| Testing and quality gates: TDD, ArchUnit, coverage and formatting gates, end to end, CI | 6. Testing and quality gates |
| Assumptions (four named, and sessions 01–02's) and limitations (six named) | 7. Assumptions and limitations: nine assumptions, the six limitations |
| AI usage: process, `CLAUDE.md`, skills, hooks, sessions, the change of course, what the reviews caught | 8. AI usage, with continuity after the dropped sessions (session 09, `continuation.md`) |

**Every relative link resolves.** A script (kept out of git) read every inline link target in `SOLUTION.md`, checked that the file or folder exists in the worktree and, for a `#fragment`, that the target file has a heading with that anchor under GitHub's slug rules (lower case, punctuation dropped, spaces to hyphens). Result: **114 links, 0 broken.** The script was shown to catch a failure first: on a test file it reported `MISSING ANCHOR` for a wrong fragment and `MISSING FILE` for a missing target, and exited 1. Links to documents later tasks create (`docs/architecture/search-api.md`, `docs/architecture/observability.md`, the session after 09) are not written yet; each is a `TODO` comment instead.

**Every figure matches its source:**

| Figure in `SOLUTION.md` | Source | Matches |
|---|---|---|
| 277,605 of 999,466 itineraries visiting an airport twice | [progress 2026-11-13](2026-11-13.md), "Dataset statistics" | yes |
| 38,227 of 94,639 direct and one-stop itineraries single-airline (40.4%) | [progress 2026-11-14](2026-11-14.md), round 2 "Before / after" | yes |
| 1,021,205 itineraries; 14.4 s cold (`PT14.374S`), 11.9 to 12.0 s later; 316 MiB under `-Xmx512m` | [progress 2026-11-15](2026-11-15.md), plan 4 "Task 11 — evidence" | yes |
| With Beta failing: 1,021,094 itineraries, Beta's 3,579 offers kept | same | yes |
| Valkey: 250 ms command timeout, about 1.25 s for five commands, 10 s skip, fallback within 500 ms | [progress, plan 5](2026-11-15-search-api.md), "Task 5 — evidence" and its extras; plan 5 Q9 and Task 5 | yes |
| Up to 29 minutes of lock after a crash, runs nearly an hour apart | [progress 2026-11-15](2026-11-15.md), "Re-review of the fix wave" | yes |
| 67 tests; about 14 red phases | [progress 2026-11-13](2026-11-13.md) (67 tests); session 04 (14 red phases) | yes |
| 27 ambiguities, 4 questions; four gaps; 50 to 60 minutes per task | sessions 01, 02 and 09; roadmap | yes |
| About 27 minutes for a slow provider without the time limiter | plan 4 decisions; session 09 (a computed bound, not a measurement) | yes |

The other numbers are design values, each cited to its spec section or plan question: the cadence, threshold and TTL (§6.4), 45 minutes and 24 hours (§4), today + 37 days (§4.3; `ingestion.md`), 2.5 s and 60 s (§6.1), two million itineraries (plan 4), six indexes (plan 5 Q6), 200 characters (plan 5 Task 9), 60 and 10 requests a minute (plan 5 Q9), the four alert thresholds (plan 7 Q3–Q4), 90% and 80% (§6.11).

**Length:** about 4,300 words of prose (link labels included), about 15 minutes for a technical reader; the depth stays in the spec, linked from every paragraph.

### Placeholders left, and the task that fills each

Each is an HTML comment `<!-- TODO(plan N Task M): … -->` in `SOLUTION.md`; those that stand for a figure also carry a visible bracketed note in the text.

| Placeholder | Section | Filled by |
|---|---|---|
| The "Draft" note at the top | intro | plan 7 Task 9 Step 3, once the rest are filled |
| Link to `docs/architecture/search-api.md` | 1, "Four flows" | plan 5 Task 13 (creates it) |
| Duration of one stale-origin refresh | 3, stale-route refresh | plan 5 Task 13 (Step 3 run) |
| The reason the cadence stays at 30 minutes (visible note "For the user") | 3, cadence | plan 7 Task 9 Step 3, the user's decision, recorded in spec §6.4 first |
| Load time with six indexes per slot against plan 4's 12 to 14 s | 4, indexes | plan 5 Task 13 |
| `EXPLAIN (ANALYZE, BUFFERS)` of pages 1 and 50 per sort and filter, and the search latency | 4, merge across partitions | plan 5 Task 13 |
| The cache hit ratio | 5, honest note on Valkey | plan 7 Task 6 (Step 3 run), or Task 7 |
| Search with Valkey stopped | 5, honest note on Valkey | plan 5 Task 13 (and plan 5 Task 12's `SearchWithoutValkeyTest`) |
| Re-price with Beta stopped and with all three stopped | 5, re-price | plan 5 Task 13 |
| Link to `docs/architecture/observability.md` | 5, observability | plan 7 Task 7 (creates it) |
| Final test counts and JaCoCo coverage | 6, gates | plan 7 Task 10 (Step 4) |
| Link to the session record after session 09, and any later review finding | 8 | plan 7 Task 10 |

### For the user's review (Step 3)

- **The cadence (§3).** Spec §6.4 chose 30 minutes because "rebuilding ~1M itineraries takes minutes", and said the values would be tuned against the measured run; plan 4 measured 12 to 14 s. `SOLUTION.md` states both and leaves the reason to you rather than inventing one.
- **The hit ratio (§5).** No task measures a hit ratio under realistic traffic: plan 7 Task 6 Step 3 reads the panel after two identical searches, which gives a toy value. Decide which figure to cite, or whether to present the ratio only as the metric that would show it.
- **Rejections stated by inference.** Three "Rejected" lines restate an alternative the sources imply rather than name: a sequence for the itinerary id (§4's "16 bytes instead of 8"), the newest quote for freshness, and in-memory fakes in tests (§6.1's "instead of in-memory fakes").
- **Presentation.** An "At a glance" list and a Mermaid runtime diagram open the document; neither adds a decision.
- **README.** `SOLUTION.md` is not indexed: the index has no slot for it, and the "Work in progress" note already names the file; Task 10 Step 3 links it in the final README. This report is indexed under "Progress reports", with the same line Task 1's branch adds (`f5e6520`), so the two merge cleanly.

### Gates

- `./gradlew build`: `BUILD SUCCESSFUL`, exit status 0 (66 tasks, 36 from the build cache). No code changed; `npm run verify` does not apply (`frontend/` untouched).
- Self-review against the outline and `brief-scope`: every paragraph traces to the spec, a plan, a session or a progress report; no requirement is added or dropped; the open cadence reason is left to the user instead of being decided here.

Extras: none.

## Task 9 — evidence

The placeholders that later tasks could fill are filled, and the text is brought up to date with what changed since the draft. Rebased first on `main` at `9377e02`, then at `d37b894`, `e8e8228` and `9ad9528` (docs only). Twice the same conflict, this report (append-only): `main`'s sections first, then the draft's. The user's four review points stay as applied (`59dcb9a`). The `writing-clearly-and-concisely` skill applied to every new sentence. No design decision is taken here.

### Placeholders filled, each from its report

| Placeholder | Section | Filled with | Source |
|---|---|---|---|
| Link to `search-api.md` | 1, "Four flows" | the link | `docs/architecture/search-api.md` |
| One stale-origin refresh | 3, the refresh | Madrid: 15.0 s, 530,603 of 1,033,722 itineraries, the row held 13.9 s; 2 of 3 refreshes at once failed | plan 5 progress, "Task 13 — evidence", Step 3 |
| Load time with six indexes against plan 4's 12 to 14 s | 4, indexes | 23.8 to 27.5 s; one run's `COPY` 15.0 s and `ANALYZE` 5.1 s | same |
| `EXPLAIN` figures and search latency | 4, merge | 39 partitions; `Limit` over `Merge Append`, no sequential scan; warm 23 of 24 statements 0.2 to 14.7 ms, the slowest 42 ms (departure, origin alone, before V8); up to 321 ms over a refresh's dead rows; HTTP uncached 9 to 60 ms (first 104 ms), cached 4.6 to 6.8 ms; 50 pages, 1,000 distinct rows, median 5.0 ms (price) and 16.8 ms (departure from Madrid) | same |
| V8 and V9 (origin and destination alone) | 4, indexes | without V8 JFK by price 948 ms warm, up to 1,351 ms cold; without V9 3 to 4 s on the re-review's host; with them 0.3 to 7.1 ms warm; twelve indexes: runs 34.9 to 41.6 s, 31.4 to 33.6 s with V9's dropped | plan 5 progress, "End-of-plan fix round 1 — Importants" and "— re-review fixes" |
| Search with Valkey stopped | 5, Valkey | 12 repeats 3.8 to 10.7 ms (4.4 to 28.9 ms with Valkey up); two new searches 15 and 29 ms; `DEGRADED` with 200; back on its own | plan 5 progress, "Task 13 — evidence" |
| Re-price: confirmed, Beta failing, all stopped | 5, re-price | 0.13 to 1.03 s; `PRICE_CHANGED` from another offer 5.8 s, `ALTERNATIVE` 1.5 s; `503` with `Retry-After: 60` in 13.8, 1.35 and 0.02 s, kept as built by the user | same; plan 5, "Decided by the user at the end-of-plan review" |
| Link to `observability.md` | 5, observability | the link | `docs/architecture/observability.md` |

"Beta stopped" in the draft's placeholder is "Beta failing every request" in the run (its error rate set to 1), and the text says so.

### Text brought up to date

- `/health` without `db`, about 2 s instead of 4 s (§5, outages; plan 5, "Decided by the user on the health probe's time").
- One refresh at a time, skipped before downloading, and the run's ceiling on a refresh (§3; plan 5 decisions, closing rounds 1 and 3), with the burst of 32 origins.
- Twelve indexes per slot instead of six, and why (§4).
- The dashboard's airport and airline suggestions (§2, reference data; `dashboard.md`).
- The `-alpha` appender as the user's exception to the version rule (§1, the stack; plan 7 Global Constraints).
- M1 and M4 as known security limits (§7), linking the backlog's "Left by plan 5's closing round 3"; §5's `X-Forwarded-For` sentence now says "a client on the internet" and points to them.
- The `admin-service` client named in §5's security paragraph, for the Interfaces item "Admin access".
- Eight ArchUnit rules in §6, not seven: closing round 3 added the ban on Jackson 2 (`HexagonalRules.nothingUsesJacksonTwo`). §1's "seven rules enforce the packages" still holds.
- Logs in Loki with the jump to Tempo were in the draft already. Extras E1, E4 and E5 are not on `main` (it was at `9ad9528` when this was written), so §5 holds a `TODO(plan 7 Task 10)` naming them.

### Step 2 — check

- **Every Interfaces item has its section:** as in the draft's table, plus "Admin access" in §5's security paragraph (the `admin-service` client, client credentials, the README's commands).
- **Every relative link resolves.** A script (in the session's scratch folder, out of git) reads each `](…)` target outside code fences, checks that the file or folder exists and, for a `#fragment`, that the file has a heading with that GitHub slug, numbering repeated headings. Shown red first on a test file: `MISSING ANCHOR t.md#nope`, `MISSING FILE none.md`, exit 1. On `SOLUTION.md`: **134 relative links, 0 broken**, exit 0.
- **Every new figure matches its report:** each was read again from the rows and lines named in the first table above.
- **Formatting:** Prettier checks `frontend/` only, so no format gate applies to `SOLUTION.md`.

### Left for Task 10

- The top "Draft" note (its `TODO` now names Task 10).
- Final test counts and coverage (§6). Its `TODO` now also notes CI's first full end-to-end run in the new order: 10 min, run 43847065593 on `d37b894` (`continuation.md`), to cite from a progress report.
- The session record after session 09 (§8).
- E1, E4 and E5 in §5, if they reach `main`.

### For the user

- **§4's index history.** Q6 rejected adding indexes "only where a measurement shows a slow query"; the end-of-plan review then measured two slow cases and the user added V8 and V9. The paragraph tells it in that order. Check that it reads as you want.
- **§3's refresh.** "The user chose this over the other options Task 13 listed" names the options Task 13 put to you; the decision itself names no rejected option.
- **§3's cadence** still cites plan 4's 12 to 14 s, as review point 1 set it; a run now takes 24 to 55 s on the hosts measured (§4 gives the figures), which leaves "the database is not what limits the cadence" true.
- **§7 now has eight limitations,** the plan's six and M1 and M4.

Extras: none.

## Extras E1, E4, E5 — evidence

The approved extras that no task had built, as the user chose on 2026-11-16 at about 11:13 (option B, recorded in the plan's "⚠ Extras" section and session 09): E1 (a link from a trace to its logs), E4 (a logs panel and an alert list on the dashboard), E5 (an HTTP panel). E2 (database spans) and E7 (`mock-gds` telemetry) moved to the [extras backlog](../extras-backlog.md). Branch `plan7-extras-e1-e4-e5`, from `main` `e8e8228` (a first worktree from `9377e02` was removed during the user's pause, 11:15–12:40, before any change; `main` had moved by docs only).

- **Files:** `deploy/grafana/provisioning/datasources/datasources.yaml` (Tempo's `tracesToLogsV2`), `deploy/grafana/dashboards/flight-aggregator.json` (panels "HTTP requests", "Alerts", "Logs"), `GrafanaProvisioningTest` (three tests added, three adapted), `frontend/e2e/observability.spec.ts` and `telemetry.ts` (two tests added, one adapted), `docs/architecture/observability.md`, README "Observability".
- **The live stack:** a throwaway compose project `p7ex` from this worktree, every published port moved by a scratch override (`ports: !override`: Grafana `43001`, Prometheus `49090`, Keycloak `48180`, frontend `43000`, app `48080`, mock-gds `48081`); the specs ran through a scratch Playwright config outside the repository that imports the repository's and changes only paths and `baseURL`, with `GRAFANA_URL`, `PROMETHEUS_URL`, `KEYCLOAK_URL`, `--project observability --no-deps`. Mutations changed files of this worktree that `p7ex` mounts, never the main checkout's; the user's stack was not stopped, restarted or rebuilt.

### Red → green, one test at a time

1. **E1, `GrafanaProvisioningTest.linksATraceToItsLogLines`** (Tempo's `tracesToLogsV2`: `datasourceUid: loki`, a tag `service.name` → `service_name`, `customQuery: true`, query `{${__tags}} | trace_id="${__trace.traceId}"`).
   - Red: `expected: "loki" but was: ""`.
   - Green: `tracesToLogsV2` in `datasources.yaml`, `$$` escaping `${…}` from provisioning's environment expansion as Task 5 did, five minutes either side of the span. Refactor: `linksALogLineToItsTrace` reads its datasource through the same `datasource(uid)` helper.
   - Why a custom query: a line holds its trace id as the structured metadata `trace_id`, not in its text, so Grafana's default line filter finds nothing (shown red below).
2. **E1, e2e `'jumps from a trace to its log lines in Grafana'`** (Chromium, no sign-in: the newest run line in Loki names a trace; Explore on Tempo with it; the trace header's **Related logs**; the Loki pane shows "Ingestion run ended").
   - First run red for the test's own reason: it waited for a "Logs for this span" button; Grafana 13.3 calls the link "Related logs" (one in the trace header, one in each span's details, both to `{service_name="flight-aggregator"} | trace_id="<id>"`). Fixed; green.
   - Red by mutation: the query as a line filter, `|= "<trace id>"`, Grafana restarted: `getByText('Ingestion run ended')`, `element(s) not found`. Restored: green.
3. **E4, `GrafanaProvisioningTest.showsTheApplicationsLogLines`** (a panel "Logs", type `logs`, on uid `loki`, one query `{service_name="flight-aggregator"}`).
   - Red: `No panel titled Logs` (from a new `panel(title)` helper that fails naming the panel).
   - Green: the panel, full width at the bottom, newest first. Two Task 6 tests then failed on their premise and were adapted: `loadsTheDashboardWithItsSevenPanels` → `loadsTheDashboardWithItsPanels` (the extras' titles appended), and `everyPanelQueriesAKnownMetric` checks the panels on the `prometheus` datasource (a LogQL selector names no metric). It still bites: `rate(provider_call_total…` → `Expecting empty but was: ["provider_call_total"]`, restored. Refactor: the gauge-tile test finds its panels with `panel(title)` (a renamed panel now fails naming it, Task 6 re-review F-m2).
   - End to end: `'feeds every dashboard panel'` already runs every query of every panel, the logs panel's included. Red by mutation, `{service_name="no-such-service"}`: `Logs/A: Error: No data from … after 60 s`. Restored: green.
4. **E4, `GrafanaProvisioningTest.listsTheAlertRulesInEveryState`** (a panel "Alerts", type `alertlist`, every state shown).
   - Explored first on a scratch dashboard file, removed after: Grafana's alert list reads Prometheus's rules for the anonymous Viewer; with its defaults a healthy stack shows "No alerts matching filters".
   - Red: `No panel titled Alerts`. Green: the panel, right of the HTTP panel.
   - `'feeds every dashboard panel'` then failed: `TypeError: Cannot read properties of undefined (reading 'map')`; the alert list has no query. It now runs the panels that do (`runsQueries`, a type guard in `telemetry.ts`).
   - New e2e `'lists the alert rules on the dashboard'` (the Alerts panel alone, `viewPanel`, the four alert names visible): **red for a real gap**, `getByText('IngestionFailing')` not found, the page showing "No alerts matching filters": a rule that is not firing has no instance, and the list hides it unless `showInactiveAlerts` is on. Back to the provisioning test first: it asserts `showInactiveAlerts`, red (`[rules with no instance shown] Expecting value to be true but was false`), then green with the option; the e2e test green.
5. **E5, `GrafanaProvisioningTest.showsTheSearchAndRepriceRequests`** (a panel "HTTP requests"; every query filters `{uri=~"/api/itineraries(/.+/reprice)?"}`; one groups by `outcome`; the names used are exactly `http_server_requests_seconds_count`, `_sum`, `_max`).
   - Checked first on `p7ex`'s Prometheus: the `uri` tags are route templates; after a search, a re-price and a bad search the filter gave `/api/itineraries` SUCCESS and CLIENT_ERROR and `/api/itineraries/{id}/reprice` SUCCESS, and left `/health` out.
   - Red: `No panel titled HTTP requests`. Green: requests per second by route and outcome (CLIENT_ERROR orange, SERVER_ERROR red), mean latency (`rate` of `_sum` over `rate` of `_count`) and `max by (uri)` of `_max`, both dashed on the right axis in seconds; the titles test gains it.
   - End to end: `'feeds every dashboard panel'` runs its three queries. Red by mutation, `/api/itinerary(/.+/reprice)?` in all three: `HTTP requests/A`, `/B`, `/C`: `No data from … after 60 s`. Restored: green.

The whole `observability` project on `p7ex`, after the self-review's changes: see Gates. A screenshot of the dashboard with all ten panels holding data, kept out of git for the user: `.superpowers/sdd/2026-11-15-observability-and-delivery/extras-e1-e4-e5-dashboard.png`.

### Deviations from the plan's wording

- **E1's link is "Related logs"**, Grafana 13.3's name for trace to logs, in the trace header and in each span's details; it opens the whole trace's lines, not only the span's, since the query filters on the trace id within five minutes of the span.
- **E4's alert list shows each alert twice** (a rule on its value and one labelled `gauge: unknown`, Task 3's design); the panel's description says so.
- **E5 shows errors as series by outcome** (client errors and server errors) rather than a separate error-rate query, which would have no series on a healthy stack and leave the panel's query empty.
- **Three Task 6 and Task 7 tests were adapted** to the new panels (above): the titles list, the metric check limited to Prometheus panels, the panel data test limited to panels with queries.

### Concerns

- The HTTP panel's route filter is written in each of its three queries (Grafana's JSON has no shared constant short of a dashboard variable); `showsTheSearchAndRepriceRequests` pins it in all three.
- The alert list and the trace-to-logs link are checked in the browser only; Grafana resolves both in its frontend, so no API call shows them.

### Gates

- Between commits, `GrafanaProvisioningTest` (15 tests at the end, 0 failed) and the touched e2e tests on `p7ex`.
- The whole `observability` project on `p7ex`, on the final files: `8 passed (1.7m)` (search 4.2 s, manual run 1.3 min, log to trace 2.4 s, trace to logs 1.5 s, labels 5 ms, rules 12.2 s, panels 453 ms, alert list 1.7 s).
- `npm run verify` in `frontend/`: exit 0 (typecheck, lint, format check, 155 component tests).
- `./gradlew build`: exit 0, `BUILD SUCCESSFUL` in 2 min 12 s; 1,108 tests across the modules (Task 7's 1,104 plus this work's four), none failed or skipped.
- Self-review (thermo-nuclear) of the diff, three findings fixed: `'feeds every dashboard panel'` carried optional fields with silent fallbacks (`datasource?.uid ?? ''`, `targets = []`), replaced by a `QueryPanel` type and its `runsQueries` guard; the alert list test resolved the panel id with a fallback to `0`, replaced by `panelUrl(dashboard, title)`, which throws naming a missing panel; the HTTP test's `containsOnly(…).contains(…)` became one set equality. Kept: Grafana's own mechanisms (`tracesToLogsV2`, the `alertlist` and `logs` panels, the file provider, read-only); `GrafanaProvisioningTest` 463 lines; no production code changed.
- Throwaway stack: `docker compose -p p7ex … down -v`, exit 0; no container, volume or network of `p7ex` left; the user's `flight-aggregator` stack still running, 11 services, untouched.

## Task 10 — delivery docs

Step 3 and the docs parts of Task 10, docs only, in a worktree from `main` `fede859`. No code, configuration or test changed.

- **README final:** the "Work in progress" note replaced by a link to `SOLUTION.md`; a "Requirements" section (below); a table of the six published services, each checked against `compose.yaml` (dashboard `127.0.0.1:3000`, API `127.0.0.1:8080`, Keycloak `127.0.0.1:8180`, Grafana `127.0.0.1:3001`, Prometheus `127.0.0.1:9090`, mocks `127.0.0.1:8081`; nothing else published); the dashboard's ten panels, read from `deploy/grafana/dashboards/flight-aggregator.json`; `SOLUTION.md` in the index.
- **Stale facts fixed:** "a run takes about 24 to 28 seconds" now cites 23.8 to 27.5 s with six indexes ([plan 5 Task 13](2026-11-15-search-api.md#task-13--evidence)) and 34.9 to 41.6 s with twelve on a shared host ([plan 5's re-review fixes](2026-11-15-search-api.md#end-of-plan-fix-round-1--re-review-fixes)), as `SOLUTION.md` §3 does. "A run takes about half a minute" became "under a minute": the slowest run recorded is 54.6 s, in the same report's V8 load times. "Keycloak takes 20 to 40 seconds" had no source and was dropped; this stack's Keycloak logged `started in 5.477s`, 11 s after its container started, on a warm host. The extras backlog's index line names plan 7 too. Checked and still true: the token's 5 minutes (the realm sets no `accessTokenLifespan`; Keycloak's default is 300 s), the mock's latency and error defaults (`ProviderSettings`), the 30-minute cadence (`ingestion.interval: PT30M`).
- **Memory,** for "Requirements": `docker stats --no-stream` on the containers labelled `com.docker.compose.project=flight-aggregator` (the user's running stack, neither stopped nor restarted), every 15 to 17 s from 12:14:07 to 12:23:07 UTC: 32 complete samples, covering the scheduled ingestion run that ended at 12:22:59 UTC. Eleven containers, 1,803 to 2,335 MiB in total (1.8 to 2.3 GiB); 2.0 to 2.1 GiB during the run; the highest sample came minutes after Grafana had been restarted. Peaks per container: Grafana 631 MiB, Keycloak 543, app 509, Tempo 306, PostgreSQL 218, mock-gds 181, Loki 131, Collector 98, Prometheus 68, Valkey 14, frontend 5; together 2.6 GiB. Host: Docker Desktop on macOS arm64, a 7.75 GiB VM shared with other compose projects (33 containers in all). Not measured: the image build (the Gradle and npm stages of `docker compose up --build`). The README asks for at least 4 GB.
- **amd64 and arm64:** `docker buildx imagetools inspect --raw` on each of the 12 pinned references (the 9 images in `compose.yaml` and the base images of the `bootstrap/`, `mock-gds/` and `frontend/` Dockerfiles): every one is an index listing both `linux/amd64` and `linux/arm64`.
- **Windows:** the README holds `<!-- E9: Windows notes go here -->` for the user's check.
- **`SOLUTION.md`:** §5 names E1, E4 and E5, citing "Extras E1, E4, E5 — evidence"; §8 links session 09's Monday sections and adds the hand run's two gaps. The draft note and the final counts and coverage are left for Step 4.
- **Session 09** held Monday's prompts, but under "Lesson" and with no account of the day. Added, without changing any entry: sections 11 (what happened: plans 5 and 6 closed, plan 7 built, reviews by group, the hand run's gaps), 12 (decisions) and 13 (lesson), and the heading "14. Prompts that added value, from Sunday night on" over the existing entries. Its date, goal and title now cover Monday, and both indexes carry the new title.
- **Roadmap:** plans 5 and 6 done, linked to their closing verdicts; plan 7 linked, with Tasks 1–7 and 9 done, E1, E4 and E5 built, Task 10 in progress and Tasks 8 and 11 not built.
- **Links:** a script in the session's scratch folder (out of git) checks each relative `](…)` target outside code fences and inline code, and each `#fragment` against the target's GitHub heading slugs. Shown red first on a test file (`MISSING ANCHOR`, `MISSING FILE`, exit 1). On `README.md`, `SOLUTION.md`, the roadmap, session 09, the sessions index and this report: **244 relative links, 0 broken**, exit 0.
- **From the final risk review** (relayed by the controller while this note was written): a stack just started from a clean clone on a throwaway compose project used about 1.85 GB in total (app 435 MB, Keycloak 508 MB, Grafana 321 MB); the six host ports cannot be changed (8180 and 3000 are also fixed in Keycloak's hostname, the realm's redirect URIs and the issuer, checked in `compose.yaml`, `deploy/keycloak/flight-aggregator-realm.json` and `application.yaml`); the read-only bind mounts carry no `:z`, so SELinux in enforcing mode keeps Keycloak and the telemetry services from reading their files; and `.gitattributes` forces LF, so a Windows clone builds. The README's "Requirements" now gives both memory figures, says the ports must be free and what a busy 8180 stops (the app and the frontend wait for a healthy Keycloak in `compose.yaml`), and tells SELinux users to relabel `deploy` (`chcon -R -t container_file_t deploy`) or, for a demo, `sudo setenforce 0`. The Windows notes stay for E9.

## Task 10 — final triple review (Steps 1 and 2)

Three reviewers over plan 7 and the whole repository at `fede859` (README and `SOLUTION.md` read again at `467263d`), each with its own scratch clone and throwaway stack; common brief in `.superpowers/sdd/2026-11-15-observability-and-delivery/final-review-brief.md`, replies saved next to it (`final-review-{functional,code,risk}.md`).

- **Risk: APPROVED.** No secret, token, password or IP in 48 traces and 146 Loki lines; metric cardinality bounded; a paused Collector costs searches and runs nothing; Grafana's anonymous Viewer cannot write; Keycloak's console needs local access; no CORS; actions by SHA, images by digest. Minors N1 (Grafana proxies anonymous cross-origin POSTs to Tempo: `GF_SECURITY_CSRF_ALWAYS_CHECK`), N2 (health and scrape traces fill Tempo), N3 (fixed ports), N4 (SELinux bind mounts), N5 (= Task 4 C-M1).
- **Code: CHANGES REQUESTED.** `./gradlew build --rerun-tasks` 1,108 tests, `npm run verify` 155. **I1** a refresh's fresh offers are never stored, so a later refresh of another origin restores the run's old prices into shared trips (proven by a failing probe). **I2** the re-price compares with the stored row, not the price shown, so after a cached page a changed price reads "confirmed" (proven). Minors listed in its reply.
- **Functional: CHANGES REQUESTED.** JaCoCo 99.0% lines, 96.9% branches; every `SOLUTION.md` figure matches its report; 0 broken links; every §2 capability works on a fresh stack. **I1** the admin e2e waits 90 s for a run that now takes 78–86 s in CI (run 43864300834 failed there). Minors M-a to M-d (run times in the docs, which ports are fixed, Task 8 "not built", E3 delivered).

**The user's decision (about 13:44):** fix the three Importants. Two lanes: backend (code I1; Task 4 C-M1 / N5; risk N1; P5r3 F4) and frontend and docs (code I2 with the approved copy only; functional I1 and group 1's M5; M-a to M-d; panel descriptions; group 1's M6). The other Minors stay as recorded limitations or in the backlog.

## Final review — fix round, frontend and docs

The frontend and docs lane of the user's decision above, in its own worktree on `main` `4d8eeb2`. The backend lane (code I1, `IngestionJob`, Grafana's CSRF, `CountedFlightProvider`, `ingestion.md`) runs in parallel and is not touched here.

### Code I2: the re-price is worded against the price the card showed

Only copy already fixed or approved is used: the spec's "Price changed: was X, now Y" (§6.3) and "Price confirmed: €…".

1. **`'says the price changed when the confirmed price is not the one the card showed'`** (`RepricePanel.test.tsx`; the card shows €412.60, the API answers `CONFIRMED` at €431.00, as after another customer's re-price with a cached page).
   - Red: `Unable to find an element with the text: Price changed: was €412.60, now €431.00.` The panel showed `Price confirmed: €431.00`.
   - Green: `RepricePanel` takes `shownPrice` from the card; `confirmation()` words a `CONFIRMED` answer whose formatted price differs from it as a change.
2. **`'confirms the price an earlier re-price put on the card'`** (a first opening answers `PRICE_CHANGED` to €431.00, so the stub shows €431.00; the second answers `CONFIRMED` at €431.00).
   - Red: `Unable to find an element with the text: Price confirmed: €431.00.` The panel said `Price changed: was €412.60, now €431.00`, comparing with the search's price rather than the stub's.
   - Green: `ItineraryCard` keeps the stub's price at each opening (`shownPrice`) and passes it to the panel.
3. Self-review refactor: `confirmation()` is an exhaustive `switch` on the outcome, so `ALTERNATIVE` no longer falls through a `CONFIRMED` guard. `dashboard.md`'s re-price table and a paragraph say why.

### Functional I1 and group 1's M5: the e2e wait for a run

- `frontend/e2e/ingestion.ts` holds one budget, `RUN_PUBLISHED_WITHIN_MS = 240_000`, for `admin.spec.ts` (90 s before) and `observability.spec.ts`; its comment no longer says "about half a minute". `runAnIngestion` reads the generation before each POST, not after the 202 (M5). No other e2e wait assumed a run's length (`global-setup.ts` waits 180 s for the first run's itineraries).
- **CI's run times**, read from run 43864300834's `e2e-reports` artifact (`compose-logs.txt`, the `Ingestion run ended` lines): the startup run (generation 1, `scheduling-1`) took `PT1M26.260725159S`, Chromium's manual run (generation 2, `ingestion-on-demand`) `PT1M18.38147093S`; Firefox's run had not ended when its 90 s poll failed. These are the figures the README and `dashboard.md` cite.
- **Run on a throwaway stack:** compose project `p7ff` from this worktree with no published ports (a scratch override, `ports: !reset []`), so the user's stack kept 3000 and 8180. The Linux Playwright image `v1.64.1-noble` joined `p7ff_default`, with a small Node TCP forward from its own `localhost:3000`, `8180`, `3001` and `9090` to `p7ff`'s frontend, Keycloak, Grafana and Prometheus, so the sign-in's fixed addresses held. `--project admin-chromium admin-firefox admin-webkit observability --no-deps`: 17 passed (5.0 min). Then `re-price-{chromium,firefox,webkit}` and `accessibility-chromium`: 5 passed. The five runs took 46.8 to 60.6 s on this Mac. `down -v` exit 0; no `p7ff` container, volume or network left; the user's `flight-aggregator` stack still 11 containers, untouched.
- M5's race (a run publishing between the 202 and the read) was not reproduced, so it has no red.

### Docs

- **M-a:** the README's "A run takes under a minute" and `dashboard.md`'s "about half a minute" now say about a minute, more on a loaded host, citing CI's figures above.
- **M-b:** the README's "Requirements": only 3000 and 8180 are fixed (Keycloak's hostname, the realm's redirect URIs and the token issuer); the others can be moved in a `compose.override.yaml`.
- **M-c:** `observability.md`, "What is not collected": the exporters and the JVM dashboard of Task 8 were not built.
- **M-d:** E3, a background refresh as one trace, was delivered by Task 4's fix round 1 (`RefreshTraceTest.aRefreshContinuesTheTraceOfTheSearchThatAskedForIt`, above).
- **Panel descriptions** (`flight-aggregator.json`): Provider calls says the startup run's calls show from the second run on (Task 6 M1); freshness names a provider with nothing stored among the unknown causes (F-m1); HTTP requests, and `observability.md`'s caveat, say `rate` does not draw a series' first request, since Micrometer creates the series on it (code review). No description is pinned by `GrafanaProvisioningTest`, so no test was added.
- **Group 1's M6:** `2026-11-15-dashboard.md` cites `48a066b`, `141b7cc`, `e759970` and `7223931`, the commits on `main`, instead of their pre-rebase hashes (subjects matched with `git log --no-walk`).

### ⚠ For the user

- A `PRICE_CHANGED` answer still says "was" with the API's `previousPrice`, the stored price, which a cached page may not show either. Wording every answer only against the card's price would fix that too, but drops `previousPrice` and the approved "Price changed: now €…". Left as it is unless you decide otherwise.
- **Decided (2026-11-16, option A):** every answer is worded against the card's price; see "The "was" price of a changed price (option A)" below.

### Gates

- Thermo-nuclear self-review of the diff: one finding fixed (the `switch` above); the alternative wording left to the user (above); the card's `shownPrice` state is set in the same handler that starts the re-price, so nothing is half-applied.
- `npm run verify` in `frontend/`: exit 0, 21 files, 157 tests (155 before, plus the two above).
- `./gradlew build`: BUILD SUCCESSFUL; `bootstrap:test` ran (250 tests, 0 failures, `GrafanaProvisioningTest` 15 of them), the other modules' tests from Gradle's cache, unchanged by this lane.

## Final review — fix round, backend

The user's decision of 2026-11-16, about 13:30: fix the final review's Importants. This round takes the backend ones (code I1) and four backend Minors (Task 4 C-M1 = risk N5, risk N1, P5r3 F4, and I1's doc rule), in a worktree from `main` `467263d`. Strict TDD, one test at a time; the full red and green messages are in `.superpowers/sdd/2026-11-15-observability-and-delivery/final-fix-backend-evidence.md`.

### I1: a refresh's fresh offers are stored with its rewrite

The bug (the code review's probe): a refresh rebuilt its trips from the run's stored offers and never stored the ones it downloaded, so Madrid's refresh rebuilt BCN→MAD→LHR from the run's €100 BCN→MAD offer after Barcelona's refresh had published €150.

1. `RefreshStaleRouteServiceTest.keepsThePricesAnEarlierRefreshOfAnotherOriginFound` (the reviewer's probe, renamed, its print removed). Red: `AssertionError: Expecting actual: [[Leg[... BCN-MAD ... price=Money[amount=100.00] ...` Green: a new port method `OfferStore.replaceFrom(provider, origin, offers)`, called for each `FRESH` provider; a kept provider's offers stay.
2. `rewritesAndStoresTheFreshOffersInOneTransaction`, with the fake `WatchedTransactions` (the state read just before the work and once it returns). Red: first the compile (`cannot find symbol StoreTransactions`), then, with the port and the constructor parameter unused, `Expecting actual: [] to contain exactly ...`. Green: a new port `StoreTransactions.inOne(Supplier)`; the rewrite and the swaps run inside one, and the ceiling's exception leaves it before it is caught, so past the ceiling neither is written.
3. Wiring, `FlightAggregatorApplicationTest.rollsBackEveryStoresWriteOfAFailedStoreTransaction`. Red: `No qualifying bean of type 'com.flightaggregator.application.port.out.StoreTransactions'`. Green: `PersistenceConfig.storeTransactions`, a `TransactionTemplate` over the one `DataSourceTransactionManager` every store is built from, so each store's own template joins it.
4. `JdbcOfferStoreTest.replacesOnlyTheProvidersOffersFromTheOrigin`. Red: `UnsupportedOperationException: not yet` (step 1's stub). Green: one transaction bounded by `StatementTimeout.STORE`: `DELETE` of the provider's offers from the origin, the next position (`COALESCE(MAX(position) + 1, 0)`; the key is `(provider, position)`), then the replacement's `COPY` from it.
5. T7-C2 not made worse. A run stores each provider's offers in a transaction of its own, outside the refresh's ShedLock. `JdbcOfferStoreTest.swapsTheOffersOfARunThatReplacedThemMeanwhile`, red: the run's Madrid offer (`55.00`) stayed beside the refresh's (`80.00`). Green: `replaceFrom` first takes `LOCK TABLE provider_offer IN SHARE ROW EXCLUSIVE MODE` (self-exclusive, blocks writes, not reads). The reverse order, `letsARunReplaceTheOffersOfARefreshThatSwappedThemMeanwhile`, was written after the lock; its red is recorded by mutation: without the lock, the run's `COPY` failed with `SQL state [23505] ... duplicate key value violates unique constraint`, which would have failed that provider's store in the run. Lock order: publication row, then this table; a run's offer replacement never takes the publication row, so there is no cycle, and inside a refresh the wait is bounded by the rewrite's 5 s `lock_timeout` (past it the refresh rolls back whole, logs its `ERROR`, and the origin stays unmarked). The publication row's `FOR UPDATE`, the generation `+ 1` and `published_at` are unchanged. The T7-C2 row of `extras-backlog.md` now says the refresh also stores its own offers in that window, never duplicated, and that the proposed "superseded" check would roll them back too.

Docs: `ingestion.md` (step 2 now writes `provider_offer`, step 4 lists `replaceFrom` and its lock in the same transaction, the ceiling stores no offer), the service's and the port's Javadoc. `SOLUTION.md` §3 does not state the old rule, so it is unchanged.

**Live check** on a throwaway compose project `fixcheck` (only the app published, on `127.0.0.1:18080`; `SEARCH_FRESHNESSTHRESHOLD=PT20S`): the startup run published generation 1. A search from BCN refreshed it (`REFRESHED`, generation 2, 108,901 itineraries, three providers `FRESH`): `provider_offer`'s BCN rows took the refresh's quotes with the same counts (124/132/118), MAD and LHR rows unchanged, no duplicate. A search from MAD refreshed it (generation 3, 521,905 itineraries). In the active slot, of the Barcelona legs inside trips that also have a Madrid leg, 54,101 carry Barcelona's refresh quote and 0 the run's; every Barcelona leg of the slot matches its `provider_offer` row (0 mismatches). `down -v --rmi local`: no `fixcheck` container or volume left; the user's `flight-aggregator` stack (11 containers) untouched.

### Minors

- **Task 4 C-M1 / risk N5,** `IngestionJobTest.endsAFailedRunsObservationWithItsError` (a real `ObservationRegistry` keeping each stopped context). Red: `Expecting actual: null and: java.lang.IllegalStateException: database down`. Green: the run's catch calls `observation.error(e)`. The Javadoc says a scheduled run sits under Spring's `task ingestionJob.run` span; one clause in `observability.md`.
- **Risk N1,** `GrafanaProvisioningTest.refusesACrossOriginPostThroughTheDatasourceProxy` (anonymous `POST /api/datasources/proxy/uid/tempo/api/echo`, `Origin: http://evil.example`, `Content-Type: text/plain`). Red: `expected: 403 but was: 502` (Grafana forwarded it to Tempo, absent in the test). Green: `GF_SECURITY_CSRF_ALWAYS_CHECK: "true"` in `compose.yaml`; the anonymous Viewer, Explore and the signal links still pass; one sentence in `observability.md`.
- **P5r3 F4,** `CountedFlightProvider`'s Javadoc: a refresh's download failure is only counted; its report line names the provider `KEPT`, with no reason or stack trace. Comment only, no test.

### Self-review (thermo-nuclear)

One finding fixed: the two concurrency tests repeated a held-open-transaction scaffold, now one `HeldWrite` helper (the mutation still turns both red). Kept, with reasons: the swap is made in memory for the build and in the store (reading the store back inside the transaction would hold the table lock for a whole rewrite, 15 s for Madrid, and invert the lock order); `StoreTransactions` is an anonymous class in `PersistenceConfig` (a generic method cannot be a lambda); `IngestionJob` records an `Error` on the observation twice (the same throwable). No file crosses 1,000 lines.

### Gates

- Between commits: the tests of the touched modules (`:application`, `:adapters:persistence`, `:bootstrap`), each exit 0.
- `./gradlew build`: exit 0 at `5acf6c5` (`BUILD SUCCESSFUL` in 1 min 42 s), and again before the last commit, exit 0 (every task up to date or rerun after the test refactor): 1,116 tests across the modules (the final review's 1,108 plus this round's eight), none failed or skipped.

## Final review — fix round re-review

One reviewer, functional then code, over `467263d..591249d`: **Functional APPROVED, Code APPROVED**; no Critical or Important. Code I1 closed (`replaceFrom` inside `StoreTransactions.inOne` after the rewrite; one transaction manager; lock order publication row then `provider_offer`, no cycle; the 5 s lock timeout bounds the `LOCK TABLE` wait; a failure rolls back both). Code I2 closed with approved copy only. Functional I1 closed (one 240 s budget; generation read before each POST). Ten mutations, each red (the `replaceFrom` call removed or moved out of the transaction; the `LOCK TABLE` dropped; the DELETE's origin inverted; the next position 0; `StoreTransactions` not transactional; `observation.error` removed; the Grafana CSRF setting removed; `CONFIRMED` always "confirmed"; the search's price passed instead of the stub's). `./gradlew build --rerun-tasks` 1,116 tests; `npm run verify` 157. CI 43874670820 on `591249d`: images, frontend, e2e green; backend red on three `ValkeySearchCacheTest` cases, `RedisCommandTimeoutException` in `@BeforeEach ValkeyTestServer.empty()`, code the round did not touch and green locally three times: re-run.

Minors: (1) `RefreshStaleRouteService.java:129` a re-price's stored quote is lost when a refresh of another origin of that trip rebuilds it from `provider_offer` (the card shows the older price until the next re-price, which never sells unconfirmed): recorded as a limitation; (2) `RepricePanel.tsx:39` a `PRICE_CHANGED`'s "was" is the stored `previousPrice`: put to the user; (3) `JdbcOfferStore.java:86` `replaceFrom` trusts a download from one origin holds only that origin's offers (hardening; the mocks filter by origin); (4) `JdbcOfferStoreTest.java:331` reads `pg_backend_pid()` itself although `LockWaits.backendPid` exists.

## The "was" price of a changed price (option A)

**The user's decision (2026-11-16, about 14:40), option A**, closing the fix round's "⚠ For the user" and the re-review's Minor (2): a `PRICE_CHANGED` answer's "was" is the price the card showed when it was opened (`shownPrice`, as for `CONFIRMED`), not the API's stored `previousPrice`, so the customer always sees the difference from what they had in front of them. Approved wording only. Worktree `was-price` on `main` `4460fdc`.

1. **`'says a changed price was the one the card showed, not the stored one'`** (the card shows €500.00; `PRICE_CHANGED`, `previousPrice` €520.00, price €540.00).
   - Red: `Unable to find an element with the text: Price changed: was €500.00, now €540.00.` The panel said `was €520.00`.
   - Green: the `PRICE_CHANGED` branch words the answer against the card's price. `'shows a changed price without the one shown before'` pinned the "Price changed: now €…" fallback for a missing `previousPrice`, which option A drops: removed.
2. **`'confirms a changed price that is the one the card showed'`** (the card shows €500.00; `PRICE_CHANGED` from a stored €520.00 to €500.00, as after another customer's re-price with a cached page).
   - Red: `Unable to find an element with the text: Price confirmed: €500.00.` The panel said `Price changed: was €500.00, now €500.00`.
   - Green: `CONFIRMED` and `PRICE_CHANGED` share one branch: the card's price reads "Price confirmed: €…", another "Price changed: was €…, now €…".

`dashboard.md`'s re-price table and paragraph say so. `frontend/e2e/re-price.spec.ts` matches "Price confirmed: €" or "Price changed: " without an amount, so it still holds (read, not run).

## Task 10 — full verification

Step 4, in a fresh clone of `main` at `4460fdc` (`scratchpad/step4`): `./gradlew build --rerun-tasks` BUILD SUCCESSFUL in 1 min 55 s, 1,116 tests, none failed or skipped (domain 91, application 121, adapters/persistence 136, adapters/providers 199, adapters/web 73, bootstrap 253, mock-gds 201, architecture-tests 42); the aggregate JaCoCo report (`coverage/build/reports/jacoco/testCodeCoverageReport`): lines 3,031 of 3,062 (99.0%), branches 695 of 717 (96.9%), instructions 99.0%. `npm ci && npm run verify`: 21 files, 157 tests; 158 after the re-price's "was" change (option A, below). End to end: CI run 43874670820 on `591249d`, 42 passed in 5.5 min of Playwright, the whole job 10 min 9 s (13:19:32 to 13:29:41 UTC), after one re-run of the backend job, whose three `ValkeySearchCacheTest` cases had failed on a `RedisCommandTimeoutException` in `@BeforeEach` (code untouched by the round; green locally three times and on the re-run). Firefox does not start on macOS 27, so the Mac's end-to-end run is Chromium only; CI runs all three browsers.

## E9 — the Windows check and the user's decision

The Windows PC's session ran the clean-clone check on `0ddb3a9` ([record](e9-windows-check.md)): every README check passed and the dashboard and admin sign-in worked, but on a 4-core laptop with 4 GB for Docker the first `up --build` failed on Keycloak's health check, two runs hit the 5-minute `COPY` bound, and the token command's `python3` is missing on Windows. **The user's decision (about 14:51): document, do not tune** — the README's "Requirements" gains "Minimum hardware" (measured) and "On an older or slower machine" (what to do: Docker's resources, a second `up`, a manual run, the token without Python, Git Bash). Grafana's two startup errors for missing provisioning folders stay as noise.

## Task 10 — clean clone on the Mac (Step 6)

A fresh `git clone https://github.com/dromero001/flight-aggregator.git` at `efdb2c8`, with the working copy's stack stopped and its volumes kept. The clone's folder has the same name as the working copy and `compose.yaml` sets no `name:`, so both would have been one compose project sharing `flight-aggregator_postgres-data`; every compose command in the clone ran under `COMPOSE_PROJECT_NAME=fa-clean`, the README's commands otherwise typed exactly as written (a reviewer cloning once is not affected). **No defect: every README command worked verbatim.**

- `docker compose build --no-cache` 3 min 58 s; `docker compose up --build` 32 s to eleven services up (ten healthy; the Collector has no health check); the first flights 69 s after `up` (the first run 49.5 s).
- The dashboard: 20 cards; 32 airport and 12 airline suggestions; `madrid` → `New York`, `iberia`, `2000,5` gave `?origin=MAD&destination=JFK&carrier=IB&maxPrice=2000.5`; a card's re-price "Price confirmed: €405.64"; `/admin` signed in through Keycloak with `admin` / `demo-admin-password`.
- The README's admin commands verbatim in bash and zsh: the Keycloak wait loop, the token, status 200, ingestions 202 then 409, no token 401. API, `/health`, Swagger, `/v3/api-docs`; the mocks with and without their keys, Beta's XML and Gamma's CSV; the mocks alone with `docker compose up --build mock-gds`.
- Prometheus: the app target up, every rule inactive. Grafana with no login: all ten panels with data; from the README's Loki query, "View trace" opened `ingestion.run` (18 spans) and "Related logs" led back to its line.
- End to end from the clone (`npm ci`, `npx playwright install chromium`, the Chromium projects with `--no-deps`, since the config chains every project): **20 passed** in 2.1 min (smoke, search, re-price, accessibility and admin in Chromium, observability, rate-limit). Firefox does not start on macOS 27; WebKit and Firefox run in CI.
- `docker compose down -v` in the clone removed only `fa-clean_postgres-data`; the working stack came back with `up --build -d --wait` in 29 s with its stored inventory.

Covered at delivery: macOS arm64 (this clone), Linux amd64 (CI's `e2e` job from a clean checkout on every push), Windows 11 amd64 ([E9](e9-windows-check.md)).

## G2 — evidence

Review group G2, "dashboard polish", built on 2026-11-16 by the user's decision of about 15:05, in a worktree from `ecadaa4`, one test at a time. Each cycle: the test, the failure seen, what made it pass.

1. **Decimal comma only without a dot.** `searchParams.test.ts` › "keeps an amount with a dot and a comma as typed, and rejects it". Red: `Expected: "1.234,5"  Received: "1.234.5"`. Green: `withDecimalPoint` reads the comma as a dot only when the amount has one comma and no dot.
2. **No immediate retry with `Retry-After`.** `SearchPage.test.tsx` › "does not retry a failure that says when to ask again" (503, `Retry-After: 60`). Red: `expect(requests).toBe(1)`, received 4. Green: `queryClient.ts` retries only a network error or a 5xx without `retryAfterSeconds` (`mayPassOnRetrying`). The 502 test (no `Retry-After`) still expects 4 requests; its comment now says so.
3. **"Loading…" on the first status read.** `StatusPage.test.tsx` › "says it is loading until the first status arrives". Red: `Unable to find an element with the text: Loading….` Green: `<p role="status">Loading…</p>` while the query `isLoading`.
4. **No polling after a 403.** `StatusPage.test.tsx` › "stops refreshing once the account is refused the admin role". Red: `expect(requests).toBe(1)`, received 4 after 30 s. Green: `refetchInterval` answers `false` while the error is a 403 (`lacksAdminRole`, shared with `adminFailure`).
5. **Hours in "Next ingestion in …".** "gives the next ingestion in hours and minutes when it is over an hour away". Red: no "Next ingestion in 1 h 30 min" (the page said "90 min"). Green: `hoursAndMinutes`.
6. **Whole hours.** "gives the next ingestion in whole hours without minutes". Red: no "Next ingestion in 2 h" (it said "2 h 0 min"). Green: the minutes are left out of a whole hour.
7. **"+1" on each leg.** `ItineraryCard.test.tsx` › "marks each leg that lands on the next local day". Red: expected "Iberia IB 3166 MAD 22:40 to LHR 06:15 +1", received it without "+1". Green: `LegList` adds the mark per leg (departure to arrival of that leg); `laterDayMark` in `format.ts` now builds "+N" for the summary and the legs.
8. **Caption on the alternative's legs.** `RepricePanel.test.tsx` › "captions the alternative legs, to tell them from the chosen ones". Red: `Unable to find an element with the text: Alternative legs.` Green: `LegList`'s `captioned` shows the label as a caption that names the list (`aria-labelledby`). The chosen list keeps its name "Legs", which `e2e/re-price.spec.ts` finds it by.
9. **Focus after "Load more".** `SearchPage.test.tsx` › "moves the focus to the first new card after Load more". Red: the focus was on `<body>` (the button left with the last page). Green: `ResultsList` remembers the first new card's index on "Load more", and that card's summary (`ItineraryCard`'s `summaryRef`) takes the focus as it mounts.
10. **The ingestion's lock, read (adapter).** Port `IngestionLock.isHeld()` and a compile stub first. `JdbcIngestionLockTest` › `isHeldWhileARunHoldsIt`, with ShedLock's own provider over the database's clock. Red: `Expecting value to be true but was false`; green: `return true`. Then `isNotHeldOnceTheRunReleasesIt`. Red: `Expecting value to be false but was true`; green: `JdbcIngestionLock` asks whether the lock's row has `lock_until` ahead of `timezone('utc', CURRENT_TIMESTAMP)`, in a `BoundedSnapshot`.
11. **The status use case.** `InventoryStatusServiceTest` › `reportsAnIngestionGoingOn`. Red: `Expecting value to be true but was false` (the stub passed `false`). Green: `StatusReport.ingestionRunning` from `lock.isHeld()`; `PersistenceConfig.ingestionLock` over `IngestionJob.LOCK`, now public so the lock's name has one owner.
12. **The API and the contract.** `AdminControllerTest` › `servesTheStatus` (STRICT JSON) expects `"ingestionRunning": true`. Red: `Expected: ingestionRunning but none found`. Green: `StatusResponse.ingestionRunning`. `OpenApiContractTest` then failed on the drift, as it should; `openapi.json` regenerated (the one property and its `required` entry) and `schema.d.ts` with `npm run api:generate`, in the same commit.
13. **The screen, a run in progress.** "says an ingestion is in progress instead of when the next one is due". Red: no "Ingestion in progress". Green: the last fact reads it while `ingestionRunning`.
14. **The screen, the first run in progress.** "says the first ingestion is in progress". Red: no "Ingestion in progress." (it said "No ingestion has run yet."). Green: that line reads "Ingestion in progress." while the first run goes on.

New user-facing text, for the user's approval: "Loading…" (admin screen; the same word as "Load more"'s), "Next ingestion in 1 h 30 min" and "Next ingestion in 2 h" (forms of the approved line), "Ingestion in progress" and "Ingestion in progress.". The caption "Alternative legs" is the approved list name, now visible.

Self-review (`thermo-nuclear-code-quality-review`): no blocker. Minor, left: the index of the first new card stays remembered after a "Load more" page that adds no card (every itinerary already shown); it focuses nothing until a later page reaches that index, which a first page of at most 20 never does.

Gates: `npm run verify` exit 0 (169 tests); `./gradlew build` BUILD SUCCESSFUL in 1 min 59 s, exit 0. No live stack check was run: the lock's query is tested against PostgreSQL with ShedLock's own provider, and the e2e suite needs the default ports the running stack holds. `dashboard.md`, `search-api.md` and the README's admin line describe the changes; the built rows of `extras-backlog.md` moved to a "Built on 2026-11-16 (G2)" note.
