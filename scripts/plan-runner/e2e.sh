#!/usr/bin/env bash
# End-to-end check of the driver's control flow — nudge, fresh review session, escalation,
# judge — against a stub `claude` and a stub `gradlew` in a scratch repository. No session is
# started and nothing is spent. Run by selfcheck.sh (and so by CI), or by hand:
#   scripts/plan-runner/e2e.sh
#
# Each scenario queues one behaviour per `claude` invocation; the stub pops the queue, acts in
# the worktree like a step session would (commits, Shipped line) and prints what
# `--output-format stream-json` prints: an init line, the behaviour's lines, its result line last.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
export GIT_AUTHOR_NAME=e2e GIT_AUTHOR_EMAIL=e2e@example.invalid GIT_COMMITTER_NAME=e2e GIT_COMMITTER_EMAIL=e2e@example.invalid
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
export PLAN_RUNNER_CLAUDE_HOME=$TMP/claude-home      # session transcripts: the stub writes them, the driver reads them

fail=0; n=0
check() { # check <name> <expected> <actual>
    n=$((n + 1))
    if [ "$2" = "$3" ]; then printf '  ok   %s\n' "$1"
    else fail=$((fail + 1)); printf '  FAIL %s\n       expected: %q\n       actual:   %q\n' "$1" "$2" "$3"; fi
}

# ---- the stubs -------------------------------------------------------------------
STUB=$TMP/stub; mkdir -p "$STUB/bin"
cat > "$STUB/bin/claude" <<'STUB_CLAUDE'
#!/usr/bin/env bash
# Pops one behaviour off $STUB/queue and plays it in the cwd (the plan worktree).
set -euo pipefail
kind=step; effort='?'; advisor=none; prev=''; step=1; resume=''; budget='?'
for a in "$@"; do
    case "$prev" in --effort) effort=$a ;; --advisor) advisor=$a ;; --resume) resume=$a ;; --max-budget-usd) budget=$a ;; -n) step=${a##* }; case "$a" in "plan review"*) kind=judge ;; esac ;; esac
    [ "$a" = --resume ] && kind=nudge
    prev=$a
done
prompt=${!#}
case "$prompt" in
    *"requires skills the step session did not"*) kind=review ;;
    "This session was stopped at a usage limit"*) kind=resume ;;     # the driver's continue prompt after a limit wait
esac
beh=$(head -1 "$STUB/queue" || true); [ -n "$beh" ] || beh=unexpected
tail -n +2 "$STUB/queue" > "$STUB/queue.next" || true; mv "$STUB/queue.next" "$STUB/queue"
sid=${resume:-s$(($(wc -l < "$STUB/calls") + 1))-$kind-$beh}     # a resume keeps its session's id
echo "$kind:$beh:$effort:$advisor" >> "$STUB/calls"
echo "$kind:$step" >> "$STUB/steps"
pwd >> "$STUB/cwds"
[ -z "$resume" ] || echo "$kind:$resume" >> "$STUB/resumes"
echo "$kind:$budget" >> "$STUB/budgets"
case "$prompt" in *"## Earlier attempt"*) echo "$kind:$beh" >> "$STUB/saw-attempt-block" ;; esac
case "$prompt" in *"## Interrupted attempt"*) echo "$kind:$beh" >> "$STUB/saw-interrupted-block" ;; esac
# The session's transcript, where the CLI records a usage-limit stop as an assistant entry.
T=$PLAN_RUNNER_CLAUDE_HOME/projects/-stub/$sid.jsonl; mkdir -p "${T%/*}"
entry() { # entry <extra fields> <text>  → one main-chain assistant entry
    printf '{"type":"assistant","isSidechain":false,"sessionId":"%s"%s,"message":{"role":"assistant","content":[{"type":"text","text":"%s"}]}}\n' "$sid" "$1" "$2" >> "$T"
}
# The stream's init line names the session, and the transcript gets the prompt and a turn. A session
# that never got that far has neither.
case "$beh" in
    limit-noid|resume-gone) ;;
    *) printf '{"type":"system","subtype":"init","session_id":"%s"}\n' "$sid"
       printf '{"type":"user","isSidechain":false,"sessionId":"%s","message":{"role":"user","content":"a prompt"}}\n' "$sid" >> "$T"
       entry '' "working: $beh" ;;
esac
resets_at() { echo $(( $(date +%s) + $(cat "$STUB/reset-in" 2>/dev/null || echo 1) )); }   # $STUB/reset-in: seconds to the reset
rate_event() { # rate_event <status>  → a rate_limit_event line in the probe's shape; rejected = the five-hour window is full
    local r u=0.5; r=$(resets_at); [ "$1" != rejected ] || u=1
    printf '{"type":"rate_limit_event","rate_limit_info":{"status":"%s","resetsAt":%s,"rateLimitType":"five_hour","isUsingOverage":false,"overageStatus":"rejected","unifiedWindows":{"five_hour":{"utilization":%s,"resetsAt":%s}}},"session_id":"%s"}\n' "$1" "$r" "$u" "$r" "$sid"
}
hang() { # stays alive until the driver stops it; $STUB/signals says how it ended
    sleep 20 & local child=$!
    trap 'kill $child 2>/dev/null; echo term >> "$STUB/signals"; exit 143' TERM
    touch "$STUB/started"
    wait "$child" || true
    echo survived >> "$STUB/signals"
}
PLAN=docs/plans/mini.md
echo "$kind:$beh:$(grep -c '^\*\*Decision taken\*\*' "$PLAN" || true)" >> "$STUB/saw-decision"
[ "$kind" != judge ] || pwd > "$STUB/judge-cwd"
# What happens elsewhere while this step's session runs: an edit committed on main, a pause file.
# One-shot, run in the main checkout.
if [ -f "$STUB/during.$step" ]; then
    (cd "$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")" && bash "$STUB/during.$step")
    rm -f "$STUB/during.$step"
