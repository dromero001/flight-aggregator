# Flight Aggregator

[![CI](https://github.com/dromero001/flight-aggregator/actions/workflows/ci.yml/badge.svg)](https://github.com/dromero001/flight-aggregator/actions/workflows/ci.yml)

Flight aggregation & cache optimization system: ingests itineraries from three legacy GDS mocks (JSON, XML, CSV), deduplicates and prices them, and serves a keyset-paginated search API with a real-time dashboard.

> Work in progress. Architecture, assumptions and trade-offs will be documented in `SOLUTION.md`.

## Run the mock providers

The three mock GDS providers run as one image, built from `mock-gds/Dockerfile`:

```bash
docker compose up --build
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

By default each request waits a random 100 to 5000 ms and fails with HTTP 500 for 5% of the requests. Both settings are per provider. Override them with a `compose.override.yaml` next to `compose.yaml`, which `docker compose` merges on its own. This one makes Beta fail every request and Alpha answer at once:

```yaml
services:
  mock-gds:
    environment:
      MOCKGDS_PROVIDERS_BETA_ERRORRATE: "1"
      MOCKGDS_PROVIDERS_ALPHA_LATENCYMIN: 0ms
      MOCKGDS_PROVIDERS_ALPHA_LATENCYMAX: 0ms
```

The names follow `MOCKGDS_PROVIDERS_<ALPHA|BETA|GAMMA>_<APIKEY|LATENCYMIN|LATENCYMAX|ERRORRATE>`. The [architecture overview](docs/architecture/README.md#mock-providers) describes the formats.

## Documentation

The documentation is split by what each part shows.

### 1. Architecture — what is built
- [Architecture overview](docs/architecture/README.md): hexagonal modules, flows and the domain glossary

### 2. Planning — how the work was planned
- [Design spec](docs/planning/design-spec.md): goals, domain rules, every design decision and its reasoning
- [Roadmap](docs/planning/roadmap.md): the subsystem plans, their order and the schedule to the delivery
- Implementation plans:
  - [0 — Mock dataset](docs/planning/plans/2026-11-13-mock-dataset.md)
  - [1 — Foundations](docs/planning/plans/2026-11-14-foundations.md)
  - [2 — Domain](docs/planning/plans/2026-11-14-domain.md)
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
