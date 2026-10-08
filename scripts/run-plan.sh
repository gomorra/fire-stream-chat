#!/usr/bin/env bash
# Plan runner — runs a multi-step plan unattended, one fresh headless Claude
# session per step, in a dedicated worktree. Design and contract:
# docs/plans/done/plan-runner.md. Run it from a terminal, not from inside a
# Claude session.
#
#   scripts/run-plan.sh <plan.md> [--from N] [--to N] [--dry-run] [--cap max|strong|mid] [--budget USD]
#                                 [--variant NAME] [--base REF] [--sync-from REF|none]
#
#   --variant NAME  load scripts/plan-runner/variants/NAME.env over the tunables below and run
#                   on plan/<name>-NAME (own worktree, own log) — two configurations of one
#                   plan side by side. Pass it again on every re-run of that variant.
#   --base REF      the commit a new plan branch starts from (default: main)
#   --to N          stop after step N is validated (and judged); later steps stay untouched and a
#                   run without --to continues from there. Exit 0.
#   --sync-from REF where the owner edits the plan during a run (default: main). At every step
#                   boundary the plan file, and nothing else, is merged from REF into the branch's
#                   copy. A remote-tracking REF is fetched first: pass origin/main in a cloud
#                   container, whose local main never moves. none = no sync.
#
# Step boundary: before each launch, a pause file docs/plans/.runs/<run-id>.pause stops the run
# (exit 5; the file is removed, and running again continues), then the plan is synced from REF.
# A sync that cannot merge cleanly writes docs/plans/.runs/<run-id>.plan-sync.conflict.md,
# prints the one commit that records a hand merge, and stops with exit 5.
#
# Usage limit: the driver reads each session's stream while it runs. It stops a session whose usage
# window is used up, because a cloud session would go on on cloud credits. Then it waits for the
# reset and resumes the same session. It waits at most USAGE_WAIT_MAX_S for one reset and
# USAGE_RESUME_MAX times per step; past either, the step is blocked (exit 3). A pause file also stops
# a wait (exit 5), and the next run resumes the session. Ctrl-C stops the running session and exits 130.
#
# Exit codes: 0 all steps shipped (or dry run) · 1 usage/config error ·
#             2 a decision is needed · 3 a step is blocked · 4 stopped at a ‖ checkpoint ·
#             5 stopped for the owner: a pause file between steps or during a usage-limit wait, or a
#               plan sync that needs a hand merge · 130 interrupted (Ctrl-C or SIGTERM)
#
# The runner commits nothing but a plan sync, and never pushes. Every other commit on
# plan/<name> is a step session's. The other thing it changes in the worktree: to escalate a
# failed step it keeps the attempt (branch + patch + tarball), then resets and cleans. State lives in the plan file (**Shipped** blocks) and in the
# gitignored run log docs/plans/.runs/<name>.log (one JSON object per line;
# scripts/plan-runner/report.sh turns it into a per-step table).
set -Eeuo pipefail
# A driver crash must not look like a decision (2) or a block (3): report and exit 1.
trap 'echo "plan-runner: internal error at line $LINENO (exit $?) — this is a driver bug, not a step result" >&2; exit 1' ERR
# A missing tool must read as an environment error: without jq the first `log` trips the trap above.
for cmd in git jq; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "plan-runner: '$cmd' is not on PATH" >&2; exit 1; }
done

# ---- tunables (the only place model ids and effort levels live) -------------
# A --variant file may override the keys lib.sh's PR_VARIANT_KEYS lists, nothing else.
MODEL_MAX=opus;      EFFORT_MAX=xhigh;    ADVISOR_MAX=''      # a step's `effort:` tag overrides the tier's effort
MODEL_STRONG=opus;   EFFORT_STRONG=high;  ADVISOR_STRONG=''   # ADVISOR_*: `claude --advisor`, '' = none
MODEL_MID=opus;      EFFORT_MID=medium;   ADVISOR_MID=''       # 2026-10-03: one model, the tiers differ by effort only (was Fable for mid/max, set 2026-09-20 by scripts/plan-runner/benchmark.md)
JUDGE_MODEL='';      JUDGE_EFFORT=high    # '' = no judge pass; see plan-runner/judge-prompt.md
ESCALATE=1                 # 1 = a step that stays invalid after its nudge is re-run once, one effort rung up
DEFAULT_BUDGET_USD=30      # per step; a `budget:` heading tag overrides, --budget overrides both
NUDGE_BUDGET_USD=5         # the single fix-forward resume a step may get
REVIEW_BUDGET_USD=8        # the fresh review session that stands in for the nudge when only skills are missing
JUDGE_BUDGET_USD=7
# The usage limit. One per line: e2e.sh sets them small with sed.
USAGE_POLL_S=10            # a running session's stream is read this often for its usage-window state
USAGE_WAIT_SLACK_S=90      # added to the reset time; also the shortest wait after a limit stop
USAGE_WAIT_FALLBACK_S=3600 # the wait when no reset time is known
USAGE_WAIT_MAX_S=21600     # a reset further away is not waited for (a weekly limit): the step is blocked
USAGE_RESUME_MAX=6         # limit stops waited out per step, over its step, nudge, review and judge sessions
USAGE_SLICE_S=60           # a wait sleeps in slices against the wall clock, and looks for the pause file in each
GATE_TASKS=":app:testFirebaseDebugUnitTest :app:assembleFirebaseDebug"
NOTIFY_CMD=notify-send     # <cmd> "<title>" "<body>"; point at a phone bridge later
PERMISSION_MODE=acceptEdits
# Tuned from the run log's permission_denials (§0 Permissions). No interpreter
# (python3 & co.) — one would route around every deny pattern below.
ALLOWED_TOOLS=(
    "Bash(./gradlew *)" "Bash(git *)" "Bash(find *)" "Bash(grep *)" "Bash(rg *)" "Bash(jq *)"
    "Bash(ls *)" "Bash(cat *)" "Bash(head *)" "Bash(tail *)" "Bash(sed *)" "Bash(awk *)"
    "Bash(wc *)" "Bash(sort *)" "Bash(uniq *)" "Bash(cut *)" "Bash(tr *)" "Bash(xargs *)"
    "Bash(diff *)" "Bash(stat *)" "Bash(file *)" "Bash(test *)" "Bash(echo *)" "Bash(printf *)"
    "Bash(mkdir *)" "Bash(cp *)" "Bash(mv *)" "Bash(touch *)" "Bash(date *)" "Bash(dexdump *)"
    "Bash(rm -rf app/build/*)"
    "Bash(scripts/plan-runner/selfcheck.sh*)" "Bash(scripts/plan-runner/e2e.sh*)"   # the gate of a plan that changes the runner
    "Bash(scripts/debt.sh*)" "Bash(scripts/debt/selfcheck.sh*)"   # the debt register (docs/plans/tech-debt-paydown.md §2.2)
)
# Deny wins over allow. Prefix patterns cannot express "outside the worktree";
# the prompt forbids leaving it and the run log shows every denial.
DISALLOWED_TOOLS=(
    "Bash(git push*)" "Bash(git reset --hard*)" "Bash(git checkout main*)" "Bash(git switch main*)"
    "Bash(git branch -D*)" "Bash(./gradlew --stop*)" "AskUserQuestion" "EnterPlanMode" "EnterWorktree"
)
# The judge reads and reports. It runs without acceptEdits, so anything not listed here is
# denied; the driver also compares HEAD and `git status` before and after it.
JUDGE_ALLOWED_TOOLS=(
    "Bash(git diff *)" "Bash(git log *)" "Bash(git show *)" "Bash(git status*)" "Bash(git grep *)"
    "Bash(find *)" "Bash(grep *)" "Bash(rg *)" "Bash(jq *)" "Bash(ls *)" "Bash(cat *)" "Bash(head *)"
    "Bash(tail *)" "Bash(wc *)" "Bash(sort *)" "Bash(uniq *)" "Bash(diff *)"
)
JUDGE_DISALLOWED_TOOLS=("${DISALLOWED_TOOLS[@]}" "Edit" "Write" "NotebookEdit")

# ---- setup -------------------------------------------------------------------
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=plan-runner/lib.sh
source "$SCRIPT_DIR/plan-runner/lib.sh"
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
TEMPLATE=$SCRIPT_DIR/plan-runner/step-prompt.md
SKILLS_TEMPLATE=$SCRIPT_DIR/plan-runner/skills-prompt.md
JUDGE_TEMPLATE=$SCRIPT_DIR/plan-runner/judge-prompt.md
SCHEMA=$SCRIPT_DIR/plan-runner/step-result.schema.json
JUDGE_SCHEMA=$SCRIPT_DIR/plan-runner/judge-result.schema.json
SCHEMA_REL=scripts/plan-runner/step-result.schema.json
VARIANTS=${PLAN_RUNNER_VARIANTS_DIR:-$SCRIPT_DIR/plan-runner/variants}   # override for the self-check

usage() { # usage [exit-code]  → the header comment, from its usage line to its end
    awk '/^#   scripts\/run-plan\.sh/ { on = 1 } on && !/^#/ { exit } on { sub(/^# ?/, ""); print }' "$0"
    exit "${1:-1}"
}
need_arg() { [ $# -ge 2 ] && [ -n "$2" ] || { echo "$1 needs a value" >&2; usage 1; }; }

PLAN_ARG=''; FROM=1; TO=''; DRY_RUN=0; CAP=''; BUDGET_OVERRIDE=''; VARIANT=''; BASE_REF=''; SYNC_FROM=main
while [ $# -gt 0 ]; do
    case "$1" in
        --from)    need_arg "$@"; FROM=$2; shift 2 ;;
        --to)      need_arg "$@"; TO=$2; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --cap)     need_arg "$@"; CAP=$2; shift 2 ;;
        --budget)  need_arg "$@"; BUDGET_OVERRIDE=$2; shift 2 ;;
        --variant) need_arg "$@"; VARIANT=$2; shift 2 ;;
        --base)    need_arg "$@"; BASE_REF=$2; shift 2 ;;
        --sync-from) need_arg "$@"; SYNC_FROM=$2; shift 2 ;;
        -h|--help) usage 0 ;;
        -*)        echo "unknown flag $1" >&2; usage 1 ;;
        *)         [ -z "$PLAN_ARG" ] || usage 1; PLAN_ARG=$1; shift ;;
    esac
