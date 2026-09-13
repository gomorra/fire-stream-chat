#!/usr/bin/env bash
# Pure functions for the plan runner — sourced by scripts/run-plan.sh and by
# scripts/plan-runner/selfcheck.sh, which asserts them against fixtures/.
# Nothing in here runs git, gradle or claude; everything is text in, text out,
# so the parser, the tripwire and the result classifier can be tested without
# a session.
#
# Contract: .claude/plans/plan-runner.md §2.1 (Shipped / Decision blocks),
# §2.3 (tripwire), §2.4 (Order grammar, checkpoints), §2.5 (result handling),
# and the placeholder list at the top of scripts/plan-runner/step-prompt.md.

# ---- tripwire thresholds (§2.3) --------------------------------------------
PR_TRIPWIRE_LINES=600                       # changed lines above this → simplify
PR_TRIPWIRE_PATHS='(^|/)(data/crypto|data/worker|di)/'   # any hit → code-review
PR_TRIPWIRE_VIEWMODELS=2                    # this many *ViewModel.kt → code-review
# Paths whose change means the Gradle gate must be re-run by the driver.
PR_GATE_PATHS='^(app/|baselineprofile/|gradle/|build\.gradle|settings\.gradle|gradle\.properties|gradlew)'

# ---- small helpers ------------------------------------------------------------

# pr_csv_list <text>  → comma-separated items one per line, trimmed; `none` → nothing.
pr_csv_list() {
    local raw=$1
    [ -z "$raw" ] || [ "$raw" = none ] && return 0
    printf '%s' "$raw" | tr ',' '\n' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' | grep -v '^$' || true
}

# pr_join <newline-list> [<empty-word>]  → "a, b, c", or the empty-word when the list is empty.
pr_join() {
    local joined
    joined=$(printf '%s' "$1" | grep -v '^$' | paste -sd, - | sed 's/,/, /g' || true)
    printf '%s' "${joined:-${2:-}}"
}

# pr_step_num <step-id>  → the numeric prefix of a step id (`5a` → 5), for --from.
pr_step_num() { printf '%s' "$1" | grep -oE '^[0-9]+' || echo 0; }

# ---- Order line -------------------------------------------------------------

# pr_order_tokens <plan>
# Prints one token per line: a step id (`3`, `5a`) or CP for a ‖ checkpoint.
# Accepts every form the repo's plans use — `**Order: 1 → 2**`, `**Order:** 1 → 2`,
# `` `Order: 1 → 2` `` — and stops at the first token that is not part of the
# sequence, so prose after it is ignored. `+` (parallel) is flattened to
# sequential with one warning on stderr — v1 never runs steps in parallel.
# Fails when no line carries `Order:` or the sequence is empty.
pr_order_tokens() {
    local plan=$1 line rest warned=0 tok count=0
    line=$(grep -m1 -E '^[*`]*Order:' "$plan" || true)
    if [ -z "$line" ]; then
        echo "plan-runner: no 'Order:' line at the start of a line in $plan" >&2
        return 1
    fi
    rest=$(printf '%s' "$line" | tr -d '*`' | sed -E 's/^Order:[[:space:]]*//; s/→/ /g; s/‖/ CP /g; s/\+/ PLUS /g')
    # shellcheck disable=SC2086
    for tok in $rest; do
        case "$tok" in
            CP) echo CP; count=$((count + 1)) ;;
            PLUS)
                if [ "$warned" = 0 ]; then
                    echo "plan-runner: '+' in the Order line — parallel steps run sequentially in v1" >&2
                    warned=1
                fi ;;
            *)
                if printf '%s' "$tok" | grep -qE '^[0-9]+[a-z]?$'; then
                    echo "$tok"; count=$((count + 1))
                else
                    break   # prose after the sequence
                fi ;;
        esac
    done
    if [ "$count" = 0 ]; then
        echo "plan-runner: the Order line in $plan names no steps: $line" >&2
        return 1
    fi
}

# ---- step headings and sections --------------------------------------------

# A step id is followed by a non-alphanumeric so `Step 5` never matches `Step 5a` or `Step 50`.
pr_heading_re() { printf '^### Step %s([^0-9a-z]|$)' "$1"; }

# pr_step_heading <plan> <id>  → the `### Step n — …` line, tags included.
pr_step_heading() {
    grep -m1 -E "$(pr_heading_re "$2")" "$1" || {
        echo "plan-runner: no heading '### Step $2' in $1" >&2
        return 1
    }
}

# pr_step_section <plan> <id>  → the lines under the step heading, up to the
# next `##`/`###` heading (exclusive).
pr_step_section() {
    awk -v re="$(pr_heading_re "$2")" '
        /^##/ { if (on) exit; if ($0 ~ re) { on = 1; next } }
        on { print }
    ' "$1"
}

