#!/bin/bash
# Tests for block-destructive-git.sh. Run: bash .claude/hooks/block-destructive-git.test.sh
HOOK="$(dirname "$0")/block-destructive-git.sh"
failures=0

check() {
  local expected=$1 command=$2
  jq -n --arg c "$command" '{tool_input: {command: $c}}' | bash "$HOOK" 2>/dev/null
  local actual=$?
  if [ "$actual" -ne "$expected" ]; then
    echo "FAIL: expected exit $expected, got $actual for: $command"
    failures=$((failures + 1))
  fi
}

# Allowed: everyday work, including a normal push.
check 0 "git push"
check 0 "git push origin main"
check 0 "git push -u origin feature/x"
check 0 "git pull --rebase && git push"
check 0 "git reset --soft HEAD~1"
check 0 "git branch -d merged-branch"
check 0 "git checkout main"
check 0 "git restore --staged README.md"
check 0 "./gradlew build"

# Blocked: anything that rewrites remote history or throws away work.
check 2 "git push --force"
check 2 "git push -f origin main"
check 2 "git push --force-with-lease origin main"
check 2 "git push origin +main"
check 2 "git push origin --delete feature/x"
check 2 "git reset --hard origin/main"
check 2 "git clean -fd"
check 2 "git branch -D feature/x"
check 2 "git checkout ."
check 2 "git restore ."
check 2 "git pull --rebase && git push --force"

if [ "$failures" -eq 0 ]; then echo "All hook tests passed"; else exit 1; fi
