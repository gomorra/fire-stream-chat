#!/usr/bin/env bash
# Pure functions for the plan runner — sourced by scripts/run-plan.sh and by
# scripts/plan-runner/selfcheck.sh, which asserts them against fixtures/.
# Nothing in here runs git, gradle or claude; everything is text in, text out,
# so the parser, the tripwire and the result classifier can be tested without
# a session.
#
# Contract: docs/plans/done/plan-runner.md §2.1 (Shipped / Decision blocks),
# §2.3 (tripwire), §2.4 (Order grammar, checkpoints), §2.5 (result handling),
# and the placeholder list at the top of scripts/plan-runner/step-prompt.md.
# §5 (addendum) covers the tripwire's test exclusion, nudge routing, escalation and variants.

# ---- tripwire thresholds (§2.3) --------------------------------------------
PR_TRIPWIRE_LINES=600                       # changed lines above this → simplify
PR_TRIPWIRE_DIRS='data/crypto data/worker di'             # a changed file under any of these → code-review
PR_TRIPWIRE_PATHS="(^|/)(${PR_TRIPWIRE_DIRS// /|})/"      # the regex and the prompt text both come from the list
PR_TRIPWIRE_VIEWMODELS=2                    # this many *ViewModel.kt → code-review
# Test sources never trip the path rule: a one-line `@Config` edit in SignalManagerTest.kt
# once forced a $1.77 code-review of a minSdk bump (call-audio-routes step 1, 2026-09-14).
PR_TRIPWIRE_SKIP='(^|/)src/(test|androidTest)/'
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

# pr_step_has <plan> <id> <ERE>  → exit 0 when a line under the step heading matches.
# The section is captured and matched from a here-string, never through a pipe.
# A reader that stops at its first match kills a piped writer with SIGPIPE.
# Under pipefail the match then reads as a miss, at random.
pr_step_has() {
    local section
    section=$(pr_step_section "$1" "$2")
    grep -qE "$3" <<< "$section"
}

# pr_step_shipped <plan> <id>  → exit 0 when a **Shipped** line sits under the heading.
pr_step_shipped() { pr_step_has "$1" "$2" '^\*\*Shipped\*\*'; }

# pr_step_decision_pending <plan> <id>  → exit 0 when a **Decision needed** block
# is under the heading (an earlier attempt stopped; the human has not answered).
# An answered block is renamed **Decision taken** by the human and no longer blocks.
pr_step_decision_pending() { pr_step_has "$1" "$2" '^\*\*Decision needed\*\*'; }

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
    local numstat=$1 names=$2 lines vms prod
    lines=$(awk '$1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ { s += $1 + $2 } END { print s + 0 }' "$numstat")
    if [ "$lines" -gt "$PR_TRIPWIRE_LINES" ]; then echo simplify; fi
    prod=$(grep -vE "$PR_TRIPWIRE_SKIP" "$names" || true)       # both rules look at production sources only
    vms=$(grep -cE 'ViewModel\.kt$' <<< "$prod" || true)
    if grep -qE "$PR_TRIPWIRE_PATHS" <<< "$prod" || [ "${vms:-0}" -ge "$PR_TRIPWIRE_VIEWMODELS" ]; then
        echo code-review
    fi
}

# pr_tripwire_rules  → the rules above as prompt text, so a step session can apply them
# itself instead of learning them from a rejected result. Built from the same constants.
pr_tripwire_rules() {
    local dirs
    dirs="\`${PR_TRIPWIRE_DIRS// //\`, \`}/\`"
    printf '%s\n' \
        "  - more than $PR_TRIPWIRE_LINES changed lines (added + deleted in \`git diff --numstat\`) → \`simplify\`" \
        "  - among the changed production files (test sources do not count): any under $dirs, or $PR_TRIPWIRE_VIEWMODELS or more \`*ViewModel.kt\` → \`code-review\`"
}

# pr_needs_gate <names-file>  → exit 0 when the diff touches something the
# Gradle gate can see; a docs-only step does not earn a ten-minute test run.
pr_needs_gate() {
    grep -qE "$PR_GATE_PATHS" "$1"
}

# pr_has_code <names-file>  → exit 0 when the diff changes anything that is not
# documentation — what the judge grades. Wider than pr_needs_gate on purpose:
# functions/ and scripts/ are code the Gradle gate cannot see.
pr_has_code() {
    grep -qvE '(\.md$|^docs/)' "$1"
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
        grep -qxF "$r" <<< "$run" || echo "$r"     # a here-string, for the reason at pr_step_has
    done <<< "$req"
}

