#!/usr/bin/env bash
# Plan runner — runs a multi-step plan unattended, one fresh headless Claude
# session per step, in a dedicated worktree. Design and contract:
# docs/plans/done/plan-runner.md. Run it from a terminal, not from inside a
# Claude session.
#
#   scripts/run-plan.sh <plan.md> [--from N] [--dry-run] [--cap max|strong|mid] [--budget USD]
#
# Exit codes: 0 all steps shipped (or dry run) · 1 usage/config error ·
#             2 a decision is needed · 3 a step is blocked · 4 stopped at a ‖ checkpoint
#
# The runner never commits and never pushes. Every commit on plan/<name> is a
# step session's. State lives in the plan file (**Shipped** blocks) and in the
# gitignored run log docs/plans/.runs/<name>.log (one JSON object per line).
set -Eeuo pipefail
# A driver crash must not look like a decision (2) or a block (3): report and exit 1.
trap 'echo "plan-runner: internal error at line $LINENO (exit $?) — this is a driver bug, not a step result" >&2; exit 1' ERR

# ---- tunables (the only place model ids and effort levels live) -------------
MODEL_MAX=fable;     EFFORT_MAX=xhigh     # a step's `effort:` tag overrides the tier's effort
MODEL_STRONG=opus;   EFFORT_STRONG=xhigh
MODEL_MID=sonnet;    EFFORT_MID=high
DEFAULT_BUDGET_USD=25      # per step; a `budget:` heading tag overrides, --budget overrides both
NUDGE_BUDGET_USD=5         # the single fix-forward resume a step may get
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
)
# Deny wins over allow. Prefix patterns cannot express "outside the worktree";
# the prompt forbids leaving it and the run log shows every denial.
DISALLOWED_TOOLS=(
    "Bash(git push*)" "Bash(git reset --hard*)" "Bash(git checkout main*)" "Bash(git switch main*)"
    "Bash(git branch -D*)" "Bash(./gradlew --stop*)" "AskUserQuestion" "EnterPlanMode" "EnterWorktree"
)

# ---- setup -------------------------------------------------------------------
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=plan-runner/lib.sh
source "$SCRIPT_DIR/plan-runner/lib.sh"
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
TEMPLATE=$SCRIPT_DIR/plan-runner/step-prompt.md
SCHEMA=$SCRIPT_DIR/plan-runner/step-result.schema.json
SCHEMA_REL=scripts/plan-runner/step-result.schema.json

usage() { # usage [exit-code]
    sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-1}"
}
need_arg() { [ $# -ge 2 ] && [ -n "$2" ] || { echo "$1 needs a value" >&2; usage 1; }; }

PLAN_ARG=''; FROM=1; DRY_RUN=0; CAP=''; BUDGET_OVERRIDE=''
while [ $# -gt 0 ]; do
    case "$1" in
        --from)    need_arg "$@"; FROM=$2; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --cap)     need_arg "$@"; CAP=$2; shift 2 ;;
        --budget)  need_arg "$@"; BUDGET_OVERRIDE=$2; shift 2 ;;
        -h|--help) usage 0 ;;
        -*)        echo "unknown flag $1" >&2; usage 1 ;;
        *)         [ -z "$PLAN_ARG" ] || usage 1; PLAN_ARG=$1; shift ;;
    esac
done
[ -n "$PLAN_ARG" ] || usage 1
case "$CAP" in ''|max|strong|mid) ;; *) echo "--cap wants max|strong|mid" >&2; exit 1 ;; esac
[[ "$FROM" =~ ^[0-9]+$ ]] || { echo "--from wants a step number" >&2; exit 1; }

