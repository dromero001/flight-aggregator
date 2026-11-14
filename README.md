# Flight Aggregator

Flight aggregation & cache optimization system: ingests itineraries from three legacy GDS mocks (JSON, XML, CSV), deduplicates and prices them, and serves a keyset-paginated search API with a real-time dashboard.

> Work in progress. Architecture, assumptions and trade-offs will be documented in `SOLUTION.md`.

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
- Progress reports:
  - [2026-11-13](docs/planning/progress/2026-11-13.md)
  - [2026-11-14](docs/planning/progress/2026-11-14.md)

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