done
[ -n "$PLAN_ARG" ] || usage 1
case "$CAP" in ''|max|strong|mid) ;; *) echo "--cap wants max|strong|mid" >&2; exit 1 ;; esac
[[ "$FROM" =~ ^[0-9]+$ ]] || { echo "--from wants a step number" >&2; exit 1; }
[[ -z "$TO" || "$TO" =~ ^[0-9]+$ ]] || { echo "--to wants a step number" >&2; exit 1; }
[ -z "$TO" ] || [ "$TO" -ge "$FROM" ] || { echo "--to $TO is before --from $FROM" >&2; exit 1; }
# Without this a missing CLI ends every launch as exit 3 "blocked", with a resume hint for a session that never was.
[ "$DRY_RUN" = 1 ] || command -v claude >/dev/null 2>&1 || { echo "plan-runner: 'claude' is not on PATH" >&2; exit 1; }
if [ -n "$VARIANT" ]; then
    [[ "$VARIANT" =~ ^[a-z0-9][a-z0-9-]*$ ]] || { echo "--variant wants a lowercase name (a-z, 0-9, -)" >&2; exit 1; }
    VARIANT_FILE=$VARIANTS/$VARIANT.env
    [ -f "$VARIANT_FILE" ] || { echo "no such variant: $VARIANT_FILE" >&2; exit 1; }
    bad=$(pr_variant_check "$VARIANT_FILE")
    if [ -n "$bad" ]; then
        echo "plan-runner: $VARIANT_FILE — every line must be KEY=value with a key from PR_VARIANT_KEYS (lib.sh):" >&2
        printf '%s\n' "$bad" | sed 's/^/    /' >&2; exit 1
    fi
    # shellcheck disable=SC1090
    source "$VARIANT_FILE"
fi
# A bad tunable must read as a config error here, not as a CLI failure or a "driver bug" later.
case "$ESCALATE" in 0|1) ;; *) echo "ESCALATE wants 0 or 1" >&2; exit 1 ;; esac
for v in MODEL_MAX MODEL_STRONG MODEL_MID; do
    [ -n "${!v}" ] || { echo "plan-runner: $v is empty" >&2; exit 1; }
done
for v in EFFORT_MAX EFFORT_STRONG EFFORT_MID JUDGE_EFFORT; do
    case "${!v}" in low|medium|high|xhigh|max) ;; *) echo "plan-runner: $v='${!v}' — want low|medium|high|xhigh|max" >&2; exit 1 ;; esac
done

PLAN_ABS=$(realpath "$PLAN_ARG")
[ -f "$PLAN_ABS" ] || { echo "no such plan: $PLAN_ARG" >&2; exit 1; }
PLAN_REL=${PLAN_ABS#"$ROOT"/}
NAME=$(basename "$PLAN_ABS" .md)
RUN_ID=$NAME${VARIANT:+-$VARIANT}       # names the branch, the worktree, the log and every run file
BRANCH=plan/$RUN_ID
WT=$ROOT/.claude/worktrees/plan-$RUN_ID
RUNS=${PLAN_RUNNER_RUNS_DIR:-$ROOT/docs/plans/.runs}   # override for the self-check
mkdir -p "$RUNS"
RUNS=$(cd "$RUNS" && pwd)              # absolute: escalate writes into it from inside the worktree
LOG=$RUNS/$RUN_ID.log
PAUSE=$RUNS/$RUN_ID.pause                        # the owner creates it; the next step boundary stops for it
SYNC_CONFLICT=$RUNS/$RUN_ID.plan-sync.conflict.md
# Session transcripts: <home>/projects/<cwd slug>/<session id>.jsonl. Override for the self-check.
CLAUDE_HOME=${PLAN_RUNNER_CLAUDE_HOME:-${CLAUDE_CONFIG_DIR:-$HOME/.claude}}
# The prompt that resumes a session after a usage-limit wait. e2e.sh's stub recognises it by its start.
CONTINUE_PROMPT='This session was stopped at a usage limit, and the limit has reset. Carry on where you left off, and end with the JSON result object your instructions ask for.'
if [ -n "$BASE_REF" ]; then
    git -C "$ROOT" rev-parse --verify --quiet "$BASE_REF^{commit}" >/dev/null || { echo "--base: '$BASE_REF' is not a commit" >&2; exit 1; }
fi

# A driver launched from inside a Claude session would nest; scrub the markers
# so the step sessions start clean either way. CLAUDE_CODE_REMOTE_SESSION_ID is set
# only in a cloud (claude.ai/code) container: left in place, every step session
# adopts the host session's id (verified 2026-10-03), and a nudge's --resume would
# land in the wrong session. Unset on a desktop, where it does not exist.
unset CLAUDECODE CLAUDE_CODE_ENTRYPOINT CLAUDE_CODE_SESSION_ID CLAUDE_CODE_CHILD_SESSION \
      CLAUDE_CODE_SSE_PORT CLAUDE_CODE_MESSAGING_SOCKET CLAUDE_CODE_MESSAGING_TOKEN \
      CLAUDE_CODE_BRIDGE_SESSION_ID CLAUDE_CODE_REMOTE_SESSION_ID CLAUDE_PID CLAUDE_EFFORT 2>/dev/null || true
export PLAN_RUNNER=1

NL=$'\n'     # "${x:+$'\n'}" is not a newline inside double quotes; "${x:+$NL}" is
ts()  { date -u +%Y-%m-%dT%H:%M:%SZ; }
# Progress goes to stderr: validate_step's stdout is its reasons text, captured by the caller.
say() { printf '[plan-runner %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
log() { # log <event> [jq --arg k v]…  → one JSON line; `step` is always a string
    local ev=$1; shift
    jq -nc --arg ts "$(ts)" --arg plan "$NAME" --arg variant "$VARIANT" --arg event "$ev" "$@" \
        '{ts:$ts, plan:$plan, variant:$variant, event:$event} + ($ARGS.named | del(.ts,.plan,.variant,.event))' >> "$LOG"
}
notify() {
    say "$1 — $2"
    if command -v "$NOTIFY_CMD" >/dev/null 2>&1; then "$NOTIFY_CMD" "plan-runner: $RUN_ID" "$1 — $2" || true; fi
}
model_for()   { case "$1" in max) echo "$MODEL_MAX" ;; strong) echo "$MODEL_STRONG" ;; mid) echo "$MODEL_MID" ;; esac; }
effort_for()  { case "$1" in max) echo "$EFFORT_MAX" ;; strong) echo "$EFFORT_STRONG" ;; mid) echo "$EFFORT_MID" ;; esac; }
advisor_for() { case "$1" in max) echo "$ADVISOR_MAX" ;; strong) echo "$ADVISOR_STRONG" ;; mid) echo "$ADVISOR_MID" ;; esac; }
wt_git() { git -C "$WT" "$@"; }
# count_tests <commit>  → `@Test` annotations under app/src/test at that commit. A proxy, not the
# gate's count — it is there to make a step that deletes tests visible, which a green gate is not.
count_tests() { { wt_git grep -hcw '@Test' "$1" -- app/src/test 2>/dev/null || true; } | pr_sum_counts; }
rel() { printf '%s' "${1#"$ROOT"/}"; }

