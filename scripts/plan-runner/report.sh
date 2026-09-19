#!/usr/bin/env bash
# Per-step table from one or more plan-runner logs — the read-out for comparing variants.
#
#   scripts/plan-runner/report.sh <run-id>…        e.g. call-audio-routes-a call-audio-routes-b
#
# A run id is the log's file stem in docs/plans/.runs/ (<plan> or <plan>-<variant>).
# Columns: config = model/effort(+advisor) of the last launch · att = sessions launched ·
# ndg = nudges (resume or fresh review) · esc = escalations · adv = advisor consults the
# sessions reported · usd / turns / deny = summed over every session of the step, judge
# excluded · min = first launch → validated, which includes any wait for the human ·
# tests = @Test delta · judge = high/medium/low findings and the judge's own cost, or what a
# failed judge cost. The header names the commit the run's branch forked from: two variants
# are only comparable from the same base. Numbers only; reading the departures and the diffs
# stays with the human.
set -euo pipefail
ROOT=$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)
RUNS=${PLAN_RUNNER_RUNS_DIR:-$ROOT/docs/plans/.runs}
[ $# -ge 1 ] || { sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

for id in "$@"; do
    log=$RUNS/$id.log
    [ -f "$log" ] || { echo "no log: $log" >&2; exit 1; }
    echo "== $id (base $(jq -rs 'map(select(.event == "launched" and .base != null) | .base) | unique | if length == 0 then "not recorded" else join(", ") end' "$log"))"
    {
        printf 'step\tconfig\tatt\tndg\tesc\tadv\tusd\tturns\tdeny\tmin\toutcome\ttests\tjudge\n'
        jq -rs '
            def ts: sub("Z$"; "") | strptime("%Y-%m-%dT%H:%M:%S") | mktime;
            map(select(.step != null)) | group_by(.step) | map(
                . as $e
                | ($e | map(select(.event == "launched"))) as $l
                | ($e | map(select(.event == "result"))) as $r
                | ($e | map(select(.event == "validated")) | last) as $v
                | ($e | map(select(.event == "judged")) | last) as $j
                | ($e | map(select(.event == "judge_failed")) | last) as $jf
                | { step: $e[0].step,
                    config: (($l | last) as $x | if $x == null then "-" else
                        "\($x.model)/\($x.effort // "?")" + (if ($x.advisor // "none") != "none" then "+\($x.advisor)" else "" end) end),
                    att: ($l | length),
                    ndg: ($e | map(select(.event == "nudged" or .event == "review")) | length),
                    esc: ($e | map(select(.event == "escalated")) | length),
                    adv: ($r | map(.consults | select(. != null)) | if length == 0 then "-" else add end),
                    usd: ($r | map(.cost // 0) | add // 0),
                    turns: ($r | map(.turns // 0) | add // 0),
                    deny: ($r | map(.denials // [] | length) | add // 0),
                    min: (if $v != null and ($l | length) > 0 then ((($v.ts | ts) - ($l[0].ts | ts)) / 60 | floor) else null end),
                    outcome: (if $v != null then "validated"
                        elif ($e | any(.event == "blocked")) then "blocked"
                        elif ($e | any(.event == "needs_decision")) then "needs_decision" else "open" end),
                    tests: (if $v != null and $v.tests_before != null then
                        "\($v.tests_before)→\($v.tests_after)" + (if ($v.deleted_tests // "") != "" then " (-files)" else "" end) else "-" end),
                    judge: (if $j != null then "\($j.high)/\($j.medium)/\($j.low) $\($j.cost * 100 | round / 100)"
                        elif $jf != null then "failed $\(($jf.cost // 0) * 100 | round / 100)" else "-" end) })
            | sort_by(.step | tonumber? // 0)
            | (.[] | [.step, .config, .att, .ndg, .esc, .adv, (.usd * 100 | round / 100), .turns, .deny, (.min // "-"), .outcome, .tests, .judge]),
              ["total", "", (map(.att) | add), (map(.ndg) | add), (map(.esc) | add), (map(.adv | numbers) | add // "-"), (map(.usd) | add * 100 | round / 100),
               (map(.turns) | add), (map(.deny) | add), (map(.min // 0) | add), "", "", ""]
            | @tsv' "$log"
    } | if command -v column >/dev/null 2>&1; then column -t -s $'\t'; else cat; fi
    echo
done