PLAN_ABS=$(realpath "$PLAN_ARG")
[ -f "$PLAN_ABS" ] || { echo "no such plan: $PLAN_ARG" >&2; exit 1; }
PLAN_REL=${PLAN_ABS#"$ROOT"/}
NAME=$(basename "$PLAN_ABS" .md)
BRANCH=plan/$NAME
WT=$ROOT/.claude/worktrees/plan-$NAME
RUNS=${PLAN_RUNNER_RUNS_DIR:-$ROOT/docs/plans/.runs}   # override for the self-check
LOG=$RUNS/$NAME.log
mkdir -p "$RUNS"

# A driver launched from inside a Claude session would nest; scrub the markers
# so the step sessions start clean either way.
unset CLAUDECODE CLAUDE_CODE_ENTRYPOINT CLAUDE_CODE_SESSION_ID CLAUDE_CODE_CHILD_SESSION \
      CLAUDE_CODE_SSE_PORT CLAUDE_CODE_MESSAGING_SOCKET CLAUDE_CODE_MESSAGING_TOKEN \
      CLAUDE_CODE_BRIDGE_SESSION_ID CLAUDE_PID CLAUDE_EFFORT 2>/dev/null || true
export PLAN_RUNNER=1

ts()  { date -u +%Y-%m-%dT%H:%M:%SZ; }
# Progress goes to stderr: validate_step's stdout is its reasons text, captured by the caller.
say() { printf '[plan-runner %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
log() { # log <event> [jq --arg k v]…  → one JSON line; `step` is always a string
    local ev=$1; shift
    jq -nc --arg ts "$(ts)" --arg plan "$NAME" --arg event "$ev" "$@" \
        '{ts:$ts, plan:$plan, event:$event} + ($ARGS.named | del(.ts,.plan,.event))' >> "$LOG"
}
notify() {
    say "$1 — $2"
    if command -v "$NOTIFY_CMD" >/dev/null 2>&1; then "$NOTIFY_CMD" "plan-runner: $NAME" "$1 — $2" || true; fi
}
model_for()  { case "$1" in max) echo "$MODEL_MAX" ;; strong) echo "$MODEL_STRONG" ;; mid) echo "$MODEL_MID" ;; esac; }
effort_for() { case "$1" in max) echo "$EFFORT_MAX" ;; strong) echo "$EFFORT_STRONG" ;; mid) echo "$EFFORT_MID" ;; esac; }
wt_git() { git -C "$WT" "$@"; }
rel() { printf '%s' "${1#"$ROOT"/}"; }

# ---- worktree ----------------------------------------------------------------
ensure_worktree() {
    if [ -d "$WT" ]; then
        local on; on=$(wt_git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')
        [ "$on" = "$BRANCH" ] || { echo "plan-runner: $WT exists but is on '$on', not $BRANCH — remove it (git worktree remove) or check out $BRANCH there" >&2; exit 1; }
        return
    fi
    say "creating worktree $(rel "$WT") on $BRANCH"
    if git -C "$ROOT" show-ref --verify --quiet "refs/heads/$BRANCH"; then
        git -C "$ROOT" worktree add "$WT" "$BRANCH" >/dev/null
    else
        git -C "$ROOT" worktree add -b "$BRANCH" "$WT" main >/dev/null
    fi
    # Gradle needs both; neither is tracked. See docs/GOTCHAS.md / CLAUDE.md.
    for f in local.properties app/google-services.json; do
        if [ -f "$ROOT/$f" ] && [ ! -f "$WT/$f" ]; then cp "$ROOT/$f" "$WT/$f"; fi
    done
}

# ---- one step ----------------------------------------------------------------
# Per-step state, set by prepare_step / run_step and read by the validate path.
STEP=''; HEADING=''; TIER=''; TAGGED_TIER=''; MODEL=''; EFFORT=''; BUDGET=''
FLOOR=''; START_SHA=''; SESSION_ID=''; RESULT_FILE=''; NUDGED=0

prepare_step() {
    STEP=$1
    HEADING=$(pr_step_heading "$PLAN_WT" "$STEP")
    FLOOR=$(pr_tag_skills "$HEADING")
    TAGGED_TIER=$(pr_tag_tier "$HEADING")
    TIER=$(pr_cap_tier "$TAGGED_TIER" "$CAP")
    MODEL=$(model_for "$TIER")
    # Effort follows the *tagged* tier, then an explicit `effort:` tag; --cap lowers the
    # model only — a cheaper model thinking longer is the better trade.
    EFFORT=$(pr_tag_effort "$HEADING" "$(effort_for "$TAGGED_TIER")")
    BUDGET=${BUDGET_OVERRIDE:-$(pr_tag_budget "$HEADING" "$DEFAULT_BUDGET_USD")}
    NUDGED=0; SESSION_ID=''; RESULT_FILE=''
}

# Renders the prompt for the current step; START_SHA must already be set by the caller.
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
        "BASE=$base" "COMMITS=$commits" "SCHEMA_PATH=$SCHEMA_REL")
    left=$(pr_unfilled "$out")
    [ -z "$left" ] || { echo "plan-runner: template placeholders left unfilled: $left" >&2; exit 1; }
    printf '%s' "$out"
}