# pr_step_shipped <plan> <id>  → exit 0 when a **Shipped** line sits under the heading.
pr_step_shipped() {
    pr_step_section "$1" "$2" | grep -qE '^\*\*Shipped\*\*'
}

# pr_step_decision_pending <plan> <id>  → exit 0 when a **Decision needed** block
# is under the heading (an earlier attempt stopped; the human has not answered).
# An answered block is renamed **Decision taken** by the human and no longer blocks.
pr_step_decision_pending() {
    pr_step_section "$1" "$2" | grep -qE '^\*\*Decision needed\*\*'
}

# pr_shipped_commit <plan> <id>  → the hash named by the **Shipped** line, or nothing.
pr_shipped_commit() {
    pr_step_section "$1" "$2" | grep -m1 -E '^\*\*Shipped\*\*' | grep -oE '`[0-9a-f]{7,40}`' | head -1 | tr -d '`' || true
}

# pr_shipped_skills <plan> <id>  → the skills the **Shipped** line records, one
# per line (`skills: a, b.` — terminated by a period; `none` → nothing).
pr_shipped_skills() {
    local raw
    raw=$(pr_step_section "$1" "$2" | grep -m1 -E '^\*\*Shipped\*\*' | grep -oE 'skills:[[:space:]]*[^.]*' | head -1 | sed -E 's/^skills:[[:space:]]*//' || true)
    pr_csv_list "$raw"
}

# pr_last_shipped <plan> <tokens…>  → the last shipped step in Order sequence, or nothing.
pr_last_shipped() {
    local plan=$1 last='' tok; shift
    for tok in "$@"; do
        [ "$tok" = CP ] && continue
        if pr_step_shipped "$plan" "$tok"; then last=$tok; fi
    done
    [ -n "$last" ] && echo "$last"
    return 0
}

# ---- heading tags -----------------------------------------------------------

# pr_tag <heading> <key>  → the value of `key: …` up to the next `;` or end,
# trimmed; empty when absent.
pr_tag() {
    printf '%s' "$1" | grep -oE "(^|[^a-z])$2:[[:space:]]*[^;]*" | head -1 \
        | sed -E "s/^.*$2:[[:space:]]*//; s/[[:space:]]+\$//" || true
}

# pr_tag_skills <heading>  → skills from the `skills:` tag, one per line.
pr_tag_skills() { pr_csv_list "$(pr_tag "$1" skills)"; }

# pr_tag_tier <heading>  → max | strong | mid (untagged → mid); exit 1 on an unknown tier.
pr_tag_tier() {
    local t
    t=$(pr_tag "$1" model)
    case "${t:-mid}" in
        max|strong|mid) echo "${t:-mid}" ;;
        *) echo "plan-runner: unknown tier '$t' (want max|strong|mid)" >&2; return 1 ;;
    esac
}

# pr_tag_effort <heading> <default>  → the `effort:` tag or the default (the tier's
# effort); exit 1 on a value the CLI does not accept. Untagged steps follow the
# tier; the tag is for the steps where volume and subtlety disagree.
pr_tag_effort() {
    local e
    e=$(pr_tag "$1" effort)
    case "${e:-$2}" in
        low|medium|high|xhigh|max) echo "${e:-$2}" ;;
        *) echo "plan-runner: unknown effort '$e' (want low|medium|high|xhigh|max)" >&2; return 1 ;;
    esac
}

# pr_tag_budget <heading> <default>  → the `budget:` tag or the default.
pr_tag_budget() {
    local b
    b=$(pr_tag "$1" budget | grep -oE '^[0-9]+(\.[0-9]+)?' || true)
    echo "${b:-$2}"
}

# pr_tier_rank <tier> → 0 mid, 1 strong, 2 max
pr_tier_rank() { case "$1" in mid) echo 0 ;; strong) echo 1 ;; max) echo 2 ;; esac; }

# pr_cap_tier <tier> <cap>  → the lower of the two ('' cap → unchanged).
pr_cap_tier() {
    local tier=$1 cap=${2:-}
    if [ -z "$cap" ] || [ "$(pr_tier_rank "$tier")" -le "$(pr_tier_rank "$cap")" ]; then
        echo "$tier"
    else
        echo "$cap"
    fi
}

# ---- tripwire (§2.3) --------------------------------------------------------