fi
code() { # code <path>  → one production file and one test, committed
    mkdir -p "$(dirname "$1")" app/src/test/java/x
    echo "// $beh $RANDOM" >> "$1"; printf '    @Test fun t%s() {}\n' "$RANDOM" >> app/src/test/java/x/SeedTest.kt
    git add -A -- . ":(exclude)$PLAN"; git commit -qm "feat(x): $beh"     # -A: a blocked attempt's leftovers are this session's to settle
}
source scripts/plan-runner/lib.sh     # the worktree's copy: the stub finds sections the way the driver does
section_add() { # section_add <block>  → the block at the end of this step's section, where a session writes it
    awk -v re="$(pr_heading_re "$step")" -v block="$1" '
        on && /^##/ { print ""; print block; if (!blank) print ""; on = 0; done = 1 }
        !done && $0 ~ re { on = 1 }
        on && /^$/ { blank++; next }
        { while (blank > 0) { print ""; blank-- } print }
        END { if (on) { print ""; print block } }' "$PLAN" > "$PLAN.new"
    mv "$PLAN.new" "$PLAN"
}
ship() { # ship <skills>  → Shipped block naming the last code commit, committed
    section_add "**Shipped** \`$(git log --format=%h -1 -- app)\` (2026-09-20) — tier: mid. skills: $1. Reviewer models: none.\nDepartures (for sign-off): none"
    git add "$PLAN"; git commit -qm "docs(plan): step $step shipped"
}
result() { # result <status> <blockedKind|null> <run-json>
    local kindjson=null; [ "$2" = null ] || kindjson="\"$2\""
    printf '{"type":"result","subtype":"success","is_error":false,"session_id":"%s","num_turns":5,"total_cost_usd":1.5,"permission_denials":[],"modelUsage":{"stub-model":{"costUSD":1.5}},"structured_output":{"status":"%s","step":"%s","commit":%s,"skills":{"intended":[],"run":%s,"skipped":[]},"reviewerModels":[],"question":null,"blockedKind":%s,"advisorConsults":null,"summary":"%s"}}\n' \
        "$sid" "$1" "$step" "$( [ "$1" = done ] && printf '"%s"' "$(git log --format=%h -1 -- app 2>/dev/null || echo 0000000)" || echo null)" "$3" "$kindjson" "$beh"
}
LIMIT_429='"subtype":"success","is_error":true,"terminal_reason":"api_error","api_error_status":429,"num_turns":3,"total_cost_usd":0.88,"result":"You'\''ve hit your limit · resets 3am","structured_output":null'
case "$beh" in
    limit-stream)    rate_event rejected; hang ;;     # rejected, and goes on working: the driver must stop it
    limit-429)       printf '{"type":"result",%s,"session_id":"%s"}\n' "$LIMIT_429" "$sid" ;;
    limit-noid)      printf '{"type":"result",%s}\n' "$LIMIT_429" ;;     # no init line, no session id: nothing to resume
    limit-transcript) entry ",\"isApiErrorMessage\":true,\"error\":\"rate_limit\",\"apiErrorStatus\":429,\"quotaLimits\":{\"resetsAt\":$(resets_at)}" "You've hit your limit"
                     printf '{"type":"result","subtype":"success","is_error":false,"session_id":"%s","total_cost_usd":0.88,"structured_output":null}\n' "$sid" ;;
    resume-gone)     echo "No conversation found with session ID: $resume" >&2; exit 1 ;;
    hang)            hang ;;
    done-valid-rejected) echo green > "$STUB/gate"; code app/src/main/java/x/ui/Screen.kt; ship none
                     printf '%s\n%s\n' "$(rate_event rejected)" "$(result done null '[]')" ;;     # one write: done, and the window is used up
    done-valid)      echo green > "$STUB/gate"; code app/src/main/java/x/ui/Screen.kt; ship none; result done null '[]' ;;
    done-annotate)   code app/src/main/java/x/ui/Screen.kt; sed -i 's/^Body 2\.$/Body 2. **(step-1)** a note for step 2./' "$PLAN"; ship none; result done null '[]' ;;
    done-valid-di)   code app/src/main/java/x/di/Module.kt; ship none; result done null '[]' ;;
    done-no-shipped) code app/src/main/java/x/ui/Screen.kt; result done null '[]' ;;
    done-no-shipped-di) code app/src/main/java/x/di/Module.kt; result done null '[]' ;;
    claims-review)   result done null '["code-review"]' ;;
    needs-decision)  section_add '**Decision needed** — left or right?'; result needs_decision null '[]' ;;
    budget)          printf '{"type":"result","subtype":"error_max_budget_usd","is_error":true,"session_id":"%s","total_cost_usd":25,"structured_output":null}\n' "$sid" ;;
    api-error)       printf '{"type":"result","subtype":"success","is_error":true,"terminal_reason":"api_error","api_error_status":500,"session_id":"%s","total_cost_usd":0.88,"result":"API Error: 500 Internal server error","structured_output":null}\n' "$sid" ;;
    judge-nofindings) printf '{"type":"result","subtype":"success","is_error":false,"session_id":"%s","total_cost_usd":0.5,"structured_output":{"verdict":"cannot_judge","testsAdequate":true,"summary":"x"}}\n' "$sid" ;;
    done-gate-red)   code app/src/main/java/x/ui/Screen.kt; ship none; echo red > "$STUB/gate"; result done null '[]' ;;
    noop-done)       result done null '[]' ;;
    done-stray)      code app/src/main/java/x/ui/Screen.kt; ship none; echo scratch > stray.txt; echo wip >> README.md; result done null '[]' ;;
    tidy)            rm -f stray.txt; git checkout -q -- README.md; result done null '[]' ;;
    no-result)       printf '{"type":"result","subtype":"success","is_error":false,"session_id":"%s","total_cost_usd":1,"structured_output":null}\n' "$sid" ;;
    blocked-gate)    echo wip >> README.md; echo scratch > leftover.txt; result blocked gate '[]' ;;
    blocked-env)     result blocked environment '[]' ;;
    review-ok)       sed -i 's/skills: none\./skills: code-review./' "$PLAN"; git add "$PLAN"; git commit -qm "docs(plan): review"; result done null '["code-review"]' ;;
    judge-ok)        printf '{"type":"result","subtype":"success","is_error":false,"session_id":"%s","num_turns":3,"total_cost_usd":0.75,"permission_denials":[],"structured_output":{"verdict":"meets_spec","findings":[{"axis":"spec","severity":"medium","file":null,"summary":"m"},{"axis":"standards","severity":"low","file":"a.kt","summary":"l"}],"testsAdequate":false,"summary":"ok"}}\n' "$sid" ;;
    judge-writes)    echo tampered >> README.md; printf '{"type":"result","subtype":"success","is_error":false,"session_id":"%s","structured_output":{"verdict":"meets_spec","findings":[],"testsAdequate":true,"summary":"x"}}\n' "$sid" ;;
    *)               echo "stub claude: nothing queued for this call ($kind)" >&2; exit 9 ;;
esac
STUB_CLAUDE
chmod +x "$STUB/bin/claude"
export STUB PATH="$STUB/bin:$PATH"

