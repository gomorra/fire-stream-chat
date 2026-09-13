#!/usr/bin/env bash
# Self-check for the plan runner: lib.sh's pure functions against fixtures/,
# a syntax pass over run-plan.sh, and one --dry-run of the driver on the
# fixture plan. Runs in CI (.github/workflows/plan-runner.yml) and by hand:
#   scripts/plan-runner/selfcheck.sh
# A broken parser or tripwire must be found here, not by an unattended run.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib.sh
source "$HERE/lib.sh"
F=$HERE/fixtures
PLAN=$F/plan.md
DEFAULT_BUDGET=25
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

fail=0; n=0
check() { # check <name> <expected> <actual>
    n=$((n + 1))
    if [ "$2" = "$3" ]; then
        printf '  ok   %s\n' "$1"
    else
        fail=$((fail + 1))
        printf '  FAIL %s\n       expected: %q\n       actual:   %q\n' "$1" "$2" "$3"
    fi
}
check_rc() { # check_rc <name> <expected-rc> <cmd…>
    local name=$1 want=$2 rc=0; shift 2
    "$@" >/dev/null 2>&1 || rc=$?
    check "$name" "$want" "$rc"
}
lines() { printf '%s' "$1" | tr '\n' ' ' | sed 's/ $//'; }

echo "Order line"
tokens=$(pr_order_tokens "$PLAN" 2>"$TMP/err")
check "tokens: → + ‖ and trailing prose"      "1 2 3 4 CP 5 6" "$(lines "$tokens")"
check "warns once about +"                    "1" "$(grep -c "parallel steps run sequentially" "$TMP/err")"
printf '**Order:** 1 → 2 → 3+4 → 5\n' > "$TMP/o1.md"
check "form: **Order:** 1 → 2"                "1 2 3 4 5" "$(lines "$(pr_order_tokens "$TMP/o1.md" 2>/dev/null)")"
printf '`Order: 1 → 2 → 5a → 5b → 6`\n' > "$TMP/o2.md"
check "form: backticked with 5a/5b ids"       "1 2 5a 5b 6" "$(lines "$(pr_order_tokens "$TMP/o2.md")")"
printf '**Order: 1 → 2 → 3 → 4 → 5 → 6 → 7** (fully sequential — steps 4–7 all edit\n' > "$TMP/o3.md"
check "form: prose after the closing stars"   "1 2 3 4 5 6 7" "$(lines "$(pr_order_tokens "$TMP/o3.md")")"
printf '# no order here\n' > "$TMP/o4.md"
check_rc "missing Order line fails"           1 pr_order_tokens "$TMP/o4.md"
printf '**Order:** see the steps below\n' > "$TMP/o5.md"
check_rc "Order line without steps fails"     1 pr_order_tokens "$TMP/o5.md"
check "step id numeric prefix"                "5" "$(pr_step_num 5a)"

echo "Headings and sections"
check "heading of step 2 carries its tags"    "### Step 2 — Tagged step (\`feat(x):\`, no CHANGELOG) — skills: code-review, simplify; model: strong; budget: 12" "$(pr_step_heading "$PLAN" 2)"
check_rc "heading of a missing step fails"    1 pr_step_heading "$PLAN" 42
check "section of step 5 stops at next ###"   "The word **Shipped** inside prose, not at line start, must not count as shipped." "$(pr_step_section "$PLAN" 5 | grep -v '^$')"
check "section of step 7 stops at ## heading" "0" "$(pr_step_section "$PLAN" 7 | grep -c deadbee || true)"
check "step 1 matches only step 1, not 10+"   "### Step 1 — Already done (\`docs:\`) — skills: none" "$(pr_step_heading "$PLAN" 1)"
printf '### Step 5 — five\nbody5\n### Step 5a — five-a\nbody5a\n' > "$TMP/ids.md"
check "step 5 does not match step 5a"         "body5" "$(pr_step_section "$TMP/ids.md" 5)"
check "step 5a is found"                      "body5a" "$(pr_step_section "$TMP/ids.md" 5a)"

