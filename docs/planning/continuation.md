# Continuation point

The single place that says what is in flight, where it lives and what comes next, so that any session, on either machine, can take over in a few minutes after a dropped connection. Sessions drop without warning ([session 09](../ai/sessions/09-lost-sessions-and-recovery.md)); the conversation is lost, the repository is not.

**Rule:** the controller rewrites this file and pushes it at every checkpoint: a subagent dispatched or returned, a review verdict, a user decision, a task integrated (`CLAUDE.md`, "Continuity"). If something here contradicts `git log`, `git log` wins: say so and fix this file first.

**Last updated:** 2026-11-15 16:55 CET, on the Mac, `main` at `96e2bfb` plus the commit that adds this file.

## Resume in five minutes

1. `cd flight-aggregator && git pull --ff-only`, then `gh run list --workflow CI --branch main --limit 1` must be green (prefix `gh` with `direnv exec .` on the Mac).
2. Read **In flight** below. A subagent whose dispatching session is gone is dead, even if its worktree looks busy: check its worktree (`git -C <worktree> status -sb`, `git -C <worktree> log --oneline main..HEAD`) and re-dispatch it from what this file records.
3. On the other machine, the worktrees do not exist: take each task's committed work from its `wip/…` branch (`git fetch && git worktree add .claude/worktrees/<name> -b <local name> origin/wip/<…>`). Uncommitted work in a Mac worktree is lost to the PC; redo it from the round's description below.
4. Opened from the parent folder (`~/IdeaProjects`): the repository's `.claude/` is not loaded; reviewers run as general-purpose subagents told to act exactly as their `.claude/agents/<name>.md`, skills are read from `.claude/skills/`. Read `.claude/skills/prompt-log/SKILL.md` at the start.
5. Docker must run for Testcontainers (`open -a Docker` on the Mac). On the Mac, keep it awake with `caffeinate -ims` (one runs until Tuesday 17 00:48, started 2026-11-15 16:48).
6. Then go to **Next steps**.

## Where the truth is

| What | Where |
|---|---|
| Design and every decision | [design-spec.md](design-spec.md) |
| Plans, order and schedule | [roadmap.md](roadmap.md), [plans/](plans/) |
| TDD evidence and review verdicts | [progress/](progress/): `2026-11-15.md` (plans 3–4), `2026-11-15-search-api.md` (plan 5), `2026-11-15-dashboard.md` (plan 6) |
| User decisions and prompts | [session 09](../ai/sessions/09-lost-sessions-and-recovery.md), and the plans' "Decided by the user" lines |
| Task briefs, review reports, implementer reports | `.superpowers/sdd/<plan>/` on the Mac only (git-ignored): each brief is the plan's task text plus its Global Constraints, so it can be rebuilt from the plan; the open findings of every in-flight round are copied below |

## State of the plans

| Plan | State |
|---|---|
| 0–4 | Closed, on `main` |
| 5 · Search API | Tasks 1, 2, 3, 4 and 11 on `main`. Tasks 5 and 8 in fix round 1 (below). Order: 2+3+11 → 4 → **5+8** → 6 → 7+9 → 10+12 → 13. One double reviewer (functional, then code) per task; three reviewers at the end of the plan |
| 6 · Dashboard | Tasks 1, 3 and 9 on `main`. Tasks 2 and 4–8 wait for the OpenAPI contract of plan 5 Task 9; Tasks 10–11 after them |
| 7 · Observability and delivery | Approved 2026-11-15 (Q1–Q4 A; extras E1–E5, E7–E10; E6 out). Tasks 1 and 9 (draft) may start now; 2 and 4 once plan 5 is closed |

## In flight

| Task | State | Worktree (Mac) and branch | Pushed as |
|---|---|---|---|
| Plan 5 Task 5, Valkey | Fix round 1, re-dispatched 16:45 after the drop; then its re-review | `.claude/worktrees/agent-a8dc11a74aba5a730`, `worktree-agent-a8dc11a74aba5a730`, on `dd899d3` over `d307687` | `wip/plan5-task-5` |
| Plan 5 Task 8, re-price | Fix round 1, re-dispatched 16:45 after the drop; then its re-review | `.claude/worktrees/agent-aa031dfa12b4cd6b5`, `worktree-agent-aa031dfa12b4cd6b5`, on `9cc2489` over `d307687` | `wip/plan5-task-8` |

**Plan 5 Task 5, fix round 1** (review: both verdicts CHANGES REQUESTED):
- F1 + C1, as the user decided: after a failed Valkey command, skip Valkey for 10 seconds, then let one call probe it, with Resilience4j's circuit breaker in `ValkeyFailures` (one breaker shared by the four operations; half-open with one permitted call; the single WARN and the recovery INFO tied to its transitions). The wait is a required setting in the `search:` block, above zero, `10s` in `application.yaml`. Tests: a paused Valkey costs one command timeout and then operations return at once; paused before the first command, concurrent callers return within about one timeout; after the wait, one probe resumes operations. Deterministic: drive the breaker's clock or wait, no flaky sleeps.
- F2: an unreadable cached page or `fresh:` mark is a counted miss (`search.cache{result=error}`), never a hit, with a WARN naming the key's kind, never its content.
- The first attempt left uncommitted work in the worktree; its evidence was lost, so its tests are re-shown red by mutation ("recorded after the fact by mutation").

**Plan 5 Task 8, fix round 1** (review: functional CHANGES REQUESTED, code APPROVED):
- F1, as the user decided: a live-confirmed price that cannot be stored because a run holds the publication row is still returned; the store is skipped, logged at WARN and counted. Controller's ruling: `updateInPlace` takes the publication row `FOR SHARE NOWAIT` and returns an outcome (stored, gone, busy) instead of a `boolean`; meter `reprice.store{outcome}`. Tests: busy answers at once and writes nothing; two concurrent re-prices both store; gone answers with the live result. Say whether "an update waiting for a switch writes the slot the run switched to" still holds.
- F2: pin Q9's values (3 alternatives, 10 s budget) with a test, as the 37-day horizon is pinned.
- C1: the update gets its `StatementTimeout`.
- Same note on the lost evidence as Task 5. Deferred to the end-of-plan review: F3, C2–C5.

## Next steps

1. When a fix round returns: push its branch to `wip/…`, update this file, and dispatch its re-review (one subagent acting as `functional-reviewer` then `code-reviewer`, on the round's diff only, with the review file's findings).
2. When a task is approved twice: rebase on `main`, `./gradlew build` (check the exit status), fast-forward `main`, push, watch the CI run, update this file and the handoff page.
3. With Tasks 5 and 8 on `main`: plan 5 Task 6 (the search use case).
4. In parallel, as soon as there is room: plan 7 Task 1 (telemetry backends in compose) and Task 9 (draft of `SOLUTION.md`).

## Waiting on the user

- Delete each `wip/plan*-task-*` branch once its task is on `main` (the git guard stops the agent from deleting remote branches): `git push origin --delete <branch>`. (`wip/grill-me-observability` is already gone.)
- E9 of plan 7: the clean-clone check on the Windows PC, on Monday.