claude_args() { # claude_args <budget> [--resume <id>]  → fills CLAUDE_ARGS
    CLAUDE_ARGS=(-p --output-format json --json-schema "$(cat "$SCHEMA")"
        --model "$MODEL" --effort "$EFFORT" --max-budget-usd "$1"
        --permission-mode "$PERMISSION_MODE"
        --allowedTools "${ALLOWED_TOOLS[@]}" --disallowedTools "${DISALLOWED_TOOLS[@]}"
        -n "plan $NAME step $STEP")
    if [ $# -ge 3 ] && [ "$2" = --resume ]; then CLAUDE_ARGS+=(--resume "$3"); fi
}

run_claude() { # run_claude <out-file> <prompt>  (cwd = worktree; a non-zero exit is a result, not an error)
    (cd "$WT" && claude "${CLAUDE_ARGS[@]}" "$2" > "$1" 2> "$1.stderr") || true
}

# result_get <jq-path>  → the field from RESULT_FILE as text, or "null". Shape: lib.sh pr_result_kind.
result_get() { pr_result_field "$RESULT_FILE" "$1"; }

log_result() { # never fails: every value has a fallback (the file may be empty or not JSON)
    log result --arg step "$STEP" --arg session "$(result_get .session_id)" --arg subtype "$(result_get .subtype)" \
        --arg status "$(result_get .structured_output.status)" --arg commit "$(result_get .structured_output.commit)" \
        --argjson cost "$(pr_result_json "$RESULT_FILE" .total_cost_usd 0)" \
        --argjson turns "$(pr_result_json "$RESULT_FILE" .num_turns 0)" \
        --argjson denials "$(pr_result_json "$RESULT_FILE" .permission_denials '[]')" \
        --arg file "$(rel "$RESULT_FILE")"
}

resume_hint() { printf 'cd %q && claude --resume %s\n' "$WT" "$SESSION_ID"; }

# validate_step → prints reasons (empty = valid). Uses START_SHA, RESULT_FILE, FLOOR.
validate_step() {
    local reasons='' commit head numstat names required run missing gate_log
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
    numstat=$(mktemp); names=$(mktemp)
    wt_git diff --numstat "$START_SHA..HEAD" > "$numstat"; wt_git diff --name-only "$START_SHA..HEAD" > "$names"
    if pr_needs_gate "$names"; then
        gate_log=$RUNS/$NAME.step$STEP.gate.$(date +%s).log
        say "re-running the gate in the worktree ($GATE_TASKS) → $(rel "$gate_log")"
        # shellcheck disable=SC2086
        if ! (cd "$WT" && ./gradlew $GATE_TASKS > "$gate_log" 2>&1); then
            reasons+="the gate is red (see $(rel "$gate_log")): $(grep -m3 -E 'FAILED|error:|e: |What went wrong' "$gate_log" | tr '\n' ' ' || true)"$'\n'
        fi
    else
        say "diff touches nothing the gate can see — gate skipped"
    fi
    required=$(pr_union "$FLOOR" "$(pr_tripwire "$numstat" "$names")")
    # The run list comes from the result; a step the human finished in a resumed
    # session has no `done` result, so the Shipped line's `skills:` stands in.
    run=$(jq -r 'select(.structured_output.status == "done") | .structured_output.skills.run[]?' "$RESULT_FILE" 2>/dev/null || true)
    [ -n "$run" ] || run=$(pr_shipped_skills "$PLAN_WT" "$STEP")
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
    echo; echo "  $(result_get .structured_output.summary)"; echo
    echo "  inspect the worktree, then either resume the session or fix by hand and run this again:"
    echo "    $(resume_hint)"
    exit 3
}

# nudge <text>  — the one fix-forward resume a step may get; sets RESULT_FILE to the new result.
nudge() {
    [ "$NUDGED" = 0 ] || stop_blocked "already nudged once — $1"
    NUDGED=1
    log nudged --arg step "$STEP" --arg session "$SESSION_ID" --arg reasons "$1"
    say "nudging the session once (budget \$$NUDGE_BUDGET_USD)"
    claude_args "$NUDGE_BUDGET_USD" --resume "$SESSION_ID"
    RESULT_FILE=$RUNS/$NAME.step$STEP.$(date +%Y%m%d-%H%M%S).nudge.result.json
    run_claude "$RESULT_FILE" "$1"$'\n\n'"Then end again with the JSON result object."
    log_result
}

# handle_result — loops at most once through the nudge; exits on every stop.
handle_result() {
    local reasons
    while :; do
        case "$(pr_result_kind "$RESULT_FILE")" in
            budget) stop_blocked "budget or turn limit exhausted ($(result_get .subtype)) — not resumed" ;;
            failed) stop_blocked "session ended without a usable result ($(result_get .subtype); stderr in $(rel "$RESULT_FILE").stderr)" ;;
            incomplete)
                nudge "Your session ended without the JSON result object the prompt requires. If the step is done, report it; if not, finish it first."
                continue ;;
        esac
        case "$(result_get .structured_output.status)" in
            needs_decision) stop_needs_decision ;;
            blocked)        stop_blocked "$(result_get .structured_output.summary)" ;;
            done)
                reasons=$(validate_step)
                if [ -n "$reasons" ]; then
                    say "validation failed:"; printf '%s' "$reasons" | sed 's/^/    /'
                    nudge "The driver validated your step $STEP result and found:"$'\n'"$reasons"$'\n'"Fix forward: address every point, re-run the gate, commit (amend the docs(plan) commit if the **Shipped** line changes)."
                    continue
                fi
                log validated --arg step "$STEP" --arg session "$SESSION_ID" --arg head "$(wt_git rev-parse --short HEAD)"
                say "step $STEP validated at $(wt_git rev-parse --short HEAD)"
                return 0 ;;
            *) stop_blocked "unknown status '$(result_get .structured_output.status)'" ;;
        esac
    done
}