# pr_only_skill_reasons <reasons>  → exit 0 when every validation reason is a missed
# skill. Such a result is repaired by a fresh review session on the diff, not by
# resuming the step session and re-reading its whole context on every turn.
pr_only_skill_reasons() {
    local lines
    lines=$(printf '%s\n' "$1" | grep -v '^$' || true)
    [ -n "$lines" ] && ! grep -qvE '^required skills not run:' <<< "$lines"
}

# pr_missing_from_reasons <reasons>  → the skills a "required skills not run: a, b (…)"
# reason names, one per line.
pr_missing_from_reasons() {
    pr_csv_list "$(printf '%s\n' "$1" | grep -m1 -E '^required skills not run:' \
        | sed -E 's/^required skills not run:[[:space:]]*//; s/[[:space:]]*\(.*$//' || true)"
}

# ---- escalation and the test count -------------------------------------------

# pr_next_effort <effort>  → one rung up the CLI's effort ladder; exit 1 at the top.
pr_next_effort() {
    case "$1" in
        low) echo medium ;; medium) echo high ;; high) echo xhigh ;; xhigh) echo max ;;
        *) return 1 ;;
    esac
}

# pr_sum_counts  → stdin is one number per line (`git grep -hc`); prints the sum.
pr_sum_counts() { awk '$1 ~ /^[0-9]+$/ { s += $1 } END { print s + 0 }'; }

# ---- variants ------------------------------------------------------------------

# A variant file may set these keys and nothing else — a typo must fail the launch,
# not silently run the default configuration under the variant's name.
PR_VARIANT_KEYS='MODEL_MAX|MODEL_STRONG|MODEL_MID|EFFORT_MAX|EFFORT_STRONG|EFFORT_MID|ADVISOR_MAX|ADVISOR_STRONG|ADVISOR_MID|JUDGE_MODEL|JUDGE_EFFORT|ESCALATE'

# pr_variant_check <file>  → prints every line that is not a comment, blank, or
# `KEY=value` with a known key and a plain value; empty output = loadable.
pr_variant_check() {
    grep -vE "^[[:blank:]]*(#.*)?\$|^($PR_VARIANT_KEYS)=[A-Za-z0-9._-]*([[:blank:]]+#.*)?[[:blank:]]*\$" "$1" || true   # [[:blank:]], not [[:space:]]: a CR must not pass
}

# ---- result classification (§2.5) -----------------------------------------

# pr_result_kind <result-file>  → complete | incomplete | budget | usage_limit | failed
# The result object `claude -p --output-format json --json-schema …` writes,
# confirmed live on 2026-09-13 (this build):
#   { "type":"result", "subtype":"success", "is_error":false, "terminal_reason":"completed",
#     "session_id":"…", "num_turns":2, "total_cost_usd":0.059, "permission_denials":[],
#     "result":"<json text>", "structured_output":{…}, … }
# Sessions run with --output-format stream-json --verbose, and the result file is the stream's last
# `result` line (pr_stream_result). That line has the same fields, structured_output included, and
# its total_cost_usd counts the whole session across --resume. Confirmed by a probe on CLI 2.1.288
# (2026-10-03 23:51 UTC, fixtures/stream-probe.jsonl).
# Budget exhaustion: "subtype":"error_max_budget_usd", "is_error":true,
#   "terminal_reason":"budget_exhausted", "structured_output":null.
# API error: "subtype":"success", "is_error":true, "terminal_reason":"api_error",
#   "api_error_status":<http>, "result":"<the CLI's message>". The subtype says success;
#   is_error is what marks it (confirmed 2026-10-03).
# Usage limit: the same with "api_error_status":429. Read from the CLI's code by a desktop session on
#   2026-10-03 (CLI 2.1.263–2.1.278); no headless limit result has been seen live
#   (fixtures/result-usage-limit.json). The driver also reads the stream and the transcript for it.
#   complete    — success, not an error, structured_output is an object
#   incomplete  — success but no structured object (the session ended without the
#                 result); worth exactly one fix-forward nudge
#   budget      — budget or turn limit hit; blocked at once, never resumed
#   usage_limit — an API error with HTTP 429: the driver waits for the reset and resumes the session
#   failed      — anything else (no file, no JSON, API error, execution error); say why with pr_result_error
pr_result_kind() {
    local f=$1 subtype is_error so
    [ -s "$f" ] && jq -e . "$f" >/dev/null 2>&1 || { echo failed; return; }
    # `//` treats false as missing, so booleans go through tostring.
    subtype=$(jq -r '.subtype // "null"' "$f"); is_error=$(jq -r '.is_error | tostring' "$f")
    so=$(jq -r '.structured_output | type' "$f")
    case "$subtype" in error_max_budget_usd|error_max_turns) echo budget; return ;; esac
    if [ "$is_error" = true ] && [ "$(pr_result_field "$f" .api_error_status)" = 429 ]; then echo usage_limit; return; fi
    if [ "$subtype" = success ] && [ "$is_error" = false ]; then
        if [ "$so" = object ]; then echo complete; else echo incomplete; fi
    else
        echo failed
    fi
}

