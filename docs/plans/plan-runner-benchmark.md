# Plan runner benchmark — which configuration needs the human least

Protocol for comparing two step configurations of `scripts/run-plan.sh` on one real plan. Written
2026-09-20, **before** any run, so the metrics and the decision rule cannot be fitted to the result.
Not a runnable plan (no `Order:` line) — the plan being run is `docs/plans/call-audio-routes.md`.

## The question

The goal of the runner is a multi-step plan implemented with the human stepping in only for
decisions that are really theirs. So the question is not "which model is cheapest" but: **under
which configuration does a plan get through with the fewest interventions, at acceptable quality —
and what does that cost?**

## What is compared

Both variants change only the untagged (`mid`) tier. A tagged step keeps its tier, so step 3 of the
plan (`model: strong`) is identical in both and is not a comparison point. That leaves four pairs:
steps 1, 2, 4, 5.

| Variant | Untagged steps run on | File |
|---|---|---|
| **a** | Fable, effort `low`, alone | `scripts/plan-runner/variants/a.env` |
| **b** | Opus, effort `medium`, with Fable as advisor (two prompted consults) | `scripts/plan-runner/variants/b.env` |
| reference | Sonnet, effort `high` — step 1 only, run 2026-09-14 on the old driver | branch `plan/call-audio-routes`, log `call-audio-routes.log` |

Why these two: Anthropic's cost guide says to price the advisor's model alone at low effort first —
"that is the baseline to beat" — and measures Opus + Fable-advisor as the most accurate coding
configuration it tested. The same guide measures the orchestrator shape (frontier model holds the
loop and delegates) as losing to a single model on work that is one dependent chain in one context,
which is what a plan step is; that shape is deliberately not a variant. On its SWE-bench Pro subset
Sonnet 5 at its default (77.4% at $0.84 per solved task) is dominated by Opus 5 at `low` (84.0% at
$0.25), so Sonnet is the reference, not a candidate.

Same for both: the `**Approach**` block, the tripwire rules in the prompt, escalation on, the judge
(Opus, `high`). Only b's prompt has the advisor section.

## Procedure

Run the variants one after the other, never at the same time — Gradle daemons are shared across
worktrees and concurrent gates distort the minutes. Both start from the same commit, which must
contain the 2026-09-20 runner changes (the step sessions read the result schema from their worktree).

Pin the base once, as a branch — it survives a closed terminal, needs no shell variable (the
commands below are the same in fish, bash and zsh), and unlike a tag it does not feed
`git describe`, which `versionName` is derived from:

```
git branch plan-base/call-audio-routes main
scripts/run-plan.sh docs/plans/call-audio-routes.md --dry-run --variant a --base plan-base/call-audio-routes

scripts/run-plan.sh docs/plans/call-audio-routes.md --variant a --base plan-base/call-audio-routes   # steps 1 → 5, one command
scripts/run-plan.sh docs/plans/call-audio-routes.md --variant b --base plan-base/call-audio-routes   # only after a has finished

scripts/plan-runner/report.sh call-audio-routes-a call-audio-routes-b call-audio-routes
```

Delete the branch when the benchmark is decided (`git branch -d plan-base/call-audio-routes`).

The plan's `‖` after step 1 was removed on 2026-09-20 for this benchmark: a forced stop puts a
human wait into the middle of both runs and makes the minutes meaningless, and it protects nothing
irreversible — the runner never merges or pushes. The price: a step 1 that is wrong in a way the
gate misses is only found at the end, after steps 2 to 5 of that variant were built on it. If a run
does stop (exit 2 or 3), the same command without `--base` continues it.

`report.sh` prints each run's base in its header — the two variants must show the same one. The
driver warns when a variant branch is created without `--base`, and when `--base` is ignored
because the branch already exists.

Spend is bounded per session, not per step: a step can at worst take two attempts at the $25 cap,
one nudge each ($5 resume or $8 review session) and the judge ($5) — about $70. The one real step so
far cost $3.62 all in.

While a run is going, change nothing on its branch — a hand fix is an intervention and is counted
as one. If a run stops (`blocked`, `needs_decision`), note the time you spent and what you did, then
continue the same variant. Step 1's fallout (the version bump it chose, tests it deleted, files
outside its scope) is read at the end, for both variants side by side.

