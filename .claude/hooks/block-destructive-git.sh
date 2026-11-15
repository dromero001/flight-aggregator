#!/bin/bash
# PreToolUse hook: blocks git commands that rewrite remote history, delete main or throw away work,
# whatever global options precede the subcommand, and deleting main through `gh api`. Each pattern
# reads one simple command, so what follows `;`, `&&`, `||`, `|` or a line break is not part of it.
# A normal `git push` and deleting other remote branches stay allowed: agents push their green commits to main and clean up their own branches.
# Adapted from Matt Pocock's git-guardrails-claude-code skill (MIT).

if command -v jq > /dev/null; then
  COMMAND=$(jq -r '.tool_input.command // empty')
else
  # Without jq (Git for Windows does not ship it), match the raw JSON: the command is inside it. Its
  # escapes are decoded in order: an escaped backslash (\\) is set aside first, so the \t of
  # C:\tools is not read as a tab; then \r\n and \n become a line break and \t a tab; then each
  # escaped backslash becomes one backslash. JSON never holds a raw control character, so \001 is
  # free to stand in for it.
  COMMAND=$(cat)
  COMMAND=${COMMAND//\\\\/$'\001'}
  COMMAND=${COMMAND//\\r\\n/$'\n'}
  COMMAND=${COMMAND//\\n/$'\n'}
  COMMAND=${COMMAND//\\t/$'\t'}
  COMMAND=${COMMAND//$'\001'/\\}
fi

# grep matches one line at a time, so a line break ends what a pattern can match, as it ends a
# command in the shell. Only an explicit continuation does not: bash's, a backslash at the end of
# the line, and PowerShell's, a backtick after a space at the end of the line, each become a space.
# A Windows line ending (CRLF) is read as a line break first, so it does not hide a continuation.
COMMAND=${COMMAND//$'\r\n'/$'\n'}
COMMAND=${COMMAND//\\$'\n'/ }
COMMAND=${COMMAND//[[:space:]]\`$'\n'/ }

# Where a ref or path word ends: at any character that cannot continue it (a space, a quote, `;`,
# the JSON's closing quote), or at the end of the line. So `main` matches, but `main-fix` does not.
END='([^[:alnum:]_./-]|$)'

# A quoted string; the raw JSON escapes double quotes as \".
DOUBLE_QUOTED='\\?"[^"]*"'
SINGLE_QUOTED="'[^']*'"

# An option value, possibly quoted. Unquoted, it ends at a space or at a character that ends the
# command (`;`, `&`, `|`).
VALUE="(${DOUBLE_QUOTED}|${SINGLE_QUOTED}|[^[:space:];|&]+)"
# `git` and its global options, up to the subcommand: `git -C <dir> push` is still a push.
OPTION_WITH_VALUE="(-[Cc]|--(git-dir|work-tree|namespace|config-env|attr-source))[[:space:]]+${VALUE}"
FLAG='--?[a-zA-Z][a-zA-Z-]*(=[^[:space:];|&]+)?'
GIT="git([[:space:]]+(${OPTION_WITH_VALUE}|${FLAG}))*[[:space:]]+"

# A `git branch` option token that deletes, and one that forces (`-D` is both).
DELETE='(--delete|-[a-zA-Z]*d[a-zA-Z]*)'
FORCE='(--force|-[a-zA-Z]*f[a-zA-Z]*)'

# The two halves of deleting main through the GitHub API, in either order.
HTTP_DELETE='(-X|--method)[=[:space:]]*DELETE\b'
MAIN_REF="refs/heads/main${END}"

# The text of one simple command: it does not cross `;`, `&&` or `|` (so `||` either). A lone `&`,
# as in `2>&1`, does not end it, nor does an escaped separator (`a\;b`; `a\\;b` in the raw JSON).
# A quoted string can be taken whole, so a separator inside quotes does not end the command, and a
# quote can also be read as a plain character, so quoting that does not pair as written (an escaped
# quote, a quoted word that holds the target) never hides what follows it: where the reading is
# ambiguous, the guard blocks.
ESCAPED_SEPARATOR='\\\\?[;|&]'
IN_COMMAND="([^;|&]|&[^;|&]|${ESCAPED_SEPARATOR}|${DOUBLE_QUOTED}|${SINGLE_QUOTED})*"

DESTRUCTIVE_PATTERNS=(
  "${GIT}push\b${IN_COMMAND}[[:space:]](--force|--mirror|-[a-zA-Z]*f|\+[^[:space:]])"
  "${GIT}push\b${IN_COMMAND}([[:space:]](--delete|-d)[[:space:]](${IN_COMMAND}[[:space:]])?|[[:space:]]:)(refs/heads/)?main${END}"
  "${GIT}reset[[:space:]]+(${IN_COMMAND}[[:space:]])?--hard"
  "${GIT}clean[[:space:]]+(${IN_COMMAND}[[:space:]])?(--force|-[a-zA-Z]*f)"
  "${GIT}branch[[:space:]]+(${IN_COMMAND}[[:space:]])?-[a-zA-Z]*(D|d[a-zA-Z]*f|f[a-zA-Z]*d)"
  "${GIT}branch[[:space:]]+(${IN_COMMAND}[[:space:]])?(${DELETE}[[:space:]](${IN_COMMAND}[[:space:]])?${FORCE}|${FORCE}[[:space:]](${IN_COMMAND}[[:space:]])?${DELETE})"
  "${GIT}(checkout|restore)[[:space:]]+(--[[:space:]]+)?\.${END}"
  "${GIT}update-ref[[:space:]]+(${IN_COMMAND}[[:space:]])?-d[[:space:]]+${MAIN_REF}"
  "gh[[:space:]]+api\b${IN_COMMAND}(${HTTP_DELETE}${IN_COMMAND}${MAIN_REF}|${MAIN_REF}${IN_COMMAND}${HTTP_DELETE})"
)

for pattern in "${DESTRUCTIVE_PATTERNS[@]}"; do
  if grep -qE "$pattern" <<< "$COMMAND"; then
    echo "BLOCKED: '$COMMAND' is a destructive git command. Ask the user to run it themselves if it is really needed." >&2
    exit 2
  fi
done

exit 0