# pr_tripwire <numstat-file> <names-file>  → required skills, one per line.
#   numstat-file: `git diff --numstat start..end`
#   names-file:   `git diff --name-only start..end`
pr_tripwire() {
    local numstat=$1 names=$2 lines vms
    lines=$(awk '$1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ { s += $1 + $2 } END { print s + 0 }' "$numstat")
    if [ "$lines" -gt "$PR_TRIPWIRE_LINES" ]; then echo simplify; fi
    vms=$(grep -cE 'ViewModel\.kt$' "$names" || true)
    if grep -qE "$PR_TRIPWIRE_PATHS" "$names" || [ "${vms:-0}" -ge "$PR_TRIPWIRE_VIEWMODELS" ]; then
        echo code-review
    fi
}

# pr_needs_gate <names-file>  → exit 0 when the diff touches something the
# Gradle gate can see; a docs-only step does not earn a ten-minute test run.
pr_needs_gate() {
    grep -qE "$PR_GATE_PATHS" "$1"
}

# pr_union <list-a> <list-b>  → newline lists merged, deduplicated, in order.
pr_union() {
    printf '%s\n%s\n' "$1" "$2" | grep -v '^$' | awk '!seen[$0]++' || true
}

# pr_missing <required-list> <run-list>  → items of required not in run.
pr_missing() {
    local req=$1 run=$2 r
    while IFS= read -r r; do
        [ -z "$r" ] && continue
        printf '%s\n' "$run" | grep -qxF "$r" || echo "$r"
    done <<< "$req"
}

# ---- result classification (§2.5) -----------------------------------------

# pr_result_kind <result-file>  → complete | incomplete | budget | failed
# The result object `claude -p --output-format json --json-schema …` writes,
# confirmed live on 2026-09-13 (this build):
#   { "type":"result", "subtype":"success", "is_error":false, "terminal_reason":"completed",
#     "session_id":"…", "num_turns":2, "total_cost_usd":0.059, "permission_denials":[],
#     "result":"<json text>", "structured_output":{…}, … }
# Budget exhaustion: "subtype":"error_max_budget_usd", "is_error":true,
#   "terminal_reason":"budget_exhausted", "structured_output":null.
#   complete   — success, not an error, structured_output is an object
#   incomplete — success but no structured object (the session ended without the
#                result); worth exactly one fix-forward nudge
#   budget     — budget or turn limit hit; blocked at once, never resumed
#   failed     — anything else (no file, no JSON, API error, execution error)
pr_result_kind() {
    local f=$1 subtype is_error so
    [ -s "$f" ] && jq -e . "$f" >/dev/null 2>&1 || { echo failed; return; }
    # `//` treats false as missing, so booleans go through tostring.
    subtype=$(jq -r '.subtype // "null"' "$f"); is_error=$(jq -r '.is_error | tostring' "$f")
    so=$(jq -r '.structured_output | type' "$f")
    case "$subtype" in error_max_budget_usd|error_max_turns) echo budget; return ;; esac
    if [ "$subtype" = success ] && [ "$is_error" = false ]; then
        if [ "$so" = object ]; then echo complete; else echo incomplete; fi
    else
        echo failed
    fi
}

# ---- checkpoints (§2.4) -----------------------------------------------------

# pr_checkpoint_due <log-file> <step-id> <ran-now 0|1>  → exit 0 when the ‖ after
# <step> must stop this run. Due when the step shipped in this invocation, or
# when the runner had a hand in it (any log event for the step — a launch, a
# needs_decision the human then finished by resuming) and no `checkpoint`
# event has been logged for it yet. A step the runner never touched (shipped
# by hand, no log) passes: the human already had that pause.
pr_checkpoint_due() {
    local log=$1 step=$2 ran_now=$3
    [ "$ran_now" = 1 ] && return 0
    [ -f "$log" ] || return 1
    jq -e --arg s "$step" 'select(.step == $s)' "$log" >/dev/null 2>&1 || return 1
    if jq -e --arg s "$step" 'select(.step == $s and .event == "checkpoint")' "$log" >/dev/null 2>&1; then
        return 1
    fi
    return 0
}

# ---- prompt rendering -------------------------------------------------------

# pr_render <template-file> KEY=VALUE…  → the template with every {{KEY}}
# replaced by VALUE (values may contain anything, including newlines) and the
# leading <!-- … --> header comment removed.
pr_render() {
    local tpl=$1 out kv key val; shift
    out=$(awk 'BEGIN{skip=1} skip && /-->/ {skip=0; next} !skip {print}' "$tpl")
    for kv in "$@"; do
        key=${kv%%=*}
        val=${kv#*=}
        out=${out//"{{$key}}"/"$val"}
    done
    printf '%s\n' "$out"
}

# pr_unfilled <rendered-text>  → any {{PLACEHOLDER}} left over, one per line.
pr_unfilled() {
    printf '%s' "$1" | grep -oE '\{\{[A-Z_]+\}\}' | sort -u || true
}