# new_repo <dir>  → a scratch repo on `main` with the runner, a stub gradlew and a one-step plan.
new_repo() {
    local r=$1
    mkdir -p "$r/scripts" "$r/docs/plans" "$r/app"
    cp "$HERE/../run-plan.sh" "$r/scripts/"; cp -r "$HERE" "$r/scripts/plan-runner"
    # No desktop notifications from a test, and no long sleeps: 1 s polls, slices and fallback, no slack.
    sed -i -e 's/^NOTIFY_CMD=.*/NOTIFY_CMD=true/' -e 's/^USAGE_POLL_S=.*/USAGE_POLL_S=1/' -e 's/^USAGE_SLICE_S=.*/USAGE_SLICE_S=1/' \
        -e 's/^USAGE_WAIT_SLACK_S=.*/USAGE_WAIT_SLACK_S=0/' -e 's/^USAGE_WAIT_FALLBACK_S=.*/USAGE_WAIT_FALLBACK_S=1/' "$r/scripts/run-plan.sh"
    [ "$(grep -cE '^USAGE_(POLL_S=1|SLICE_S=1|WAIT_SLACK_S=0|WAIT_FALLBACK_S=1)$' "$r/scripts/run-plan.sh")" = 4 ] \
        || { echo "e2e: run-plan.sh has no USAGE_* tunable line for e2e.sh to shorten — a scenario would sleep for an hour" >&2; exit 1; }
    printf '#!/usr/bin/env bash\n[ "$(cat "$STUB/gate" 2>/dev/null)" != red ]\n' > "$r/gradlew"; chmod +x "$r/gradlew"
    printf 'readme\n' > "$r/README.md"; printf '// seed\n' > "$r/app/Seed.kt"
    mkdir -p "$r/app/src/test/java/x"; printf 'class SeedTest {\n    @Test fun seed() {}\n' > "$r/app/src/test/java/x/SeedTest.kt"
    printf 'local.properties\nruns/\n' > "$r/.gitignore"; printf 'sdk.dir=/nowhere\n' > "$r/local.properties"   # ignored; the driver copies it into the worktree
    printf '# Mini plan\n\n**Order: 1**\n\n## 3. Steps\n\n### Step 1 — Only step (`feat(x):`)\nBody.\n' > "$r/docs/plans/mini.md"
    printf 'MODEL_MID=opus\nEFFORT_MID=medium\nADVISOR_MID=fable\nJUDGE_MODEL=opus\n' > "$r/scripts/plan-runner/variants/j.env"
    git -C "$r" init -q -b main; git -C "$r" add -A; git -C "$r" commit -qm seed
}
# scenario <name> <queue…>  → fresh repo + queue; sets R (repo), RUNS, and runs nothing yet.
scenario() {
    echo "$1"; shift
    R=$TMP/repo-$n-$RANDOM; RUNS=$R/runs; new_repo "$R"
    : > "$STUB/calls"; : > "$STUB/steps"; : > "$STUB/saw-attempt-block"; : > "$STUB/saw-decision"; rm -f "$STUB/judge-cwd" "$STUB"/during.*
    : > "$STUB/cwds"; : > "$STUB/resumes"; : > "$STUB/budgets"; : > "$STUB/saw-interrupted-block"; : > "$STUB/signals"; rm -f "$STUB/started" "$STUB/reset-in"
    rm -rf "$PLAN_RUNNER_CLAUDE_HOME"
    echo green > "$STUB/gate"
    printf '%s\n' "$@" > "$STUB/queue"
}
# write_plan <order> <id>…  → the scratch plan, one section per id in that order (body "Body <id>."), committed on main
write_plan() {
    local order=$1 id; shift
    { printf '# Mini plan\n\n**Order: %s**\n\n## 3. Steps\n' "$order"
      for id in "$@"; do printf '\n### Step %s — Step %s (`feat(x):`)\nBody %s.\n' "$id" "$id" "$id"; done
    } > "$R/docs/plans/mini.md"
    git -C "$R" commit -qam "plan: $order"
}
# during <step> <script>  → bash <script> runs in the main checkout while step <step>'s session runs
during() { printf '%s\n' "$2" > "$STUB/during.$1"; }
# Edits of main's plan, each committed on main.
REVISE_2='sed -i "s/^Body 2\.$/Body 2, revised on main./" docs/plans/mini.md && git commit -qam "plan: revise step 2"'
REVISE_3='sed -i "s/^Body 3\.$/Body 3, revised on main./" docs/plans/mini.md && git commit -qam "plan: revise step 3"'
INSERT_1A='sed -i -e "s/^\*\*Order: 1 → /**Order: 1 → 1a → /" -e "s/^### Step 2 —/### Step 1a — Step 1a (feat(x):)\nBody 1a.\n\n&/" docs/plans/mini.md && git commit -qam "plan: step 1a"'
steps() { paste -sd' ' - < "$STUB/steps"; }
count_event() { jq -c --arg e "$1" 'select(.event == $e)' "$RUNS/mini.log" | wc -l | tr -d ' '; }
with_origin() { # → a bare scratch remote `origin` holding main, fetched; sets BARE
    BARE=$TMP/remote-$n.git; git clone -q --bare "$R" "$BARE"; git -C "$R" remote add origin "$BARE"; git -C "$R" fetch -q origin
}
branch_plan() { git -C "$R" show plan/mini:docs/plans/mini.md; }
run() { rc=0; PLAN_RUNNER_RUNS_DIR=$RUNS "$R/scripts/run-plan.sh" "$R/docs/plans/mini.md" "$@" > "$TMP/out" 2> "$TMP/err" || rc=$?; }
requeue() { printf '%s\n' "$@" > "$STUB/queue"; }     # a second invocation on the same scratch repo
events() { jq -r '.event' "$RUNS/$1.log" | paste -sd' ' -; }
WT() { echo "$R/.claude/worktrees/plan-$1"; }
calls() { paste -sd' ' - < "$STUB/calls"; }
resumes() { paste -sd' ' - < "$STUB/resumes"; }        # <kind>:<id> of every call that passed --resume
signals() { paste -sd' ' - < "$STUB/signals"; }        # how a hanging stub ended: term, or survived
ev() { jq -r "select(.event == \"$1\") | $2" "$RUNS/mini.log"; }     # ev <event> <jq>  → that field of mini.log's events

scenario "A plain step validates" done-valid
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result validated" "$(events mini)"
check "one session, default config, no advisor"  "step:done-valid:medium:none" "$(calls)"
check "validated event carries the @Test delta"  "1 2" "$(jq -r 'select(.event=="validated") | "\(.tests_before) \(.tests_after)"' "$RUNS/mini.log")"
check "the launch records the base it forked from" "$(git -C "$R" rev-parse main | cut -c1-8)" "$(jq -r 'select(.event=="launched") | .base' "$RUNS/mini.log")"

scenario "--to stops cleanly before the next step" done-valid
write_plan "1 → 2" 1 2
run --to 1
check "exit 0"                                   "0" "$rc"
check "events: step 1 only"                      "launched result validated" "$(events mini)"
check "step 2 was never launched"                "1" "$(wc -l < "$STUB/calls" | tr -d ' ')"
check "the driver says why it stopped"           "1" "$(grep -c 'stopped after step 1 as asked (--to)' "$TMP/err" || true)"

scenario "An invalid done result is nudged by resume, then validates" done-no-shipped done-valid
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result nudged result validated" "$(events mini)"
check "second call is a resume"                  "nudge" "$(sed -n '2p' "$STUB/calls" | cut -d: -f1)"

scenario "A done step that leaves the worktree dirty is nudged to clean up" done-stray tidy
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result nudged result validated" "$(events mini)"
check "the nudge names the stray files"          "1" "$(jq -r 'select(.event=="nudged") | .reasons' "$RUNS/mini.log" | grep -c 'not clean.*README.md,stray.txt')"
check "the plan worktree is clean afterwards"    "" "$(git -C "$(WT mini)" status --porcelain)"

