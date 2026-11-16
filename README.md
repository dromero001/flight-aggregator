# Flight Aggregator

[![CI](https://github.com/dromero001/flight-aggregator/actions/workflows/ci.yml/badge.svg)](https://github.com/dromero001/flight-aggregator/actions/workflows/ci.yml)

Flight aggregation & cache optimization system: ingests itineraries from three legacy GDS mocks (JSON, XML, CSV), deduplicates and prices them, and serves a keyset-paginated search API with a real-time dashboard.

How it is built and why, with its assumptions and limitations: [SOLUTION.md](SOLUTION.md).

## Requirements

- **Docker with Compose v2** (the `docker compose` command): Docker Desktop on macOS and Windows, Docker Engine with its Compose plugin on Linux. The images build the Java and the frontend code themselves, so nothing else is needed to run the stack.
- **At least 4 GB of memory for Docker.** The eleven running containers used 1.8 to 2.3 GiB over nine minutes that included an ingestion run, and their peaks added up to 2.6 GiB (`docker stats` on the Mac, [measured on 2026-11-16](docs/planning/progress/2026-11-15-observability-and-delivery.md#task-10--delivery-docs)); the aggregator, Keycloak and Grafana take about half a gigabyte each. The rest leaves room for the image build, which was not measured. In Docker Desktop, this is its memory limit.
- **Free host ports:** 3000, 3001, 8080, 8081, 8180 and 9090, each published on `127.0.0.1` only ([the services](#run-the-aggregator)).
- **amd64 or arm64.** Every image `compose.yaml` pins, and every base image of the three Dockerfiles, is a multi-arch image with both. The stack has run on macOS arm64 (the author's Mac) and on Linux amd64 (CI's `e2e` job builds and starts it from a clean checkout on every push).

<!-- E9: Windows notes go here -->

## Run the aggregator

```bash
docker compose up --build
```

One command starts the whole stack. These services are published, each on the loopback interface only:

| Service | Address | What it is |
|---|---|---|
| Dashboard | `http://localhost:3000` | The search, and the provider status on `/admin` ([Use the dashboard](#use-the-dashboard)) |
| API | `http://localhost:8080` | The search API, `/health` and Swagger UI on `/swagger-ui.html` |
| Keycloak | `http://localhost:8180` | The token issuer of the [admin side](#admin-access), realm `flight-aggregator` |
| Grafana | `http://localhost:3001` | Metrics, traces and logs, with no login ([Observability](#observability)) |
| Prometheus | `http://localhost:9090` | Its alerts page and query screen |
| Mock providers | `http://localhost:8081` | Alpha, Beta and Gamma ([Run the mock providers](#run-the-mock-providers)) |

PostgreSQL, Valkey (the search's result cache, refresh claims and rate-limit counters), the OpenTelemetry Collector, Tempo, Loki and the aggregator's management port are reachable only inside compose's network. The aggregator (`bootstrap/Dockerfile`, `-Xmx512m`) ingests the providers at startup, then every 30 minutes, and publishes about one million itineraries into PostgreSQL. A run took 23.8 to 27.5 s with six sort indexes per slot ([plan 5 Task 13](docs/planning/progress/2026-11-15-search-api.md#task-13--evidence)), and 34.9 to 41.6 s with today's twelve, on a host shared with another stack ([plan 5's re-review fixes](docs/planning/progress/2026-11-15-search-api.md#end-of-plan-fix-round-1--re-review-fixes)). Its health is on `http://localhost:8080/health` and the search on `http://localhost:8080/api/itineraries`. How a run works, and what each failure does: [Ingestion](docs/architecture/ingestion.md); the API: [Search API](docs/architecture/search-api.md).

**The database password is a committed demo default too**, the same deliberate exception as the provider keys below (spec 6.6): `POSTGRES_PASSWORD` in the shell or a `.env` file overrides it. It protects only a database that no port exposes outside the compose network; in production it would be a secret injected at deploy time. PostgreSQL sets the password only when it creates its volume: to change it after the first start, remove the volume with `docker compose down -v` (which deletes the stored inventory) and start again.

A `providers.*` setting that names no setting (a misspelt `providers.download-timout`, say) stops the aggregator at startup instead of leaving a default in force. Spring Boot's report of it prints the value it was given, so a secret under a misspelt key (an `api-kye`) would be printed: spell the keys' settings with care.

## Use the dashboard

The same `docker compose up --build` serves the dashboard on `http://localhost:3000` (loopback only), from nginx, which proxies `/api/` to the aggregator. It shows flights once the first ingestion has published. The search is public: the four filters, the sort, "Load more", and a card that re-prices when opened. The provider-status screen is `http://localhost:3000/admin`: it sends you to Keycloak's login, where the realm's demo user `admin` with the password `demo-admin-password` signs in ([Admin access](#admin-access)). Screens, states, sign-in and tests: [Dashboard](docs/architecture/dashboard.md).

The frontend is an npm project in `frontend/` (Node from `frontend/.nvmrc`), outside Gradle. In `frontend/`, after `npm ci`:

| Command | What it does |
|---|---|
| `npm run dev` | Vite's dev server on `http://localhost:5173`, proxying `/api` to the aggregator on `http://localhost:8080` (the admin sign-in needs port 3000: [Dashboard](docs/architecture/dashboard.md#run-it)) |
| `npm run verify` | Type check, lint, format check and the component tests: the gate before every commit |
| `npm run api:generate` | Regenerates `src/api/schema.d.ts` from `docs/architecture/openapi.json`; `npm run api:check` fails when it is stale |
| `npm run e2e` | Playwright with axe in Chromium, Firefox and WebKit against the running stack (`npx playwright install --with-deps chromium firefox webkit` first). Firefox does not start on macOS 27: run it in Playwright's Linux image, as [Dashboard](docs/architecture/dashboard.md#how-each-layer-is-tested) shows |

## Airports in the dataset

The mock network has 32 airports, from `mock-gds`'s [`StandardCatalogue`](mock-gds/src/main/java/com/flightaggregator/mockgds/reference/StandardCatalogue.java). Hubs connect to every other hub; a spoke connects through its hub; a long-haul airport through its gateway hubs. The aggregator's catalogue is [`V2__airport_catalogue.sql`](adapters/persistence/src/main/resources/db/migration/V2__airport_catalogue.sql), and the dashboard's list of airports (`frontend/src/search/airports.ts`) must match it; `frontend/src/search/suggestions.test.ts` checks that both, and this table, hold the same codes.

| Code | City | Country | Role in the mock network |
|---|---|---|---|
| MAD | Madrid | Spain | Hub |
| BCN | Barcelona | Spain | Hub |
| LHR | London | United Kingdom | Hub |
| CDG | Paris | France | Hub |
| FRA | Frankfurt | Germany | Hub |
| AMS | Amsterdam | Netherlands | Hub |
| LIS | Lisbon | Portugal | Hub |
| FCO | Rome | Italy | Hub |
| ZRH | Zurich | Switzerland | Hub |
| SVQ | Seville | Spain | Spoke, through MAD |
| LPA | Gran Canaria | Spain | Spoke, through MAD |
| PMI | Palma de Mallorca | Spain | Spoke, through BCN |
| IBZ | Ibiza | Spain | Spoke, through BCN |
| EDI | Edinburgh | United Kingdom | Spoke, through LHR |
| MAN | Manchester | United Kingdom | Spoke, through LHR |
| NCE | Nice | France | Spoke, through CDG |
| BOD | Bordeaux | France | Spoke, through CDG |
| HAM | Hamburg | Germany | Spoke, through FRA |
| BER | Berlin | Germany | Spoke, through FRA |
| EIN | Eindhoven | Netherlands | Spoke, through AMS |
| OPO | Porto | Portugal | Spoke, through LIS |
| FAO | Faro | Portugal | Spoke, through LIS |
| NAP | Naples | Italy | Spoke, through FCO |
| VCE | Venice | Italy | Spoke, through FCO |
| GVA | Geneva | Switzerland | Spoke, through ZRH |
| JFK | New York | United States | Long-haul, through MAD, LHR, CDG, FRA, AMS |
| MIA | Miami | United States | Long-haul, through MAD, LHR |
| BOG | Bogota | Colombia | Long-haul, through MAD |
| MEX | Mexico City | Mexico | Long-haul, through MAD |
| GRU | Sao Paulo | Brazil | Long-haul, through LIS, MAD |
| DXB | Dubai | United Arab Emirates | Long-haul, through LHR, FRA |
| NRT | Tokyo | Japan | Long-haul, through FRA, CDG |

## Airlines in the dataset

The mock network has 12 airlines, from the same [`StandardCatalogue`](mock-gds/src/main/java/com/flightaggregator/mockgds/reference/StandardCatalogue.java): a network airline flies from its hub, a low-cost one between its bases. The aggregator keeps no airline catalogue, so an unknown airline code is not rejected: it just matches no flight. The dashboard's list of airlines (`frontend/src/itinerary/carriers.ts`) must match the mock's; `frontend/src/search/suggestions.test.ts` checks that both hold the same codes and names, and this table the same codes.

| Code | Name | Type | Hub or bases |
|---|---|---|---|
| IB | Iberia | Network | MAD |
| UX | Air Europa | Network | MAD |
| BA | British Airways | Network | LHR |
| AF | Air France | Network | CDG |
| LH | Lufthansa | Network | FRA |
| KL | KLM | Network | AMS |
| TP | TAP Air Portugal | Network | LIS |
| AZ | ITA Airways | Network | FCO |
| LX | Swiss | Network | ZRH |
| FR | Ryanair | Low-cost | LPA, MAN, EDI, EIN, BER, NAP, FAO, BOD, OPO, VCE, HAM |
| U2 | easyJet | Low-cost | CDG, AMS, LIS, GVA |
| VY | Vueling | Low-cost | BCN, PMI, IBZ |

## Run the mock providers

The three mock GDS providers run as one image, built from `mock-gds/Dockerfile`. `docker compose up --build` starts them with the rest of the stack; to start the mocks alone:

```bash
docker compose up --build mock-gds
```

They listen on `http://localhost:8081`. Each provider asks for its own key in the `X-Api-Key` header, and answers `401` without it:

| Provider | Endpoint | Format | Demo key |
|---|---|---|---|
| Alpha | `GET /alpha/legs?limit=&cursor=` | JSON | `demo-alpha-key` |
| Beta | `GET /beta/segments?pageSize=&page=` | XML | `demo-beta-key` |
| Gamma | `GET /gamma/legs.csv?limit=&offset=` | CSV | `demo-gamma-key` |

```bash
curl -H "X-Api-Key: demo-alpha-key" "http://localhost:8081/alpha/legs?limit=2"
```

**The demo keys are committed on purpose**: the take-home must run with a single `docker compose up --build`. They protect only these local mocks. In a real production environment they would be secrets, injected at deploy time and never committed. Change one with `ALPHA_API_KEY`, `BETA_API_KEY` or `GAMMA_API_KEY` in the shell or a `.env` file.

By default the latency has a long tail, as a real GDS's does: 90% of the requests wait a random 100 to 1000 ms, and the slow 10% wait 1000 to 5000 ms. After its wait, 5% of the requests fail with HTTP 500. Every setting is per provider. Override them with a `compose.override.yaml` next to `compose.yaml`, which `docker compose` merges on its own. This one makes Beta fail every request and Alpha answer without waiting:

```yaml
services:
  mock-gds:
    environment:
      MOCKGDS_PROVIDERS_BETA_ERRORRATE: "1"
      MOCKGDS_PROVIDERS_ALPHA_FASTLATENCYMIN: 0ms
      MOCKGDS_PROVIDERS_ALPHA_FASTLATENCYMAX: 0ms
      MOCKGDS_PROVIDERS_ALPHA_SLOWSHARE: "0"
```

The names follow `MOCKGDS_PROVIDERS_<ALPHA|BETA|GAMMA>_<SETTING>`:

| Setting | Default | What it sets |
|---|---|---|
| `APIKEY` | none (`compose.yaml` sets the demo key) | the key the provider asks for |
| `FASTLATENCYMIN`, `FASTLATENCYMAX` | `100ms`, `1000ms` | the wait of most requests |
| `SLOWLATENCYMIN`, `SLOWLATENCYMAX` | `1000ms`, `5000ms` | the wait of the slow requests |
| `SLOWSHARE` | `0.1` | the share of requests that wait in the slow range |
| `ERRORRATE` | `0.05` | the share of requests that fail with HTTP 500 |

No latency may be negative, each minimum must not exceed its maximum, and both shares go from 0 to 1. A value outside these rules stops the mock at startup with a message that names the setting. So does a `MOCKGDS_PROVIDERS_` variable that is not in this table, such as a misspelt or outdated name: the message names the variable, never its value. Only the spellings in the table are accepted. Outside the environment, Spring Boot itself rejects an unknown setting in a properties file or a command-line argument, and its report prints the value, but it ignores one in a JVM system property. The [architecture overview](docs/architecture/README.md#mock-providers) describes the formats.

## Admin access

Search and re-price are public. The admin side is OAuth2: the aggregator is a resource server that accepts JWTs signed by Keycloak (started by `docker compose up --build`, on `http://localhost:8180`, realm `flight-aggregator`, imported from [`deploy/keycloak/flight-aggregator-realm.json`](deploy/keycloak/flight-aggregator-realm.json)) and requires the realm role `ADMIN` in `realm_access.roles`. Public: `GET /api/itineraries`, `POST /api/itineraries/{id}/reprice`, `GET /v3/api-docs`, Swagger UI (`GET /swagger-ui.html`, `/swagger-ui/**` and `/v3/api-docs/swagger-config`), `GET /health` (its status only; the components are shown to `ADMIN`), and `GET /.well-known/oauth-protected-resource` (Spring Security's own resource-server metadata, RFC 9728, which every `401`'s `WWW-Authenticate` header names; it holds no secret). Everything else, the admin endpoints `/api/admin/**` included, needs `ADMIN`.

The metrics are not on the API port: Actuator serves them on its management port, 8079, which only compose's network reaches and compose does not publish. Prometheus scrapes them there, and the team reads them in Grafana on `http://localhost:3001`, with no login.

Swagger UI is on `http://localhost:8080/swagger-ui.html`, to browse the API and try it; its admin operations answer `401` there without a token, as anywhere else.

The admin endpoints are `GET /api/admin/status` (the inventory's generation, when it was published and when the next run is due, and each provider's health, circuit breaker, newest quote, legs and discarded records) and `POST /api/admin/ingestions` (start a run now: `202 Accepted`, or `409 Conflict` while a run holds the ingestion's lock).

**1. Wait until Keycloak is ready.** This polls the realm until it answers:

```bash
until curl -sf http://localhost:8180/realms/flight-aggregator/.well-known/openid-configuration > /dev/null; do sleep 2; done
```

**2. Get a token** with the `admin-service` client, a confidential client whose service account holds `ADMIN` (client-credentials grant). The secret is read from the variable compose uses, with the demo default:

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/realms/flight-aggregator/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=admin-service \
  -d "client_secret=${ADMIN_SERVICE_CLIENT_SECRET:-demo-admin-service-secret}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
```

(or pipe the response through `jq -r .access_token`). The token lasts 5 minutes; ask for another when it expires. If you set `ADMIN_SERVICE_CLIENT_SECRET` in a `.env` file instead of the shell, `export` the same value in this shell too: the command reads the shell, not `.env`.

**3. Call an admin endpoint with it:**

```bash
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/admin/status
curl -i -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/admin/ingestions
```

| Request | Answer |
|---|---|
| no `Authorization` header, or a token that is invalid, expired, signed with another key or issued by another realm | `401 Unauthorized` |
| a valid token whose holder lacks the `ADMIN` role | `403 Forbidden` |
| a valid token with the `ADMIN` role | `200 OK` (the status), `202 Accepted` or `409 Conflict` (a run) |

`curl -i http://localhost:8080/api/admin/status` gives the 401. No account in the demo realm lacks `ADMIN`, so the 403 is proven by the tests (`SecurityTest`), not by a command.

**The client secret is a committed demo default on purpose**, the same deliberate exception as the provider keys and the database password (spec 6.6): the take-home must run with a single `docker compose up --build`, and a reviewer must be able to call the admin endpoints without the author. It protects only a Keycloak whose port is bound to the loopback interface. In production it would be a secret injected at deploy time. Override it with `ADMIN_SERVICE_CLIENT_SECRET` in the shell or a `.env` file. Keycloak keeps nothing between container creations (compose gives it no data volume, only a read-only bind mount of the realm file) and imports the realm each time it is created, so changing the variable and running `docker compose up -d keycloak` is enough: compose recreates the container, which imports the realm with the new secret. A recreated Keycloak signs with new realm keys, so a token issued before the recreation then answers `401`: get a new one. Do not use `docker compose down -v` for this: it also deletes the stored inventory in PostgreSQL. Keycloak has no administrator console account; the realm's `admin` user, who signs in to the dashboard's admin screen, has the demo password `demo-admin-password`, which `ADMIN_USER_PASSWORD` overrides in the same way.

**Why client credentials.** The grant is for machines acting as themselves: scripts, CI, a reviewer's `curl`. People sign in through the dashboard's browser login (authorization code with PKCE, [Dashboard](docs/architecture/dashboard.md#sign-in-and-the-admins-token)), which has its own public client, `dashboard`. The resource-owner password grant was rejected: OAuth 2.1 removes it, because the client would handle the user's password.

**Two addresses, on purpose.** Tokens name the issuer `http://localhost:8180/realms/flight-aggregator` (`KC_HOSTNAME`), the address a browser uses, while the aggregator, inside the compose network, fetches the signing keys from `http://keycloak:8080/...`. It checks the signature against the second (`spring.security.oauth2.resourceserver.jwt.jwk-set-uri`) and the issuer against the first (`...jwt.issuer-uri`). They are Spring Boot's own resource-server settings, as is the mapping of `realm_access.roles` to `ROLE_ADMIN`, so another identity provider is a change of configuration only.

## Observability

The same `docker compose up --build` starts the telemetry services, provisioned from `deploy/`: the OpenTelemetry Collector, Prometheus, Tempo, Loki and Grafana. The aggregator's metrics go to Prometheus, its traces and logs through the Collector to Tempo and Loki, and Grafana shows all three with no login, as an anonymous Viewer.

| Service | Address (loopback only) | What it shows |
|---|---|---|
| Grafana | `http://localhost:3001` | The "Flight aggregator" dashboard, the alert list, and Explore for logs and traces |
| Prometheus | `http://localhost:9090` | Its alerts page (`/alerts`) and its query screen |

**See an ingestion run as a trace,** in three steps:

1. Start a run: the one at startup, "Run ingestion now" on the dashboard's admin screen, or `POST /api/admin/ingestions` with a token ([Admin access](#admin-access)). A run takes under a minute ([Run the aggregator](#run-the-aggregator)).
2. Open Grafana's Explore (`http://localhost:3001/explore`), choose the **Loki** datasource, and run `{service_name="flight-aggregator"} |= "Ingestion run ended"`.
3. Click the run's line to open its details, then **View trace**: the trace opens beside the logs, with the run (`ingestion.run`) and one request per provider page to Alpha, Beta and Gamma.

The way back works too: **Related logs** on a trace, or on any of its spans, opens that trace's log lines.

**The "Flight aggregator" dashboard** has ten panels. Seven show the metrics the spec promises: provider calls, circuit breakers, data freshness, ingestion, cache hit ratio, discarded records and partitions. Three more show the search's and re-price's HTTP requests, the alert rules in every state, and the application's log lines, where a line's details hold **View trace**. What each panel shows: [Observability, the dashboard](docs/architecture/observability.md#the-dashboard).

The pipeline, the alert rules, the dashboard's panels, what is not collected and how each part is tested: [Observability](docs/architecture/observability.md).

## Documentation

[SOLUTION.md](SOLUTION.md) summarises the solution: each decision, why it was taken, the alternative rejected and the measured figures, then the assumptions, the limitations and how AI was used. The rest of the documentation is split by what each part shows.

### 1. Architecture — what is built
- [Architecture overview](docs/architecture/README.md): hexagonal modules, flows and the domain glossary
- [Ingestion](docs/architecture/ingestion.md): the run, the double-buffered slots, and what each failure does
- [Domain visibility rule](docs/architecture/domain-visibility.md): which domain members are public
- [Search API](docs/architecture/search-api.md): the endpoints, cursor and generation, cache, refresh, re-price, auth and rate limit, measured on the real volume
- [OpenAPI contract](docs/architecture/openapi.json): the API as the application serves it, from which the dashboard's types are generated; `OpenApiContractTest` fails when it drifts
- [Dashboard](docs/architecture/dashboard.md): the two screens and their states, the URL parameters, re-price, the admin sign-in and its token, `/config.json`, nginx, and how each layer is tested
- [Observability](docs/architecture/observability.md): metrics, traces and logs from the application to Grafana, who reaches what, the alert rules and what they mean on a restart, the dashboard, the jumps between a log line and its trace, and how each part is tested

### 2. Planning — how the work was planned
- [Design spec](docs/planning/design-spec.md): goals, domain rules, every design decision and its reasoning
- [Roadmap](docs/planning/roadmap.md): the subsystem plans, their order and the schedule to the delivery
- Implementation plans:
  - [0 — Mock dataset](docs/planning/plans/2026-11-13-mock-dataset.md)
  - [1 — Foundations](docs/planning/plans/2026-11-14-foundations.md)
  - [2 — Domain](docs/planning/plans/2026-11-14-domain.md)
  - [3 — Mock GDS over HTTP](docs/planning/plans/2026-11-15-mock-gds-http.md)
  - [4 — Providers and ingestion](docs/planning/plans/2026-11-15-providers-and-ingestion.md)
  - [5 — Search API](docs/planning/plans/2026-11-15-search-api.md)
  - [6 — Dashboard](docs/planning/plans/2026-11-15-dashboard.md)
  - [7 — Observability and delivery](docs/planning/plans/2026-11-15-observability-and-delivery.md)
- [Continuation point](docs/planning/continuation.md): what is in flight and what comes next, for a session that takes over
- [Extras backlog](docs/planning/extras-backlog.md): extras proposed during plans 5, 6 and 7, out of the delivery, kept in case time remains
- Progress reports:
  - [2026-11-13](docs/planning/progress/2026-11-13.md)
  - [2026-11-14](docs/planning/progress/2026-11-14.md)
  - [2026-11-15](docs/planning/progress/2026-11-15.md)
  - [2026-11-15, plan 5 (search API)](docs/planning/progress/2026-11-15-search-api.md)
  - [2026-11-15, plan 6 (dashboard)](docs/planning/progress/2026-11-15-dashboard.md)
  - [2026-11-15, plan 7 (observability and delivery)](docs/planning/progress/2026-11-15-observability-and-delivery.md)

### 3. AI usage — how the agent was steered
- [How this project uses AI](docs/ai/README.md): the process, the skills, plugins and hooks, and why each one
- [Agent instructions (`CLAUDE.md`)](CLAUDE.md)
- [Working sessions](docs/ai/sessions/):
  1. [Requirements clarification](docs/ai/sessions/01-requirements-clarification.md)
  2. [Hiring team's answers and project plan](docs/ai/sessions/02-team-answers-and-project-plan.md)
  3. [Grill-me on the open design decisions](docs/ai/sessions/03-grill-me-design-decisions.md)
  4. [Autonomous agent work and a TDD correction](docs/ai/sessions/04-autonomous-work-and-tdd.md)
  5. [A scope guard, the dashboard design and the agent's skills](docs/ai/sessions/05-scope-guard-and-agent-skills.md)
  6. [Grill-me: observability, CI and pagination](docs/ai/sessions/06-grill-me-observability-ci-pagination.md)
  7. [Plans, a review protocol and the foundations run](docs/ai/sessions/07-plans-review-protocol-and-foundations.md)
  8. [The domain plan, its review and its run](docs/ai/sessions/08-domain-plan-and-run.md)
  9. [Lost sessions, recovery, and the plans to the delivery](docs/ai/sessions/09-lost-sessions-and-recovery.md)
