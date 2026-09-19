#!/usr/bin/env bash
# Plan runner — runs a multi-step plan unattended, one fresh headless Claude
# session per step, in a dedicated worktree. Design and contract:
# docs/plans/done/plan-runner.md. Run it from a terminal, not from inside a
# Claude session.
#
#   scripts/run-plan.sh <plan.md> [--from N] [--dry-run] [--cap max|strong|mid] [--budget USD]
#                                 [--variant NAME] [--base REF]
#
#   --variant NAME  load scripts/plan-runner/variants/NAME.env over the tunables below and run
#                   on plan/<name>-NAME (own worktree, own log) — two configurations of one
#                   plan side by side. Pass it again on every re-run of that variant.
#   --base REF      the commit a new plan branch starts from (default: main)
#
# Exit codes: 0 all steps shipped (or dry run) · 1 usage/config error ·
#             2 a decision is needed · 3 a step is blocked · 4 stopped at a ‖ checkpoint
#
# The runner never commits and never pushes. Every commit on plan/<name> is a
# step session's. The one thing it changes in the worktree: to escalate a failed
# step it keeps the attempt (branch + patch + tarball), then resets and cleans. State lives in the plan file (**Shipped** blocks) and in the
# gitignored run log docs/plans/.runs/<name>.log (one JSON object per line;
# scripts/plan-runner/report.sh turns it into a per-step table).
set -Eeuo pipefail
# A driver crash must not look like a decision (2) or a block (3): report and exit 1.
trap 'echo "plan-runner: internal error at line $LINENO (exit $?) — this is a driver bug, not a step result" >&2; exit 1' ERR

# ---- tunables (the only place model ids and effort levels live) -------------
# A --variant file may override the keys lib.sh's PR_VARIANT_KEYS lists, nothing else.
MODEL_MAX=fable;     EFFORT_MAX=xhigh;    ADVISOR_MAX=''      # a step's `effort:` tag overrides the tier's effort
MODEL_STRONG=opus;   EFFORT_STRONG=xhigh; ADVISOR_STRONG=''   # ADVISOR_*: `claude --advisor`, '' = none
MODEL_MID=sonnet;    EFFORT_MID=high;     ADVISOR_MID=''
JUDGE_MODEL='';      JUDGE_EFFORT=high    # '' = no judge pass; see plan-runner/judge-prompt.md
ESCALATE=1                 # 1 = a step that stays invalid after its nudge is re-run once, one effort rung up
DEFAULT_BUDGET_USD=25      # per step; a `budget:` heading tag overrides, --budget overrides both
NUDGE_BUDGET_USD=5         # the single fix-forward resume a step may get
REVIEW_BUDGET_USD=8        # the fresh review session that stands in for the nudge when only skills are missing
JUDGE_BUDGET_USD=5
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

PLAN_ARG=''; FROM=1; DRY_RUN=0; CAP=''; BUDGET_OVERRIDE=''; VARIANT=''; BASE_REF=''
while [ $# -gt 0 ]; do
    case "$1" in
        --from)    need_arg "$@"; FROM=$2; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --cap)     need_arg "$@"; CAP=$2; shift 2 ;;
        --budget)  need_arg "$@"; BUDGET_OVERRIDE=$2; shift 2 ;;
        --variant) need_arg "$@"; VARIANT=$2; shift 2 ;;
        --base)    need_arg "$@"; BASE_REF=$2; shift 2 ;;
        -h|--help) usage 0 ;;
        -*)        echo "unknown flag $1" >&2; usage 1 ;;
        *)         [ -z "$PLAN_ARG" ] || usage 1; PLAN_ARG=$1; shift ;;
    esac
done
[ -n "$PLAN_ARG" ] || usage 1
case "$CAP" in ''|max|strong|mid) ;; *) echo "--cap wants max|strong|mid" >&2; exit 1 ;; esac
[[ "$FROM" =~ ^[0-9]+$ ]] || { echo "--from wants a step number" >&2; exit 1; }
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
if [ -n "$BASE_REF" ]; then
    git -C "$ROOT" rev-parse --verify --quiet "$BASE_REF^{commit}" >/dev/null || { echo "--base: '$BASE_REF' is not a commit" >&2; exit 1; }
fi

# A driver launched from inside a Claude session would nest; scrub the markers
# so the step sessions start clean either way.
unset CLAUDECODE CLAUDE_CODE_ENTRYPOINT CLAUDE_CODE_SESSION_ID CLAUDE_CODE_CHILD_SESSION \
      CLAUDE_CODE_SSE_PORT CLAUDE_CODE_MESSAGING_SOCKET CLAUDE_CODE_MESSAGING_TOKEN \
      CLAUDE_CODE_BRIDGE_SESSION_ID CLAUDE_PID CLAUDE_EFFORT 2>/dev/null || true
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
    NUDGED=0; SESSION_ID=''; RESULT_FILE=''; ATTEMPT=1; ATTEMPT_BLOCK=''; RUN_SKILLS=''; PRESTATE=''
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

claude_args() { # claude_args <budget> [--resume <id>]  → fills CLAUDE_ARGS
    CLAUDE_ARGS=(-p --output-format json --json-schema "$(cat "$SCHEMA")"
        --model "$MODEL" --effort "$EFFORT" --max-budget-usd "$1"
        --permission-mode "$PERMISSION_MODE"
        --allowedTools "${ALLOWED_TOOLS[@]}" --disallowedTools "${DISALLOWED_TOOLS[@]}"
        -n "plan $RUN_ID step $STEP")
    if [ -n "$ADVISOR" ]; then CLAUDE_ARGS+=(--advisor "$ADVISOR"); fi
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
        --argjson models "$(pr_result_json "$RESULT_FILE" '.modelUsage | map_values(.costUSD)' '{}')" \
        --argjson consults "$(pr_result_json "$RESULT_FILE" .structured_output.advisorConsults null)" \
        --arg file "$(rel "$RESULT_FILE")"
}