echo "Shipped / Decision blocks"
check_rc "step 1 is shipped"                  0 pr_step_shipped "$PLAN" 1
check_rc "step 2 is not shipped"              1 pr_step_shipped "$PLAN" 2
check_rc "inline 'Shipped' prose does not count" 1 pr_step_shipped "$PLAN" 5
check_rc "Shipped under a ## heading counts for no step" 1 pr_step_shipped "$PLAN" 6
check_rc "step 3 has a pending decision"      0 pr_step_decision_pending "$PLAN" 3
check_rc "step 2 has no pending decision"     1 pr_step_decision_pending "$PLAN" 2
check "shipped commit of step 1"              "abc1234" "$(pr_shipped_commit "$PLAN" 1)"
check "shipped commit of step 7"              "0123abc" "$(pr_shipped_commit "$PLAN" 7)"
check "shipped commit of unshipped step 2"    "" "$(pr_shipped_commit "$PLAN" 2)"
check "shipped skills of step 1 (none)"       "" "$(pr_shipped_skills "$PLAN" 1)"
check "shipped skills of step 7"              "code-review simplify" "$(lines "$(pr_shipped_skills "$PLAN" 7)")"
# shellcheck disable=SC2086
check "last shipped among order tokens"       "1" "$(pr_last_shipped "$PLAN" $tokens)"

echo "Heading tags"
h1=$(pr_step_heading "$PLAN" 1); h2=$(pr_step_heading "$PLAN" 2); h4=$(pr_step_heading "$PLAN" 4)
h5=$(pr_step_heading "$PLAN" 5); h6=$(pr_step_heading "$PLAN" 6)
check "skills tag: two skills"                "code-review simplify" "$(lines "$(pr_tag_skills "$h2")")"
check "skills tag: none"                      "" "$(pr_tag_skills "$h1")"
check "skills tag: absent"                    "" "$(pr_tag_skills "$h5")"
check "skills tag: single"                    "app-ui-design" "$(pr_tag_skills "$h6")"
check "tier tag: strong"                      "strong" "$(pr_tag_tier "$h2")"
check "tier tag: max"                         "max" "$(pr_tag_tier "$h4")"
check "tier tag: untagged → mid"              "mid" "$(pr_tag_tier "$h5")"
check_rc "tier tag: unknown value fails"      1 pr_tag_tier "### Step 9 — x — model: huge"
check "effort tag: present"                   "low" "$(pr_tag_effort "$h6" xhigh)"
check "effort tag: absent → tier default"     "xhigh" "$(pr_tag_effort "$h2" xhigh)"
check_rc "effort tag: unknown value fails"    1 pr_tag_effort "### Step 9 — x — effort: infinite" high
check "budget tag: present"                   "12" "$(pr_tag_budget "$h2" "$DEFAULT_BUDGET")"
check "budget tag: absent → default"          "25" "$(pr_tag_budget "$h5" "$DEFAULT_BUDGET")"
check "budget tag: decimal"                   "7.5" "$(pr_tag_budget "### Step 9 — x — budget: 7.5" "$DEFAULT_BUDGET")"
check "join: list"                            "a, b" "$(pr_join $'a\nb')"
check "join: empty → word"                    "none" "$(pr_join "" none)"

echo "Tier cap"
check "cap lowers max to strong"              "strong" "$(pr_cap_tier max strong)"
check "cap does not raise mid"                "mid" "$(pr_cap_tier mid max)"
check "no cap leaves tier"                    "strong" "$(pr_cap_tier strong '')"
check "cap equal leaves tier"                 "max" "$(pr_cap_tier max max)"

echo "Tripwire"
check "big diff → simplify"                   "simplify" "$(lines "$(pr_tripwire "$F/numstat-big.txt" "$F/names-app.txt")")"
check "small diff, plain ui → nothing"        "" "$(pr_tripwire "$F/numstat-small.txt" "$F/names-app.txt")"
check "crypto path → code-review"             "code-review" "$(pr_tripwire "$F/numstat-small.txt" "$F/names-crypto.txt")"
check "worker path → code-review"             "code-review" "$(pr_tripwire "$F/numstat-small.txt" "$F/names-worker.txt")"
check "two ViewModels → code-review"          "code-review" "$(pr_tripwire "$F/numstat-small.txt" "$F/names-two-viewmodels.txt")"
check "one ViewModel → nothing"               "" "$(pr_tripwire "$F/numstat-small.txt" "$F/names-one-viewmodel.txt")"
check "big + crypto → both"                   "simplify code-review" "$(lines "$(pr_tripwire "$F/numstat-big.txt" "$F/names-crypto.txt")")"
check "binary rows (- -) are ignored"         "" "$(pr_tripwire "$F/numstat-small.txt" "$F/names-docs.txt")"
: > "$TMP/empty.txt"
check "empty diff → nothing"                  "" "$(pr_tripwire "$TMP/empty.txt" "$TMP/empty.txt")"
check_rc "docs-only diff needs no gate"       1 pr_needs_gate "$F/names-docs.txt"
check_rc "app diff needs the gate"            0 pr_needs_gate "$F/names-app.txt"