scenario "No result object: nudged, and escalated when the nudge ends the same way" no-result no-result done-valid
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result nudged result escalated launched result validated" "$(events mini)"
check "attempt 2 ran one rung up"                "step:done-valid:high:none" "$(sed -n '3p' "$STUB/calls")"
scenario "…and blocked when the escalated attempt has no result either, after its own nudge" no-result no-result no-result no-result
run
check "exit 3 (blocked)"                         "3" "$rc"
check "events"                                   "launched result nudged result escalated launched result nudged result blocked" "$(events mini)"

scenario "Only a missed skill: a fresh review session, not a resume" done-valid-di review-ok
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result review result validated" "$(events mini)"
check "second call is a fresh review session"    "review" "$(sed -n '2p' "$STUB/calls" | cut -d: -f1)"
check "the missed skill was named"               "code-review" "$(jq -r 'select(.event=="review") | .missing' "$RUNS/mini.log")"

scenario "Still invalid after the nudge: escalate one rung, keep the failed attempt" done-no-shipped noop-done done-valid
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result nudged result escalated launched result validated" "$(events mini)"
check "rung: medium → high"                      "medium high" "$(jq -r 'select(.event=="escalated") | "\(.from) \(.to)"' "$RUNS/mini.log")"
check "attempt 2 ran at the higher effort"       "step:done-valid:high:none" "$(sed -n '3p' "$STUB/calls")"
check "attempt 2's prompt tells it what failed"  "step:done-valid" "$(cat "$STUB/saw-attempt-block")"
check "the failed attempt's commit is kept on a branch" "1" "$(git -C "$R" branch --list 'plan-attempts/mini-step1-*' | wc -l | tr -d ' ')"
check "the plan branch holds only attempt 2 (code + plan commit)" "2" "$(git -C "$R" rev-list --count main..plan/mini)"
check "validated on attempt 2"                   "2" "$(jq -r 'select(.event=="validated") | .attempt' "$RUNS/mini.log")"

scenario "A red gate that survives the nudge escalates too" done-gate-red noop-done done-valid
run     # the stub gate turns red with attempt 1, stays red through the nudge, and done-valid turns it green again
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result nudged result escalated launched result validated" "$(events mini)"
check "the escalation reason names the gate"     "1" "$(jq -r 'select(.event=="escalated") | .why' "$RUNS/mini.log" | grep -c 'the gate is red')"

scenario "blocked/gate escalates at once; uncommitted work is saved, the worktree is reset" blocked-gate done-valid
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result escalated launched result validated" "$(events mini)"
check "tracked WIP saved as a patch"             "1" "$(ls "$RUNS"/mini.step1.*.attempt1.patch 2>/dev/null | wc -l | tr -d ' ')"
check "untracked WIP saved as a tarball"         "leftover.txt" "$(tar -tzf "$RUNS"/mini.step1.*.attempt1.untracked.tgz)"
check "attempt 2 started from a clean tree"      "" "$(git -C "$R/.claude/worktrees/plan-mini" status --porcelain)"
check "nothing was committed, so no attempts branch" "0" "$(git -C "$R" branch --list 'plan-attempts/*' | wc -l | tr -d ' ')"
check "gitignored files survive the clean (local.properties)" "sdk.dir=/nowhere" "$(cat "$(WT mini)/local.properties")"

scenario "A second failure after escalating is the human's — and a re-run does not escalate again" blocked-gate blocked-gate
run
check "exit 3 (blocked)"                         "3" "$rc"
check "events"                                   "launched result escalated launched result blocked" "$(events mini)"
requeue done-valid; run
check "re-run: exit 0"                           "0" "$rc"
check "re-run resumes on the rung it had reached" "step:done-valid:high:none" "$(tail -1 "$STUB/calls")"
check "one escalation for the step across both runs" "1" "$(jq -r 'select(.event=="escalated") | .step' "$RUNS/mini.log" | wc -l | tr -d ' ')"
requeue blocked-gate
scenario "…and a step that escalated before, then fails again, is blocked without a third attempt" blocked-gate blocked-gate
run; requeue blocked-gate; run
check "exit 3, no second escalation"             "3 1" "$rc $(jq -r 'select(.event=="escalated") | .step' "$RUNS/mini.log" | wc -l | tr -d ' ')"

scenario "needs_decision stops for the human and is never re-run" needs-decision
run
check "exit 2"                                   "2" "$rc"
check "events"                                   "launched result needs_decision" "$(events mini)"

scenario "Escalation keeps the human's uncommitted **Decision taken** answer" needs-decision
run
sed -i 's/^\*\*Decision needed\*\*/**Decision taken**/' "$(WT mini)/docs/plans/mini.md"
requeue blocked-gate done-valid; run
check "exit 0"                                   "0" "$rc"
check "attempt 1 and the escalated attempt 2 both saw the answer" "step:blocked-gate:1 step:done-valid:1" "$(tail -2 "$STUB/saw-decision" | paste -sd' ' -)"
check "the answer landed with the plan commit"   "1" "$(git -C "$(WT mini)" show HEAD:docs/plans/mini.md | grep -c '^\*\*Decision taken\*\*')"

scenario "A spent budget is blocked at once, never re-run" budget
run
check "exit 3 (blocked)"                         "3" "$rc"
check "events"                                   "launched result blocked" "$(events mini)"

scenario "A non-429 API error is still blocked at once, and the stop names the error, not 'success' or 'null'" api-error
run
check "exit 3 (blocked)"                         "3" "$rc"
check "events: no wait, no nudge, no escalation" "launched result blocked" "$(events mini)"
check "the blocked reason carries the CLI's message" "1" "$(jq -r 'select(.event=="blocked") | .why' "$RUNS/mini.log" | grep -c "api_error, HTTP 500: API Error: 500 Internal server error" || true)"
check "the launch line reads failed, not success" "1" "$(grep -c 'session s1-step-api-error: failed, status null' "$TMP/err" || true)"
check "the driver says why it does not retry"   "1" "$(grep -c 'ended on an API error' "$TMP/err" || true)"
check "no bare 'null' summary line"             "0" "$(grep -cx '  null' "$TMP/out" || true)"
check "the resume hint names the session"       "1" "$(grep -c 'claude --resume s1-step-api-error' "$TMP/out" || true)"

# ---- the usage limit -----------------------------------------------------------------
scenario "A session whose stream says rejected is stopped within a poll, waits, and resumes the same session" limit-stream done-valid
echo 3 > "$STUB/reset-in"
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result usage_limit resumed result validated" "$(events mini)"
check "the driver stopped the stub, which did not run on" "term" "$(signals)"
check "the limit came from the stream, with its reset time" "step stream number" "$(ev usage_limit '"\(.kind) \(.source) \(.resets_at | type)"')"
check "the resume names the same session"        "resume:s1-step-limit-stream" "$(resumes)"
check "waited_s is logged"                       "number" "$(ev resumed '.waited_s | type')"
check "the interrupted invocation's result, stream and stderr are kept apart" "3" "$(ls "$RUNS"/mini.step1.*.result.limit1.json* 2>/dev/null | wc -l | tr -d ' ')"
check "one notification names the local reset time" "1" "$(grep -c 'usage limit in step 1 — the limit resets .*; waiting .*, until ' "$TMP/err" || true)"

