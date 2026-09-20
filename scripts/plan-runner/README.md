# Plan runner — how it runs

`scripts/run-plan.sh` is a bash loop with no model in it. It starts one fresh headless Claude
session per step, checks the branch itself before it believes a result, and stops for the human in
three situations. Contract and rationale: `docs/plans/done/plan-runner.md` (§5 for escalation,
variants, the review session and the judge). Keep the two diagrams below in step with
`handle_result` and the main loop when either changes. The same diagrams, with a who-writes-what
figure and the exit-code table, are a standalone page: `flow.html` next to this file (open it in a
browser; it loads mermaid and its fonts from a CDN).

## The loop over the plan

The plan's `Order:` line is the program: a number is a step, `‖` is a checkpoint. A step is done
only when a `**Shipped**` block sits under its heading in the branch's copy of the plan — that is
the runner's whole state, which is why a run can stop anywhere and be started again.

```mermaid
flowchart TD
  start(["run-plan.sh plan.md --variant b"]) --> setup["load the variant file, create or reuse the worktree"]
  setup --> byhand{"last shipped step launched by the runner but never validated?"}
  byhand -- "yes: finished by hand" --> v0["validate it first"]
  byhand -- "no" --> tok
  v0 --> tok{"next token of the Order line"}
  tok -- "checkpoint after a step this run shipped" --> e4(["exit 4: checkpoint"])
  tok -- "a step" --> shipped{"Shipped block under its heading?"}
  shipped -- "yes" --> tok
  shipped -- "no" --> pend{"unanswered Decision needed block?"}
  pend -- "yes" --> e2(["exit 2: needs a decision"])
  pend -- "no" --> run[["run one step"]]
  run -- "validated" --> tok
  run -- "needs_decision" --> e2
  run -- "blocked" --> e3(["exit 3: blocked"])
  tok -- "none left" --> e0(["exit 0: plan complete"])
```

## One step

A step gets at most three kinds of help, each once — per step, across re-runs of the driver: a
nudge, then a fresh attempt one effort rung higher, then the human.

```mermaid
flowchart TD
  L["launch a fresh session: tier's model, effort, advisor, budget"] --> K{"what came back?"}
  K -- "budget spent, or no JSON at all" --> B(["exit 3: blocked"])
  K -- "ended without the result object" --> N
  K -- "a complete result" --> S{"status"}
  S -- "needs_decision" --> D(["exit 2: needs a decision"])
  S -- "blocked: spec or environment" --> B
  S -- "blocked: gate" --> E
  S -- "done" --> V["validate on the branch, trusting nothing"]
  V --> OK{"valid?"}
  OK -- "yes" --> T["log validated, with the test-count delta"]
  T --> J["judge pass in a throwaway worktree, if the variant sets one"]
  J --> X(["next token"])
  OK -- "no" --> U{"nudge already spent?"}
  U -- "no, and only skills are missing" --> R["fresh review session runs them on the diff"]
  U -- "no, anything else" --> N["nudge: resume the same session with the reasons"]
  R --> K
  N --> K
  U -- "yes" --> E{"first attempt, a rung left, ESCALATE=1?"}
  E -- "no" --> B
  E -- "yes" --> P["keep the failed attempt: branch, patch, tarball"]
  P --> Z["reset to what attempt 1 started from, effort one rung up"]
  Z --> L
```

*Validate* is five checks the driver runs itself: HEAD moved; the `**Shipped**` line names a commit
on the branch; the plan file is committed; the Gradle gate, re-run by the driver, is green (skipped
for a docs-only diff); every required skill ran — the step's `skills:` tag plus what the diff
tripwire demands. What it takes on a session's word: that a skill listed as run was run, and that
the step met its spec — the judge grades that, nothing acts on the grade yet.

## Files

| File | Role |
|---|---|
| `../run-plan.sh` | The driver: tunables, worktree, launch, validate, nudge, escalate, judge, the main loop. |
| `lib.sh` | Pure functions (text in, text out): Order grammar, heading tags, Shipped/Decision blocks, tripwire, result classification, variant check. |
| `step-prompt.md` | What every step session is told. |
| `skills-prompt.md` | The fresh review session that stands in for the nudge when only skills are missing. |
| `judge-prompt.md`, `judge-result.schema.json` | The read-only grading pass and its result. |
| `step-result.schema.json` | The result every step session ends with. |
| `variants/*.env` | Named configurations for `--variant`; known keys only. |
| `report.sh` | Per-step table from one or more run logs. |
| `selfcheck.sh`, `fixtures/` | Pure-function and dry-run checks; runs in CI. |
| `e2e.sh` | The driver's control flow against a stub `claude` and `gradlew` in a scratch repo; called by `selfcheck.sh`. |
| `benchmark.md` | The 2026-09-20 configuration benchmark: protocol, results of every arm, the hand-read of the judge's findings, and the decision behind the mid-tier default. Raw run data stays in the gitignored `docs/plans/.runs/`. |
| `flow.html` | The two flow diagrams below plus a who-writes-what figure and the exit-code table, as a standalone page. |