run_step() {
    prepare_step "$1"
    local stamp prompt prompt_file
    stamp=$(date +%Y%m%d-%H%M%S)
    if [ -d "$WT" ]; then START_SHA=$(wt_git rev-parse HEAD); else START_SHA='(dry run)'; fi
    prompt=$(render_step_prompt)
    prompt_file=$RUNS/$NAME.step$STEP.$stamp.prompt.md
    printf '%s' "$prompt" > "$prompt_file"
    claude_args "$BUDGET"
    if [ "$DRY_RUN" = 1 ]; then
        local shown=() a
        for a in "${CLAUDE_ARGS[@]}"; do [ "$a" = "$(cat "$SCHEMA")" ] && shown+=('<schema>') || shown+=("$a"); done
        echo; echo "step $STEP — $HEADING"
        echo "  tier $TIER (tagged $TAGGED_TIER) → model $MODEL, effort $EFFORT, budget \$$BUDGET"
        echo "  floor: $(pr_join "$FLOOR" none) | tripwire: >$PR_TRIPWIRE_LINES lines → simplify; $PR_TRIPWIRE_PATHS or ≥$PR_TRIPWIRE_VIEWMODELS ViewModels → code-review"
        echo "  prompt: $(rel "$prompt_file")"
        echo "  would run (cwd $WT):"; printf '    claude'; printf ' %q' "${shown[@]}"; echo ' "<prompt>"'
        return 0
    fi
    if [ -n "$(wt_git status --porcelain)" ]; then
        say "note: the worktree has uncommitted changes (a previous attempt?) — the session will see them"
    fi
    RESULT_FILE=$RUNS/$NAME.step$STEP.$stamp.result.json
    log launched --arg step "$STEP" --arg start "$START_SHA" --arg tier "$TIER" --arg tagged "$TAGGED_TIER" \
        --arg model "$MODEL" --arg effort "$EFFORT" --arg budget "$BUDGET" --arg prompt "$(rel "$prompt_file")"
    say "step $STEP → $MODEL/$EFFORT, budget \$$BUDGET, floor $(pr_join "$FLOOR" none)"
    run_claude "$RESULT_FILE" "$prompt"
    SESSION_ID=$(result_get .session_id)
    log_result
    say "step $STEP session $SESSION_ID: $(result_get .subtype), status $(result_get .structured_output.status), \$$(result_get .total_cost_usd 0), denials $(pr_result_json "$RESULT_FILE" '.permission_denials | length' '?')"
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
    # Only a `done` result is evidence; a needs_decision result would carry a stale run list.
    RESULT_FILE=''
    for f in $(ls -t "$RUNS/$NAME.step$last."*.result.json 2>/dev/null || true); do
        if [ "$(jq -r '.structured_output.status // ""' "$f" 2>/dev/null)" = done ]; then RESULT_FILE=$f; break; fi
    done
    SESSION_ID=$(jq -r --arg s "$last" 'select(.step==$s and .session != null) | .session' "$LOG" | tail -1)
    reasons=$(validate_step)
    if [ -n "$reasons" ]; then
        say "validation of step $last failed:"; printf '%s' "$reasons" | sed 's/^/    /'
        stop_blocked "step $last, finished by hand, does not validate: $(printf '%s' "$reasons" | paste -sd';' -)"
    fi
    log validated --arg step "$last" --arg session "${SESSION_ID:-unknown}" --arg head "$(wt_git rev-parse --short HEAD)"
}