scenario "A 429 result alone leads to the same wait and resume; with no reset time anywhere, the fallback wait" limit-429 done-valid
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result usage_limit resumed result validated" "$(events mini)"
check "source result, no reset time, the 1 s fallback" "result null 1" "$(ev usage_limit '"\(.source) \(.resets_at) \(.wait_s)"')"
check "the resume names the same session"        "resume:s1-step-limit-429" "$(resumes)"

scenario "…and so does a transcript entry alone, with the transcript's reset time" limit-transcript done-valid
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result usage_limit resumed result validated" "$(events mini)"
check "source transcript, with its reset time and path" "transcript number 1" "$(ev usage_limit '"\(.source) \(.resets_at | type) \(.transcript | test("s1-step-limit-transcript.jsonl$") | if . then 1 else 0 end)"')"
check "the resume names the same session"        "resume:s1-step-limit-transcript" "$(resumes)"

scenario "Past USAGE_RESUME_MAX waits in one step, the cap blocks with exit 3" limit-429 limit-429 limit-429
sed -i 's/^USAGE_RESUME_MAX=.*/USAGE_RESUME_MAX=2/' "$R/scripts/run-plan.sh"
run
check "exit 3 (blocked)"                         "3" "$rc"
check "events: two waits, the third stop blocks" "launched result usage_limit resumed result usage_limit resumed result usage_limit blocked" "$(events mini)"
check "the block names the cap"                  "1" "$(ev blocked .why | grep -c 'at most 2 per step' || true)"

scenario "A reset days away blocks at once, with no resume call" limit-stream
echo 259200 > "$STUB/reset-in"
run
check "exit 3 (blocked)"                         "3" "$rc"
check "events"                                   "launched result usage_limit blocked" "$(events mini)"
check "no resume call"                           "1 " "$(wc -l < "$STUB/calls" | tr -d ' ') $(resumes)"
check "the block names the reset time"           "1" "$(ev blocked .why | grep -c 'resets .* away), past the 6h 00m the runner waits' || true)"

scenario "A nudge resumes its own session after a limit" done-no-shipped limit-429 done-valid
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result nudged result usage_limit resumed result validated" "$(events mini)"
check "the nudge and its limit resume both name the step session" "nudge:s1-step-done-no-shipped resume:s1-step-done-no-shipped" "$(resumes)"

scenario "A review session resumes its own session after a limit" done-valid-di limit-429 review-ok
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result review result usage_limit resumed result validated" "$(events mini)"
check "the resume names the review session"      "resume:s2-review-limit-429" "$(resumes)"

scenario "A judge resumes its own session after a limit, in its own worktree" done-valid limit-429 judge-ok
run --variant j
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result validated result usage_limit resumed result judged" "$(events mini-j)"
check "the resume names the judge session"       "resume:s2-judge-limit-429" "$(resumes)"
check "…and runs in the judge's worktree"        "judge" "$(basename "$(sed -n '3p' "$STUB/cwds")" | cut -d- -f1)"
check "the judge's results stay out of the step's cost" "1.5" "$(PLAN_RUNNER_RUNS_DIR=$RUNS "$R/scripts/plan-runner/report.sh" mini-j | awk '$1 == "1" { print $7 }')"

scenario "The nudge is still available after a resume" limit-429 done-no-shipped done-valid
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result usage_limit resumed result nudged result validated" "$(events mini)"

scenario "A pause file during the wait exits 5, and the re-run resumes the same session" limit-stream
write_plan "1 → 2" 1 2
echo 3 > "$STUB/reset-in"
during 1 'touch "$PLAN_RUNNER_RUNS_DIR/mini.pause"'
run
check "exit 5"                                   "5" "$rc"
check "events"                                   "launched result usage_limit paused" "$(events mini)"
check "the pause file is gone"                   "no" "$([ -e "$RUNS/mini.pause" ] && echo yes || echo no)"
( cd "$R" && bash -c "$REVISE_2" )
requeue done-valid done-valid; run
check "the re-run: exit 0"                       "0" "$rc"
check "…resumes the interrupted session, not a fresh one" "resume:s1-step-limit-stream" "$(resumes)"
check "…closes the wait with resumed, and syncs the plan only at the next real boundary" \
    "launched result usage_limit paused resumed result validated plan_synced launched result validated" "$(events mini)"

scenario "…and a pause in a wait with no reset time: the re-run waits out the rest, on what is left of the budget" limit-429
sed -i 's/^USAGE_WAIT_FALLBACK_S=.*/USAGE_WAIT_FALLBACK_S=4/' "$R/scripts/run-plan.sh"
during 1 'touch "$PLAN_RUNNER_RUNS_DIR/mini.pause"'
run
check "exit 5"                                   "5" "$rc"
requeue done-valid; run
check "the re-run: exit 0"                       "0" "$rc"
check "…waited out the rest of the 4 s fallback" "1" "$(ev resumed 'if .waited_s >= 1 then 1 else 0 end')"
check "…and resumed on the budget less the 0.88 the session spent" "step:30 resume:29.12" "$(paste -sd' ' - < "$STUB/budgets")"

scenario "A judge stopped at the cap gets no grade, and keeps its cost" done-valid limit-429
sed -i 's/^USAGE_RESUME_MAX=.*/USAGE_RESUME_MAX=0/' "$R/scripts/run-plan.sh"
run --variant j
check "exit 0: the plan goes on"                 "0" "$rc"
check "events"                                   "launched result validated result usage_limit judge_failed" "$(events mini-j)"
check "judge_failed names the cap and its cost"  "1 0.88" "$(jq -r 'select(.event=="judge_failed") | "\(.why | test("at most 0 per step") | if . then 1 else 0 end) \(.cost)"' "$RUNS/mini-j.log")"

scenario "A session stopped before its init line is replaced by a fresh step session with the Interrupted attempt block" limit-noid done-valid
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result usage_limit resumed result validated" "$(events mini)"
check "no resume call"                           "" "$(resumes)"
check "the fresh session's prompt has the block" "step:done-valid" "$(cat "$STUB/saw-interrupted-block")"
check "…and the step keeps its start commit"     "1 2" "$(ev validated '"\(.tests_before) \(.tests_after)"')"

scenario "A resume that ends without a result is replaced by a fresh session" limit-429 resume-gone done-valid
run
check "exit 0"                                   "0" "$rc"
check "calls: the step, the failed resume, a fresh step session" "step:limit-429 resume:resume-gone step:done-valid" "$(cut -d: -f1,2 "$STUB/calls" | paste -sd' ' -)"
check "the fresh session's prompt has the block" "step:done-valid" "$(cat "$STUB/saw-interrupted-block")"

