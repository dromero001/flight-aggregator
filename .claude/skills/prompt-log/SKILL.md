---
name: prompt-log
description: Use in flight-aggregator whenever the user sends a message that challenges an assumption, corrects or reverses a recommendation, decides or changes the design, scope or process, or reports a defect; and whenever a subagent brief is written that makes delegated work verifiable. Also use when starting or resuming a working session.
---

# Prompt log

## Core principle

The prompts that changed the outcome are part of the deliverable (`CLAUDE.md`, "Documenting AI usage"). Sessions drop without warning, so a prompt counts as recorded only once it is in the session file in the repository. A mention in the progress report, the plan or memory does not count.

## The recipe

**When a qualifying prompt arrives, the next file change you make is its entry in the session file**, before you edit the spec, the plan or the progress report and before you dispatch or resume any subagent.

1. **Find the session file:** the highest-numbered `docs/ai/sessions/NN-*.md` that belongs to today's session. If there is none, create `docs/ai/sessions/NN-<short-slug>.md` (next number) with this skeleton, and add it to `docs/ai/sessions/README.md`:

   ```markdown
   # Session NN — <working title>

   - **Date:** <YYYY-MM-DD>
   - **Tooling:** Claude Code (<model>) as the controller; <subagents used>
   - **Goal:** <one sentence>

   ## Prompts that added value
   ```

2. **Append one entry** under `## Prompts that added value`, in exactly this shape:

   ```markdown
   - *"<the prompt, translated faithfully into English>"*: <what it changed, in one or two sentences: the assumption it exposed, the decision it took, or the work it made verifiable, with the spec section, plan or commit it touched>.
   ```

   For a subagent brief, start the entry with `The brief for <task> (a subagent prompt):` and quote only the sentence that made the work verifiable.

3. **Commit it** with the next commit you make, and push it with that commit (continuity rule in `CLAUDE.md`).

4. **When the effect grows later** (the decision lands in the spec, a review confirms it), edit the same entry's "what it changed" part. Never add a second entry for the same prompt.

## What qualifies

| The message | Entry? |
|---|---|
| Questions a recommendation or an assumption ("isn't B more complete?") | Yes |
| Takes or changes a design, scope or process decision | Yes |
| Reports a defect the tests missed ("on my phone, the button is under the keyboard") | Yes |
| Delegates a choice and states the options ("you choose and carry on") | Yes: quote it; say which option was taken and why |
| A subagent brief whose sentence made the work checkable (a test that must fail first, honest labelling of evidence) | Yes |
| "go on", "ok", "commit and push", "document it", "hurry" | No |

A message that mixes a qualifying part with a routine one ("…fix it in task 3 and carry on") is one entry; quote the qualifying part.

## Translation

- Translate the user's Spanish faithfully: keep the meaning, the tone and the order of the ideas. Do not polish, shorten into a summary, or add politeness.
- Keep names, code, numbers and quoted UI text exactly as written (`"Load more"`, `Max price`, `€50`).
- Never invent, merge or improve a prompt. If you only remember its gist, write the gist as a paraphrase without quotation marks and say so: `Paraphrased: …`.

## Red flags: you are deferring the entry

- "I'll add it to the session write-up at the end of the session."
- "It's already in the progress report."
- "The deadline is close; documentation can wait."
- "I'll note it in memory and write it later."

Each of these loses the prompt when the session drops. Write the entry now; it takes one edit.