# pr_result_error <result-file>  → one line on why a `failed` result failed: the subtype
# (left out when it is "success"), terminal_reason, HTTP status, then the CLI's own message
# from `result`, cut to 300 characters. Never fails, never prints nothing.
pr_result_error() {
    local out
    [ -s "$1" ] || { printf 'the CLI wrote no result'; return; }
    out=$(jq -r '([(.subtype | select(. != "success")), .terminal_reason, (.api_error_status | select(. != null) | "HTTP \(.)")]
            | map(select(. != null and . != "")) | join(", ")) as $why
        | (.result // "" | tostring | gsub("\\s+"; " ") | if length > 300 then .[0:300] + "…" else . end) as $msg
        | [$why, $msg] | map(select(. != "")) | join(": ")' "$1" 2>/dev/null || true)
    printf '%s' "${out:-the CLI output is not a result object}"
}

# pr_result_field <file> <jq-path> [<default>]  → the field as text, or the default
# ("null" unless given) when the file is missing, empty, not JSON, or the field is
# null. Never fails, never prints nothing — safe inside $(…) under set -e.
pr_result_field() {
    local out
    out=$(jq -r "$2 | if . == null then empty else tostring end" "$1" 2>/dev/null || true)
    printf '%s' "${out:-${3:-null}}"
}

# pr_result_json <file> <jq-path> <default-json>  → the field as compact JSON, or the
# default when absent — for `jq --argjson`, which rejects an empty string.
pr_result_json() {
    local out
    out=$(jq -c "$2 // empty" "$1" 2>/dev/null || true)
    printf '%s' "${out:-$3}"
}

# ---- streams, transcripts and the usage limit ------------------------------------
# A session's stream (`--output-format stream-json --verbose`) is one JSON object per line and carries
# every tool output, so it grows to megabytes. Each reader picks its candidate lines with grep -F and
# parses only those. A tool output sits in the stream as an escaped string, so its quotes are `\"` and
# a fixed-string match on `"key"` never hits it. Never fails, never stops a pipe early (GOTCHAS: pipefail).

# pr_stream_result <stream>  → the stream's last line whose type is `result` (the object the json
# output format prints), compact; nothing when the session ended without one.
pr_stream_result() {
    local cand
    [ -f "$1" ] || return 0
    cand=$(grep -F '"result"' "$1" || true)
    [ -n "$cand" ] || return 0
    jq -cnR 'last(inputs | fromjson? | objects | select(.type == "result")) // empty' <<< "$cand" 2>/dev/null || true
}

# pr_session_id <stream>  → the session id from the `system`/`init` line, else from the result line;
# nothing when the session stopped before either.
pr_session_id() {
    local cand sid=''
    [ -f "$1" ] || return 0
    cand=$(grep -F '"init"' "$1" || true)
    if [ -n "$cand" ]; then
        sid=$(jq -rnR 'first(inputs | fromjson? | objects | select(.type == "system" and .subtype == "init") | .session_id | strings) // empty' <<< "$cand" 2>/dev/null || true)
    fi
    [ -n "$sid" ] || sid=$(jq -r '.session_id // empty' <<< "$(pr_stream_result "$1")" 2>/dev/null || true)
    printf '%s' "$sid"
}

# pr_stream_limit <stream>  → the usage-window state of the newest `rate_limit_info`, at any depth:
#   "limited <resetsAt>" when the window is used up — status "rejected", or a unified window at
#   utilization ≥ 1. The reset is the latest resetsAt among the full windows, else the top-level one;
#   plain "limited" when neither has one. "open" otherwise; nothing when the stream has no such object.
# Its fields, as seen in real streams: status, rateLimitType, resetsAt (epoch s), isUsingOverage,
# overageStatus, unifiedWindows.<name>.{utilization, resetsAt} (2026-10-03).
pr_stream_limit() {
    local cand
    [ -f "$1" ] || return 0
    cand=$(grep -F '"rate_limit_info"' "$1" || true)
    [ -n "$cand" ] || return 0
    jq -rnR '(last(inputs | fromjson? | [.. | objects | .rate_limit_info? | objects] | last // empty) // empty)
        | ([.unifiedWindows // {} | .[]? | objects | select((.utilization // 0) >= 1)]) as $full
        | if .status == "rejected" or ($full | length) > 0
          then "limited" + (([$full[].resetsAt | numbers] | max) // (.resetsAt | numbers) // "" | tostring | if . == "" then "" else " " + . end)
          else "open" end' <<< "$cand" 2>/dev/null || true
}

# pr_transcript_usage_limit <transcript.jsonl>  → exit 0 when the transcript's last main-chain message
# is an assistant entry that is a usage-limit stop (`error: "rate_limit"`), and print its
# quotaLimits.resetsAt (epoch s; nothing when absent). Exit 1 otherwise. A user entry after the stop is
# a resume's prompt: that resume has not stopped there. A sidechain (sub-agent) entry is not the
# session's own turn. Field names as recorded in 27 real entries (CLI 2.1.263–2.1.278): error,
# apiErrorStatus, quotaLimits.resetsAt.
pr_transcript_usage_limit() {
    local out
    [ -f "$1" ] || return 1
    grep -qF '"rate_limit"' "$1" || return 1      # a file, not a pipe: grep -q stopping early is safe here
    out=$(jq -nrR 'reduce (inputs | fromjson? | objects | select((.type == "assistant" or .type == "user") and .isSidechain != true)) as $e (null; $e)
        | select(.type? == "assistant" and .error? == "rate_limit")
        | "rate_limit " + ([.. | objects | .quotaLimits? | objects | .resetsAt? | numbers] | first // "" | tostring)' "$1" 2>/dev/null || true)
    [ -n "$out" ] || return 1
    out=${out#rate_limit}
    printf '%s' "${out# }"
}

# pr_fmt_duration <seconds>  → "45s", "59m", "1h 05m", "3d 1h"; a negative duration is "0s".
pr_fmt_duration() {
    local s=${1%.*}
    [ "$s" -ge 0 ] 2>/dev/null || s=0
    if [ "$s" -lt 60 ]; then printf '%ss' "$s"
    elif [ "$s" -lt 3600 ]; then printf '%sm' "$((s / 60))"
    elif [ "$s" -lt 86400 ]; then printf '%sh %02dm' "$((s / 3600))" "$((s % 3600 / 60))"
    else printf '%sd %sh' "$((s / 86400))" "$((s % 86400 / 3600))"; fi
}

# pr_cost_delta <total> <previous>  → what one invocation added to its session's cost, from the session's
# total_cost_usd now and at its previous result. A resumed session reports its cost so far (CLI 2.1.288),
# so the increase is the difference. A total below the previous one is a per-invocation figure from an
# older CLI, and counts whole.
pr_cost_delta() {
    awk -v t="$1" -v p="$2" 'BEGIN { d = (t + 0 >= p + 0) ? t - p : t + 0; s = sprintf("%.6f", d); sub(/0+$/, "", s); sub(/\.$/, "", s); print s }'
}

# pr_budget_left <budget> <spent>  → the --max-budget-usd of a resumed session: what is left of its
# budget, at least 1. The flag counts per invocation.
pr_budget_left() {
    awk -v b="$1" -v s="$2" 'BEGIN { l = b - s; if (l < 1) l = 1; o = sprintf("%.2f", l); sub(/0+$/, "", o); sub(/\.$/, "", o); print o }'
}

# ---- checkpoints (§2.4) -----------------------------------------------------

# pr_checkpoint_due <log-file> <step-id>  → exit 0 when the ‖ after a step shipped in
# an earlier invocation must stop this run (the driver's checkpoint_due covers the
# steps of this invocation and the frontier). Due when the runner had a hand in the
# step (any log event for it — a launch, a needs_decision the human then finished by
# resuming) and no `checkpoint` event has been logged for it yet. A step the runner
# never touched (shipped by hand, no log) passes: the human already had that pause.
pr_checkpoint_due() {
    local log=$1 step=$2
    [ -f "$log" ] || return 1
    jq -e --arg s "$step" 'select(.step == $s)' "$log" >/dev/null 2>&1 || return 1
    if jq -e --arg s "$step" 'select(.step == $s and .event == "checkpoint")' "$log" >/dev/null 2>&1; then
        return 1
    fi
    return 0
}

# pr_shipped_after <plan> <index> <tokens…>  → exit 0 when a step after position <index>
# (0-based) of the Order tokens is shipped. A ‖ with a shipped step to its right is behind
# the frontier: an earlier invocation went past it, so it never stops a run again.
pr_shipped_after() {
    local plan=$1 i=$2 tok; shift 2
    [ $# -gt "$i" ] || return 1
    shift "$((i + 1))"
    for tok in "$@"; do
        [ "$tok" = CP ] && continue
        if pr_step_shipped "$plan" "$tok"; then return 0; fi
    done
    return 1
}

# ---- plan sync -----------------------------------------------------------------
# The driver merges the plan file from the sync ref into the branch's copy at a step boundary.
# Only the plan: main's code would change what the gate and the diff checks measure mid-run.

# pr_merge_inserts <merge-file>  → <merge-file> is `git merge-file -p --zdiff3 --marker-size=13`
# output. A conflict whose base part is empty is a same-spot insertion: a **Shipped** block on
# the branch and a new step on the sync ref, both written right after the same line. It is
# placed: the branch's lines, a blank line where neither side has one, then the ref's lines.
# Any other conflict stays marked, and the exit code is 1. Literal markers, not `<{13}`:
# mawk has no interval expressions.
pr_merge_inserts() {
    awk '
        function marker(c) { return substr($0, 1, 13) == c && (length($0) == 13 || substr($0, 14, 1) == " ") }
        BEGIN { L = "<<<<<<<<<<<<<"; B = "|||||||||||||"; S = "============="; R = ">>>>>>>>>>>>>" }
        state == 0 && marker(L) { state = 1; head = $0; no = 0; nb = 0; nt = 0; hasbase = 0; next }
        state == 1 && marker(B) { state = 2; bhead = $0; hasbase = 1; next }
        (state == 1 || state == 2) && $0 == S { state = 3; next }
        state == 3 && marker(R) {
            state = 0
            if (hasbase && nb == 0) {
                for (i = 1; i <= no; i++) print ours[i]
                if (no > 0 && nt > 0 && ours[no] != "" && theirs[1] != "") print ""
                for (i = 1; i <= nt; i++) print theirs[i]
            } else {
                bad = 1; print head
                for (i = 1; i <= no; i++) print ours[i]
                if (hasbase) { print bhead; for (i = 1; i <= nb; i++) print base[i] }
                print S
                for (i = 1; i <= nt; i++) print theirs[i]
                print
            }
            next
        }
        state == 1 { ours[++no] = $0; next }
        state == 2 { base[++nb] = $0; next }
        state == 3 { theirs[++nt] = $0; next }
        { print }
        END { exit bad }
    ' "$1"
}

# pr_plan_check <plan> [<before>]  → prints what keeps <plan> from being run, one problem per
# line; exit 1 when there is any. The Order line must parse, and every step it names must have
# a heading whose tier and effort tags are valid. With <before> (the branch's copy before a
# merge), every step shipped there must still be shipped here, naming the same commit.
pr_plan_check() {
    local plan=$1 before=${2:-} tokens tok h id out=''
    if ! tokens=$(pr_order_tokens "$plan" 2>/dev/null); then
        out+="no Order line that names a step"$'\n'
    else
        for tok in $tokens; do
            [ "$tok" = CP ] && continue
            if ! h=$(pr_step_heading "$plan" "$tok" 2>/dev/null); then
                out+="the Order line names step $tok, which has no '### Step $tok' heading"$'\n'; continue
            fi
            pr_tag_tier "$h" >/dev/null 2>&1 || out+="step $tok: unknown tier '$(pr_tag "$h" model)'"$'\n'
            pr_tag_effort "$h" medium >/dev/null 2>&1 || out+="step $tok: unknown effort '$(pr_tag "$h" effort)'"$'\n'
        done
    fi
    if [ -n "$before" ]; then
        for id in $(sed -nE 's/^### Step ([0-9]+[a-z]?)([^0-9a-z].*)?$/\1/p' "$before"); do
            pr_step_shipped "$before" "$id" || continue
            if ! pr_step_shipped "$plan" "$id"; then
                out+="step $id lost its **Shipped** line"$'\n'
            elif [ "$(pr_shipped_commit "$plan" "$id")" != "$(pr_shipped_commit "$before" "$id")" ]; then
                out+="step $id's **Shipped** line names another commit"$'\n'
            fi
        done
    fi
    printf '%s' "$out"
    [ -z "$out" ]
}

PR_SYNC_TRAILER=Plan-Synced-From     # names the REF commit a sync commit merged the plan from

# pr_synced_from <commit-message>  → the sha of its last `Plan-Synced-From: <sha>` trailer, or nothing.
pr_synced_from() {
    { grep -E "^$PR_SYNC_TRAILER: [0-9a-f]{40,64}\$" <<< "$1" || true; } | tail -1 | sed "s/^$PR_SYNC_TRAILER: //"
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