scenario "A fresh review session that replaces a dead resume gets the review prompt, not the continue prompt" done-valid-di limit-429 resume-gone review-ok
run
check "exit 0"                                   "0" "$rc"
check "calls"                                    "step:done-valid-di review:limit-429 resume:resume-gone review:review-ok" "$(cut -d: -f1,2 "$STUB/calls" | paste -sd' ' -)"

scenario "A fresh session that ends without a result as well is blocked, not replaced again and again" limit-429 resume-gone resume-gone
rc=0; PLAN_RUNNER_RUNS_DIR=$RUNS timeout 60 "$R/scripts/run-plan.sh" "$R/docs/plans/mini.md" > "$TMP/out" 2> "$TMP/err" || rc=$?
check "exit 3 (blocked), within the timeout"     "3" "$rc"
check "three calls: the step, its resume, one fresh session" "3" "$(wc -l < "$STUB/calls" | tr -d ' ')"

scenario "…even after a transcript stop, whose entry is still the transcript's last: no second wait, a fresh session" limit-transcript resume-gone done-valid
run
check "exit 0"                                   "0" "$rc"
check "calls: the step, the failed resume, a fresh step session" "step:limit-transcript resume:resume-gone step:done-valid" "$(cut -d: -f1,2 "$STUB/calls" | paste -sd' ' -)"
check "one wait only"                            "1" "$(count_event usage_limit)"

scenario "A step that ends done while its stream says rejected: the next step's launch waits first" done-valid-rejected done-valid
write_plan "1 → 2" 1 2
echo 3 > "$STUB/reset-in"
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result validated usage_limit resumed launched result validated" "$(events mini)"
check "a launch wait for step 2"                 "2 launch stream" "$(ev usage_limit '"\(.step) \(.kind) \(.source)"')"

scenario "Ctrl-C during a session stops the stub claude and exits 130" hang
set -m       # job control: the driver gets its own process group, so SIGINT is not ignored as in a plain background job
PLAN_RUNNER_RUNS_DIR=$RUNS "$R/scripts/run-plan.sh" "$R/docs/plans/mini.md" > "$TMP/out" 2> "$TMP/err" &
pid=$!
set +m
for _ in $(seq 1 100); do [ -f "$STUB/started" ] && break; sleep 0.1; done
kill -INT "$pid"; rc=0; wait "$pid" || rc=$?
check "exit 130"                                 "130" "$rc"
check "the stub was stopped"                     "term" "$(signals)"
check "events"                                   "launched interrupted" "$(events mini)"
check "the interrupted event names the session"  "s1-step-hang step" "$(ev interrupted '"\(.session) \(.kind)"')"

scenario "A step finished by hand is not validated on a discarded attempt's skill claim" done-no-shipped-di claims-review blocked-env
run
check "exit 3 after nudge + escalation"          "3" "$rc"
( cd "$(WT mini)" && mkdir -p app/src/main/java/x/di && echo "// by hand" > app/src/main/java/x/di/Module.kt && git add app && git commit -qm "feat(x): by hand" \
  && printf '\n**Shipped** `%s` (2026-09-20) — tier: mid. skills: none. Reviewer models: none.\nDepartures (for sign-off): none\n' "$(git log --format=%h -1)" >> docs/plans/mini.md \
  && git add docs && git commit -qm "docs(plan): by hand" )
requeue; run
check "the hand-finished di/ step is blocked: nobody reviewed it" "3" "$rc"
check "…for the missing skill, not credited to the reset attempt" "1" "$(jq -r 'select(.event=="blocked") | .why' "$RUNS/mini.log" | tail -1 | grep -c 'required skills not run: code-review')"

scenario "blocked/environment is never re-run" blocked-env
run
check "exit 3 (blocked)"                         "3" "$rc"
check "events"                                   "launched result blocked" "$(events mini)"

scenario "ESCALATE=0 in a variant turns the re-run off" blocked-gate
printf 'ESCALATE=0\n' > "$R/scripts/plan-runner/variants/noesc.env"
run --variant noesc
check "exit 3 (blocked)"                         "3" "$rc"
check "events"                                   "launched result blocked" "$(events mini-noesc)"

scenario "A variant: own branch and log, advisor passed, judge grades the validated step" done-valid judge-ok
run --variant j
check "exit 0"                                   "0" "$rc"
check "events: the judge's result too"           "launched result validated result judged" "$(events mini-j)"
check "the judge's result is logged as kind judge" "step judge" "$(jq -r 'select(.event=="result") | .kind' "$RUNS/mini-j.log" | paste -sd' ' -)"
check "step ran opus/medium with the advisor; judge without" "step:done-valid:medium:fable judge:judge-ok:high:none" "$(calls)"
check "the variant has its own branch"           "1" "$(git -C "$R" branch --list plan/mini-j | wc -l | tr -d ' ')"
check "grade logged per severity"                "meets_spec 0 1 1 false" "$(jq -r 'select(.event=="judged") | "\(.verdict) \(.high) \(.medium) \(.low) \(.tests_adequate)"' "$RUNS/mini-j.log")"
check "every event names its variant"            "j" "$(jq -r '.variant' "$RUNS/mini-j.log" | sort -u)"
check "report reads the variant log"             "1 opus/medium+fable 1 0 0 - 1.5 5 0" "$(PLAN_RUNNER_RUNS_DIR=$RUNS "$R/scripts/plan-runner/report.sh" mini-j | awk '$1 == "1" { print $1, $2, $3, $4, $5, $6, $7, $8, $9 }')"
check "the judge ran in a throwaway worktree, not in the variant's" "judge" "$(basename "$(cat "$STUB/judge-cwd")" | cut -d- -f1)"
check "…whose path does not name the variant"    "0" "$(grep -c 'mini-j' "$STUB/judge-cwd" || true)"
check "…and which is gone afterwards"            "0" "$(git -C "$R" worktree list | grep -c '/judge-' || true)"

scenario "A grade without a findings array is logged as zeros, not a driver crash" done-valid judge-nofindings
run --variant j
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result validated result judged" "$(events mini-j)"
check "counts default to 0"                      "cannot_judge 0 0 0" "$(jq -r 'select(.event=="judged") | "\(.verdict) \(.high) \(.medium) \(.low)"' "$RUNS/mini-j.log")"

scenario "A judge that writes anyway loses its grade; the plan and its branch are untouched" done-valid judge-writes
run --variant j
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result validated result judge_failed" "$(events mini-j)"
check "the plan worktree is clean"               "" "$(git -C "$(WT mini-j)" status --porcelain)"

