#!/usr/bin/env bash
# End-to-end check of the driver's control flow — nudge, fresh review session, escalation,
# judge — against a stub `claude` and a stub `gradlew` in a scratch repository. No session is
# started and nothing is spent. Run by selfcheck.sh (and so by CI), or by hand:
#   scripts/plan-runner/e2e.sh
#
# Each scenario queues one behaviour per `claude` invocation; the stub pops the queue, acts in
# the worktree like a step session would (commits, Shipped line) and prints a result object.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
export GIT_AUTHOR_NAME=e2e GIT_AUTHOR_EMAIL=e2e@example.invalid GIT_COMMITTER_NAME=e2e GIT_COMMITTER_EMAIL=e2e@example.invalid
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null

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
kind=step; effort='?'; advisor=none; prev=''
for a in "$@"; do
    case "$prev" in --effort) effort=$a ;; --advisor) advisor=$a ;; -n) case "$a" in "plan review"*) kind=judge ;; esac ;; esac
    [ "$a" = --resume ] && kind=nudge
    prev=$a
done
prompt=${!#}
case "$prompt" in *"requires skills the step session did not"*) kind=review ;; esac
beh=$(head -1 "$STUB/queue" || true); [ -n "$beh" ] || beh=unexpected
tail -n +2 "$STUB/queue" > "$STUB/queue.next" || true; mv "$STUB/queue.next" "$STUB/queue"
echo "$kind:$beh:$effort:$advisor" >> "$STUB/calls"
case "$prompt" in *"## Earlier attempt"*) echo "$kind:$beh" >> "$STUB/saw-attempt-block" ;; esac
PLAN=docs/plans/mini.md
echo "$kind:$beh:$(grep -c '^\*\*Decision taken\*\*' "$PLAN" || true)" >> "$STUB/saw-decision"
[ "$kind" != judge ] || pwd > "$STUB/judge-cwd"
code() { # code <path>  → one production file and one test, committed
    mkdir -p "$(dirname "$1")" app/src/test/java/x
    echo "// $beh $RANDOM" >> "$1"; printf '    @Test fun t%s() {}\n' "$RANDOM" >> app/src/test/java/x/SeedTest.kt
    git add "$1" app/src/test; git commit -qm "feat(x): $beh"
}
ship() { # ship <skills>  → Shipped line naming the last code commit, committed
    local h; h=$(git log --format=%h -1 -- app)
    printf '\n**Shipped** `%s` (2026-09-20) — tier: mid. skills: %s. Reviewer models: none.\nDepartures (for sign-off): none\n' "$h" "$1" >> "$PLAN"
    git add "$PLAN"; git commit -qm "docs(plan): step 1 shipped"
}
result() { # result <status> <blockedKind|null> <run-json>
    local kindjson=null; [ "$2" = null ] || kindjson="\"$2\""
    printf '{"type":"result","subtype":"success","is_error":false,"session_id":"s-%s-%s","num_turns":5,"total_cost_usd":1.5,"permission_denials":[],"modelUsage":{"stub-model":{"costUSD":1.5}},"structured_output":{"status":"%s","step":1,"commit":%s,"skills":{"intended":[],"run":%s,"skipped":[]},"reviewerModels":[],"question":null,"blockedKind":%s,"advisorConsults":null,"summary":"%s"}}\n' \
        "$kind" "$beh" "$1" "$( [ "$1" = done ] && printf '"%s"' "$(git log --format=%h -1 -- app 2>/dev/null || echo 0000000)" || echo null)" "$3" "$kindjson" "$beh"
}
case "$beh" in
    done-valid)      echo green > "$STUB/gate"; code app/src/main/java/x/ui/Screen.kt; ship none; result done null '[]' ;;
    done-valid-di)   code app/src/main/java/x/di/Module.kt; ship none; result done null '[]' ;;
    done-no-shipped) code app/src/main/java/x/ui/Screen.kt; result done null '[]' ;;
    done-no-shipped-di) code app/src/main/java/x/di/Module.kt; result done null '[]' ;;
    claims-review)   result done null '["code-review"]' ;;
    needs-decision)  printf '\n**Decision needed** — left or right?\n' >> "$PLAN"; result needs_decision null '[]' ;;
    budget)          printf '{"type":"result","subtype":"error_max_budget_usd","is_error":true,"session_id":"s-budget","total_cost_usd":25,"structured_output":null}\n' ;;
    judge-nofindings) printf '{"type":"result","subtype":"success","is_error":false,"session_id":"s-judge","total_cost_usd":0.5,"structured_output":{"verdict":"cannot_judge","testsAdequate":true,"summary":"x"}}\n' ;;
    done-gate-red)   code app/src/main/java/x/ui/Screen.kt; ship none; echo red > "$STUB/gate"; result done null '[]' ;;
    noop-done)       result done null '[]' ;;
    blocked-gate)    echo wip >> README.md; echo scratch > leftover.txt; result blocked gate '[]' ;;
    blocked-env)     result blocked environment '[]' ;;
    review-ok)       sed -i 's/skills: none\./skills: code-review./' "$PLAN"; git add "$PLAN"; git commit -qm "docs(plan): review"; result done null '["code-review"]' ;;
    judge-ok)        printf '{"type":"result","subtype":"success","is_error":false,"session_id":"s-judge","num_turns":3,"total_cost_usd":0.75,"permission_denials":[],"structured_output":{"verdict":"meets_spec","findings":[{"axis":"spec","severity":"medium","file":null,"summary":"m"},{"axis":"standards","severity":"low","file":"a.kt","summary":"l"}],"testsAdequate":false,"summary":"ok"}}\n' ;;
    judge-writes)    echo tampered >> README.md; printf '{"type":"result","subtype":"success","is_error":false,"session_id":"s-judge","structured_output":{"verdict":"meets_spec","findings":[],"testsAdequate":true,"summary":"x"}}\n' ;;
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
    sed -i 's/^NOTIFY_CMD=.*/NOTIFY_CMD=true/' "$r/scripts/run-plan.sh"      # no desktop notifications from a test
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
    : > "$STUB/calls"; : > "$STUB/saw-attempt-block"; : > "$STUB/saw-decision"; rm -f "$STUB/judge-cwd"; echo green > "$STUB/gate"
    printf '%s\n' "$@" > "$STUB/queue"
}
run() { rc=0; PLAN_RUNNER_RUNS_DIR=$RUNS "$R/scripts/run-plan.sh" "$R/docs/plans/mini.md" "$@" > "$TMP/out" 2> "$TMP/err" || rc=$?; }
requeue() { printf '%s\n' "$@" > "$STUB/queue"; }     # a second invocation on the same scratch repo
events() { jq -r '.event' "$RUNS/$1.log" | paste -sd' ' -; }
WT() { echo "$R/.claude/worktrees/plan-$1"; }
calls() { paste -sd' ' - < "$STUB/calls"; }

