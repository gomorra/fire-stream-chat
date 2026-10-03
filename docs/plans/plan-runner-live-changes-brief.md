# Plan runner: plan changes during a run, and a wait at the usage limit

Plan built from the brief that stood in this file at `4f42e3d`
(`git show 4f42e3d:docs/plans/plan-runner-live-changes-brief.md`). Written 2026-10-03 against
`main` at `4f42e3d`. Line numbers are from that commit; re-verify them before editing.

**Order: 1 → 2 → 3**

No checkpoint. The runner never pushes, and the owner reads `plan/<run>` before the new driver
replaces the one in any checkout where a run is alive.

## Rules for every step session

- **This plan changes the runner that runs it.** The driver executing this plan is the copy in the
  main checkout. You edit the worktree's copy. Never touch the main checkout, and never run
  `scripts/run-plan.sh` against a real plan. `e2e.sh` runs it against a stub `claude`; that is the
  only way to exercise it.
- **The gate for this plan is `scripts/plan-runner/selfcheck.sh`.** It runs `e2e.sh`. No step touches
  `app/`, so the driver skips the Gradle gate and so do you. CLAUDE.md post-step items 2–3 are
  replaced by the self-check here; this is not a departure.
- **Read the pipefail entry in `docs/GOTCHAS.md` before writing any pipeline.** Never pipe into a
  reader that can stop early. Capture, then match from a here-string, or end the pipeline with
  `|| true`.
- **Every new branch of the driver gets an `e2e.sh` scenario that fails without it.** Every new pure
  function in `lib.sh` gets `selfcheck.sh` cases.
- No CHANGELOG entry, no version bump: this is tooling.

## Why

Two things went wrong on 2026-10-03 and 2026-10-04.

- The video-calls verdict was committed on main eight minutes after its run forked. The run could
  not see it, and no moment existed to merge it in.