scenario "A typo in a variant or a heading tag is a config error, not a 'driver bug'" done-valid
printf 'EFFORT_MID=medum\n' > "$R/scripts/plan-runner/variants/typo.env"
run --variant typo
check "bad effort in a variant: exit 1, plain message" "1 0" "$rc $(grep -c 'internal error' "$TMP/err" || true)"
sed -i 's/^### Step 1 — Only step.*/&  — effort: infinite/' "$R/docs/plans/mini.md"; git -C "$R" commit -qam "bad tag"
run
check "bad effort tag in the plan: exit 1, plain message" "1 0" "$rc $(grep -c 'internal error' "$TMP/err" || true)"

scenario "The plan warning fires on an uncommitted edit in the main checkout only" done-valid
run; requeue; run
check "second run: nothing to do, exit 0"        "0" "$rc"
check "…and no warning, though the branch's copy has a Shipped block main's lacks" "0" "$(grep -c 'warning:' "$TMP/err" || true)"
echo "a committed afterthought" >> "$R/docs/plans/mini.md"; git -C "$R" commit -qam afterthought; run
check "an edit committed on main is not warned about: a sync carries it" "0" "$(grep -c 'warning:' "$TMP/err" || true)"
echo "an uncommitted afterthought" >> "$R/docs/plans/mini.md"; run
check "an uncommitted edit in the main checkout is warned about" "1" "$(grep -c 'warning: docs/plans/mini.md has uncommitted changes in the main checkout' "$TMP/err" || true)"

scenario "A pause file stops the run at the next step boundary, and the next run continues" done-valid
write_plan "1 → 2" 1 2
during 1 'touch "$PLAN_RUNNER_RUNS_DIR/mini.pause"'
run
check "exit 5 (stopped for the owner)"           "5" "$rc"
check "events: step 1, then the pause"           "launched result validated paused" "$(events mini)"
check "the pause file is gone"                   "no" "$([ -e "$RUNS/mini.pause" ] && echo yes || echo no)"
check "step 2 was not launched"                  "step:1" "$(steps)"
requeue done-valid; run
check "the next run continues: exit 0"           "0" "$rc"
check "…with step 2"                             "step:1 step:2" "$(steps)"

scenario "A pause file found at start-up is stale: removed with a note, and the run goes on" done-valid
mkdir -p "$RUNS"; touch "$RUNS/mini.pause"
run
check "exit 0"                                   "0" "$rc"
check "the stale pause file is gone"             "no" "$([ -e "$RUNS/mini.pause" ] && echo yes || echo no)"
check "the driver says so"                       "1" "$(grep -c 'stale pause file' "$TMP/err" || true)"

scenario "A step added on main right after the running step runs next, without a stop" done-valid done-valid done-valid
write_plan "1 → 2" 1 2
during 1 "$INSERT_1A"
run
check "exit 0"                                   "0" "$rc"
check "steps in the merged order"                "step:1 step:1a step:2" "$(steps)"
check "events: one sync between step 1 and step 1a" "launched result validated plan_synced launched result validated launched result validated" "$(events mini)"
check "the same-spot insertion was placed, not stopped on" "1" "$(jq -r 'select(.event=="plan_synced") | .placed' "$RUNS/mini.log")"
check "step 1's Shipped block sits above step 1a's heading" "1" "$(branch_plan | awk '/^\*\*Shipped\*\*/ && !s { s = NR } /^### Step 1a / { h = NR } END { print (s && s < h) ? 1 : 0 }')"
sync=$(jq -r 'select(.event=="launched" and .step=="1a") | .start' "$RUNS/mini.log")
check "step 1a's launch starts at the sync commit" "docs(plan): sync docs/plans/mini.md from main at $(git -C "$R" rev-parse --short main)" "$(git -C "$R" log -1 --format=%s "$sync")"
check "…which carries main's sha as its trailer" "Plan-Synced-From: $(git -C "$R" rev-parse main)" "$(git -C "$R" log -1 --format=%B "$sync" | grep '^Plan-Synced-From:')"
check "…and changes the plan only"               "docs/plans/mini.md" "$(git -C "$R" show --name-only --format= "$sync")"

scenario "Two syncs in one run; the second merges from the first sync's base, cleanly" done-valid done-valid done-valid done-valid
write_plan "1 → 2 → 3" 1 2 3
during 1 "$INSERT_1A"; during 2 "$REVISE_3"
run
check "exit 0"                                   "0" "$rc"
check "steps"                                    "step:1 step:1a step:2 step:3" "$(steps)"
check "two syncs, the second with nothing to place" "1 0" "$(jq -r 'select(.event=="plan_synced") | .placed' "$RUNS/mini.log" | paste -sd' ' -)"
check "step 1a's heading is there once"          "1" "$(branch_plan | grep -c '^### Step 1a ')"
check "main's second edit reached the branch"    "1" "$(branch_plan | grep -c '^Body 3, revised on main\.$')"
check "no sync failed"                           "0" "$(count_event plan_sync_failed)"

scenario "A clean sync reaches the branch, and a later merge of main is clean" done-valid done-valid
write_plan "1 → 2" 1 2
# Away from the end of a step section: a line main changes right where the branch later writes a
# Shipped block makes `git merge main` ask about it, sync or no sync (the end-of-run hint says so).
during 1 'sed -i "s/^# Mini plan$/# Mini plan, revised on main/" docs/plans/mini.md && git commit -qam "plan: retitle"'
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result validated plan_synced launched result validated" "$(events mini)"
check "main's edit is on the branch"             "1" "$(branch_plan | grep -c '^# Mini plan, revised on main$')"
check "the end-of-run message names the sync"    "1" "$(grep -c 'plan sync commit' "$TMP/out" || true)"
mrc=0; git -C "$(WT mini)" merge -q --no-edit main >/dev/null 2>&1 || mrc=$?
check "git merge main afterwards is clean"       "0" "$mrc"

scenario "--sync-from none never syncs" done-valid done-valid
write_plan "1 → 2" 1 2
during 1 "$REVISE_2"
run --sync-from none
check "exit 0"                                   "0" "$rc"
check "no sync"                                  "launched result validated launched result validated" "$(events mini)"
check "main's edit is not on the branch"         "0" "$(branch_plan | grep -c 'revised on main' || true)"

scenario "--sync-from origin/main fetches the owner's push first" done-valid done-valid
write_plan "1 → 2" 1 2
with_origin
during 1 "git clone -q '$BARE' '$TMP/clone-$n' && cd '$TMP/clone-$n' && $REVISE_2 && git push -q origin main"
run --sync-from origin/main
check "exit 0"                                   "0" "$rc"
check "one sync, from origin/main"               "origin/main" "$(jq -r 'select(.event=="plan_synced") | .ref' "$RUNS/mini.log")"
check "the pushed edit is on the branch"         "1" "$(branch_plan | grep -c '^Body 2, revised on main\.$')"
check "local main did not move"                  "0" "$(git -C "$R" show main:docs/plans/mini.md | grep -c 'revised on main' || true)"