scenario "A plain step validates" done-valid
run
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result validated" "$(events mini)"
check "one session, default config, no advisor"  "step:done-valid:medium:none" "$(calls)"
check "validated event carries the @Test delta"  "1 2" "$(jq -r 'select(.event=="validated") | "\(.tests_before) \(.tests_after)"' "$RUNS/mini.log")"
check "the launch records the base it forked from" "$(git -C "$R" rev-parse main | cut -c1-8)" "$(jq -r 'select(.event=="launched") | .base' "$RUNS/mini.log")"

scenario "--to stops cleanly before the next step" done-valid
# Step 2's section sits above step 1's on purpose: the stub appends its Shipped line to the end
# of the file, and it has to land under step 1.
printf '# Mini plan\n\n**Order: 1 → 2**\n\n## 3. Steps\n\n### Step 2 — Never reached (`feat(x):`)\nBody.\n\n### Step 1 — First step (`feat(x):`)\nBody.\n' > "$R/docs/plans/mini.md"
git -C "$R" commit -qam "two steps"
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
check "events"                                   "launched result validated judged" "$(events mini-j)"
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
check "events"                                   "launched result validated judged" "$(events mini-j)"
check "counts default to 0"                      "cannot_judge 0 0 0" "$(jq -r 'select(.event=="judged") | "\(.verdict) \(.high) \(.medium) \(.low)"' "$RUNS/mini-j.log")"

scenario "A judge that writes anyway loses its grade; the plan and its branch are untouched" done-valid judge-writes
run --variant j
check "exit 0"                                   "0" "$rc"
check "events"                                   "launched result validated judge_failed" "$(events mini-j)"
check "the plan worktree is clean"               "" "$(git -C "$(WT mini-j)" status --porcelain)"

scenario "A typo in a variant or a heading tag is a config error, not a 'driver bug'" done-valid
printf 'EFFORT_MID=medum\n' > "$R/scripts/plan-runner/variants/typo.env"
run --variant typo
check "bad effort in a variant: exit 1, plain message" "1 0" "$rc $(grep -c 'internal error' "$TMP/err" || true)"
sed -i 's/^### Step 1 — Only step.*/&  — effort: infinite/' "$R/docs/plans/mini.md"; git -C "$R" commit -qam "bad tag"
run
check "bad effort tag in the plan: exit 1, plain message" "1 0" "$rc $(grep -c 'internal error' "$TMP/err" || true)"

scenario "--base starts the plan branch from the named commit" done-valid
base=$(git -C "$R" rev-parse HEAD); echo later >> "$R/README.md"; git -C "$R" commit -qam later
run --base "$base"
check "exit 0"                                   "0" "$rc"
check "branch forked from --base, not from main's tip" "$base" "$(git -C "$R" merge-base main plan/mini)"

echo
if [ "$fail" = 0 ]; then echo "e2e: $n checks passed"; else echo "e2e: $fail of $n checks FAILED"; echo "  last driver stderr:"; sed 's/^/    /' "$TMP/err" | tail -20; exit 1; fi
