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
