# Session 07 — Plans, a review protocol and the foundations run

- **Date:** 2026-11-14
- **Tooling:** Claude Code (Claude Opus 5.5) as the controller; one implementer subagent per task; reviewer subagents defined in `.claude/agents/`
- **Goal:** turn the closed design into implementation plans, decide how plans are executed and reviewed, and build the foundations every later plan relies on.

## 1. Documentation and plans

Before writing any plan, I asked for the documentation to be split by the value each part adds. It now has three blocks indexed from the README: `docs/architecture/` (what is built), `docs/planning/` (the design spec, the [roadmap](../../planning/roadmap.md), the plans and the progress reports) and `docs/ai/` (how the agent is used). The remaining work became one plan per subsystem, each written just before it starts, so that it builds on the interfaces the previous plan really produced rather than on guesses made today.

## 2. Decisions taken before the first plan

| Question | Decision |
|---|---|
| How explicit is the hexagon? | Both sides: one inbound port (an interface) per use case, outbound ports for every dependency, services implementing the inbound ports, all checked by ArchUnit |
| May the application layer use Spring? | Yes. Idiomatic Spring Boot (`@Service`, `@Transactional`) over a framework-free core; the domain stays pure Java |
| Dependabot cooldown | 14 days |
| What is a "stable" version? | One released at least 14 days ago; no release candidates. Applied, the Gradle wrapper moved from 9.9.1 to 9.8.2, and google-java-format stayed on 1.36.1 because 1.37.0 does not run on the Spotless release that meets the rule |

## 3. The review protocol

Plans run with `superpowers:subagent-driven-development`, and three reviewer subagents each answer one question ([why they are split this way](../README.md#review-protocol)):
- `functional-reviewer` and `code-reviewer` after every task;
- all three, adding `risk-reviewer`, on the whole plan.

Each reviewer brief also named the one thing the implementer could not prove on its own. The reviewer then proved it in a throwaway git worktree. Examples: that the ArchUnit rules fail on real code while the modules are still empty; that the coverage gate counts a new module and ignores `config/`; that the reformat commit builds in isolation.

## 4. The foundations run

Plan 1 ([plan](../../planning/plans/2026-11-14-foundations.md), [progress report](../../planning/progress/2026-11-14.md)) produced:
- the six hexagonal modules;
- ArchUnit rules, each with a meta-test that runs it against a fixture breaking it;
- Spotless;
- a JaCoCo gate at 90% of lines and 80% of branches;
- GitHub Actions CI and Dependabot.

Every task was approved by both of its reviewers; three of them needed a short fix round. The whole-plan review found what per-task reviews could not:
- **The git guard could be bypassed** with `git -C <dir>` or combined short flags.
- **An architecture rule could pass vacuously** behind a package typo (`adapters.web` instead of `adapter.web`).
- **The coverage report ran an older JaCoCo** than the one pinned in the catalog.
- **"Every commit on main gets a CI result" was false.** GitHub runs CI once per push.

One fix wave closed them, and a scoped re-review approved it. GitHub now also refuses force pushes to `main` and its deletion, as a server-side backstop for the local hook.

## 5. Prompts that added value

- *"I want the documentation, the Claude skills we are using and the project planning to be clearly separated; all of it adds value to the work done. I want hexagonal architecture too. Don't assume any doubt; ask me."*: produced the three documentation blocks, and made the agent ask about the ports and about Spring instead of deciding them silently.
- *"B seems more appropriate to me, because it shows a modern programming style that makes the most of Spring Boot's strengths."*: settled Spring in the application layer, and with it the shape of the second ArchUnit rule.
- *"For the code reviews too: one agent for the risks, another for the code itself, and another that checks functionally that what was built is what was asked."*: became the review protocol and the three reviewer definitions.
- *"A, but justifying it in the documentation."*: the review cadence is now argued in `docs/ai/README.md` instead of only being stated.
- *"My rule said to use the latest stable version; if it is 8 days old, it is not yet stable to my mind."*: turned an implicit rule into an explicit one (at least 14 days old) and moved the Gradle wrapper back to 9.8.2.
- **The reviewer briefs** that named, for each task, the one claim the implementer could not prove and asked the reviewer to prove it in a throwaway worktree: these turned reviews from reading a report into evidence. One of them showed that the coverage gate was really counting new modules.

## 6. Lesson

Per-task reviews catch what is wrong inside a task. The whole-plan review caught what was wrong between tasks, and what each task had overclaimed: a guard with a gap, a rule that could pass on nothing, a sentence in the spec that the CI did not honour. Splitting reviewers by question, and asking each one for proof rather than an opinion, is what made those findings concrete enough to fix in a single wave.
