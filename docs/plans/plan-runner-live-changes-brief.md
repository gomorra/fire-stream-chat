# Brief: the plan runner takes plan changes and survives a usage limit

Status: brief only. No plan yet, nothing built. The owner wants both features.

Start a session with: *plan and build the two features in
`docs/plans/plan-runner-live-changes-brief.md`*.

## Before anything else

- **Do not edit `scripts/run-plan.sh` while a run is alive.** Bash reads a running script from
  disk as it goes, so an in-place edit can break the driver. Check with `pgrep -af run-plan.sh`.
  `scripts/plan-runner/lib.sh` is loaded whole at launch and is safe to edit.
- **A run stopped at a `‖` checkpoint is not finished.** The owner restarts it. Ask before
  assuming the runs are done.
- **Remind the owner and wait for a yes before the first edit.** Planning needs no such wait.

## Why

Two things went wrong on 2026-10-03 and 2026-10-04.

- The video-calls verdict was committed on main eight minutes after its run forked. The run could
  not see it, and no moment existed to merge it in.
- A run that hits the usage limit overnight stops as blocked, in the middle of a step. Nobody is
  there to restart it, and a restart does not continue the interrupted session.

## Feature 1 — plan changes for upcoming steps during a run

What stands in the way today:

- The Order line is read once at launch (`pr_order_tokens`, then the `TOKENS` loop at the end of
  `run-plan.sh`). A step added later is invisible to a running driver.
- The driver reads the branch's copy of the plan (`PLAN_WT`). An edit on main reaches it only
  through a merge. Today it only warns about the difference, at start-up.
- No gap exists between steps. The next session starts in the second the last one is validated,
  and a step session edits the plan file while it runs.

Agreed shape. Both parts act at the step boundary, after validation and before the next launch.
That is the only moment nothing touches the worktree.

1. **A pause file.** If `docs/plans/.runs/<run-id>.pause` exists, the driver stops cleanly, the way
   it does at a checkpoint. It gives the owner a gap for anything by hand.
2. **Plan sync.** If the plan changed on main since the branch last saw it, the driver merges that
   one file three ways, commits it as `docs(plan):`, and reads the Order line again. On a conflict
   it stops and says so.

Decided: merge the plan file only, not all of main. Main's code would change what the gate and
the diff checks measure in the middle of a run.

Limits that stay: a change to the running step or to a shipped step is not picked up.

To settle in the plan:

- Which base the three-way merge uses, and how the later real merge of main behaves after it.
- What the step-diff checks (`START_SHA..HEAD`, the tripwire, the `@Test` count, the judge) see
  when a sync commit sits between two steps.
- Whether the pause file is removed by the driver or by the owner, and its exit code.
- A step id that disappears from the Order line, or a shipped step that moves.

## Feature 2 — resume after a usage limit

What happens today:

- `pr_result_kind` in `lib.sh` knows `complete`, `incomplete`, `budget` and `failed`. A session
  that dies for quota is `failed`.
- `handle_result` in `run-plan.sh` answers `failed` with
  `stop_blocked "session ended without a usable result …"`.
- A restart then launches a fresh session for the same step over the half-done worktree.

Agreed shape:

1. A usage-limit stop is its own result kind, apart from a real block.
2. The driver waits until the reset, then resumes the same session with `--resume`. The nudge path
   already resumes a session (`claude_args … --resume`).
3. The retries are capped, so a step that fails for another reason does not loop all night.

**Not verified:** how `claude -p --output-format json` reports a usage-limit stop. Find a real one
before writing the matcher: `docs/plans/.runs/*.result.json` and the `.stderr` file beside it.
If none exists yet, the plan needs a step that produces or waits for one. Do not guess the shape.

To settle in the plan:

- Where the reset time comes from, and what the driver does when it cannot read one.
- Whether a session can be resumed at all after such a stop, or whether the fallback is a fresh
  session that is told about the work in the worktree.
- How the wait shows up in the log, in `report.sh` and in the notification.
- The same failure in a judge session or a fresh review session, which are not step sessions.

## Where things are

- `scripts/run-plan.sh`: the driver. Main loop at the end; `run_step`, `launch`, `handle_result`,
  `validate_step`, `validate_pending_from_log`, `stop_blocked`.
- `scripts/plan-runner/lib.sh`: pure functions. The Order parser, the heading and section
  readers, `pr_result_kind`, `pr_checkpoint_due`.
- `scripts/plan-runner/selfcheck.sh` and `e2e.sh`: every new branch of the driver gets a case.
  `e2e.sh` runs the driver against a stub `claude` in a scratch repo. CI runs both
  (`.github/workflows/plan-runner.yml`).
- `docs/plans/done/plan-runner.md`: the contract. §2.4 is the Order grammar and checkpoints, §2.5
  result handling, §5 the addendum for everything since the first real step. Both features extend it.
- `scripts/plan-runner/README.md` and `flow.html`: how a run goes, with diagrams. Update both.
- `CLAUDE.md` § *Plan runner*: the short user-facing list of flags and stops.
- `docs/GOTCHAS.md`: the pipefail trap that caused two fixes on 2026-10-03. Read it before writing
  any pipeline in the runner.

## What done looks like

- A plan edit committed on main during a run is in effect at the next step boundary, a new step
  included, without stopping the run.
- `touch docs/plans/.runs/<run-id>.pause` stops the run at the next boundary.
- A run that hits the usage limit continues by itself after the reset, in the same session, and
  the log says how long it waited.
- `selfcheck.sh` is green, with cases that fail without each change.
