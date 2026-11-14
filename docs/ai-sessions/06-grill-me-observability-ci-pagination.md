# Session 06 — Grill-me: observability, CI and pagination

- **Date:** 2026-11-14
- **Tooling:** Claude Code (Claude Opus 5.5), with the `grilling` and `brief-scope` skills
- **Goal:** close the last three open decisions of the design (spec §10.9–§10.11) before the detailed plan.

## 1. How the questions were framed

From this session on, every proposal followed `brief-scope`: one line per item with its source (the brief, an earlier decision, or the minimum an open decision needs), then a separate "Extras — your call" list with the value and the cost of each extra. I approved or rejected each extra explicitly, so the spec now records which parts of the design go beyond the brief and why.

## 2. Decisions

| Question | Decision | Spec |
|---|---|---|
| Observability | The minimum (Micrometer metrics the other decisions already promised, one alert, structured JSON logs, OpenTelemetry traces, Prometheus and Grafana provisioned from files), plus the OpenTelemetry Collector, Tempo and Loki, so the three signals are correlated in one UI. JVM dashboards and exporters only if time remains; Alertmanager and SLO burn-rate alerts rejected | §6.10 |
| CI | Full backend and frontend suites, contract drift check, image builds and end-to-end tests on every push. Extras approved: cancelled superseded runs and a badge, Dependabot, a JaCoCo coverage gate, and Spotless with google-java-format | §6.11 |
| Coverage threshold | 90% of lines and 80% of branches over unit and integration tests together, excluding the Spring Boot entry point, configuration and generated code. Branches get a lower bar because 90% would force tests with no value | §6.11 |
| Pagination UX | A "Load more" button, as on eDreams' own results page, with no page numbers, no infinite scroll and no total count | §5.4 |

## 3. Prompts that added value

- *"I would also like 2 and 5, because I want to make sure the code is covered. What percentage can we choose — does 90% seem right to you?"*: turned an optional extra into a quality gate, and the follow-up discussion split the threshold into lines and branches instead of a single number that would have produced false failures.

## 4. Lesson

Making the agent separate requirements from extras changed the conversation: instead of accepting or trimming a long "production-like" list, I chose each extra on its value and its cost. Facts the agent could look up (how eDreams paginates) were looked up and cited instead of being asked.
