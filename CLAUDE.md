# CLAUDE.md — agent instructions for flight-aggregator

These rules apply to every AI agent (main session and subagents) working in this repository. They are versioned on purpose: the way the agent is configured is part of the solution.

## Source of truth
- Design: `docs/superpowers/specs/2026-11-13-flight-aggregator-design.md`. Do not take design decisions on your own: if one blocks you, write it down as pending in the progress report and move on.
- Decision history: `docs/ai-sessions/`. Progress reports: `docs/progress/`.

## Test-driven development (non-negotiable)
- Use the `superpowers:test-driven-development` skill for every production change.
- **One test at a time:** write one failing test → run it and confirm it fails **for the expected reason** → write the minimum code to pass → run the suite → refactor. Never write several tests ahead of the code.
- No production code without a failing test that requires it.
- Record the red → green evidence in the progress report of the day: the test, the failure message you saw, and what made it pass. Commits stay green (the full suite passes before every commit), so the evidence lives in the report, not in red commits.

## Before every commit
1. `./gradlew build` (full suite) is green.
2. Review your own diff with the `thermo-nuclear-code-quality-review` skill and fix the findings. Look for an existing mechanism in the codebase or framework before adding a new one, and for anything that fails silently or leaves state non-atomic.
3. Small commits with clear messages. **No AI attribution of any kind** in commits or PRs (no `Co-Authored-By`, no "Generated with").

## Pushing
- Only to the personal account: run `direnv exec . gh api user --jq .login` and push only if it prints `dromero001`.

## Documenting AI usage
- Every relevant working session gets a file in `docs/ai-sessions/` (numbered, in English): goal, what happened, decisions, lesson.
- Each session ends with a **"Prompts that added value"** section, holding only the prompts (the user's, or the briefs given to subagents) that changed the outcome: a challenge that exposed a wrong assumption, a framing that reshaped the design, a brief that made delegated work verifiable. For each prompt, quote or paraphrase it faithfully and say what it changed.
- Leave out routine instructions ("commit and push", "document it", "go on"), and never invent or embellish a prompt.

## Conventions
- English everywhere: code, tests, logs, docs and every user-facing text.
- Dependencies: the latest **stable GA** release, verified against a primary source (Maven Central `maven-metadata.xml`, official release pages) before pinning. No milestones or release candidates. Libraries managed by the Spring Boot BOM take the BOM version.
- Hexagonal boundaries: no framework or persistence types in the domain.