# ---- worktree ----------------------------------------------------------------
ensure_worktree() {
    if [ -d "$WT" ]; then
        local on; on=$(wt_git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')
        [ "$on" = "$BRANCH" ] || { echo "plan-runner: $WT exists but is on '$on', not $BRANCH — remove it (git worktree remove) or check out $BRANCH there" >&2; exit 1; }
        [ -z "$BASE_REF" ] || say "note: $BRANCH already exists — --base is ignored (it forked from $(wt_git merge-base main HEAD | cut -c1-8))"
        return
    fi
    say "creating worktree $(rel "$WT") on $BRANCH"
    if git -C "$ROOT" show-ref --verify --quiet "refs/heads/$BRANCH"; then
        [ -z "$BASE_REF" ] || say "note: $BRANCH already exists — --base is ignored"
        git -C "$ROOT" worktree add "$WT" "$BRANCH" >/dev/null
    else
        git -C "$ROOT" worktree add -b "$BRANCH" "$WT" "${BASE_REF:-main}" >/dev/null
        if [ -n "$VARIANT" ] && [ -z "$BASE_REF" ]; then
            say "note: variant '$VARIANT' starts from main's tip $(git -C "$ROOT" rev-parse --short main) — give the other variant(s) --base $(git -C "$ROOT" rev-parse --short main), or they are not comparable"
        fi
    fi
    # Gradle needs both; neither is tracked. See docs/GOTCHAS.md / CLAUDE.md.
    for f in local.properties app/google-services.json; do
        if [ -f "$ROOT/$f" ] && [ ! -f "$WT/$f" ]; then cp "$ROOT/$f" "$WT/$f"; fi
    done
}

# ---- one step ----------------------------------------------------------------
# Per-step state, set by prepare_step / run_step and read by the validate path.
STEP=''; HEADING=''; TIER=''; TAGGED_TIER=''; MODEL=''; EFFORT=''; ADVISOR=''; BUDGET=''
FLOOR=''; START_SHA=''; SESSION_ID=''; RESULT_FILE=''; NUDGED=0
ATTEMPT=1; ATTEMPT_BLOCK=''; RUN_SKILLS=''; PRESTATE=''; PROMPT=''; PROMPT_FILE=''; STAMP=''

prepare_step() {
    STEP=$1
    # `|| exit 1` inside and out: the function has already said what is wrong with the plan,
    # and the ERR trap would otherwise call a typo in a heading tag a driver bug — twice.
    HEADING=$(pr_step_heading "$PLAN_WT" "$STEP" || exit 1) || exit 1
    FLOOR=$(pr_tag_skills "$HEADING")
    TAGGED_TIER=$(pr_tag_tier "$HEADING" || exit 1) || exit 1
    TIER=$(pr_cap_tier "$TAGGED_TIER" "$CAP")
    MODEL=$(model_for "$TIER")
    ADVISOR=$(advisor_for "$TIER")      # follows the model that actually runs: the CLI rejects a weaker advisor
    # Effort follows the *tagged* tier, then an explicit `effort:` tag; --cap lowers the
    # model only — a cheaper model thinking longer is the better trade.
    EFFORT=$(pr_tag_effort "$HEADING" "$(effort_for "$TAGGED_TIER")" || exit 1) || exit 1
    BUDGET=${BUDGET_OVERRIDE:-$(pr_tag_budget "$HEADING" "$DEFAULT_BUDGET_USD")}
    NUDGED=0; SESSION_ID=''; RESULT_FILE=''; ATTEMPT=1; ATTEMPT_BLOCK=''; RUN_SKILLS=''; PRESTATE=''; WAITS=0
}

# The "## Advisor" section of the step prompt; empty when the step's tier has no advisor.
# Executors under-call an advisor they are not told to call, and every call re-reads the
# whole conversation uncached at the advisor's rate — hence two calls, the first one early.
advisor_block() {
    [ -n "$ADVISOR" ] || return 0
    printf '\n## Advisor\n\n%s\n' "A stronger model (\`$ADVISOR\`) is attached to this session as its advisor. Consult it twice: once right after the \`**Approach**\` block and before the first edit — ask what the approach misses — and once after the gate is green and before the \`**Shipped**\` line — ask whether the diff meets the step's spec and what a reviewer would reject. Consult early rather than late: every call re-reads the whole conversation. A third call is for a failure that has survived two fixes, nothing else. Report the count in \`advisorConsults\` and say in \`summary\` whether the advice changed anything."
}

# Renders the prompt for the current attempt; START_SHA must already be set by the caller.
render_step_prompt() {
    local base commits out left
    if [ -d "$WT" ]; then
        base=$(wt_git merge-base main HEAD)
        commits=$(wt_git log --oneline "$base..HEAD"); [ -n "$commits" ] || commits='(none yet)'
    else
        base='(dry run)'; commits='(dry run)'
    fi
    out=$(pr_render "$TEMPLATE" \
        "PLAN_PATH=$PLAN_REL" "PLAN_NAME=$NAME" "STEP=$STEP" "STEP_HEADING=$HEADING" \
        "TIER=$TIER" "TAGGED_TIER=$TAGGED_TIER" "SKILLS_FLOOR=$(pr_join "$FLOOR" none)" "BRANCH=$BRANCH" \
        "BASE=$base" "COMMITS=$commits" "SCHEMA_PATH=$SCHEMA_REL" "TRIPWIRE_RULES=$(pr_tripwire_rules)" \
        "ADVISOR_BLOCK=$(advisor_block)${ADVISOR:+$NL}" "ATTEMPT_BLOCK=$ATTEMPT_BLOCK")   # $(…) eats the block's last newline
    left=$(pr_unfilled "$out")
    [ -z "$left" ] || { echo "plan-runner: template placeholders left unfilled: $left" >&2; exit 1; }
    printf '%s' "$out"
}

# write_prompt → renders the current attempt's prompt into the runs dir; sets PROMPT, PROMPT_FILE, STAMP.
write_prompt() {
    STAMP=$(date +%Y%m%d-%H%M%S)
    PROMPT=$(render_step_prompt)
    PROMPT_FILE=$RUNS/$RUN_ID.step$STEP.$STAMP.prompt.md
    printf '%s' "$PROMPT" > "$PROMPT_FILE"
}

# session_args <schema> <model> <effort> <budget> <permission-mode>  → starts CLAUDE_ARGS with the flags
# every session shares. The caller appends the tool lists, then `-n`: the tool flags take a list, and
# `-n` ends it before the prompt.
session_args() {
    CLAUDE_ARGS=(-p --output-format stream-json --verbose --json-schema "$(cat "$1")"
        --model "$2" --effort "$3" --max-budget-usd "$4" --permission-mode "$5")
}

claude_args() { # claude_args <budget>  → fills CLAUDE_ARGS; run_session appends --resume
    session_args "$SCHEMA" "$MODEL" "$EFFORT" "$1" "$PERMISSION_MODE"
    CLAUDE_ARGS+=(--allowedTools "${ALLOWED_TOOLS[@]}" --disallowedTools "${DISALLOWED_TOOLS[@]}"
        -n "plan $RUN_ID step $STEP")
    if [ -n "$ADVISOR" ]; then CLAUDE_ARGS+=(--advisor "$ADVISOR"); fi
}

judge_args() { # judge_args <budget>  → fills CLAUDE_ARGS for the read-only judge (no acceptEdits)
    session_args "$JUDGE_SCHEMA" "$JUDGE_MODEL" "$JUDGE_EFFORT" "$1" default
    CLAUDE_ARGS+=(--allowedTools "${JUDGE_ALLOWED_TOOLS[@]}" --disallowedTools "${JUDGE_DISALLOWED_TOOLS[@]}"
        -n "plan review step $STEP")
}

# result_get <jq-path>  → the field from RESULT_FILE as text, or "null". Shape: lib.sh pr_result_kind.
result_get() { pr_result_field "$RESULT_FILE" "$1"; }

# log_result <file> <kind> <session>  → the `result` event of one invocation. <kind> is step, nudge,
# review or judge; report.sh leaves the judge's out of the step's cost. <session> comes from the
# stream, which names it even when the invocation was stopped before its result. Never fails: every
# value has a fallback (the file may be missing, empty or not JSON).
log_result() {
    local f=$1
    log result --arg step "$STEP" --arg kind "$2" --arg session "$3" \
        --arg subtype "$(pr_result_field "$f" .subtype)" \
        --arg status "$(pr_result_field "$f" .structured_output.status)" --arg commit "$(pr_result_field "$f" .structured_output.commit)" \
        --argjson cost "$(pr_result_json "$f" .total_cost_usd 0)" \
        --argjson turns "$(pr_result_json "$f" .num_turns 0)" \
        --argjson denials "$(pr_result_json "$f" .permission_denials '[]')" \
        --argjson models "$(pr_result_json "$f" '.modelUsage | map_values(.costUSD)' '{}')" \
        --argjson consults "$(pr_result_json "$f" .structured_output.advisorConsults null)" \
        --arg file "$(rel "$f")"
}

# ---- sessions and the usage limit ------------------------------------------------
# Every session runs through run_session: step, nudge, review and judge. claude runs in the background
# so the driver can read its stream and stop it at a usage limit. Contract: §6 of
# docs/plans/done/plan-runner.md.
SESSION_PID=''; SESSION_KIND=''; SESSION_STREAM=''; NAP_PID=''   # the running invocation, for the INT/TERM trap
RUN_SID=''; RUN_SPENT=0; SESSION_STOP=''; WAITS=0
# The reset time (epoch) of the newest used-up window a stream showed, and that stream's session;
# empty once a stream shows an open window or a wait has passed the reset. The next launch waits for it.
WINDOW_RESET=''; WINDOW_SID=''
LIMIT_SOURCE=''; LIMIT_RESET=''; LIMIT_TRANSCRIPT=''      # set by limit_hit

# nap <seconds>  → sleeps in the background and waits for it, so INT/TERM run their trap at once.
nap() { sleep "$1" & NAP_PID=$!; wait "$NAP_PID" 2>/dev/null || true; NAP_PID=''; }

# local_time <epoch>  → the owner's wall-clock time of it (GNU date, then BSD date).
local_time() { date -d "@$1" '+%a %H:%M %Z' 2>/dev/null || date -r "$1" '+%a %H:%M %Z' 2>/dev/null || echo "epoch $1"; }

# stop_session  → SIGTERM to the running claude, SIGKILL ten seconds later if it is still alive.
stop_session() {
    local i=0
    kill -TERM "$SESSION_PID" 2>/dev/null || true
    while kill -0 "$SESSION_PID" 2>/dev/null && [ "$i" -lt 50 ]; do nap 0.2; i=$((i + 1)); done
    kill -KILL "$SESSION_PID" 2>/dev/null || true
    wait "$SESSION_PID" 2>/dev/null || true
}

# invoke <cwd> <out> <prompt>  → one claude invocation with CLAUDE_ARGS. The stream goes to
# <out>.stream.jsonl, stderr to <out>.stderr, and the stream's last result line to <out> (empty when
# there is none). Returns 1 when the driver stopped it because its stream said the window is used up.
invoke() {
    local cwd=$1 out=$2 stream=$2.stream.jsonl ms=10 next=$((SECONDS + USAGE_POLL_S)) stopped=0 frac
    SESSION_STREAM=$stream
    : > "$stream"; : > "$out.stderr"     # there even when the cd fails: set_aside moves both
    (cd "$cwd" && exec claude "${CLAUDE_ARGS[@]}" "$3" > "$stream" 2> "$out.stderr") &
    SESSION_PID=$!
    while kill -0 "$SESSION_PID" 2>/dev/null; do
        printf -v frac '%03d' $((ms % 1000)); nap "$((ms / 1000)).$frac"
        ms=$((ms < 500 ? ms * 2 : 1000))     # a stub ends in milliseconds, a real session in minutes
        [ "$SECONDS" -ge "$next" ] || continue
        next=$((SECONDS + USAGE_POLL_S))
        case "$(pr_stream_limit "$stream")" in limited*) stop_session; stopped=1; break ;; esac
    done
    wait "$SESSION_PID" 2>/dev/null || true
    SESSION_PID=''
    pr_stream_result "$stream" > "$out"
    [ "$stopped" = 0 ]
}