echo "Required vs run"
floor=$(pr_tag_skills "$h2")
required=$(pr_union "$floor" "$(pr_tripwire "$F/numstat-small.txt" "$F/names-app.txt")")
check "required = floor when tripwire is quiet" "code-review simplify" "$(lines "$required")"
required=$(pr_union "$floor" "$(pr_tripwire "$F/numstat-big.txt" "$F/names-crypto.txt")")
check "union deduplicates"                    "code-review simplify" "$(lines "$required")"
run=$(jq -r '.structured_output.skills.run[]?' "$F/result-missing-skill.json")
check "result missing a tagged skill"         "code-review" "$(lines "$(pr_missing "$required" "$run")")"
check "nothing missing when all ran"          "" "$(pr_missing "$required" "$(printf 'simplify\ncode-review\n')")"
check "empty required → nothing missing"      "" "$(pr_missing "" "$run")"

echo "Result classification"
check "success + object → complete"           "complete"   "$(pr_result_kind "$F/result-complete.json")"
check "success + null output → incomplete"    "incomplete" "$(pr_result_kind "$F/result-incomplete.json")"
check "budget exhausted → budget"             "budget"     "$(pr_result_kind "$F/result-budget.json")"
check "execution error → failed"              "failed"     "$(pr_result_kind "$F/result-failed.json")"
check "empty file → failed"                   "failed"     "$(pr_result_kind "$F/result-empty.json")"
check "missing file → failed"                 "failed"     "$(pr_result_kind "$TMP/nope.json")"

echo "Result fields"
check "field from a complete result"          "s-complete" "$(pr_result_field "$F/result-complete.json" .session_id)"
check "nested field"                          "done" "$(pr_result_field "$F/result-complete.json" .structured_output.status)"
check "number as text"                        "0.5" "$(pr_result_field "$F/result-complete.json" .total_cost_usd)"
check "null field → null"                     "null" "$(pr_result_field "$F/result-complete.json" .structured_output.question)"
check "false is not null"                     "false" "$(pr_result_field "$F/result-complete.json" .is_error)"
check "empty file → default"                  "null" "$(pr_result_field "$F/result-empty.json" .session_id)"
check "empty file → given default"            "0" "$(pr_result_field "$F/result-empty.json" .total_cost_usd 0)"
check "missing file → default"                "null" "$(pr_result_field "$TMP/nope.json" .session_id)"
check "json field from a complete result"     "[]" "$(pr_result_json "$F/result-complete.json" .permission_denials '[]')"
check "json number"                           "0.5" "$(pr_result_json "$F/result-complete.json" .total_cost_usd 0)"
check "json field from an empty file → default" "[]" "$(pr_result_json "$F/result-empty.json" .permission_denials '[]')"
check "json default is valid for --argjson"   "ok" "$(jq -nr --argjson d "$(pr_result_json "$F/result-empty.json" .permission_denials '[]')" '"ok"')"
check "schema has no \$schema key (the CLI rejects draft URIs)" "0" "$(grep -c '"\$schema"' "$HERE/step-result.schema.json" || true)"

echo "Checkpoints"
check_rc "due when shipped in this run"                 0 pr_checkpoint_due "$F/run.log" 9 1
check_rc "not due: no log at all"                       1 pr_checkpoint_due "$TMP/nolog" 1 0
check_rc "not due: step 1 already had its checkpoint"   1 pr_checkpoint_due "$F/run.log" 1 0
check_rc "due: step 2 launched, finished by resume, no checkpoint yet" 0 pr_checkpoint_due "$F/run.log" 2 0
check_rc "not due: step 3 never touched by the runner"  1 pr_checkpoint_due "$F/run.log" 3 0

