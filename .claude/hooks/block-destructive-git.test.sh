#!/bin/bash
# Tests for block-destructive-git.sh. Run: bash .claude/hooks/block-destructive-git.test.sh
HOOK="$(dirname "$0")/block-destructive-git.sh"
failures=0

# A PATH without jq (Git for Windows does not ship it), holding only the other tools the hook uses.
NO_JQ=$(mktemp -d)
trap 'rm -rf "$NO_JQ"' EXIT
for tool in grep cat; do
  printf '#!/bin/sh\nexec "%s" "$@"\n' "$(command -v "$tool")" > "$NO_JQ/$tool"
  chmod +x "$NO_JQ/$tool"
done

# Every case must give the same result with and without jq.
check() {
  local expected=$1 command=$2 path actual
  for path in "$PATH" "$NO_JQ"; do
    jq -n --arg c "$command" '{tool_input: {command: $c}}' | PATH="$path" "$BASH" "$HOOK" 2>/dev/null
    actual=$?
    if [ "$actual" -ne "$expected" ]; then
      echo "FAIL: expected exit $expected, got $actual $([ "$path" = "$NO_JQ" ] && echo without || echo with) jq for: $command"
      failures=$((failures + 1))
    fi
  done
}

# Allowed: everyday work, including a normal push and deleting a remote branch other than main.
check 0 "git push"
check 0 "git push origin main"
check 0 "git push -u origin feature/x"
check 0 "git pull --rebase && git push"
check 0 "git reset --soft HEAD~1"
check 0 "git branch -d merged-branch"
check 0 "git branch -d feature/fix"
check 0 "git checkout main"
check 0 "git restore --staged README.md"
check 0 "./gradlew build"
check 0 "git push origin --delete feature/x"
check 0 "git push origin -d feature/x"
check 0 "git push origin :feature/x"
check 0 "git push origin -d feature/main-fix"
check 0 "git -C /abs/path push origin main"
check 0 "git push -u origin feature/fix"
check 0 "git push --follow-tags"
check 0 "gh api repos/o/r/git/refs/heads/main"
check 0 "gh api -X DELETE repos/o/r/git/refs/heads/feature/x"
check 0 "git update-ref -d refs/heads/feature/x"
check 0 $'git push origin main \\\n  --follow-tags'
check 0 $'git status\ngit log --oneline'

# Allowed: a pattern reads one simple command, so what follows `;`, `&&`, `||`, `|` or a line break
# is not part of it.
check 0 "git push -q origin main && date +%H:%M"
check 0 "git push origin main; date +%H:%M"
check 0 "git push origin main 2>&1 | grep -F +main"
check 0 "git push origin main || date +%H:%M"
check 0 $'cat > notes.txt <<\'EOF\'\nRun git push origin main\nthen date +%H:%M\nEOF'
check 0 "git push origin --delete feature/x && git checkout main"
check 0 "git reset --soft HEAD~1 && grep -n -- --hard notes.txt"
check 0 "git clean -n; rm -f build.log"
check 0 "git branch --show-current && ./gradlew test -Dorg.gradle.debug=false"
check 0 "git branch -d merged-branch && rm -f merged.patch"
check 0 "git update-ref refs/heads/backup HEAD; git show-ref -d refs/heads/main"
check 0 "gh api -X DELETE repos/o/r/git/refs/heads/feature/x && git log -1 refs/heads/main"
check 0 "git -C repo;echo push -f"
check 0 "git --git-dir=.git&&echo push -f"

# Blocked: anything that rewrites remote history, deletes main or throws away work.
check 2 "git push --force"
check 2 "git push -f origin main"
check 2 "git push --force-with-lease origin main"
check 2 "git push origin +main"
check 2 "git push origin --delete main"
check 2 "git push origin -d main"
check 2 "git push origin :main"
check 2 "git push --delete origin main"
check 2 "git reset --hard origin/main"
check 2 "git clean -fd"
check 2 "git branch -D feature/x"
check 2 "git checkout ."
check 2 "git restore ."
check 2 "git pull --rebase && git push --force"
check 2 "git -C . push --force origin main"
check 2 "git -c k=v push -f origin main"
check 2 "git --no-pager push --force"
check 2 "git --work-tree /repo push --force"
check 2 "git -C \"/my repo\" push --force"
check 2 "git -C '/my repo' push --force"
check 2 "git push -fu origin main"
check 2 "git push -uf origin main"
check 2 "git push --mirror"
check 2 "git checkout -- ."
check 2 "git clean --force -d"
check 2 "git branch -fD x"
check 2 "git branch --delete --force x"
check 2 "git branch -d -f x"
check 2 "git branch --force --delete x"
check 2 "git branch -df x"
check 2 "gh api -X DELETE repos/o/r/git/refs/heads/main"
check 2 "gh api repos/o/r/git/refs/heads/main --method DELETE"
check 2 "git update-ref -d refs/heads/main"
check 2 $'git push origin main \\\n  --force'
check 2 $'git reset \\\n  --hard origin/main'
check 2 $'git push origin main\n--force'
check 2 $'git reset\n--hard'

# Blocked: a destructive command alone, or after another one.
check 2 "git push -f"
check 2 "echo a; git push origin +main"
check 2 "echo a; git push -f"
check 2 "cd x && git push origin --delete main"
check 2 "cd x && git push origin :main"

# Blocked: a separator inside quotes does not end the command.
check 2 "git push -o \"merge_request.title=Fix; tidy\" --force"
check 2 "git push -o 'merge_request.title=Fix | tidy' --force"

if [ "$failures" -eq 0 ]; then echo "All hook tests passed"; else exit 1; fi