The old branch `plan/call-audio-routes` stays untouched as the reference. It is stale (it conflicts
with main in `CHANGELOG.md`, and main has five more `sdk = [29]` test pins than it converted), so it
is not a merge candidate either way.

## What is measured

In priority order. The first four come from `report.sh` and the run log; the rest is read by hand.

1. **Autonomy** — stops that needed the human: `blocked`, `needs_decision`, and departures rejected
   at review. Each `needs_decision` is marked *legitimate* (a gap in the plan any configuration
   would hit — check whether the other variant hit it too) or *spurious*.
2. **Quality** — the judge's confirmed findings per step by severity, and its `testsAdequate`;
   the `@Test` delta and deleted test files; whether a later step had to repair an earlier one;
   the branch-to-branch diff per step (`git diff plan/call-audio-routes-a plan/call-audio-routes-b`),
   read where it is material. For step 1 the two diffs should be nearly identical — if they are not,
   that is a finding.
3. **Reliability** — first-pass validation, nudges, escalations, permission denials, budget hits.
4. **Cost** — dollars per validated step, everything the step caused included (nudges, review
   sessions, escalated attempts, advisor consults). The judge's cost is reported separately: it is
   the instrument, not the configuration.
5. **Time** — minutes from first launch to validated, and the human's own minutes.
6. **Variant b only** — `advisorConsults` per step, and from each `summary` whether the advice
   changed anything. If the consults do not happen, b is Opus `medium` alone and should be read so.

Judgment calls to compare by hand, because the reference run made them and they are where models
differ: step 1's version bump (the Sonnet run took 2.0.0 where the plan said minor), its deleted
regression test, and its edit of `CLAUDE.md`.

## Decision rule

Fixed now:

1. Fewer interventions wins.
2. Tie → fewer confirmed `high` findings, then fewer `medium`.
3. Tie → lower total cost.

The winner becomes `MODEL_MID` / `EFFORT_MID` / `ADVISOR_MID` in `scripts/run-plan.sh`, replacing the
interim default set on 2026-09-20 (Opus at `medium`, no advisor — variant b without its advisor);
its branch is the one merged. If both need the human on the same step for the same reason, that is a verdict on
the plan, not on either configuration — fix the plan and do not count it.

## What this can and cannot show

- Four pairs separate gross differences: one variant blocks and the other does not, the advisor is
  never consulted, one costs twice the other. They do not separate a 20% cost gap or a one-finding
  quality gap; on single runs those are noise.
- The pairs are not identical tasks: step N on branch a builds on a's own step N−1.
- The judge is a model grading models, and it is Opus — the executor of variant b. It grades
  against the step's spec, reports only findings it confirmed in the code, and works in a detached
  throwaway worktree whose path and `git status` do not name the variant; it could still find out
  (branch names are one `git` command away), so self-preference is reduced by that, not excluded. Read the `high`
  findings yourself before letting them decide a tie.
- Anthropic's numbers rank the options on its own benchmarks (tasks with complete test suites);
  they do not predict what these steps cost.
- This sets a default, it does not close the question. Every later plan adds rows to the same kind
  of log, and `report.sh` reads them all; a default that stops earning its place will show there.

## Evidence this protocol rests on

- The one real step so far: Sonnet/high, $1.84 + $1.77 nudge, 74 + 29 turns, 16 minutes, 6
  permission denials, validated after one nudge. 58% of the first session's cost was cache reads —
  context re-read on every turn — which is why Fable's cheaper cache reads matter in an agent loop
  and why a resumed nudge is the expensive way to run a review.
- Both real steps (this one and the runner's own pilot) failed first validation on a driver fault.
  Those faults are fixed and covered by `scripts/plan-runner/e2e.sh`; see
  `docs/plans/done/plan-runner.md` §5.
- Sources: [Optimizing for cost and intelligence](https://platform.claude.com/docs/en/about-claude/models/optimizing-for-cost-and-intelligence)
  (effort curves, re-run-failures policy, advisor and orchestrator measurements),
  [Claude Code advisor](https://code.claude.com/docs/en/advisor) (`--advisor`, pairings, each
  consult re-reads the transcript uncached),
  [Artificial Analysis — Anthropic](https://artificialanalysis.ai/providers/anthropic) (general
  index, not coding). All read 2026-09-19/20.

## Results

_To be filled in after the runs: the `report.sh` table, the interventions log, the hand-read notes,
and the decision._