# transcript_of <session-id>  → the session's transcript file, or nothing.
transcript_of() {
    local f
    [ -n "$1" ] && [ "$1" != null ] || return 0
    for f in "$CLAUDE_HOME"/projects/*/"$1".jsonl; do
        if [ -f "$f" ]; then printf '%s' "$f"; return 0; fi
    done
}

# limit_hit <out> <stopped> <session>  → sets LIMIT_SOURCE to stream, result or transcript when the
# invocation ended at a usage limit, else to nothing; LIMIT_RESET to the reset time (the stream's, else
# the transcript's; empty when unknown). Notes the stream's newest window state in WINDOW_RESET either
# way. A complete result or a spent budget is never a limit stop: that session finished, whatever its
# stream says, and only the next launch waits (await_window). <session> is the one the invocation's
# own stream names, or empty: a resume that died before its init line must not find, in the
# transcript, the stop that preceded it.
limit_hit() {
    local out=$1 state reset='' kind tr_reset='' tr_hit=0
    LIMIT_SOURCE=''; LIMIT_RESET=''; LIMIT_TRANSCRIPT=''
    state=$(pr_stream_limit "$out.stream.jsonl")
    case "$state" in
        "limited "*) reset=${state#limited }; reset=${reset%.*}; WINDOW_RESET=$reset; WINDOW_SID=$3 ;;
        limited|open) WINDOW_RESET='' ;;
    esac
    kind=$(pr_result_kind "$out")
    case "$kind" in complete|budget) return 0 ;; esac
    LIMIT_TRANSCRIPT=$(transcript_of "$3")
    if [ -n "$LIMIT_TRANSCRIPT" ] && tr_reset=$(pr_transcript_usage_limit "$LIMIT_TRANSCRIPT" || exit 1); then tr_hit=1; fi
    if [ "$2" = 1 ] || [ "${state%% *}" = limited ]; then LIMIT_SOURCE=stream
    elif [ "$kind" = usage_limit ]; then LIMIT_SOURCE=result
    elif [ "$tr_hit" = 1 ]; then LIMIT_SOURCE=transcript
    else return 0; fi
    LIMIT_RESET=${reset:-${tr_reset%.*}}
}

# past_ceiling <resetsAt|''> <target>  → exit 0, with SESSION_STOP set, when <target> lies further ahead
# than USAGE_WAIT_MAX_S: a weekly limit, not waited for.
past_ceiling() {
    local wait_s=$(($2 - $(date +%s)))
    [ "$wait_s" -gt "$USAGE_WAIT_MAX_S" ] || return 1
    SESSION_STOP="the usage limit resets $(local_time "${1:-$2}") ($(pr_fmt_duration "$wait_s") away), past the $(pr_fmt_duration "$USAGE_WAIT_MAX_S") the runner waits — a weekly limit? Run this again after the reset"
}

# wait_out <kind> <session> <resetsAt|''> <target>  → notifies once with the local reset time, waits
# until <target> and logs resumed. Kind `launch` is the wait before a launch. The wait sleeps in slices
# of at most USAGE_SLICE_S against the wall clock, so a host that was suspended does not oversleep, and
# a pause file stops the run in any slice (exit 5).
wait_out() {
    local start left
    start=$(date +%s); left=$(($4 - start))
    if [ "$left" -gt 0 ]; then
        notify "usage limit in step $STEP" "$([ -n "$3" ] && echo "the limit resets $(local_time "$3")" || echo "no reset time known"); waiting $(pr_fmt_duration "$left"), until $(local_time "$4"), then the $([ "$1" = launch ] && echo "next session starts" || echo "$1 session resumes")"
    fi
    while [ "$left" -gt 0 ]; do
        [ ! -f "$PAUSE" ] || stop_paused "during the usage-limit wait of step $STEP"
        nap "$((left < USAGE_SLICE_S ? left : USAGE_SLICE_S))"
        left=$(($4 - $(date +%s)))
    done
    log resumed --arg step "$STEP" --arg session "$2" --arg kind "$1" --argjson waited_s "$(($(date +%s) - start))"
    WINDOW_RESET=''
}

# limit_wait <kind> <session> <source> <resetsAt|''> <file> <transcript>  → logs usage_limit and waits
# out the reset plus slack: the fallback when no reset is known, and at least the slack after a stop.
# Returns 1 with SESSION_STOP set, without waiting, past the ceiling or (any kind but launch) the cap.
limit_wait() {
    local kind=$1 reset=$4 now target
    now=$(date +%s)
    if [ -n "$reset" ]; then target=$((reset + USAGE_WAIT_SLACK_S)); else target=$((now + USAGE_WAIT_FALLBACK_S)); fi
    # A reset already past would resume into the same stop at once; the slack is the least a stop waits.
    if [ "$kind" != launch ] && [ "$target" -lt $((now + USAGE_WAIT_SLACK_S)) ]; then target=$((now + USAGE_WAIT_SLACK_S)); fi
    log usage_limit --arg step "$STEP" --arg session "$2" --arg kind "$kind" --argjson resets_at "${reset:-null}" \
        --arg source "$3" --argjson wait_s "$((target - now))" --arg file "$5" --arg transcript "$6"
    if past_ceiling "$reset" "$target"; then return 1; fi
    if [ "$kind" != launch ]; then
        WAITS=$((WAITS + 1))
        if [ "$WAITS" -gt "$USAGE_RESUME_MAX" ]; then
            SESSION_STOP="usage limit hit $WAITS times in step $STEP — the runner waits out at most $USAGE_RESUME_MAX per step"
            return 1
        fi
    fi
    wait_out "$kind" "$2" "$reset" "$target"
}

# await_window  → before a launch: while the newest used-up window a stream showed has its reset ahead,
# wait for it. Returns 1 with SESSION_STOP set when that reset is past the ceiling.
await_window() {
    [ -n "$WINDOW_RESET" ] && [ "$WINDOW_RESET" -gt "$(date +%s)" ] 2>/dev/null || return 0
    limit_wait launch "$WINDOW_SID" stream "$WINDOW_RESET" '' ''
}

# The "## Interrupted attempt" section for a fresh step session that replaces one cut off at a usage
# limit with no session left to resume.
interrupted_block() {
    printf '\n## Interrupted attempt\n\n%s\n' "An earlier session at this step was cut off at a usage limit and could not be resumed. The worktree holds its work: the commits since the step's start commit \`$START_SHA\`, and whatever is uncommitted. Read \`git log --oneline $START_SHA..HEAD\` and \`git status\` first, keep what is sound, and finish the step from there."
}

# session_total <session-id>  → the last total cost logged for that session (0 for none): a resumed
# session reports its cost cumulatively, so its next total counts from there.
session_total() {
    local c=''
    if [ -n "$1" ] && [ -f "$LOG" ]; then
        c=$(jq -r --arg s "$1" 'select(.event == "result" and .session == $s and (.cost // 0) > 0) | .cost' "$LOG" 2>/dev/null | tail -1 || true)
    fi
    printf '%s' "${c:-0}"
}

# on_signal  → the INT/TERM trap of a real run. claude runs as a background job, which ignores SIGINT in
# a non-interactive shell, so Ctrl-C reaches only the driver: it stops the running session itself.
on_signal() {
    local sid=''
    trap - INT TERM
    if [ -n "$SESSION_PID" ]; then sid=$(pr_session_id "$SESSION_STREAM"); stop_session; SESSION_PID=''; fi
    [ -z "$NAP_PID" ] || kill "$NAP_PID" 2>/dev/null || true
    log interrupted --arg step "$STEP" --arg session "$sid" --arg kind "$SESSION_KIND" || true
    [ -z "$sid" ] || SESSION_ID=$sid
    say "interrupted$([ -z "$sid" ] || echo " — session $sid stopped; resume it by hand with: $(resume_hint)")"
    exit 130
}

# set_aside <out> <to>  → moves an invocation's result, stream and stderr out of the way of the next one.
set_aside() { mv "$1" "$2"; mv "$1.stream.jsonl" "$2.stream.jsonl"; mv "$1.stderr" "$2.stderr"; }

# run_session <kind> <cwd> <out> <budget> <prompt> [<resume-id> [after-limit]]  → runs one session of
# <kind> (step, nudge, review, judge) until it ends by itself. A limit stop is logged, waited
# out and the same session resumed with CONTINUE_PROMPT on what is left of <budget>; the interrupted
# invocation's files move to <out minus .json>.limit<N>.json*. A session with no id to resume, or a
# resume after a wait that ends without a result (its files: .noresume<N>.json*), is replaced by a
# fresh session of its kind (a nudge's by a step session, with the Interrupted attempt block). Every
# invocation's result is logged; <out> ends up holding the last one. Sets RUN_SID, and RUN_SPENT to
# what the session's invocations reported spending. Sets SESSION_STOP, and returns, at the ceiling or
# the cap. <after-limit> 1: the first invocation already resumes after a wait (resume_cut_off).
run_session() {
    local kind=$1 cwd=$2 out=$3 budget=$4 prompt=$5 sid=${6:-} after_limit=${7:-0}
    local total prev n=0 tag stopped left=$4 text aside
    SESSION_STOP=''; RUN_SID=$sid; RUN_SPENT=0
    await_window || return 0
    prev=$(session_total "$sid")
    while :; do
        if [ "$kind" = judge ]; then judge_args "$left"; else claude_args "$left"; fi
        [ -z "$sid" ] || CLAUDE_ARGS+=(--resume "$sid")
        if [ "$after_limit" = 1 ]; then text=$CONTINUE_PROMPT; else text=$prompt; fi
        SESSION_KIND=$kind; stopped=0
        invoke "$cwd" "$out" "$text" || stopped=1
        SESSION_KIND=''
        RUN_SID=$(pr_session_id "$out.stream.jsonl")
        total=$(pr_result_field "$out" .total_cost_usd 0)
        if awk -v t="$total" 'BEGIN { exit !(t > 0) }'; then
            RUN_SPENT=$(awk -v a="$RUN_SPENT" -v d="$(pr_cost_delta "$total" "$prev")" 'BEGIN { print a + d }'); prev=$total
        fi
        limit_hit "$out" "$stopped" "$RUN_SID"
        RUN_SID=${RUN_SID:-$sid}
        # A resume after a wait that ends without a result had no session left to resume.
        if [ -n "$LIMIT_SOURCE" ]; then tag=limit
        elif [ "$after_limit" = 1 ] && [ ! -s "$out" ]; then tag=noresume
        else log_result "$out" "$kind" "$RUN_SID"; return 0; fi
        n=$((n + 1)); aside=${out%.json}.$tag$n.json
        set_aside "$out" "$aside"
        log_result "$aside" "$kind" "$RUN_SID"
        if [ "$tag" = limit ]; then
            say "step $STEP: the $kind session ${RUN_SID:-(no id)} stopped at a usage limit ($LIMIT_SOURCE)"
            limit_wait "$kind" "$RUN_SID" "$LIMIT_SOURCE" "$LIMIT_RESET" "$(rel "$aside")" "$LIMIT_TRANSCRIPT" || return 0
        else
            say "step $STEP: the resumed $kind session ${RUN_SID:-(no id)} ended without a result — starting a fresh one"
            RUN_SID=''
        fi
        left=$(pr_budget_left "$budget" "$RUN_SPENT")    # the flag counts per invocation
        if [ -n "$RUN_SID" ]; then sid=$RUN_SID; after_limit=1; continue; fi
        # No session to resume: a fresh one of the same kind, which resumes nothing. A step's gets the
        # Interrupted attempt block and keeps the step's START_SHA; a review or a judge its own prompt.
        # A nudge has no fresh form: a step session replaces it, on what is left of the step's budget.
        sid=''; prev=0; after_limit=0
        if [ "$kind" = nudge ]; then
            kind=step; budget=$(pr_budget_left "$BUDGET" "$(kind_spent "$SESSION_ID" launched)"); RUN_SPENT=0; left=$budget
        fi
        if [ "$kind" = step ]; then
            case "$ATTEMPT_BLOCK" in *"## Interrupted attempt"*) ;; *) ATTEMPT_BLOCK+=$(interrupted_block)$NL ;; esac
            write_prompt; prompt=$PROMPT
        fi
    done
}

resume_hint() { printf 'cd %q && claude --resume %s\n' "$WT" "$SESSION_ID"; }

# The skills every `done` result of this attempt reports, accumulated: the fresh review
# session reports only what it ran itself, and the floor was run by the session before it.
note_run_skills() {
    RUN_SKILLS=$(pr_union "$RUN_SKILLS" "$(jq -r 'select(.structured_output.status == "done") | .structured_output.skills.run[]?' "$RESULT_FILE" 2>/dev/null || true)")
}

# validate_step → prints reasons (empty = valid). Uses START_SHA, RUN_SKILLS, FLOOR.
validate_step() {
    local reasons='' commit head numstat names required run missing gate_log stray
    head=$(wt_git rev-parse HEAD)
    if [ "$head" = "$START_SHA" ]; then reasons+="HEAD did not move — nothing was committed"$'\n'; fi
    if ! pr_step_shipped "$PLAN_WT" "$STEP"; then
        reasons+="no **Shipped** line under step $STEP in $PLAN_REL"$'\n'
    else
        # The plan's own claim is what gets checked: the hash on the Shipped line
        # must be a commit on this branch (the result's `commit` may lag an amend).
        commit=$(pr_shipped_commit "$PLAN_WT" "$STEP")
        if [ -z "$commit" ] || ! wt_git merge-base --is-ancestor "$commit" HEAD 2>/dev/null; then
            reasons+="the **Shipped** line names '${commit:-no hash}', which is not a commit on $BRANCH"$'\n'
        fi
    fi
    if [ -n "$(wt_git status --porcelain -- "$PLAN_REL")" ]; then reasons+="$PLAN_REL has uncommitted changes — the Shipped block must be committed"$'\n'; fi
    # Before the gate, which writes into the tree: what a `done` step leaves uncommitted, the
    # next step's session inherits as if it were meant for it.
    stray=$(wt_git status --porcelain -- . ":(exclude)$PLAN_REL" | awk 'NR <= 5 { sub(/^.. /, ""); print }' | paste -sd, - || true)
    if [ -n "$stray" ]; then reasons+="the worktree is not clean — commit what belongs to the step, remove the rest: $stray"$'\n'; fi
    numstat=$(mktemp); names=$(mktemp)
    wt_git diff --numstat "$START_SHA..HEAD" > "$numstat"; wt_git diff --name-only "$START_SHA..HEAD" > "$names"
    if pr_needs_gate "$names"; then
        gate_log=$RUNS/$RUN_ID.step$STEP.gate.$(date +%s).log
        say "re-running the gate in the worktree ($GATE_TASKS) → $(rel "$gate_log")"
        # shellcheck disable=SC2086
        if ! (cd "$WT" && ./gradlew $GATE_TASKS > "$gate_log" 2>&1); then
            reasons+="the gate is red (see $(rel "$gate_log")): $(grep -m3 -E 'FAILED|error:|e: |What went wrong' "$gate_log" | tr '\n' ' ' || true)"$'\n'
        fi
    else
        say "diff touches nothing the gate can see — gate skipped"
    fi
    required=$(pr_union "$FLOOR" "$(pr_tripwire "$numstat" "$names")")
    # The run list is what the attempt's `done` results report plus what the Shipped line
    # records — the second covers a step the human finished in a resumed session, which
    # has no `done` result at all.
    run=$(pr_union "$RUN_SKILLS" "$(pr_shipped_skills "$PLAN_WT" "$STEP")")
    missing=$(pr_missing "$required" "$run")
    if [ -n "$missing" ]; then
        reasons+="required skills not run: $(pr_join "$missing") (floor: $(pr_join "$FLOOR" none); tripwire on the diff: $(pr_join "$(pr_tripwire "$numstat" "$names")" none))"$'\n'
    fi
    rm -f "$numstat" "$names"
    printf '%s' "$reasons"
}

stop_needs_decision() {
    log needs_decision --arg step "$STEP" --arg session "$SESSION_ID" --arg question "$(result_get .structured_output.question)"
    if ! pr_step_decision_pending "$PLAN_WT" "$STEP"; then
        say "warning: the session reported needs_decision but wrote no **Decision needed** block under step $STEP"
    fi
    notify "step $STEP needs a decision" "$(result_get .structured_output.question)"
    echo; echo "  $(result_get .structured_output.summary)"; echo
    echo "  answer it by resuming the session:"; echo "    $(resume_hint)"
    echo "  or answer in the branch's copy of the plan — $(rel "$WT")/$PLAN_REL — by renaming the block to"
    echo "  **Decision taken** and adding your answer, then run this again (the step session commits it)."
    exit 2
}

stop_blocked() { # stop_blocked <why>
    log blocked --arg step "$STEP" --arg session "${SESSION_ID:-unknown}" --arg why "$1"
    notify "step $STEP is blocked" "$1"
    local summary; summary=$(result_get .structured_output.summary)
    echo; [ "$summary" = null ] || { echo "  $summary"; echo; }
    if [ -z "$SESSION_ID" ] || [ "$SESSION_ID" = null ]; then
        echo "  no session to resume — inspect the worktree, fix by hand if needed, and run this again:"
        echo "    cd $(printf '%q' "$WT") && git status"
    else
        echo "  inspect the worktree, then either resume the session or fix by hand and run this again:"
        echo "    $(resume_hint)"
    fi
    exit 3
}

# nudge <text>  — the one fix-forward resume a step may get; sets RESULT_FILE to the new result.
nudge() {
    [ "$NUDGED" = 0 ] || stop_blocked "already nudged once — $1"
    NUDGED=1
    log nudged --arg step "$STEP" --arg session "$SESSION_ID" --arg reasons "$1"
    say "nudging the session once (budget \$$NUDGE_BUDGET_USD)"
    RESULT_FILE=$RUNS/$RUN_ID.step$STEP.$(date +%Y%m%d-%H%M%S).nudge.result.json
    run_session nudge "$WT" "$RESULT_FILE" "$NUDGE_BUDGET_USD" "$1"$'\n\n'"Then end again with the JSON result object." "$SESSION_ID"
    SESSION_ID=${RUN_SID:-$SESSION_ID}      # a fresh session replaces one it could not resume
}

# review_nudge <reasons>  — what the nudge becomes when the only thing wrong is a missed
# skill: a fresh session runs it on the diff. Resuming the step session for this re-read
# ~130k tokens of context per turn and cost as much as the step (call-audio-routes step 1).
# Spends the step's one nudge; SESSION_ID stays the step session's, for the resume hint.
review_nudge() {
    local missing prompt ADVISOR=''     # no advisor here: the review prompt asks for no consults, so attaching one only costs
    NUDGED=1
    missing=$(pr_join "$(pr_missing_from_reasons "$1")")
    log review --arg step "$STEP" --arg session "$SESSION_ID" --arg missing "$missing"
    say "the diff requires skills the session did not run ($missing) — running them in a fresh session (budget \$$REVIEW_BUDGET_USD)"
    prompt=$(pr_render "$SKILLS_TEMPLATE" "PLAN_PATH=$PLAN_REL" "STEP=$STEP" "STEP_HEADING=$HEADING" \
        "BRANCH=$BRANCH" "START_SHA=$START_SHA" "TIER=$TIER" "MISSING=$missing" "SCHEMA_PATH=$SCHEMA_REL")
    RESULT_FILE=$RUNS/$RUN_ID.step$STEP.$(date +%Y%m%d-%H%M%S).review.result.json
    run_session review "$WT" "$RESULT_FILE" "$REVIEW_BUDGET_USD" "$prompt"
}

# escalate <why>  — the one fresh re-run a step may get once its nudge is spent: the failed
# attempt is kept (its commits on a plan-attempts/ branch, its uncommitted work as a patch and
# a tarball in the runs dir), the worktree goes back to START_SHA, and the step starts over one
# effort rung up with <why> in its prompt. "Run low, re-run the failures higher" is the cheapest
# policy on the effort curve, but only where a checker names the failures — so this follows a
# red gate or a failed validation, never a needs_decision, a spent budget, or a `blocked`
# that is not `gate`. The one exception names nothing: a session that ends without its result
# object a second time, nudge spent — the ladder is nudge, re-run, human, on every path. Sets RESULT_FILE / SESSION_ID to the new attempt's.
escalate() {
    local why=$1 next keep stamp excerpt gate_log untracked
    [ "$ESCALATE" = 1 ] && [ "$ATTEMPT" = 1 ] || stop_blocked "$why"
    next=$(pr_next_effort "$EFFORT") || stop_blocked "$why (effort is already $EFFORT — no rung left)"
    stamp=$(date +%Y%m%d-%H%M%S)
    keep=''
    if [ "$(wt_git rev-parse HEAD)" != "$START_SHA" ]; then
        keep=plan-attempts/$RUN_ID-step$STEP-$stamp     # a branch, not a tag: versionName comes from `git describe --tags`
        wt_git branch "$keep" HEAD
    fi
    wt_git diff --binary HEAD > "$RUNS/$RUN_ID.step$STEP.$stamp.attempt1.patch"
    [ -s "$RUNS/$RUN_ID.step$STEP.$stamp.attempt1.patch" ] || rm -f "$RUNS/$RUN_ID.step$STEP.$stamp.attempt1.patch"
    untracked=$(wt_git ls-files -o --exclude-standard)
    if [ -n "$untracked" ]; then
        (cd "$WT" && git ls-files -o --exclude-standard -z | tar --null -czf "$RUNS/$RUN_ID.step$STEP.$stamp.attempt1.untracked.tgz" -T -)
    fi
    wt_git reset -q --hard "$START_SHA"
    wt_git clean -qfd                     # not -x: local.properties, google-services.json and build/ stay
    # Back to what attempt 1 started from, not to a bare checkout: uncommitted work that was
    # there at launch — the human's **Decision taken** answer, a needs_decision attempt's WIP —
    # is not the failed attempt's to lose.
    if [ -n "$PRESTATE" ]; then
        [ ! -s "$PRESTATE.patch" ] || wt_git apply --whitespace=nowarn "$PRESTATE.patch"
        [ ! -s "$PRESTATE.untracked.tgz" ] || tar -xzf "$PRESTATE.untracked.tgz" -C "$WT"
    fi
    gate_log=$(ls -t "$RUNS/$RUN_ID.step$STEP.gate."*.log 2>/dev/null | head -1 || true)
    excerpt=''
    if [ -n "$gate_log" ]; then excerpt=$(grep -E 'FAILED|error:|^e: |What went wrong|Caused by' "$gate_log" | head -30 || true); fi
    ATTEMPT_BLOCK=$'\n## Earlier attempt\n\n'"A first attempt at this step, at effort \`$EFFORT\`, was discarded by the driver: the worktree is back at the step's start commit, and you run one effort rung higher. What failed:"$'\n\n```\n'"$why${excerpt:+$NL$excerpt}"$'\n```\n\n'"Build the step from its start; do not go looking for the discarded work."$'\n'
    log escalated --arg step "$STEP" --arg session "${SESSION_ID:-unknown}" --arg from "$EFFORT" --arg to "$next" \
        --arg why "$why" --arg kept "${keep:-nothing committed}"
    say "escalating step $STEP: $MODEL/$EFFORT → $MODEL/$next, from $(wt_git rev-parse --short HEAD)${keep:+ (failed attempt kept on $keep)}"
    EFFORT=$next; ATTEMPT=2; NUDGED=0; RUN_SKILLS=''
    launch
}

# log_validated — the `validated` event, with the step's test-count delta: a green gate
# cannot tell a step that added tests from one that deleted them.
log_validated() {
    local before after deleted
    before=$(count_tests "$START_SHA"); after=$(count_tests HEAD)
    deleted=$(wt_git diff --name-only --diff-filter=D "$START_SHA..HEAD" -- app/src/test | paste -sd, - || true)
    log validated --arg step "$STEP" --arg session "${SESSION_ID:-unknown}" --arg head "$(wt_git rev-parse --short HEAD)" \
        --argjson attempt "$ATTEMPT" --argjson tests_before "$before" --argjson tests_after "$after" --arg deleted_tests "$deleted"
    say "step $STEP validated at $(wt_git rev-parse --short HEAD) — @Test count $before → $after${deleted:+, deleted test files: $deleted}"
}

# judge_count <file> <severity>  → findings of that severity; 0 when the result carries no
# findings array at all — a malformed grade must never take a validated step down with it.
judge_count() { pr_result_json "$1" "[.structured_output.findings[]? | select(.severity == \"$2\")] | length" 0; }

# judge_step — the read-only grading pass of a validated step (JUDGE_MODEL set, code in the
# diff). An instrument, not a gate: findings are logged and kept, never acted on, and nothing
# the judge does or fails to do stops the plan. It runs in a throwaway detached worktree at a
# neutral path, so neither its cwd nor `git status` names the variant it is grading, and so a
# judge that writes despite its tool list damages nothing — its grade is dropped instead.
judge_step() {
    [ -n "$JUDGE_MODEL" ] || return 0
    local names head out prompt jwt dirty why
    names=$(mktemp); wt_git diff --name-only "$START_SHA..HEAD" > "$names"
    if ! pr_has_code "$names"; then rm -f "$names"; say "nothing but docs in the diff — judge skipped"; return 0; fi
    rm -f "$names"
    head=$(wt_git rev-parse HEAD)
    jwt=$ROOT/.claude/worktrees/judge-$$
    if ! git -C "$ROOT" worktree add -q --detach "$jwt" "$head" 2>/dev/null; then
        log judge_failed --arg step "$STEP" --arg file '' --argjson cost 0 --arg why "could not create the judge's worktree at $(rel "$jwt")"
        say "warning: step $STEP has no grade — could not create $(rel "$jwt") (a stale one? git worktree prune); the plan goes on"
        return 0
    fi
    out=$RUNS/$RUN_ID.step$STEP.$(date +%Y%m%d-%H%M%S).judge.json
    prompt=$(pr_render "$JUDGE_TEMPLATE" "PLAN_PATH=$PLAN_REL" "STEP=$STEP" "STEP_HEADING=$HEADING" \
        "START_SHA=$START_SHA" "HEAD_SHA=$head")
    say "judging step $STEP on $JUDGE_MODEL/$JUDGE_EFFORT (read-only, budget \$$JUDGE_BUDGET_USD)"
    run_session judge "$jwt" "$out" "$JUDGE_BUDGET_USD" "$prompt"
    dirty=''
    if [ "$(git -C "$jwt" rev-parse HEAD)" != "$head" ] || [ -n "$(git -C "$jwt" status --porcelain)" ]; then dirty=1; fi
    git -C "$ROOT" worktree remove --force "$jwt" || true
    # A judge that still fails after a usage-limit wait, or stops at the ceiling or the cap, gets no grade either.
    if [ -n "$dirty" ] || [ -n "$SESSION_STOP" ] || [ "$(pr_result_kind "$out")" != complete ]; then
        if [ -n "$dirty" ]; then why="the judge wrote to its worktree — grade dropped"
        elif [ -n "$SESSION_STOP" ]; then why=$SESSION_STOP
        else why="no usable result ($(pr_result_error "$out"))"; fi
        log judge_failed --arg step "$STEP" --arg file "$(rel "$out")" --argjson cost "$RUN_SPENT" --arg why "$why"
        say "warning: step $STEP has no grade — $why; the plan goes on"
        return 0
    fi
    log judged --arg step "$STEP" --arg session "$RUN_SID" \
        --arg model "$JUDGE_MODEL" --arg effort "$JUDGE_EFFORT" --arg verdict "$(pr_result_field "$out" .structured_output.verdict)" \
        --argjson high "$(judge_count "$out" high)" --argjson medium "$(judge_count "$out" medium)" --argjson low "$(judge_count "$out" low)" \
        --arg tests_adequate "$(pr_result_field "$out" .structured_output.testsAdequate)" \
        --argjson cost "$(pr_result_json "$out" .total_cost_usd 0)" --arg file "$(rel "$out")"
    say "judge: $(pr_result_field "$out" .structured_output.verdict) — $(judge_count "$out" high) high, $(judge_count "$out" medium) medium, $(judge_count "$out" low) low → $(rel "$out")"
}

# handle_result — a result is validated, nudged once (resume, or the fresh review session),
# then escalated once; exits on every stop.
handle_result() {
    local reasons
    while :; do
        [ -z "$SESSION_STOP" ] || stop_blocked "$SESSION_STOP"      # a usage-limit wait past the ceiling or the cap
        case "$(pr_result_kind "$RESULT_FILE")" in
            budget) stop_blocked "budget or turn limit exhausted ($(result_get .subtype)) — not resumed" ;;
            failed|usage_limit)
                # A usage limit (HTTP 429) is waited out in run_session and never ends up here.
                if [ "$(result_get .terminal_reason)" = api_error ]; then
                    say "the session ended on an API error. The CLI retries transient errors before it gives up, so the runner does not retry. Once the cause is gone (a login, an outage), run this again: a fresh session restarts the step and sees what this one left uncommitted"
                fi
                stop_blocked "session ended without a usable result ($(pr_result_error "$RESULT_FILE"); stderr in $(rel "$RESULT_FILE").stderr)" ;;
            incomplete)
                if [ "$NUDGED" = 1 ]; then
                    escalate "the session ended without the JSON result object, and its one nudge was already spent"
                else
                    nudge "Your session ended without the JSON result object the prompt requires. If the step is done, report it; if not, finish it first."
                fi
                continue ;;
        esac
        case "$(result_get .structured_output.status)" in
            needs_decision) stop_needs_decision ;;
            blocked)
                if [ "$(result_get .structured_output.blockedKind)" = gate ]; then
                    escalate "the session gave up on the gate: $(result_get .structured_output.summary)"
                    continue
                fi
                stop_blocked "$(result_get .structured_output.summary)" ;;
            done)
                note_run_skills
                reasons=$(validate_step)
                if [ -z "$reasons" ]; then log_validated; judge_step; return 0; fi
                say "validation failed:"; printf '%s' "$reasons" | sed 's/^/    /'
                if [ "$NUDGED" = 1 ]; then
                    escalate "still invalid after the one nudge: $(printf '%s' "$reasons" | paste -sd';' -)"
                elif pr_only_skill_reasons "$reasons"; then
                    review_nudge "$reasons"
                else
                    nudge "The driver validated your step $STEP result and found:"$'\n'"$reasons"$'\n'"Fix forward: address every point, re-run the gate, commit (amend the docs(plan) commit if the **Shipped** line changes)."
                fi
                continue ;;
            *) stop_blocked "unknown status '$(result_get .structured_output.status)'" ;;
        esac
    done
}

# launch — one fresh session for the current attempt; sets RESULT_FILE and SESSION_ID. A used-up
# usage window that a stream reported waits first, before the launch is logged.
launch() {
    await_window || stop_blocked "$SESSION_STOP"
    write_prompt
    RESULT_FILE=$RUNS/$RUN_ID.step$STEP.$STAMP.result.json
    log launched --arg step "$STEP" --arg start "$START_SHA" --arg tier "$TIER" --arg tagged "$TAGGED_TIER" \
        --arg model "$MODEL" --arg effort "$EFFORT" --arg advisor "${ADVISOR:-none}" --argjson attempt "$ATTEMPT" \
        --arg base "$(wt_git merge-base main HEAD | cut -c1-8)" \
        --arg budget "$BUDGET" --arg prompt "$(rel "$PROMPT_FILE")"
    say "step $STEP → $MODEL/$EFFORT${ADVISOR:+ + advisor $ADVISOR}, attempt $ATTEMPT, budget \$$BUDGET, floor $(pr_join "$FLOOR" none)"
    run_session step "$WT" "$RESULT_FILE" "$BUDGET" "$PROMPT"
    SESSION_ID=$RUN_SID
    say "step $STEP session ${SESSION_ID:-(no id)}: $(pr_result_kind "$RESULT_FILE"), status $(result_get .structured_output.status), \$$(result_get .total_cost_usd), denials $(pr_result_json "$RESULT_FILE" '.permission_denials | length' '?')"
}

# cut_off <step>  → exit 0 when a stop during a usage-limit wait (a pause, a crash, Ctrl-C) left a step
# or nudge session of <step> to resume: the step's last limit event is a usage_limit with no resumed
# after it. Sets CUT_KIND, CUT_SID, CUT_RESET (epoch or empty) and CUT_TARGET, the epoch the
# interrupted wait was to end at. A review, a judge or a pre-launch wait is redone the way a re-run
# always treats a step: a fresh launch.
CUT_KIND=''; CUT_SID=''; CUT_RESET=''; CUT_TARGET=''
cut_off() {
    local last
    CUT_KIND=''; CUT_SID=''; CUT_RESET=''; CUT_TARGET=''
    [ -f "$LOG" ] || return 1
    # One line, fields split by the unit separator: IFS whitespace would merge an empty field away.
    last=$(jq -rs --arg s "$1" 'map(select(.step == $s and (.event == "usage_limit" or .event == "resumed"))) | last
        | select(.event? == "usage_limit")
        | [.kind, (.session // "" | if . == "null" then "" else . end), (.resets_at // ""), ((.ts | fromdateiso8601) + (.wait_s // 0))]
        | map(tostring) | join("\u001f")' "$LOG" 2>/dev/null || true)
    [ -n "$last" ] || return 1
    IFS=$'\x1f' read -r CUT_KIND CUT_SID CUT_RESET CUT_TARGET <<< "$last" || true
    case "$CUT_KIND" in step|nudge) [ -n "$CUT_SID" ] ;; *) return 1 ;; esac
}

# kind_spent <session> <event>  → what <session> spent since the step's last <event> (launched for a step
# session, nudged for a nudge), from the logged results: each result's increase over the session's
# previous one, the rule of lib.sh pr_cost_delta and report.sh. A result with no cost counts nothing.
kind_spent() {
    local out
    out=$(jq -rs --arg s "$STEP" --arg sid "$1" --arg ev "$2" '
        map(select(.step == $s)) | ((map(.event == $ev) | rindex(true)) // 0) as $i
        | def costs: map(select(.event == "result" and .session == $sid and (.cost // 0) > 0) | .cost);
          reduce (.[$i:] | costs)[] as $c ({p: (.[:$i] | costs | last // 0), s: 0};
              .s += (if $c >= .p then $c - .p else $c end) | .p = $c) | .s' "$LOG" 2>/dev/null || true)
    printf '%s' "${out:-0}"
}

# resume_cut_off  → the re-run side of a stop during a usage-limit wait (cut_off): waits out the rest of
# that wait if it still lies ahead, logs resumed and resumes the step or nudge session where it was cut
# off, on what is left of its kind's budget. The step keeps the START_SHA of its last launch. Sets
# RESULT_FILE and SESSION_ID.
resume_cut_off() {
    local kind=$CUT_KIND sid=$CUT_SID target=${CUT_TARGET%.*} budget=$BUDGET from=launched now
    START_SHA=$(jq -r --arg s "$STEP" 'select(.event == "launched" and .step == $s) | .start' "$LOG" 2>/dev/null | tail -1 || true)
    START_SHA=${START_SHA:-$(wt_git rev-parse HEAD)}
    SESSION_ID=$sid
    if [ "$kind" = nudge ]; then NUDGED=1; budget=$NUDGE_BUDGET_USD; from=nudged; fi
    budget=$(pr_budget_left "$budget" "$(kind_spent "$sid" "$from")")
    say "step $STEP: resuming its $kind session $sid, which a usage limit stopped in an earlier run (budget \$$budget left)"
    now=$(date +%s)
    [ -n "$target" ] && [ "$target" -gt "$now" ] 2>/dev/null || target=$now
    if past_ceiling "$CUT_RESET" "$target"; then stop_blocked "$SESSION_STOP"; fi
    wait_out "$kind" "$sid" "$CUT_RESET" "$target"
    RESULT_FILE=$RUNS/$RUN_ID.step$STEP.$(date +%Y%m%d-%H%M%S).resume.result.json
    run_session "$kind" "$WT" "$RESULT_FILE" "$budget" "$CONTINUE_PROMPT" "$sid" 1
    SESSION_ID=${RUN_SID:-$sid}
}

run_step() {
    prepare_step "$1"
    if [ -d "$WT" ]; then START_SHA=$(wt_git rev-parse HEAD); else START_SHA='(dry run)'; fi
    if [ "$DRY_RUN" = 1 ]; then
        local shown=() a
        write_prompt
        claude_args "$BUDGET"
        for a in "${CLAUDE_ARGS[@]}"; do [ "$a" = "$(cat "$SCHEMA")" ] && shown+=('<schema>') || shown+=("$a"); done
        echo; echo "step $STEP — $HEADING"
        echo "  tier $TIER (tagged $TAGGED_TIER) → model $MODEL, effort $EFFORT, budget \$$BUDGET, advisor ${ADVISOR:-none}"
        echo "  floor: $(pr_join "$FLOOR" none) | tripwire: >$PR_TRIPWIRE_LINES lines → simplify; $PR_TRIPWIRE_PATHS (not tests) or ≥$PR_TRIPWIRE_VIEWMODELS ViewModels → code-review"
        echo "  after a failed nudge: $([ "$ESCALATE" = 1 ] && echo "one re-run at effort $(pr_next_effort "$EFFORT" || echo '— none, already at the top')" || echo blocked) | judge: $([ -n "$JUDGE_MODEL" ] && echo "$JUDGE_MODEL/$JUDGE_EFFORT" || echo off)"
        echo "  prompt: $(rel "$PROMPT_FILE")"
        echo "  would run (cwd $WT):"; printf '    claude'; printf ' %q' "${shown[@]}"; echo ' "<prompt>"'
        return 0
    fi
    # One escalation per step, not per invocation: a step that already escalated in an earlier
    # run (and then blocked) resumes on the rung it reached, with no further re-run to spend.
    local reached
    reached=$(jq -r --arg s "$STEP" 'select(.event == "escalated" and .step == $s) | .to' "$LOG" 2>/dev/null | tail -1 || true)
    if [ -n "$reached" ]; then
        ATTEMPT=2; EFFORT=$reached
        say "note: step $STEP already escalated to effort $reached in an earlier run — resuming there, no further re-run"
    fi
    if [ -n "$(wt_git status --porcelain)" ]; then
        say "note: the worktree has uncommitted changes (a previous attempt? your answer to a decision?) — the session will see them"
        PRESTATE=$RUNS/$RUN_ID.step$STEP.$(date +%Y%m%d-%H%M%S).prestate
        wt_git diff --binary HEAD > "$PRESTATE.patch"
        if [ -n "$(wt_git ls-files -o --exclude-standard)" ]; then
            (cd "$WT" && git ls-files -o --exclude-standard -z | tar --null -czf "$PRESTATE.untracked.tgz" -T -)
        fi
    fi
    if cut_off "$STEP"; then resume_cut_off; else launch; fi
    handle_result
}

# On start-up: a step the runner launched but the human finished in a resumed
# session has a Shipped line and no `validated` entry — validate it first.
validate_pending_from_log() {
    local last launched reasons f
    last=$(pr_last_shipped "$PLAN_WT" "${TOKENS[@]}")
    [ -n "$last" ] && [ -f "$LOG" ] || return 0
    if jq -e --arg s "$last" 'select(.event=="validated" and .step==$s)' "$LOG" >/dev/null 2>&1; then return 0; fi
    launched=$(jq -c --arg s "$last" 'select(.event=="launched" and .step==$s)' "$LOG" | tail -1)
    [ -n "$launched" ] || return 0     # never launched by the runner: shipped by hand, the human gated it
    say "step $last was shipped after a runner launch but never validated — validating now"
    prepare_step "$last"
    START_SHA=$(jq -r .start <<< "$launched")
    # Evidence is the `done` results of the *last* attempt only: whatever an earlier attempt
    # reviewed was reset away with it, and a needs_decision result carries a stale run list.
    RESULT_FILE=''
    for f in $(jq -rs --arg s "$last" 'map(select(.step == $s)) | (map(.event == "launched") | rindex(true)) as $i
            | .[$i:] | map(select(.event == "result" and .status == "done") | .file) | .[]' "$LOG" 2>/dev/null || true); do
        RESULT_FILE=$ROOT/$f
        [ -f "$RESULT_FILE" ] || RESULT_FILE=$f      # a runs dir outside the repo logs absolute paths
        note_run_skills
    done
    SESSION_ID=$(jq -r --arg s "$last" 'select(.step==$s and .session != null) | .session' "$LOG" | tail -1)
    reasons=$(validate_step)
    if [ -n "$reasons" ]; then
        say "validation of step $last failed:"; printf '%s' "$reasons" | sed 's/^/    /'
        stop_blocked "step $last, finished by hand, does not validate: $(printf '%s' "$reasons" | paste -sd';' -)"
    fi
    log_validated
    judge_step
}

# ---- the walk over the Order line ----------------------------------------------
SHIPPED_NOW=' '     # the steps this invocation shipped (a dry run: would ship), space-delimited
NEXT=''; PENDING=0; STOPPED_TO=0; SYNCED=0; SYNC_NOTED=0; SYNC_FETCHED=0

# load_tokens  → TOKENS from the Order line of the branch's plan; read again after every sync.
load_tokens() {
    local order
    order=$(pr_order_tokens "$PLAN_WT" || exit 1) || exit 1
    mapfile -t TOKENS <<< "$order"
}

# checkpoint_due <step left of the ‖> <index of the ‖>  → exit 0 when the ‖ stops the run. Only
# at the frontier: no later step of the Order line is shipped. So a ‖ that a sync adds behind the
# run's position never stops it. At the frontier it is due when its step shipped in this
# invocation, or when the step is shipped and the log rule of pr_checkpoint_due holds.
checkpoint_due() {
    [ -n "$1" ] || return 1
    ! pr_shipped_after "$PLAN_WT" "$2" "${TOKENS[@]}" || return 1
    case "$SHIPPED_NOW" in *" $1 "*) return 0 ;; esac
    pr_step_shipped "$PLAN_WT" "$1" && pr_checkpoint_due "$LOG" "$1"
}

stop_checkpoint() { # stop_checkpoint <step>
    log checkpoint --arg step "$1"
    notify "checkpoint after step $1" "sign off the departures in $PLAN_REL, then run again to continue"
    echo "  $(wt_git log --oneline "$(wt_git merge-base main HEAD)..HEAD" | wc -l) commit(s) on $BRANCH — git -C $ROOT log main..$BRANCH"
    echo "  per-step cost, turns, nudges and grades: scripts/plan-runner/report.sh $RUN_ID"
    exit 4
}

# walk  → one pass over TOKENS from the top. A real run stops at the first step to launch and
# leaves it in NEXT (empty: nothing left, or STOPPED_TO=1 past --to). A dry run lists every
# step instead. Exits at a due ‖ (4) and at an unanswered **Decision needed** block (2).
walk() {
    local i tok prev='' last_session
    NEXT=''
    for i in "${!TOKENS[@]}"; do
        tok=${TOKENS[$i]}
        # --to: the first step past N ends the run. A ‖ between N and that step has had its turn by
        # now (CP tokens carry no number), so a checkpoint due after step N still stops with exit 4.
        if [ -n "$TO" ] && [ "$tok" != CP ] && [ "$(pr_step_num "$tok")" -gt "$TO" ]; then STOPPED_TO=1; return 0; fi
        if [ "$tok" = CP ]; then
            if checkpoint_due "$prev" "$i"; then
                if [ "$DRY_RUN" = 1 ]; then echo; echo "‖ the run would stop here after step $prev (listing continues for review)"; continue; fi
                stop_checkpoint "$prev"
            fi
            continue
        fi
        prev=$tok
        if [ "$(pr_step_num "$tok")" -lt "$FROM" ]; then continue; fi
        if pr_step_shipped "$PLAN_WT" "$tok"; then continue; fi
        if pr_step_decision_pending "$PLAN_WT" "$tok"; then
            STEP=$tok
            if [ "$DRY_RUN" = 1 ]; then echo; echo "step $tok — has an unanswered **Decision needed** block; a real run stops here"; PENDING=1; continue; fi
            say "step $tok has an unanswered **Decision needed** block — answer it (resume the last session, or edit the branch's plan and rename the block to **Decision taken**)"
            last_session=$(jq -r --arg s "$tok" 'select(.event=="needs_decision" and .step==$s) | .session' "$LOG" 2>/dev/null | tail -1 || true)
            [ -z "${last_session:-}" ] || { SESSION_ID=$last_session; echo "    $(resume_hint)"; }
            exit 2
        fi
        if [ "$DRY_RUN" = 1 ]; then PENDING=1; run_step "$tok"; SHIPPED_NOW+="$tok "; continue; fi
        NEXT=$tok; return 0
    done
}

# ---- the step boundary: pause file and plan sync ---------------------------------
stop_paused() { # stop_paused [<where>]  — default: before step NEXT. A run paused during a wait resumes its session.
    log paused --arg next "$NEXT"
    rm -f "$PAUSE"
    notify "paused ${1:-before step $NEXT}" "the pause file is removed; run the same command again to continue"
    exit 5
}

# sync_fetch  → a remote-tracking REF (origin/main) is fetched first: the owner's edits arrive
# there by push, and a cloud container's local main never moves. A failed fetch is a warning.
# Once per boundary: the main loop clears SYNC_FETCHED after each step, so the walk that follows a
# sync commit, and the first boundary after the start-up check, do not fetch again.
sync_fetch() {
    local full remote branch err
    [ "$SYNC_FETCHED" = 0 ] || return 0
    SYNC_FETCHED=1
    full=$(git -C "$ROOT" rev-parse --symbolic-full-name "$SYNC_FROM" 2>/dev/null || true)
    case "$full" in refs/remotes/*/*) ;; *) return 0 ;; esac
    full=${full#refs/remotes/}; remote=${full%%/*}; branch=${full#*/}
    # `|| exit 1` inside: a failing command in $(…) fires the ERR trap there, `if !` outside or not.
    if ! err=$(git -C "$ROOT" fetch -q "$remote" "$branch" 2>&1 || exit 1); then
        say "warning: git fetch $remote $branch failed ($(tail -1 <<< "$err")) — syncing from $SYNC_FROM as it is"
    fi
}