resume_hint() { printf 'cd %q && claude --resume %s\n' "$WT" "$SESSION_ID"; }

# The skills every `done` result of this attempt reports, accumulated: the fresh review
# session reports only what it ran itself, and the floor was run by the session before it.
note_run_skills() {
    RUN_SKILLS=$(pr_union "$RUN_SKILLS" "$(jq -r 'select(.structured_output.status == "done") | .structured_output.skills.run[]?' "$RESULT_FILE" 2>/dev/null || true)")
}

# validate_step → prints reasons (empty = valid). Uses START_SHA, RUN_SKILLS, FLOOR.
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
    RESULT_FILE=$RUNS/$RUN_ID.step$STEP.$(date +%Y%m%d-%H%M%S).nudge.result.json
    run_claude "$RESULT_FILE" "$1"$'\n\n'"Then end again with the JSON result object."
    log_result
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
    claude_args "$REVIEW_BUDGET_USD"
    RESULT_FILE=$RUNS/$RUN_ID.step$STEP.$(date +%Y%m%d-%H%M%S).review.result.json
    run_claude "$RESULT_FILE" "$prompt"
    log_result
}

# escalate <why>  — the one fresh re-run a step may get once its nudge is spent: the failed
# attempt is kept (its commits on a plan-attempts/ branch, its uncommitted work as a patch and
# a tarball in the runs dir), the worktree goes back to START_SHA, and the step starts over one
# effort rung up with <why> in its prompt. "Run low, re-run the failures higher" is the cheapest
# policy on the effort curve, but only where a checker names the failures — so this follows a
# red gate or a failed validation, never a needs_decision, a spent budget, or a `blocked`
# that is not `gate`. Sets RESULT_FILE / SESSION_ID to the new attempt's.
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
    local names head out prompt jwt dirty
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
    (cd "$jwt" && claude -p --output-format json --json-schema "$(cat "$JUDGE_SCHEMA")" \
        --model "$JUDGE_MODEL" --effort "$JUDGE_EFFORT" --max-budget-usd "$JUDGE_BUDGET_USD" \
        --permission-mode default --allowedTools "${JUDGE_ALLOWED_TOOLS[@]}" --disallowedTools "${JUDGE_DISALLOWED_TOOLS[@]}" \
        -n "plan review step $STEP" "$prompt" > "$out" 2> "$out.stderr") || true
    dirty=''
    if [ "$(git -C "$jwt" rev-parse HEAD)" != "$head" ] || [ -n "$(git -C "$jwt" status --porcelain)" ]; then dirty=1; fi
    git -C "$ROOT" worktree remove --force "$jwt" || true
    if [ -n "$dirty" ] || [ "$(pr_result_kind "$out")" != complete ]; then
        log judge_failed --arg step "$STEP" --arg file "$(rel "$out")" --argjson cost "$(pr_result_json "$out" .total_cost_usd 0)" \
            --arg why "$([ -n "$dirty" ] && echo "the judge wrote to its worktree — grade dropped" || echo "no usable result ($(pr_result_field "$out" .subtype))")"
        say "warning: step $STEP has no grade — $([ -n "$dirty" ] && echo "the judge wrote to its (throwaway) worktree" || echo "the judge ended without a usable result"); the plan goes on"
        return 0
    fi
    log judged --arg step "$STEP" --arg session "$(pr_result_field "$out" .session_id)" \
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
        case "$(pr_result_kind "$RESULT_FILE")" in
            budget) stop_blocked "budget or turn limit exhausted ($(result_get .subtype)) — not resumed" ;;
            failed) stop_blocked "session ended without a usable result ($(result_get .subtype); stderr in $(rel "$RESULT_FILE").stderr)" ;;
            incomplete)
                nudge "Your session ended without the JSON result object the prompt requires. If the step is done, report it; if not, finish it first."
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

# launch — one fresh session for the current attempt; sets RESULT_FILE and SESSION_ID.
launch() {
    write_prompt
    claude_args "$BUDGET"
    RESULT_FILE=$RUNS/$RUN_ID.step$STEP.$STAMP.result.json
    log launched --arg step "$STEP" --arg start "$START_SHA" --arg tier "$TIER" --arg tagged "$TAGGED_TIER" \
        --arg model "$MODEL" --arg effort "$EFFORT" --arg advisor "${ADVISOR:-none}" --argjson attempt "$ATTEMPT" \
        --arg base "$(wt_git merge-base main HEAD | cut -c1-8)" \
        --arg budget "$BUDGET" --arg prompt "$(rel "$PROMPT_FILE")"
    say "step $STEP → $MODEL/$EFFORT${ADVISOR:+ + advisor $ADVISOR}, attempt $ATTEMPT, budget \$$BUDGET, floor $(pr_join "$FLOOR" none)"
    run_claude "$RESULT_FILE" "$PROMPT"
    SESSION_ID=$(result_get .session_id)
    log_result
    say "step $STEP session $SESSION_ID: $(result_get .subtype), status $(result_get .structured_output.status), \$$(result_get .total_cost_usd), denials $(pr_result_json "$RESULT_FILE" '.permission_denials | length' '?')"
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
    launch
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
            echo "  per-step cost, turns, nudges and grades: scripts/plan-runner/report.sh $RUN_ID"
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
exit 0