scenario "A failed fetch is a warning, not a driver bug, and the run goes on" done-valid done-valid
write_plan "1 → 2" 1 2
with_origin
during 1 "rm -rf '$BARE'"
run --sync-from origin/main
check "exit 0"                                   "0" "$rc"
check "the fetch failure is a warning"           "1" "$(grep -c 'warning: git fetch origin main failed' "$TMP/err" || true)"
check "…and no 'internal error'"                 "0" "$(grep -c 'internal error' "$TMP/err" || true)"

scenario "A real edit conflict stops with exit 5 and leaves the branch and the worktree untouched" done-annotate
write_plan "1 → 2" 1 2
during 1 "$REVISE_2"
run
check "exit 5"                                   "5" "$rc"
check "events"                                   "launched result validated plan_sync_failed" "$(events mini)"
check "the conflict file holds the markers"      "1" "$(grep -c '^<<<<<<<<<<<<< ' "$RUNS/mini.plan-sync.conflict.md" 2>/dev/null || true)"
check "the branch ends at step 1's plan commit"  "docs(plan): step 1 shipped" "$(git -C "$R" log -1 --format=%s plan/mini)"
check "the worktree is clean"                    "" "$(git -C "$(WT mini)" status --porcelain)"
check "the hand-merge command carries the trailer" "1" "$(grep -c "Plan-Synced-From: $(git -C "$R" rev-parse main)" "$TMP/out" || true)"
check "step 2 was not launched"                  "step:1" "$(steps)"

scenario "A merged plan whose Order names a step with no heading stops with exit 5, nothing committed" done-valid
write_plan "1 → 2" 1 2
during 1 'sed -i "s/^\*\*Order: 1 → 2\*\*$/**Order: 1 → 2 → 9**/" docs/plans/mini.md && git commit -qam "plan: step 9"'
run
check "exit 5"                                   "5" "$rc"
check "events"                                   "launched result validated plan_sync_failed" "$(events mini)"
check "the reason names the step"                "1" "$(jq -r 'select(.event=="plan_sync_failed") | .why' "$RUNS/mini.log" | grep -c 'step 9')"
check "nothing committed"                        "docs(plan): step 1 shipped" "$(git -C "$R" log -1 --format=%s plan/mini)"
check "the worktree is clean"                    "" "$(git -C "$(WT mini)" status --porcelain)"

scenario "An uncommitted answer in the plan skips the sync; a later boundary syncs" needs-decision
write_plan "1 → 2" 1 2
run
check "exit 2"                                   "2" "$rc"
sed -i 's/^\*\*Decision needed\*\*/**Decision taken**/' "$(WT mini)/docs/plans/mini.md"
( cd "$R" && bash -c "$REVISE_2" )
requeue done-valid done-valid; run
check "exit 0"                                   "0" "$rc"
check "the driver says why it did not sync"      "1" "$(grep -c 'plan sync skipped' "$TMP/err" || true)"
check "the sync came at the boundary after step 1" "launched result needs_decision launched result validated plan_synced launched result validated" "$(events mini)"

scenario "A ‖ added on main at the frontier stops the run" done-valid
write_plan "1 → 2" 1 2
during 1 'sed -i "s/^\*\*Order: 1 → 2\*\*$/**Order: 1 ‖ 2**/" docs/plans/mini.md && git commit -qam "plan: checkpoint"'
run
check "exit 4"                                   "4" "$rc"
check "events"                                   "launched result validated plan_synced checkpoint" "$(events mini)"

scenario "A ‖ added on main behind the frontier does not stop the run" done-valid done-valid
write_plan "1 → 2 → 3" 1 2 3
run --to 2
check "first run: exit 0"                        "0" "$rc"
sed -i 's/^\*\*Order: 1 → 2 → 3\*\*$/**Order: 1 ‖ 2 → 3**/' "$R/docs/plans/mini.md"; git -C "$R" commit -qam "plan: checkpoint"
requeue done-valid; run
check "second run: exit 0"                       "0" "$rc"
check "the ‖ reached the branch"                 "1" "$(branch_plan | grep -c '^\*\*Order: 1 ‖ 2 → 3\*\*$' || true)"
check "step 3 ran"                               "step:1 step:2 step:3" "$(steps)"
check "no checkpoint"                            "0" "$(count_event checkpoint)"

scenario "…nor does one added behind it in the same run, after two steps it shipped" done-valid done-valid done-valid
write_plan "1 → 2 → 3" 1 2 3
during 2 'sed -i "s/^\*\*Order: 1 → 2 → 3\*\*$/**Order: 1 ‖ 2 → 3**/" docs/plans/mini.md && git commit -qam "plan: checkpoint"'
run
check "exit 0"                                   "0" "$rc"
check "step 3 ran after the sync"                "step:1 step:2 step:3" "$(steps)"
check "no checkpoint"                            "0" "$(count_event checkpoint)"

scenario "A ‖ due at the same boundary as a pause file wins; the pause file is stale at the next start" done-valid done-valid
write_plan "1 ‖ 2" 1 2
during 1 'touch "$PLAN_RUNNER_RUNS_DIR/mini.pause"'
run
check "exit 4, not 5"                            "4" "$rc"
requeue done-valid; run
check "the next run removes the stale pause file and goes on" "0 1" "$rc $(grep -c 'stale pause file' "$TMP/err" || true)"

scenario "A missing tool is an environment error (exit 1), not a driver bug or a blocked step" done-valid
BARE=$TMP/bare-$n; mkdir -p "$BARE"
for t in bash env git dirname grep awk sed cat; do ln -s "$(command -v "$t")" "$BARE/$t"; done
rc=0; PATH=$BARE PLAN_RUNNER_RUNS_DIR=$RUNS "$R/scripts/run-plan.sh" "$R/docs/plans/mini.md" > "$TMP/out" 2> "$TMP/err" || rc=$?
check "no jq: exit 1, named, no 'internal error'" "1 1 0" "$rc $(grep -c "'jq' is not on PATH" "$TMP/err" || true) $(grep -c 'internal error' "$TMP/err" || true)"
ln -s "$(command -v jq)" "$BARE/jq"
rc=0; PATH=$BARE PLAN_RUNNER_RUNS_DIR=$RUNS "$R/scripts/run-plan.sh" "$R/docs/plans/mini.md" > "$TMP/out" 2> "$TMP/err" || rc=$?
check "no claude: exit 1, named, nothing launched" "1 1 0" "$rc $(grep -c "'claude' is not on PATH" "$TMP/err" || true) $(wc -l < "$STUB/calls" | tr -d ' ')"

scenario "--base starts the plan branch from the named commit" done-valid
base=$(git -C "$R" rev-parse HEAD); echo later >> "$R/README.md"; git -C "$R" commit -qam later
run --base "$base"
check "exit 0"                                   "0" "$rc"
check "branch forked from --base, not from main's tip" "$base" "$(git -C "$R" merge-base main plan/mini)"

echo
if [ "$fail" = 0 ]; then echo "e2e: $n checks passed"; else echo "e2e: $fail of $n checks FAILED"; echo "  last driver stderr:"; sed 's/^/    /' "$TMP/err" | tail -20; exit 1; fi
