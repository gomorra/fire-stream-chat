#!/usr/bin/env bash
# Follow what a plan-runner session is doing, as readable lines.
#
#   scripts/plan-runner/watch.sh [<run-id>] [--all]      e.g. sticker-manager-and-names
#
# A run id is the log's file stem in docs/plans/.runs/ (<plan> or <plan>-<variant>). Without
# one, the newest session of any run is followed. When a newer session starts (the next step,
# a nudge, a review), the watch moves to it by itself. Ctrl-C ends the watch and nothing else.
# A line with `·` is what the session wrote, `→` is a tool it called, `==` is its result.
# The first session shows its last 40 lines, or the whole of it with --all.
# It only reads the stream files, so it is safe beside a running run.
set -euo pipefail
ROOT=$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)
RUNS=${PLAN_RUNNER_RUNS_DIR:-$ROOT/docs/plans/.runs}
POLL_S=${PLAN_RUNNER_WATCH_POLL_S:-2}

id='' all=0
for arg in "$@"; do
    case $arg in
        --all) all=1 ;;
        -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) echo "watch: unknown option $arg" >&2; exit 1 ;;
        *) id=$arg ;;
    esac
done
command -v jq >/dev/null || { echo "watch: jq is not installed" >&2; exit 1; }

# The newest stream of the run, or of any run. A stream is <run-id>.step<n>.<time>.<kind>.json.stream.jsonl.
newest() {
    local f
    f=$(find "$RUNS" -maxdepth 1 -name "${id:+$id.step}*.stream.jsonl" -printf '%T@ %p\n' 2>/dev/null |
        sort -rn | head -n 1 | cut -d' ' -f2-)
    printf '%s' "$f"
}

# One line per thing the session said or did. Long text and commands are cut, not wrapped.
FILTER='
# Paths are shown from the checkout, without the worktree in front.
def cut($n): tostring | gsub("\\s+"; " ") | gsub("/[^ ]*/\\.claude/worktrees/[^/ ]+/"; "") | gsub($root + "/"; "")
  | if length > $n then .[0:$n] + "…" else . end;
if .type == "result" then
  "== \(.subtype // "result"): \((.result // "") | cut(400))"
elif .type == "assistant" then
  .message.content[]?
  | if .type == "text" then "· \(.text | cut(240))"
    elif .type == "tool_use" then
      "→ \(.name) \((.input.command // .input.file_path // .input.pattern // .input.skill // .input.description // "") | cut(160))"
    else empty end
else empty end'

tail_pid=''
stop_tail() { [ -z "$tail_pid" ] || { kill "$tail_pid" 2>/dev/null || true; wait "$tail_pid" 2>/dev/null || true; tail_pid=''; }; }
trap 'stop_tail; exit 0' INT TERM
trap stop_tail EXIT

current='' first=1
while :; do
    f=$(newest)
    if [ -z "$f" ]; then
        [ -n "$current" ] || { echo "watch: no session stream in $RUNS${id:+ for $id}, waiting" >&2; current=none; }
    elif [ "$f" != "$current" ]; then
        stop_tail
        current=$f
        name=${f##*/}; name=${name%.json.stream.jsonl}
        printf '\n=== %s ===\n' "$name"
        # Later sessions are shown from their start: they began while the watch was open.
        from='+1'
        [ $first -eq 1 ] && [ $all -eq 0 ] && from=40
        first=0
        if [ "$from" = 40 ]; then
            # What is there now is cut to its last 40 lines. The follow starts at the line after it.
            lines=$(wc -l < "$f")
            head -n "$lines" "$f" | jq -rc --arg root "$ROOT" "$FILTER" 2>/dev/null | tail -n 40 || true
            from=+$((lines + 1))
        fi
        # --pid ends the tail with the watch. Killing the pipeline's last process alone leaves it.
        tail -n "$from" --pid=$$ -f "$f" | jq -rc --unbuffered --arg root "$ROOT" "$FILTER" 2>/dev/null &
        tail_pid=$!
    fi
    sleep "$POLL_S"
done
