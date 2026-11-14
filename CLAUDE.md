# CLAUDE.md — agent instructions for flight-aggregator

These rules apply to every AI agent (main session and subagents) working in this repository. They are versioned on purpose: the way the agent is configured is part of the solution.

## Source of truth
- Design: `docs/planning/design-spec.md`. Do not take design decisions on your own: if one blocks you, write it down as pending in the progress report and move on.
- Decision history: `docs/ai/sessions/`. Implementation plans: `docs/planning/plans/`. Progress reports: `docs/planning/progress/`.
- Documentation is split in three blocks, each indexed from `README.md`: `docs/architecture/` (what is built), `docs/planning/` (spec, plans, progress) and `docs/ai/` (how the agent is used). Add every new document to its block and to the README index.
- How the agent is used, and its skills, plugins and hooks: `docs/ai/README.md`.
- **Scope:** read `.claude/skills/brief-scope/SKILL.md` before proposing or building anything, and follow its output contract. Only what the brief requires or the user has decided goes in, each line with its source; every proposal ends with a "⚠ Extras — your call" section, and extras stay out until the user approves them.

## Test-driven development (non-negotiable)
- Use the `superpowers:test-driven-development` skill for every production change.
- **One test at a time:** write one failing test → run it and confirm it fails **for the expected reason** → write the minimum code to pass → run the suite → refactor. Never write several tests ahead of the code.
- No production code without a failing test that requires it.
- Record the red → green evidence in the progress report of the day: the test, the failure message you saw, and what made it pass. Commits stay green (the full suite passes before every commit), so the evidence lives in the report, not in red commits.

## Before every commit
1. `./gradlew build` (full suite) is green. If `spotlessCheck` fails, run `./gradlew spotlessApply` and build again.
2. Review your own diff with the `thermo-nuclear-code-quality-review` skill and fix the findings. Look for an existing mechanism in the codebase or framework before adding a new one, and for anything that fails silently or leaves state non-atomic.
3. Small commits with clear messages. **No AI attribution of any kind** in commits or PRs (no `Co-Authored-By`, no "Generated with").

## Review protocol (plans executed with subagents)
Three reviewer subagents, defined in `.claude/agents/`, each with one concern:
- **After every task:** `functional-reviewer` (does it deliver what the brief, the spec and the plan ask, with no unapproved extras?) and `code-reviewer` (maintainability with the thermo-nuclear review, plus correctness).
- **At the end of every plan:** all three on the whole plan's diff, adding `risk-reviewer` (security, data loss, silent failures, half-applied state, concurrency, resilience).
- A task moves on only when both of its reviewers say `APPROVED`; a plan closes only when all three do. Findings are fixed, or the user decides to accept them; record which in the progress report.
- Why this split: `docs/ai/README.md`, "Review protocol".

## Pushing
- Only to the personal account: run `gh api user --jq .login` and push only if it prints `dromero001`. On a machine with direnv (the Mac), run it as `direnv exec . gh api user --jq .login`, because direnv selects the personal account there.

## Working from two machines
The work moves between a Mac and a Windows PC, so nothing may live on only one of them.
- **Start of a session:** `git pull --ff-only` before reading or changing anything.
- **End of a session, or before switching machines:** commit and push. The open question or next step goes into the spec's open decisions or the day's progress report, never only into the conversation.
- **Windows:** the repository's scripts are bash; run them with Git Bash.

## Documenting AI usage
- Every relevant working session gets a file in `docs/ai/sessions/` (numbered, in English): goal, what happened, decisions, lesson.
- Each session ends with a **"Prompts that added value"** section, holding only the prompts (the user's, or the briefs given to subagents) that changed the outcome: a challenge that exposed a wrong assumption, a framing that reshaped the design, a brief that made delegated work verifiable. For each prompt, quote or paraphrase it faithfully and say what it changed.
- Prompts are written in English. The working sessions were held in Spanish, so prompts are translated faithfully, keeping their meaning and tone.
- Leave out routine instructions ("commit and push", "document it", "go on"), and never invent or embellish a prompt.

## Conventions
- English everywhere: everything that is presented or documented (code, tests, logs, commit messages, docs, prompts, the dashboard and every user-facing text), even when the conversation with the agent happens in Spanish.
- Dependencies: the latest **stable GA** release, verified against a primary source (Maven Central `maven-metadata.xml`, official release pages) before pinning. No milestones or release candidates. Libraries managed by the Spring Boot BOM take the BOM version.
- Hexagonal boundaries: no framework or persistence types in the domain.
