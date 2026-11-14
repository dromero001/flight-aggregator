---
name: brief-scope
description: Use when proposing, designing, planning or building anything in flight-aggregator (API parameters, response fields, dashboard screens and states, metrics, alerts, CI steps, endpoints, dataset features) — especially when asked to make it "production-like" or "like a real OTA", or when tempted to add a filter, badge, panel, button, flow, tool or service that the brief does not name.
---

# Brief scope

## Core principle

The brief and the user's recorded decisions define the scope. Everything else is the user's call: name it as an **EXTRA**, never slip it in. "Production-like" or "a real OTA does this" is a reason to *propose* an extra, never a licence to include it.

## Sources (check them before classifying)

| Class | Source |
|---|---|
| **REQUIRED** | The brief, as summarised in the design spec (`docs/planning/design-spec.md`) §2 Goals and §4 Domain, and `docs/ai/sessions/01-requirements-clarification.md` |
| **AGREED** | Decisions recorded in the spec (§5, §6, sections marked "decided") and in `docs/ai/sessions/` |
| **IMPLIED** | Something a REQUIRED or AGREED item cannot work without at all (e.g. a `cursor` parameter for keyset pagination). If the item could ship without it, it is an EXTRA |
| **NON-GOAL** | Spec §2 Non-goals (date filter, round trips, other currencies, codeshare, more than 2 stops, load testing). Never propose these |
| **EXTRA** | Anything you cannot trace to one of the rows above, including details you consider standard practice |

**Open decisions** (spec §10, e.g. observability, CI): a goal named in one word makes only its **minimum** REQUIRED — the smallest set that lets a reviewer see the goal working. Put that minimum in the Proposal with the source `(minimum for §10.N)`. Every further metric, dashboard, alert, panel, service or tool is an EXTRA.

## Output contract for every proposal

Every proposal to the user has exactly these parts, in this order:

1. **Proposal** — only REQUIRED, AGREED and IMPLIED items, one per line, and **every line ends with its source in brackets**: `(brief)`, `(§6.5)`, `(implied by keyset pagination)`, `(minimum for §10.9)`. A line you cannot give a source goes to Extras.
2. **⚠ Extras — your call** — one entry per extra: what it is, the value it adds, its cost (code, tests, time), and the words "left out unless you approve". When there are none, write `Extras: none.`

The default for every extra is **out**. Do not build it, describe it as decided, or design other parts around it until the user approves it.

## When implementing (main session or subagent)

- Build only REQUIRED, AGREED and IMPLIED items.
- If you discover a worthwhile extra mid-task, write it as pending in the day's progress report (`docs/planning/progress/<date>.md`) under "Extras for the user to decide", and carry on without it.

## Quick reference

| Item | Class |
|---|---|
| Origin, Destination, Max Price and Carrier filters, all optional | REQUIRED |
| Sort by price, departure or duration | AGREED (§5.4) |
| `cursor` and page size parameters | IMPLIED by keyset pagination |
| Date filter | NON-GOAL |
| Stops filter, "Cheapest" badge, popular-route chips, airline logos, SLO burn-rate alerts | EXTRA |

## Red flags — stop and move the item to Extras

- "A real OTA / production system always has this."
- "It's cheap, just one more parameter / panel / badge."
- A preference question about an extra mixed in among required items.
- Any item in your proposal without a source in brackets.