# last_sync <range>  → the REF sha named by the newest sync commit's trailer in <range>, or nothing.
last_sync() { pr_synced_from "$(wt_git log -1 --format=%B --grep="^$PR_SYNC_TRAILER: " "$1")"; }

# sync_message <ref-sha>  → the -m arguments of a sync commit, one per line: subject, then trailer.
sync_message() {
    printf 'docs(plan): sync %s from %s at %s\n%s: %s\n' "$PLAN_REL" "$SYNC_FROM" "$(git -C "$ROOT" rev-parse --short "$1")" "$PR_SYNC_TRAILER" "$1"
}

# sync_base <ref-sha>  → the merge base of the plan: the newer of the fork point and the REF commit
# named by the trailer of the branch's newest sync commit. A trailer commit no longer on REF is
# ignored. Prints nothing when the two share no history.
sync_base() {
    local mb t
    mb=$(wt_git merge-base "$1" HEAD 2>/dev/null || true)
    [ -n "$mb" ] || return 0
    t=$(last_sync "$mb..HEAD")
    if [ -n "$t" ] && wt_git merge-base --is-ancestor "$t" "$1" 2>/dev/null && ! wt_git merge-base --is-ancestor "$t" "$mb" 2>/dev/null; then
        echo "$t"
    else
        echo "$mb"
    fi
}