echo "Prompt template"
TPL=$HERE/step-prompt.md
commits=$'abc1234 feat: one\n0123abc docs(plan): two'
special='a $HOME & \\backslash "quoted" `tick` {{NOT_A_KEY}}'
out=$(pr_render "$TPL" "PLAN_PATH=.claude/plans/x.md" "PLAN_NAME=x" "STEP=2" \
    "STEP_HEADING=$h2" "TIER=strong" "TAGGED_TIER=max" "SKILLS_FLOOR=code-review, simplify" \
    "BRANCH=plan/x" "BASE=deadbeef" "COMMITS=$commits" "SCHEMA_PATH=scripts/plan-runner/step-result.schema.json")
check "header comment stripped"               "0" "$(printf '%s' "$out" | grep -c '^<!--' || true)"
check "every placeholder filled"              "" "$(pr_unfilled "$(printf '%s' "$out" | sed 's/{{NOT_A_KEY}}//')")"
check "multi-line value kept"                 "2" "$(printf '%s' "$out" | grep -cE '^(abc1234 feat: one|0123abc docs\(plan\): two)$')"
out2=$(pr_render "$TPL" "STEP=$special")
check "special characters pass through verbatim" "1" "$(printf '%s' "$out2" | grep -cF "Implement **step $special** of" )"
check "unfilled placeholders are reported"    "{{BASE}}" "$(pr_unfilled "$(pr_render "$TPL" "STEP=1" | grep -m1 -o '{{BASE}}')")"

echo "Driver"
check_rc "run-plan.sh parses"                 0 bash -n "$HERE/../run-plan.sh"
dry=$(PLAN_RUNNER_RUNS_DIR=$TMP/runs "$HERE/../run-plan.sh" "$PLAN" --dry-run --cap strong 2>"$TMP/dry.err") || { echo "  dry-run exit $? — stderr:"; sed 's/^/    /' "$TMP/dry.err"; fail=$((fail + 1)); }
check "dry run skips shipped step 1"          "0" "$(printf '%s' "$dry" | grep -c '^step 1 ' || true)"
check "dry run lists step 2 with its tags"    "1" "$(printf '%s' "$dry" | grep -c '^step 2 .*skills: code-review, simplify; model: strong; budget: 12' || true)"
check "dry run applies the cap and the budget tag" "1" "$(printf '%s' "$dry" | grep -c 'tier strong (tagged strong) → model opus, effort xhigh, budget \$12' || true)"
check "dry run flags the pending decision on step 3" "1" "$(printf '%s' "$dry" | grep -c '^step 3 .*Decision needed' || true)"
check "dry run caps max to strong on step 4"  "1" "$(printf '%s' "$dry" | grep -c 'tier strong (tagged max)' || true)"
check "dry run marks the checkpoint after step 4" "1" "$(printf '%s' "$dry" | grep -c 'would stop here after step 4' || true)"
check "dry run lists steps 5 and 6"           "2" "$(printf '%s' "$dry" | grep -cE '^step (5|6) ' || true)"
check "dry run honours the effort tag on step 6" "1" "$(printf '%s' "$dry" | grep -c 'tier mid (tagged mid) → model sonnet, effort low' || true)"
check "dry run keeps xhigh when the cap lowers the model" "1" "$(printf '%s' "$dry" | grep -c 'tier strong (tagged max) → model opus, effort xhigh' || true)"
check "dry run renders a prompt per runnable step (2, 4, 5, 6; not the pending 3)" "4" "$(ls "$TMP/runs/plan.step"*.prompt.md 2>/dev/null | wc -l)"
check "dry run prompt has no placeholders left" "0" "$(cat "$TMP/runs/plan.step2."*.prompt.md | grep -c '{{' || true)"
check "usage exits 1 without a plan"          "1" "$("$HERE/../run-plan.sh" >/dev/null 2>&1; echo $?)"
check "--help exits 0"                        "0" "$("$HERE/../run-plan.sh" --help >/dev/null 2>&1; echo $?)"
check "--from without a value exits 1"        "1" "$("$HERE/../run-plan.sh" "$PLAN" --from >/dev/null 2>&1; echo $?)"
check "bad --cap exits 1"                     "1" "$("$HERE/../run-plan.sh" "$PLAN" --cap huge --dry-run >/dev/null 2>&1; echo $?)"

echo
if [ "$fail" = 0 ]; then
    echo "selfcheck: $n checks passed"
else
    echo "selfcheck: $fail of $n checks FAILED"; exit 1
fi
