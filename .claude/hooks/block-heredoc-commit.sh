#!/usr/bin/env bash
# PreToolUse hook — blocks `git commit` shapes that hang the Bash harness on
# this host. See memory: feedback_git_commit_hang.md.
#
# Bad shapes:
#   * `git commit -m "$(cat <<EOF ... EOF)"` — HEREDOC reintroduces a pipe
#     boundary that the inherited stdout/stderr fds keep open after git exits.
#   * `git commit ... | tail`, `| head`, `| sed`, `| awk` — same pipe issue.
#
# Good shape: chained `-m` flags, no pipe, no command substitution.
#   git commit -m "subject" -m "body line 1" -m "body line 2" -m "Co-Authored-By: …"
#
# Exit 2 = block + show stderr to Claude. Exit 0 = allow.
set -euo pipefail

input=$(cat)

# Only inspect Bash tool calls. Other tools pass through.
tool=$(printf '%s' "$input" | jq -r '.tool_name // empty')
[ "$tool" = "Bash" ] || exit 0

cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty')
[ -n "$cmd" ] || exit 0

# Real-commit detection: strip heredoc bodies and single-quoted string literals
# before asking "is this a real `git commit` invocation?". Avoids false positives
# on diagnostic scripts that mention "git commit" inside a python heredoc, jq
# filter, or sed pattern. Falls back to the raw command if python3 is missing.
real_cmd=$(printf '%s' "$cmd" | python3 -c '
import sys, re
s = sys.stdin.read()
s = re.sub(
    r"""<<-?\s*(?:["\x27]?)([A-Za-z_]\w*)(?:["\x27]?)[^\n]*\n.*?^\s*\1\s*$""",
    "",
    s,
    flags=re.DOTALL | re.MULTILINE,
)
s = re.sub(r"\x27[^\x27]*\x27", "", s)
sys.stdout.write(s)
' 2>/dev/null) || real_cmd="$cmd"

case "$real_cmd" in
    *"git commit"*) ;;
    *) exit 0 ;;
esac

# HEREDOC inside the command (anywhere — covers `<<EOF`, `<<-EOF`, `<<'EOF'`).
if printf '%s' "$cmd" | grep -qE '<<-?[[:space:]]*'\''?[A-Za-z_]'; then
    cat >&2 <<'MSG'
[block-heredoc-commit] Refusing `git commit` with HEREDOC.

This shape hangs the Bash harness on this host (see
~/.claude/projects/-home-hanz3000-repo-fire-stream-chat/memory/feedback_git_commit_hang.md).

Use chained -m flags instead, e.g.:
  git commit -m "feat(x): subject" \
             -m "First paragraph of body." \
             -m "Second paragraph." \
             -m "Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
MSG
    exit 2
fi

# Piping commit output through tail/head/sed/awk also re-introduces the issue.
if printf '%s' "$cmd" | grep -qE 'git commit[^|]*\|[[:space:]]*(tail|head|sed|awk)\b'; then
    cat >&2 <<'MSG'
[block-heredoc-commit] Refusing `git commit | tail|head|sed|awk`.

The pipe keeps reading stdin until EOF; combined with VS Code's git-extension
fd inheritance on this host, the harness pipe never closes. Just run
`git commit -m …` without piping its output.
MSG
    exit 2
fi

exit 0