stop_sync() { # stop_sync <ref-sha> <why>  — nothing was written to the worktree
    local msg
    mapfile -t msg < <(sync_message "$1")
    log plan_sync_failed --arg next "$NEXT" --arg ref "$SYNC_FROM" --arg sha "$1" --arg why "$2" --arg file "$(rel "$SYNC_CONFLICT")"
    notify "the plan sync from $SYNC_FROM needs you" "$2"
    echo; echo "  the merged plan is in $(rel "$SYNC_CONFLICT"); the worktree and $BRANCH are untouched."
    echo "  merge it by hand into $(rel "$WT")/$PLAN_REL, record the merge with this one commit, and run this again:"
    echo "    git -C $(printf '%q' "$WT") commit -m '${msg[0]}' -m '${msg[1]}' -- $PLAN_REL"
    echo "  or run again with --sync-from none to go on without the change."
    exit 5
}

# sync_plan  → merges the plan file, and only the plan file, from SYNC_FROM into the branch's copy
# and commits it. Sets SYNCED=1 when it committed; exits 5 when the merge needs the owner.
sync_plan() {
    local sha base tmp rc=0 why='' placed=0 problems msg
    SYNCED=0
    [ "$SYNC_FROM" != none ] || return 0
    if [ -n "$(wt_git status --porcelain -- "$PLAN_REL")" ]; then
        say "note: $PLAN_REL has uncommitted changes in the worktree (an answer to a decision?) — plan sync skipped; a later step boundary syncs"
        return 0
    fi
    # A step whose session a usage limit cut off resumes mid-step: a sync commit here would land inside its range.
    if cut_off "$NEXT"; then say "note: step $NEXT resumes a session cut off at a usage limit — plan sync deferred to the next step boundary"; return 0; fi
    sync_fetch
    sha=$(git -C "$ROOT" rev-parse --verify --quiet "$SYNC_FROM^{commit}" || true)
    if [ -z "$sha" ]; then say "warning: $SYNC_FROM no longer names a commit — plan sync skipped"; return 0; fi
    if ! git -C "$ROOT" cat-file -e "$sha:$PLAN_REL" 2>/dev/null; then
        [ "$SYNC_NOTED" = 1 ] || say "note: $SYNC_FROM has no $PLAN_REL — nothing to sync"
        SYNC_NOTED=1; return 0
    fi
    base=$(sync_base "$sha")
    if [ -z "$base" ]; then say "warning: $BRANCH and $SYNC_FROM share no history — plan sync skipped"; return 0; fi
    # The plan changed on REF only when its blob differs from the base's.
    [ "$(git -C "$ROOT" rev-parse "$sha:$PLAN_REL")" != "$(git -C "$ROOT" rev-parse --verify --quiet "$base:$PLAN_REL" || true)" ] || return 0
    tmp=$(mktemp -d)
    cp "$PLAN_WT" "$tmp/ours"
    git -C "$ROOT" show "$sha:$PLAN_REL" > "$tmp/theirs"
    git -C "$ROOT" show "$base:$PLAN_REL" > "$tmp/base" 2>/dev/null || : > "$tmp/base"
    git merge-file -p --zdiff3 --marker-size=13 -L "$BRANCH" -L "base" -L "$SYNC_FROM" \
        "$tmp/ours" "$tmp/base" "$tmp/theirs" > "$tmp/merged" || rc=$?
    if [ "$rc" -ge 128 ]; then
        why="git merge-file failed (exit $rc)"
    elif [ "$rc" -gt 0 ]; then
        # Same-spot insertions (a Shipped block here, a new step there) are placed, not stopped on.
        if pr_merge_inserts "$tmp/merged" > "$tmp/placed"; then placed=$rc; else why="$PLAN_REL changed at the same place on $BRANCH and on $SYNC_FROM"; fi
        mv "$tmp/placed" "$tmp/merged"
    fi
    if [ -z "$why" ]; then
        problems=$(pr_plan_check "$tmp/merged" "$tmp/ours" || true)
        [ -z "$problems" ] || why="the merged plan does not check: ${problems//$'\n'/; }"
    fi
    if [ -n "$why" ]; then cp "$tmp/merged" "$SYNC_CONFLICT"; rm -rf "$tmp"; stop_sync "$sha" "$why"; fi
    if cmp -s "$tmp/merged" "$PLAN_WT"; then rm -rf "$tmp"; return 0; fi     # REF's change is already here
    cp "$tmp/merged" "$PLAN_WT"; rm -rf "$tmp"
    mapfile -t msg < <(sync_message "$sha")
    if ! wt_git commit -q -m "${msg[0]}" -m "${msg[1]}" -- "$PLAN_REL"; then
        cp "$PLAN_WT" "$SYNC_CONFLICT"
        wt_git checkout -q -- "$PLAN_REL"
        stop_sync "$sha" "git commit of the merged plan failed"
    fi
    log plan_synced --arg next "$NEXT" --arg ref "$SYNC_FROM" --arg sha "$sha" --arg base "$base" \
        --arg commit "$(wt_git rev-parse HEAD)" --argjson placed "$placed"
    say "${msg[0]#docs(plan): }$([ "$placed" = 0 ] || echo ", $placed same-spot insertion(s) placed with the branch's lines first") — reading the Order line again"
    SYNCED=1
}