- A run that hit the usage limit at 00:45 (owner's desktop) stopped as blocked in the middle of
  step 3. Nobody was there to restart it, and a restart does not continue the interrupted session.
  A cloud session does not stop at the limit at all: it keeps working on cloud credits, which the
  owner does not want spent on a plan run.

## 0. Decisions (taken 2026-10-03 — do not re-litigate)

| # | Question | Decision |
|---|---|---|
| 1 | Go-ahead | The owner gave it on 2026-10-03: plan in an interactive session, then build with the runner itself (`scripts/run-plan.sh docs/plans/plan-runner-live-changes-brief.md`). |
| 2 | Pause file | `docs/plans/.runs/<run-id>.pause` (the runs dir: `PLAN_RUNNER_RUNS_DIR` in tests). Checked at every step boundary, the first one included, and once a tick during a usage-limit wait. The driver deletes the file when it honours it, logs `paused`, notifies and exits **5**. Running the driver again continues the run. |
| 3 | Exit code 5 | **5 = stopped between steps for the owner**: a pause file, or a plan sync that conflicts. Exit 4 stays the `‖` checkpoint. When a pause and a due checkpoint meet at one boundary, the pause wins and the checkpoint stays due for the next run. |
| 4 | Sync source | `--sync-from REF`, default `main`. A ref under `refs/remotes/` (`origin/main`) is fetched first with `git fetch <remote> <branch>`. A failed fetch is a warning, and the sync uses the ref as it is. `--sync-from none` turns sync off. A cloud container uses `origin/main`, because the owner's edits arrive there by push. |
| 5 | What is merged | The plan file only. Main's code would change what the gate and the diff checks measure in the middle of a run. |
| 6 | Merge base | The main-side commit the branch last took its plan from. That is the commit named by the `Plan-Synced-From: <sha>` trailer of the newest sync commit on the branch, or `git merge-base REF HEAD` when that commit is newer or no sync commit exists. The plan changed on main when the blob `REF:<plan>` differs from the blob `<base>:<plan>`. |
| 7 | Clean merge | `git merge-file -p` over three temporary copies: ours = `HEAD:<plan>`, base, theirs = `REF:<plan>`. The driver writes the result over the worktree's plan and commits only that path as `docs(plan): sync <plan> from <REF> at <short sha>`, with the trailer `Plan-Synced-From: <full sha of REF>`. It logs `synced`. **The runner now makes this one kind of commit.** It still never pushes. |
| 8 | Conflict | Nothing is written to the worktree. The conflicted text goes to `docs/plans/.runs/<run-id>.plan-sync.conflict.md`. The driver logs `sync_conflict`, notifies, prints how to finish by hand (resolve the file, copy it over the worktree's plan, commit with the trailer line) and exits 5. |
| 9 | When it does not sync | The worktree's plan file has uncommitted changes (a `**Decision taken**` answer, a `**Decision needed**` block): skip with a note, and the next boundary syncs. `REF` does not contain the plan: skip with a note once per run. A dry run never syncs. |
| 10 | After a sync | The driver re-reads the Order line and walks it from the top, as a fresh start would, every boundary. The first unshipped step in Order sequence runs next, wherever it sits. A step id gone from the Order line is not run. A shipped step that moved stays shipped. A change to a shipped step's text merges as text and re-runs nothing. A `‖` added behind the run's position stops the run once, because the driver cannot tell it from a checkpoint it skipped. |
| 11 | Step-diff checks | Unchanged. The sync commit lands before `run_step` records `START_SHA`, so `START_SHA..HEAD`, the tripwire, the `@Test` count and the judge see the step alone. The prompt's commit list shows the sync commit. |
| 12 | Later real merge of main | Main's plan edits sit on both sides, and git takes identical hunks without a conflict. When the plan file still conflicts and `REF:<plan>` is the blob the branch last synced, the end-of-run message says to take the branch's copy (`git checkout --ours <plan>`). |
| 13 | Where the limit is read | From the session's own output: `--output-format stream-json --verbose`, written to `<result>.stream.jsonl`. The result file becomes the stream's last line whose `type` is `result`, so everything that reads result files today is unchanged. The newest object carrying `rate_limit_info`, found at any depth, is the limit state. Its fields, seen in a cloud session's transcript on 2026-10-03: `status` (`"rejected"`), `rateLimitType` (`"five_hour"`), `resetsAt` (epoch seconds), `isUsingOverage`, `overageStatus`, `unifiedWindows`. The CLI's message text is never parsed. |
| 14 | What a limit stop is | The newest `rate_limit_info` of a session's stream has `status: "rejected"`, or the session ended `failed` with `api_error_status: 429` (no reset time then). A cloud session keeps running past the limit on cloud credits. So the driver **stops the session itself** as soon as its stream says `rejected`, on a desktop and in a cloud container alike: SIGTERM to its `claude` process, SIGKILL ten seconds later if it is still alive. |
| 15 | How long it waits | Until `resetsAt + LIMIT_MARGIN_S` (120 s). With no reset time, `LIMIT_FALLBACK_WAIT_MIN` (60). A reset more than `LIMIT_MAX_WAIT_H` (12) hours away (a weekly window) is blocked at once, exit 3, without a wait. The wait sleeps in ticks of `LIMIT_TICK_S` (10 s) and checks the pause file on each. |
| 16 | How it continues | The same session: `--resume <id>` with a fixed continue prompt and the session kind's own budget. The session id comes from the stream's `system`/`init` line, or from the result. A session with no id (stopped before its first message), or whose resume ends without a result, is replaced by a fresh session of the same kind. A fresh step session gets an `## Interrupted` block in its prompt: the worktree holds an interrupted attempt's work. |
| 17 | Cap | `LIMIT_MAX_WAITS` (6) waits per step, every session kind counted together. The next limit stop is blocked, exit 3. A limit stop never spends the step's nudge or its escalation. |
| 18 | Which sessions | Every session the driver starts goes through one wrapper: step, nudge, review and judge. A judge that hits the limit waits and resumes like the others; the next step needs the reset anyway. |
| 19 | Before a launch | When the newest known limit state is `rejected` and its `resetsAt` lies ahead, the driver waits before launching. The newest known state is this run's newest stream, or at start-up the log's last `limit_wait`. A cold start inside a rejected window learns it only from the first session's first `rate_limit_event` (see §5). |
| 20 | A re-run after a stop during a wait | A pause, a crash or Ctrl-C during a wait leaves the step's last limit event a `limit_wait` without a later `limit_resumed`. A re-run then resumes that session (step or nudge kind) instead of launching a fresh one, after waiting out the reset if it still lies ahead. An interrupted review or judge is redone the way a re-run treats that step today. |
| 21 | Log, report, notification | `limit_wait` at the start of a wait: step, session, kind, `resets_at` (epoch or `null`), `limit_type`, `wait_s`, and the cost of the interrupted invocation (0 when it was stopped before its result). `limit_resumed` after it: step, session, kind, `waited_s`. One notification per wait, naming the local reset time. `report.sh` gets a `wait` column (minutes per step) and adds interrupted invocations' cost to `usd`. |
| 22 | Ctrl-C | A session now runs in the background (the driver polls its stream), and a background job ignores SIGINT in a non-interactive shell. So the driver traps INT and TERM, stops its running session, logs `interrupted` and exits 130. |

## 1. Current state (verified 2026-10-03 at `4f42e3d`)

- **One fixed walk of the Order line.** `pr_order_tokens` runs once (`run-plan.sh:650`), and the `for tok
  in "${TOKENS[@]}"` loop (`:655–687`) walks it. `ran_prev` / `prev` carry the checkpoint rule
  (`pr_checkpoint_due`, `lib.sh:365–374`). `--to` breaks out at `:658`, and `--from` skips at `:674`.
- **Main's plan is only warned about.** `:638–641` compares the main *checkout's* working copy with
  `merge-base main HEAD` and says "merge main into the branch". The e2e scenario at `e2e.sh:282`
  pins that warning. Step 1 replaces both.
- **Sessions run synchronously with `--output-format json`.** `run_claude` (`:273–275`) blocks on
  `claude … > out 2> out.stderr`. `claude_args` (`:263–271`) builds the flags. The judge has its own
  `claude` call (`:483–486`) with its own flags.
- **An API error is a block.** `handle_result`'s `failed` arm (`:511–515`) prints why it does not
  retry and calls `stop_blocked`. The e2e scenario at `e2e.sh:218` pins a 429 `api-error` as
  blocked at once. Step 2 turns a 429 into a wait and keeps the block for other API errors.
- **Exit codes** are listed in the header (`:17–18`): 0, 1, 2, 3, 4. `stop_blocked` is `:357–370`.
- **Real limit evidence.** The owner's desktop stop is
  `docs/plans/.runs/video-calls.step3.20261004-004454.result.json` on the owner's machine. It is
  gitignored and not readable from a cloud container. The driver printed `success, status null,
  $0.88` for it. `fixtures/result-api-error.json` carries a 429 with the text "You've hit your
  limit · resets 3am", which nobody has checked against that file. The `rate_limit_info` fields in
  §0 decision 13 are from a cloud session's transcript (session `01HwmrT3…`, 23:02 UTC). They
  were `status: rejected`, `rateLimitType: five_hour`, `resetsAt: 1791071400`, `overageStatus:
  rejected`, `overageDisabledReason: org_level_disabled`. That session kept working.
- **The stream shape of `claude -p`** is recorded under *Probe* below. The planning session made
  that live call after the 23:50 UTC reset, before the run.
- **Step sessions may run the self-check.** The planning session added `Bash(scripts/plan-runner/selfcheck.sh*)`
  and `Bash(scripts/plan-runner/e2e.sh*)` to `ALLOWED_TOOLS` before this run. Without them every step of
  this plan would have been denied its own gate.

**Probe**: pending until the reset.

## 2. Design

### 2.1 The step boundary (feature 1)

The real-run main loop becomes a loop around one boundary and one walk:

```
boundary   → pause file? exit 5 · plan sync (§0 4–10): commit, skip, or exit 5 on a conflict
walk       → Order tokens from the top, the rules of today's loop:
             --from skip · shipped skip · a due ‖ → exit 4 · **Decision needed** → exit 2 ·
             first step past --to → stop (exit 0) · first unshipped step → run it · none left → done
act        → run_step; the validated step joins RAN_NOW; back to boundary
```

`RAN_NOW` (the steps validated in this invocation) replaces `ran_prev` as the `ran-now` argument
of `pr_checkpoint_due`. The walk can be a function that prints the action, so its rules stay in one
place. A dry run keeps one pass with no boundary, and its output does not change (`selfcheck.sh`
pins it). "Nothing to do" (no unshipped step on the first walk) and "plan complete" keep their
messages and exit 0.

The start-up warning about the main checkout becomes: the main checkout has uncommitted edits to
the plan (`git status --porcelain -- <plan>` in `ROOT`), and those never reach the run — commit them
on `REF`. The "behind main" note stays.

### 2.2 Sessions and the usage limit (feature 2)

One wrapper replaces `run_claude`, with the same call shape for its callers:

1. Start `claude … --output-format stream-json --verbose` in the background from the worktree
   (`exec` so `$!` is the `claude` process). The stream goes to `<out>.stream.jsonl` and stderr to
   `<out>.stderr`.
2. Every `LIMIT_TICK_S` while it runs: read the newest limit state (`lib.sh`). On `rejected`, stop
   the session (§0 14).
3. When it has exited: write the stream's last `result` line to `<out>`, or leave `<out>` empty
   when there is none. All existing result handling reads `<out>` as today.
4. Classify (§0 14). A limit stop logs `limit_wait`, renames the interrupted invocation's files to
   `<out>.limit<N>.*`, waits (§0 15), logs `limit_resumed`, and resumes (§0 16). Then it goes back
   to 1. The cap is §0 17.
5. Return with `<out>` holding the last invocation's result.

`lib.sh` gets the pure parts, each with `selfcheck.sh` cases and fixtures:
`pr_stream_result <stream>` (the last `result` line), `pr_session_id <stream>`,
`pr_limit_state <stream>` (`status resetsAt rateLimitType` of the newest `rate_limit_info`, or
nothing), and `pr_limit_stop <result> <stream>` (`rejected <resetsAt|unknown> <type>`, or nothing).
`pr_limit_state` finds candidate lines with `grep -F '"rate_limit_info"'` and parses only the last
one: the stream carries every tool output and grows to megabytes.

New tunables, next to the others: `LIMIT_TICK_S=10`, `LIMIT_MARGIN_S=120`,
`LIMIT_FALLBACK_WAIT_MIN=60`, `LIMIT_MAX_WAIT_H=12`, `LIMIT_MAX_WAITS=6`. `e2e.sh` sets them small
with `sed`, the way it sets `NOTIFY_CMD`, so no scenario sleeps for long.

What stays: a spent budget is blocked at once. The nudge and escalation ladder is unchanged. A
`failed` result that is not a limit stop is blocked as today, with today's message.

## 3. Steps

### Step 1 — Plan changes reach a running plan at the step boundary (`feat(plan-runner):`) — skills: code-review; model: strong

- `run-plan.sh`: §2.1 and §0 decisions 2–12. `--sync-from REF` in the header's usage text and the
  flag parser. Exit 5 in the header's exit-code list. The end-of-run hint of §0 12.
- `lib.sh`: anything pure the sync needs, for example reading the trailer out of a commit message.
  `lib.sh` never runs git.
- `e2e.sh` scenarios, each failing without its change:
  - a plan edit committed on `main` between step 1 and step 2 is merged before step 2 launches
    (two-step mini plan, edit made by the stub during step 1), and the run does not stop;
  - a new step added to main's Order line during step 1 runs in the same invocation;
  - the sync commit is on the branch with its trailer, and step 2's `launched.start` is after it;
  - a second edit on main syncs against the first sync's base, with no spurious conflict;
  - a conflicting edit stops with exit 5, writes the conflict file, and leaves the worktree clean;
  - an uncommitted plan in the worktree is not synced, and the run goes on;
  - a pause file stops before the next launch with exit 5 and is gone afterwards; a re-run
    continues;
  - a pause file present at start-up stops before anything launches;
  - `--sync-from none` never syncs; `--sync-from origin/main` fetches (a bare scratch remote).
  The plan-differs scenario at `e2e.sh:282` becomes the uncommitted-edit warning of §2.1.
- `selfcheck.sh`: the dry-run listing is unchanged.

### Step 2 — Wait out a usage limit and resume the same session (`feat(plan-runner):`) — skills: code-review; model: max; budget: 40

- `run-plan.sh`: §2.2 and §0 decisions 13–22. The judge's `claude` call moves onto the wrapper.
  The pre-launch wait (§0 19) and the re-run resume (§0 20) sit in `run_step`. The INT/TERM trap
  is §0 22. `handle_result`'s `failed` arm loses its API-error advice line for a 429, because a 429
  no longer reaches it.
- `lib.sh`: the four functions of §2.2. Fixtures: trimmed copies of the probe's stream, one line
  per kind it showed, plus `stream-limit-rejected.jsonl` built from §0 13's fields in the probe's
  line shape, and `stream-killed.jsonl` (an `init` line, no result). `result-api-error.json` keeps
  its 429 and is now classified as a limit stop with no reset time.
- `report.sh`: the `wait` column, and interrupted invocations' cost in `usd` (§0 21).
- `e2e.sh`: the stub `claude` writes stream lines: an `init` line, then optionally a
  `rate_limit_event`, then its result line. Existing behaviours keep working when their result line
  is the stream's only `result` line. Scenarios, each failing without its change:
  - a session whose stream says `rejected` and then keeps running is stopped by the driver within
    a tick, waits, and is resumed with `--resume <the same id>`. The step validates, and the events
    read `launched limit_wait limit_resumed result validated`;
  - a session that ends `failed` with HTTP 429 and no `rate_limit_event` waits the fallback and
    resumes;
  - a non-429 API error is still blocked at once (the old scenario at `e2e.sh:218`, with its
    status changed);
  - a reset more than `LIMIT_MAX_WAIT_H` away is blocked with no wait;
  - the cap: `LIMIT_MAX_WAITS=1`, two limit stops, blocked;
  - a pause file during the wait exits 5. The re-run resumes the same session and does not launch
    a fresh one;
  - a session stopped before its `init` line is replaced by a fresh step session whose prompt has
    the `## Interrupted` block;
  - a judge that hits the limit waits, resumes and logs its grade;
  - a step that finishes `done` while its last `rate_limit_event` says `rejected`: the next step's
    launch waits first (two-step mini plan);
  - Ctrl-C (SIGINT to the driver) during a session stops the stub `claude` and exits 130;
  - `report.sh` shows the waited minutes.

### Step 3 — Write the two features down (`docs(plan-runner):`) — skills: none

- `docs/plans/done/plan-runner.md`: a §6 addendum dated with this step. One table row per change,
  in the §5 shape (change, why, where). It also says the runner now commits one kind of commit (§0
  *Isolation* and §2.1 change), and exit 5.
- `scripts/plan-runner/README.md`: both diagrams. The loop diagram gets the boundary (pause, sync,
  exit 5). The step diagram gets the limit wait between "what came back?" and the result kinds.
  Add the stop list.
- `scripts/plan-runner/flow.html`: the same two diagrams, the exit-code table with 5, and the
  who-writes-what figure, where the runner now writes the sync commit.
- `CLAUDE.md` § *Plan runner*: `--sync-from`, the pause file, exit 5 in **Stops**, and one bullet on
  the usage-limit wait. Keep each to a line or two.
- `docs/GOTCHAS.md`: any host-independent trap the two steps hit. Cloud sessions run past the
  limit on cloud credits, and background jobs ignore SIGINT in a script. Add an entry only if a step
  hit it.
- Do not move this plan to `docs/plans/done/`. The driver validates this step by reading the plan
  at its path. The owner moves it after the run is merged.

## 4. Gates

Every step: `scripts/plan-runner/selfcheck.sh` green before its commit (it runs `e2e.sh`). Steps 1
and 2 run `/code-review`. Step 2 is `model: max` because a mistake there loses work silently or
spends without a ceiling while nobody watches. Step 1 is `strong` because it changes the main loop
and makes the runner commit.

## 5. Known limits

- A run started cold inside a rejected window spends one session's first requests before the
  first `rate_limit_event` arrives. When that event arrives is not documented. The one observed came
  three minutes into an interactive session.
- An interrupted review or judge session is redone, not resumed, by a re-run (§0 20).
- The resumed session's budget is the kind's full budget again. `LIMIT_MAX_WAITS` bounds the total.
- Revisit §0 14 if the CLI starts to wait at the limit by itself in print mode, as its interactive
  "Continue automatically at usage limit" setting does.
