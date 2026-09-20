# Handoff: read out the plan-runner benchmark and set the default

## Your job

The user is benchmarking two configurations of `scripts/run-plan.sh` on the plan
`docs/plans/call-audio-routes.md`. The runs are launched by the user from a terminal (the runner
refuses to nest inside a Claude session). When both variants have finished, read the results out
against the protocol, fill in its Results section, and apply the decision rule. Do not re-derive
anything below — it is settled and lives in tracked files.

The user writes informal English, thinks out loud, and pushes back on claims without a source.
Separate measured from inferred. Their shell is **fish** (the Bash tool is zsh): no `VAR=$(…)` in
commands you hand them. Never feed `git commit` a heredoc on this host, and never put a heredoc in
the same Bash call as a `git commit` — a hook blocks it.

## Read these, in this order

1. `docs/plans/plan-runner-benchmark.md` — the protocol, written before any run: variants, metrics
   in priority order, the fixed decision rule, the limits. Its **Results** section is yours to fill.
2. `scripts/plan-runner/README.md` — how the driver runs, two flow diagrams, file map.
3. `docs/plans/done/plan-runner.md` §5 — what changed on 2026-09-20 and why.
4. Local memory `project_plan_runner.md` — decisions and history.

## State when this was written (2026-09-20, ~23:30 UTC)

- Local `main` is at `04bfe9f0`, **unpushed**. Commits of this work: `bc3b6245` (driver rework),
  `7679148b`, `69d86557` (checkpoint after step 1 removed), `e1418c2c` (mid tier default is now
  opus/medium), `04bfe9f0` (base pinned as a branch).
- Base of both variants: branch `plan-base/call-audio-routes` → `04bfe9f0`. Delete it when the
  benchmark is decided.
- **Variant a** (Fable low alone) was running: steps 1 and 2 validated first pass — $3.27 / 58
  turns / 5 min and $2.68 / 40 turns / 5 min, 0 nudges, judge `meets_spec` with 0 high findings
  on both. Step 3 (tagged `model: strong` → opus/xhigh, identical in both variants by design) had
  just launched. **Variant b** (Opus medium + Fable advisor) not started; it must run only after a
  has finished (shared Gradle daemons).
- Commands (shell-neutral):
  `scripts/run-plan.sh docs/plans/call-audio-routes.md --variant b --base plan-base/call-audio-routes`
  then `scripts/plan-runner/report.sh call-audio-routes-a call-audio-routes-b call-audio-routes`.
  A stopped run (exit 2 or 3) continues with the same command.

## What to do

1. `scripts/plan-runner/report.sh call-audio-routes-a call-audio-routes-b call-audio-routes`
   (the third id is the old Sonnet/high reference, step 1 only). Check both headers show the same
   base. Raw data: `docs/plans/.runs/` (gitignored): `*.log`, `*.result.json`, `*.judge.json`.
2. Count interventions per variant (every `blocked` / `needs_decision`, plus anything the user did
   by hand — ask them). Read the judge's `high` and `medium` findings yourself before letting them
   decide anything; the judge is Opus, which is also variant b's executor.
3. For variant b: did the advisor consults happen (`adv` column, `advisorConsults`, each result's
   `summary`)? If not, b is just Opus medium and must be read that way.
4. By hand: step 1's version bump, deleted test and out-of-scope edits in both variants
   (`git diff plan/call-audio-routes-a plan/call-audio-routes-b`); the Shipped blocks' departures.
5. Apply the decision rule from the protocol, fill in Results, set `MODEL_MID` / `EFFORT_MID` /
   `ADVISOR_MID` in `scripts/run-plan.sh` to the winner (update the dry-run expectations in
   `selfcheck.sh` and `e2e.sh`; `scripts/plan-runner/selfcheck.sh` must stay green: 160 checks).
   Be honest about power: four pairs separate gross differences only.

## Open items the user knows about

- **Step 3 was held constant on purpose**, and the user questioned that. Agreed follow-up, only
  after both variants are done: re-run step 3 from the commit where step 2 ended on variant a's
  branch (`8678ab31`) with a variant whose `strong` tier is Fable at `high` — both step-3 attempts
  then start from identical code. The driver has no `--to N`; without one the run continues
  through steps 4 and 5.
- The judge costs about as much as the step it grades ($3.49, $2.69 on steps 1–2): fine for a
  benchmark, too expensive as a default. Its findings are logged, never acted on; feeding confirmed
  `high` findings back as a nudge is the obvious next step once a default exists.
- Nothing has been pushed. The user pushes; the runner never does.

## Suggested skills

- `claude-api` before any claim about model prices or effort — but its bundled cost numbers are
  dated 2026-06-24 and stale; fetch the live guide (see `docs/GOTCHAS.md`, last entry).
- `code-review` if you change the driver beyond the three tunables.