# ---- main --------------------------------------------------------------------
if [ "$DRY_RUN" = 0 ]; then
    if [ "$SYNC_FROM" != none ]; then
        sync_fetch
        git -C "$ROOT" rev-parse --verify --quiet "$SYNC_FROM^{commit}" >/dev/null \
            || { echo "--sync-from: '$SYNC_FROM' is not a commit (none turns the plan sync off)" >&2; exit 1; }
    fi
    ensure_worktree
    PLAN_WT=$WT/$PLAN_REL
    [ -f "$PLAN_WT" ] || { echo "plan-runner: $PLAN_REL is not on $BRANCH — commit the plan on main first, then merge main into $BRANCH or recreate the worktree" >&2; exit 1; }
    # A committed edit reaches the run by the plan sync at the next step boundary; an uncommitted one never does.
    if [ -n "$(git -C "$ROOT" status --porcelain -- "$PLAN_REL" 2>/dev/null || true)" ]; then
        say "warning: $PLAN_REL has uncommitted changes in the main checkout — a run never sees them; $([ "$SYNC_FROM" = none ] && echo "commit them and merge them into $BRANCH" || echo "commit them on $SYNC_FROM, and the next step boundary syncs them")"
    fi
    if [ -f "$PAUSE" ]; then
        rm -f "$PAUSE"
        say "note: removed a stale pause file ($(rel "$PAUSE")) from before this start — to pause this run, create it again while it runs"
    fi
    behind=$(git -C "$ROOT" rev-list --count "$BRANCH..main" 2>/dev/null || echo 0)
    [ "$behind" = 0 ] || say "note: $BRANCH is $behind commit(s) behind main"
