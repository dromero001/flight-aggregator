# Flight Aggregator

[![CI](https://github.com/dromero001/flight-aggregator/actions/workflows/ci.yml/badge.svg)](https://github.com/dromero001/flight-aggregator/actions/workflows/ci.yml)

Flight aggregation & cache optimization system: ingests itineraries from three legacy GDS mocks (JSON, XML, CSV), deduplicates and prices them, and serves a keyset-paginated search API with a real-time dashboard.

> Work in progress. Architecture, assumptions and trade-offs will be documented in `SOLUTION.md`.

## Run the aggregator

```bash
docker compose up --build
```

This starts the three mock providers, PostgreSQL and the aggregator (`bootstrap/Dockerfile`, `-Xmx512m`). The aggregator ingests the providers at startup, then every 30 minutes, and publishes about one million itineraries into PostgreSQL; the first run takes about 12 to 15 seconds. Its health is on `http://localhost:8080/actuator/health`, published on the loopback interface only. PostgreSQL is not published. How a run works, and what each failure does: [Ingestion](docs/architecture/ingestion.md).

**The database password is a committed demo default too**, the same deliberate exception as the provider keys below (spec 6.6): `POSTGRES_PASSWORD` in the shell or a `.env` file overrides it. It protects only a database that no port exposes outside the compose network; in production it would be a secret injected at deploy time. PostgreSQL sets the password only when it creates its volume: to change it after the first start, remove the volume with `docker compose down -v` (which deletes the stored inventory) and start again.

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

## Documentation

The documentation is split by what each part shows.

### 1. Architecture — what is built
- [Architecture overview](docs/architecture/README.md): hexagonal modules, flows and the domain glossary
- [Ingestion](docs/architecture/ingestion.md): the run, the double-buffered slots, and what each failure does
- [Domain visibility rule](docs/architecture/domain-visibility.md): which domain members are public

### 2. Planning — how the work was planned
- [Design spec](docs/planning/design-spec.md): goals, domain rules, every design decision and its reasoning
- [Roadmap](docs/planning/roadmap.md): the subsystem plans, their order and the schedule to the delivery
- Implementation plans:
  - [0 — Mock dataset](docs/planning/plans/2026-11-13-mock-dataset.md)
  - [1 — Foundations](docs/planning/plans/2026-11-14-foundations.md)
  - [2 — Domain](docs/planning/plans/2026-11-14-domain.md)
  - [3 — Mock GDS over HTTP](docs/planning/plans/2026-11-15-mock-gds-http.md)
  - [4 — Providers and ingestion](docs/planning/plans/2026-11-15-providers-and-ingestion.md)
  - [5 — Search API](docs/planning/plans/2026-11-15-search-api.md) (draft, under the user's review)
- Progress reports:
  - [2026-11-13](docs/planning/progress/2026-11-13.md)
  - [2026-11-14](docs/planning/progress/2026-11-14.md)
  - [2026-11-15](docs/planning/progress/2026-11-15.md)

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