# ---- main --------------------------------------------------------------------
if [ "$DRY_RUN" = 0 ]; then
    ensure_worktree
    PLAN_WT=$WT/$PLAN_REL
    [ -f "$PLAN_WT" ] || { echo "plan-runner: $PLAN_REL is not on $BRANCH — commit the plan on main first, then merge main into $BRANCH or recreate the worktree" >&2; exit 1; }
    if ! cmp -s "$PLAN_ABS" "$PLAN_WT"; then
        say "warning: $PLAN_REL differs between main's tree and the branch — the branch's copy ($(rel "$WT")/$PLAN_REL) is the one the runner reads"
    fi
    behind=$(git -C "$ROOT" rev-list --count "$BRANCH..main" 2>/dev/null || echo 0)
    [ "$behind" = 0 ] || say "note: $BRANCH is $behind commit(s) behind main"
else
    PLAN_WT=$PLAN_ABS
    [ -d "$WT" ] && PLAN_WT=$WT/$PLAN_REL
    say "dry run — reading $PLAN_REL from $([ -d "$WT" ] && echo "the worktree" || echo "the main tree"); nothing is launched, rendered prompts go to $(rel "$RUNS")"
fi

if ! ORDER=$(pr_order_tokens "$PLAN_WT"); then exit 1; fi
mapfile -t TOKENS <<< "$ORDER"
[ "$DRY_RUN" = 1 ] || validate_pending_from_log

ran_prev=0; prev=''; pending=0
for tok in "${TOKENS[@]}"; do
    if [ "$tok" = CP ]; then
        # Due after a step this run shipped (or, dry, would ship), or one the runner had a
        # hand in earlier that never got its pause — see pr_checkpoint_due.
        if [ -n "$prev" ] && { [ "$ran_prev" = 1 ] || pr_step_shipped "$PLAN_WT" "$prev"; } \
           && pr_checkpoint_due "$LOG" "$prev" "$ran_prev"; then
            if [ "$DRY_RUN" = 1 ]; then echo; echo "‖ the run would stop here after step $prev (listing continues for review)"; ran_prev=0; continue; fi
            log checkpoint --arg step "$prev"
            notify "checkpoint after step $prev" "sign off the departures in $PLAN_REL, then run again to continue"
            echo "  $(wt_git log --oneline "$(wt_git merge-base main HEAD)..HEAD" | wc -l) commit(s) on $BRANCH — git -C $ROOT log main..$BRANCH"
            exit 4
        fi
        continue
    fi
    prev=$tok; ran_prev=0
    if [ "$(pr_step_num "$tok")" -lt "$FROM" ]; then continue; fi
    if pr_step_shipped "$PLAN_WT" "$tok"; then continue; fi
    if pr_step_decision_pending "$PLAN_WT" "$tok"; then
        STEP=$tok
        if [ "$DRY_RUN" = 1 ]; then echo; echo "step $tok — has an unanswered **Decision needed** block; a real run stops here"; pending=1; continue; fi
        say "step $tok has an unanswered **Decision needed** block — answer it (resume the last session, or edit the branch's plan and rename the block to **Decision taken**)"
        last_session=$(jq -r --arg s "$tok" 'select(.event=="needs_decision" and .step==$s) | .session' "$LOG" 2>/dev/null | tail -1 || true)
        [ -z "${last_session:-}" ] || { SESSION_ID=$last_session; echo "    $(resume_hint)"; }
        exit 2
    fi
    pending=1
    run_step "$tok"
    ran_prev=1
done

if [ "$pending" = 0 ]; then
    say "nothing to do — no unshipped step at or after step $FROM (a plan shipped by hand has no **Shipped** lines and would list every step; see --dry-run)"
    exit 0
fi
if [ "$DRY_RUN" = 1 ]; then exit 0; fi
notify "plan complete" "every step is shipped on $BRANCH"
echo; git -C "$ROOT" log --oneline "main..$BRANCH"; echo
behind=$(git -C "$ROOT" rev-list --count "$BRANCH..main" 2>/dev/null || echo 0)
if [ "$behind" = 0 ]; then
    echo "  fast-forward and push when you are happy:"
    echo "    git -C $ROOT checkout main && git -C $ROOT merge --ff-only $BRANCH && git -C $ROOT push"
else
    echo "  main moved on by $behind commit(s) while the plan ran — merge main into $BRANCH (or rebase), re-run the gate,"
    echo "  then: git -C $ROOT checkout main && git -C $ROOT merge --ff-only $BRANCH && git -C $ROOT push"
fi
exit 0