else
    PLAN_WT=$PLAN_ABS
    [ -d "$WT" ] && PLAN_WT=$WT/$PLAN_REL
    say "dry run — reading $PLAN_REL from $([ -d "$WT" ] && echo "the worktree" || echo "the main tree"); nothing is launched, rendered prompts go to $(rel "$RUNS")"
fi

load_tokens
if [ "$DRY_RUN" = 0 ]; then
    trap on_signal INT TERM
    # A driver that dies on its ERR trap must not leave a session running in the worktree.
    trap '[ -z "$SESSION_PID" ] || stop_session' EXIT
    # The newest known window state at start-up is the log's last usage_limit: a run stopped during a
    # wait, or blocked past the ceiling, waits out a reset that still lies ahead before its first launch.
    last_limit=$(jq -r 'select(.event == "usage_limit") | "\(.resets_at // "") \(.session)"' "$LOG" 2>/dev/null | tail -1 || true)
    WINDOW_RESET=${last_limit%% *}; WINDOW_RESET=${WINDOW_RESET%.*}; WINDOW_SID=${last_limit#* }
    validate_pending_from_log
fi

if [ "$DRY_RUN" = 1 ]; then
    walk        # one pass, no boundary: a dry run never pauses and never syncs
else
    # Walk from the top to the next step, stop at the boundary for a pause file, sync the plan;
    # a sync commit means a new Order line, so walk again before launching anything.
    while :; do
        walk
        [ -n "$NEXT" ] || break
        [ ! -f "$PAUSE" ] || stop_paused
        sync_plan
        if [ "$SYNCED" = 1 ]; then load_tokens; continue; fi
        PENDING=1
        run_step "$NEXT"
        SHIPPED_NOW+="$NEXT "; SYNC_FETCHED=0
    done
fi

if [ "$PENDING" = 0 ]; then
    say "nothing to do — no unshipped step at or after step $FROM (a plan shipped by hand has no **Shipped** lines and would list every step; see --dry-run)"
    exit 0
fi
if [ "$DRY_RUN" = 1 ]; then
    [ "$STOPPED_TO" = 0 ] || { echo; echo "--to $TO: the run would stop here; later steps are not listed"; }
    exit 0
fi
if [ "$STOPPED_TO" = 1 ]; then
    say "stopped after step $TO as asked (--to) — later steps are untouched; run again without --to to continue"
    echo "  per-step cost, turns, nudges and grades: scripts/plan-runner/report.sh $RUN_ID"
    exit 0
fi
notify "plan complete" "every step is shipped on $BRANCH"
echo; git -C "$ROOT" log --oneline "main..$BRANCH"; echo
echo "  per-step cost, turns, nudges and grades: scripts/plan-runner/report.sh $RUN_ID"
[ -z "$VARIANT" ] || echo "  this is variant '$VARIANT' of $NAME — compare it with the other variant(s) before merging either"
behind=$(git -C "$ROOT" rev-list --count "$BRANCH..main" 2>/dev/null || echo 0)
if [ "$behind" = 0 ]; then
    echo "  fast-forward and push when you are happy:"
    echo "    git -C $ROOT checkout main && git -C $ROOT merge --ff-only $BRANCH && git -C $ROOT push"
else
    echo "  main moved on by $behind commit(s) while the plan ran — merge main into $BRANCH (or rebase), re-run the gate,"
    echo "  then: git -C $ROOT checkout main && git -C $ROOT merge --ff-only $BRANCH && git -C $ROOT push"
fi
last_sync=$(last_sync "$(wt_git merge-base main HEAD)..HEAD")
if [ -n "$last_sync" ]; then
    last_sync=$(git -C "$ROOT" rev-parse --short "$last_sync")
    echo "  $BRANCH carries plan sync commits: its plan holds main's plan as of $last_sync. Merging main is clean where a"
    echo "  sync merged cleanly. Git asks once more about the plan where a step was added right after one that shipped,"
    echo "  or where a Shipped block sits next to a line main changed. Keep the branch's side there, then re-apply what"
    echo "  main changed in the plan after the last sync: git -C $ROOT diff $last_sync main -- $PLAN_REL"
fi
exit 0
