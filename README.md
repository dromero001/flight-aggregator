# Flight Aggregator

[![CI](https://github.com/dromero001/flight-aggregator/actions/workflows/ci.yml/badge.svg)](https://github.com/dromero001/flight-aggregator/actions/workflows/ci.yml)

Flight aggregation & cache optimization system: ingests itineraries from three legacy GDS mocks (JSON, XML, CSV), deduplicates and prices them, and serves a keyset-paginated search API with a real-time dashboard.

> Work in progress. Architecture, assumptions and trade-offs will be documented in `SOLUTION.md`.

## Run the aggregator

```bash
docker compose up --build
```

This starts the three mock providers, PostgreSQL, Valkey (the search's result cache, refresh claims and rate-limit counters), Keycloak (the token issuer of the [admin side](#admin-access)) and the aggregator (`bootstrap/Dockerfile`, `-Xmx512m`). The aggregator ingests the providers at startup, then every 30 minutes, and publishes about one million itineraries into PostgreSQL; a run takes about 25 to 30 seconds. Its health is on `http://localhost:8080/health` and the search on `http://localhost:8080/api/itineraries`, both published on the loopback interface only. PostgreSQL and Valkey are not published. How a run works, and what each failure does: [Ingestion](docs/architecture/ingestion.md); the API: [Search API](docs/architecture/search-api.md).

**The database password is a committed demo default too**, the same deliberate exception as the provider keys below (spec 6.6): `POSTGRES_PASSWORD` in the shell or a `.env` file overrides it. It protects only a database that no port exposes outside the compose network; in production it would be a secret injected at deploy time. PostgreSQL sets the password only when it creates its volume: to change it after the first start, remove the volume with `docker compose down -v` (which deletes the stored inventory) and start again.

A `providers.*` setting that names no setting (a misspelt `providers.download-timout`, say) stops the aggregator at startup instead of leaving a default in force. Spring Boot's report of it prints the value it was given, so a secret under a misspelt key (an `api-kye`) would be printed: spell the keys' settings with care.

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

Search and re-price are public. The admin side is OAuth2: the aggregator is a resource server that accepts JWTs signed by Keycloak (started by `docker compose up --build`, on `http://localhost:8180`, realm `flight-aggregator`, imported from [`deploy/keycloak/flight-aggregator-realm.json`](deploy/keycloak/flight-aggregator-realm.json)) and requires the realm role `ADMIN` in `realm_access.roles`. Public: `GET /api/itineraries`, `POST /api/itineraries/{id}/reprice`, `GET /v3/api-docs`, Swagger UI (`GET /swagger-ui.html`, `/swagger-ui/**` and `/v3/api-docs/swagger-config`), and `GET /health` (its status only; the components are shown to `ADMIN`). Everything else, the admin endpoints `/api/admin/**` included, needs `ADMIN`.

The metrics are not on the API port: Actuator serves them on its management port, 8079, which only compose's network reaches and compose does not publish. Prometheus scrapes them there, and the team reads them in Grafana on `http://localhost:3001`, with no login.

Swagger UI is on `http://localhost:8080/swagger-ui.html`, to browse the API and try it; its admin operations answer `401` there without a token, as anywhere else.

The admin endpoints are `GET /api/admin/status` (the inventory's generation, when it was published and when the next run is due, and each provider's health, circuit breaker, newest quote, legs and discarded records) and `POST /api/admin/ingestions` (start a run now: `202 Accepted`, or `409 Conflict` while a run holds the ingestion's lock).

**1. Wait until Keycloak is ready.** It takes 20 to 40 seconds after `docker compose up`; this polls the realm until it answers:

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

**The client secret is a committed demo default on purpose**, the same deliberate exception as the provider keys and the database password (spec 6.6): the take-home must run with a single `docker compose up --build`, and a reviewer must be able to call the admin endpoints without the author. It protects only a Keycloak whose port is bound to the loopback interface. In production it would be a secret injected at deploy time. Override it with `ADMIN_SERVICE_CLIENT_SECRET` in the shell or a `.env` file. Keycloak keeps nothing between container creations (compose gives it no volume) and imports the realm each time it is created, so changing the variable and running `docker compose up -d keycloak` is enough: compose recreates the container, which imports the realm with the new secret. Do not use `docker compose down -v` for this: it also deletes the stored inventory in PostgreSQL. Keycloak has no administrator console account; the realm's `admin` user has the demo password `demo-admin-password`.

**Why client credentials.** The grant is for machines acting as themselves: scripts, CI, a reviewer's `curl`. People will sign in through the dashboard's browser login (authorization code with PKCE, plan 6), which has its own public client. The resource-owner password grant was rejected: OAuth 2.1 removes it, because the client would handle the user's password.

**Two addresses, on purpose.** Tokens name the issuer `http://localhost:8180/realms/flight-aggregator` (`KC_HOSTNAME`), the address a browser uses, while the aggregator, inside the compose network, fetches the signing keys from `http://keycloak:8080/...`. It checks the signature against the second (`spring.security.oauth2.resourceserver.jwt.jwk-set-uri`) and the issuer against the first (`...jwt.issuer-uri`). They are Spring Boot's own resource-server settings, as is the mapping of `realm_access.roles` to `ROLE_ADMIN`, so another identity provider is a change of configuration only.

## Documentation

The documentation is split by what each part shows.

### 1. Architecture — what is built
- [Architecture overview](docs/architecture/README.md): hexagonal modules, flows and the domain glossary
- [Ingestion](docs/architecture/ingestion.md): the run, the double-buffered slots, and what each failure does
- [Domain visibility rule](docs/architecture/domain-visibility.md): which domain members are public
- [Search API](docs/architecture/search-api.md): the endpoints, cursor and generation, cache, refresh, re-price, auth and rate limit, measured on the real volume
- [OpenAPI contract](docs/architecture/openapi.json): the API as the application serves it, from which the dashboard's types are generated; `OpenApiContractTest` fails when it drifts

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
- [Extras backlog](docs/planning/extras-backlog.md): extras proposed during plans 5 and 6, out of the delivery, kept in case time remains
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
  9. [Lost sessions, recovery, and the providers and ingestion plan](docs/ai/sessions/09-lost-sessions-and-recovery.md)
