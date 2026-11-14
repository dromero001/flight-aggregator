#!/bin/bash
# PreToolUse hook: blocks git commands that rewrite remote history, delete main or throw away work,
# whatever global options precede the subcommand, and deleting main through `gh api`.
# A normal `git push` and deleting other remote branches stay allowed: agents push their green commits to main and clean up their own branches.
# Adapted from Matt Pocock's git-guardrails-claude-code skill (MIT).

if command -v jq > /dev/null; then
  COMMAND=$(jq -r '.tool_input.command // empty')
else
  # Without jq (Git for Windows does not ship it), match the raw JSON: the command is inside it.
  COMMAND=$(cat)
fi

# Where a ref or path word ends: at any character that cannot continue it (a space, a quote, `;`,
# the JSON's closing quote), or at the end of the line. So `main` matches, but `main-fix` does not.
END='([^[:alnum:]_./-]|$)'

# An option value, possibly quoted; the raw JSON escapes double quotes as \".
VALUE="(\\\\?\"[^\"]*\"|'[^']*'|[^[:space:]]+)"
# `git` and its global options, up to the subcommand: `git -C <dir> push` is still a push.
OPTION_WITH_VALUE="(-[Cc]|--(git-dir|work-tree|namespace|config-env|attr-source))[[:space:]]+${VALUE}"
FLAG='--?[a-zA-Z][a-zA-Z-]*(=[^[:space:]]+)?'
GIT="git([[:space:]]+(${OPTION_WITH_VALUE}|${FLAG}))*[[:space:]]+"

# A `git branch` option token that deletes, and one that forces (`-D` is both).
DELETE='(--delete|-[a-zA-Z]*d[a-zA-Z]*)'
FORCE='(--force|-[a-zA-Z]*f[a-zA-Z]*)'

# The two halves of deleting main through the GitHub API, in either order.
HTTP_DELETE='(-X|--method)[=[:space:]]*DELETE\b'
MAIN_REF="refs/heads/main${END}"

DESTRUCTIVE_PATTERNS=(
  "${GIT}push\b.*[[:space:]](--force|--mirror|-[a-zA-Z]*f|\+[^[:space:]])"
  "${GIT}push\b.*([[:space:]](--delete|-d)[[:space:]](.*[[:space:]])?|[[:space:]]:)(refs/heads/)?main${END}"
  "${GIT}reset[[:space:]]+(.*[[:space:]])?--hard"
  "${GIT}clean[[:space:]]+(.*[[:space:]])?(--force|-[a-zA-Z]*f)"
  "${GIT}branch[[:space:]]+(.*[[:space:]])?-[a-zA-Z]*(D|d[a-zA-Z]*f|f[a-zA-Z]*d)"
  "${GIT}branch[[:space:]]+(.*[[:space:]])?(${DELETE}[[:space:]](.*[[:space:]])?${FORCE}|${FORCE}[[:space:]](.*[[:space:]])?${DELETE})"
  "${GIT}(checkout|restore)[[:space:]]+(--[[:space:]]+)?\.${END}"
  "${GIT}update-ref[[:space:]]+(.*[[:space:]])?-d[[:space:]]+${MAIN_REF}"
  "gh[[:space:]]+api\b.*(${HTTP_DELETE}.*${MAIN_REF}|${MAIN_REF}.*${HTTP_DELETE})"
)

for pattern in "${DESTRUCTIVE_PATTERNS[@]}"; do
  if grep -qE "$pattern" <<< "$COMMAND"; then
    echo "BLOCKED: '$COMMAND' is a destructive git command. Ask the user to run it themselves if it is really needed." >&2
    exit 2
  fi
done

exit 0
