#!/bin/bash
# PreToolUse hook: blocks git commands that rewrite remote history or throw away work.
# A normal `git push` stays allowed, because agents push their green commits to main.
# Adapted from Matt Pocock's git-guardrails-claude-code skill (MIT).

if ! command -v jq > /dev/null; then
  # Exit 1 is a non-blocking hook error: Claude Code shows it, so the missing guard is never silent.
  echo "block-destructive-git.sh: jq is not installed, so destructive git commands are NOT being blocked." >&2
  exit 1
fi

COMMAND=$(jq -r '.tool_input.command // empty')

DESTRUCTIVE_PATTERNS=(
  'git[[:space:]]+push\b.*[[:space:]](--force|--force-with-lease|-f\b|--delete|-d\b|\+[^[:space:]])'
  'git[[:space:]]+reset[[:space:]]+(.*[[:space:]])?--hard'
  'git[[:space:]]+clean[[:space:]]+(.*[[:space:]])?-[a-zA-Z]*f'
  'git[[:space:]]+branch[[:space:]]+(.*[[:space:]])?-D\b'
  'git[[:space:]]+(checkout|restore)[[:space:]]+\.([[:space:]]|$)'
)

for pattern in "${DESTRUCTIVE_PATTERNS[@]}"; do
  if grep -qE "$pattern" <<< "$COMMAND"; then
    echo "BLOCKED: '$COMMAND' is a destructive git command. Ask the user to run it themselves if it is really needed." >&2
    exit 2
  fi
done

exit 0
