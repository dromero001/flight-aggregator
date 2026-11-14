# Session 05 — A scope guard, the dashboard design and the agent's skills

- **Date:** 2026-11-14
- **Tooling:** Claude Code (Claude Opus 5.5); a background subagent for the dataset fixes; short-lived subagents to test a new skill
- **Goal:** close the dashboard design in the grill-me, finish the dataset calibration overnight work, and version the agent configuration that the project relies on.

## 1. Dataset calibration, round 2

The first calibration had met its target (about 40% single-airline itineraries) the wrong way: 95% of those were Ryanair. A one-hub airline cannot fly a 2-stop trip alone without revisiting its hub, which the domain now forbids, so the generator inflated point-to-point low-cost flights. The agent traced the cause to the target itself, and I approved three changes: measure the 40% over direct and 1-stop itineraries only, make hub routes the most frequent, and give every route its own seed. A background subagent applied them with strict TDD. Result: 1,001,909 itineraries, 40.4% single-airline over direct and 1-stop, and Ryanair down from 44.5% to 23.8% of flights ([progress report](../progress/2026-11-14.md)).

## 2. Dashboard design

The grill-me closed the dashboard with two screens: a public search page and an admin provider-status page (spec §6.8). In the first sketch of the search page, the agent added a date filter (an agreed non-goal) and a stops filter that nobody had asked for. I stopped it there.

## 3. A skill to stay within the brief

Correcting the agent once does not stop the next agent or subagent from doing the same, so I asked for a skill. The agent built it test-first, as `superpowers:writing-skills` prescribes:

1. **Red.** Three subagents without the skill proposed the search API, the result cards and the observability stack "as in a real production system". All three added unrequested features without marking them: a stops parameter, popular-route chips, badges, SLO burn-rate alerts, extra exporters and dashboards.
2. **Green.** [`brief-scope`](../../.claude/skills/brief-scope/SKILL.md) classifies every item as required (the brief), agreed (a recorded decision), implied (needed for an agreed item to work) or extra. A proposal lists only the first three, each line with its source, and ends with "Extras — your call"; extras stay out until approved. The same three tasks then came back sourced and with their extras separated.
3. **Refactor.** On an open decision (observability) one run still treated "production-like" as licence. Two fixes closed it: an open decision makes only its *minimum* required, and `CLAUDE.md` tells every agent to read the skill and states its output contract. Observability and CI both passed on the re-run.

## 4. Versioned agent configuration

`CLAUDE.md` already required skills that a fresh clone would not have. They are now in the repository ([`.claude/skills/README.md`](../../.claude/skills/README.md)): the project and author skills are copied in, Matt Pocock's skills keep their MIT licence, and the superpowers, elements-of-style and frontend-design plugins are declared in `.claude/settings.json`. A tested PreToolUse hook blocks only destructive git commands, so agents can still push their green commits.

## 5. Prompts that added value

- *"Don't introduce more filters than the test requires."*: exposed that the agent was designing to "what a real OTA has" rather than to the brief.
- *"Can we make a skill so that you stick to the test's requirements? If you add any extra feature, point it out so that I decide."*: turned a one-off correction into versioned behaviour for every agent, and defined the output contract (extras flagged, the user decides).
- *"The previous work was to get ahead overnight; let's continue the grill-me before implementing anything else."*: kept design decisions ahead of code, so the overnight work stayed limited to the independent dataset.

## 6. Lesson

"Make it production-like" is a strong pull for an agent: it fills every gap with what a real system *might* have, and it presents those additions in the same confident voice as the requirements. Asking for the source of each item is a cheap guard, and testing the guard against agents that lack it showed that it was needed.
