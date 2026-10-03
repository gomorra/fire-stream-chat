# Plan runner: plan changes during a run, and a wait at the usage limit

Plan built from the brief that stood in this file at `4f42e3d`
(`git show 4f42e3d:docs/plans/plan-runner-live-changes-brief.md`). Two planning sessions wrote it
on 2026-10-03, and the owner chose to merge them. A desktop session read the owner's local run
results and transcripts. A cloud session read cloud transcripts, and it is building the plan with
the runner. Line numbers are from `4f42e3d`; re-verify them before editing.

**Order: 1 → 2 → 3**

No checkpoint. The runner never pushes, and the owner reads `plan/<run>` before the new driver
replaces the one in any checkout where a run is alive.

## Rules for every step session

- **This plan changes the runner that runs it.** The driver executing this plan is the copy in the
  main checkout. You edit the worktree's copy. Never touch the main checkout, and never run
  `scripts/run-plan.sh` against a real plan. `e2e.sh` runs it against a stub `claude`; that is the
  only way to exercise the driver.
- **The gate for this plan is `scripts/plan-runner/selfcheck.sh`.** It runs `e2e.sh`, and CI runs the
  same. No step touches a file the Gradle build reads, so the driver skips the Gradle gate
  (`pr_needs_gate`) and so do you. CLAUDE.md post-step items 2–3 are replaced by the self-check here;
  this is not a departure.
- **Write each new `e2e.sh` case first and see it fail.** Every new pure function in `lib.sh` gets
  `selfcheck.sh` cases.
- **Read the pipefail entry in `docs/GOTCHAS.md` before writing any pipeline.** Never pipe into a
  reader that can stop early. Capture, then match from a here-string, or end the pipeline with
  `|| true`.
- **Docs ship with the code.** Each feature step updates, in its own code commit:
  - the contract `docs/plans/done/plan-runner.md`, in a new §6 addendum (one row per change, the §5
    shape);
  - `scripts/plan-runner/README.md` and `flow.html`, both diagrams, the files table and the exit-code
    table;
  - the header comment of `run-plan.sh`;
  - `CLAUDE.md` § *Plan runner*, a line or two per change.
- Do not move this plan to `docs/plans/done/`. The driver validates every step by reading the plan
  at this path. The owner moves it after the run is merged.
- No CHANGELOG entry, no version bump: this is tooling.

## Why

Two things went wrong on 2026-10-03 and 2026-10-04.

- The video-calls verdict was committed on main eight minutes after its run forked. The run could
  not see it, and no moment existed to merge it in.
- A run that hit the usage limit at 00:45 on the owner's desktop stopped as blocked in the middle
  of step 3. Nobody was there to restart it, and a restart does not continue the interrupted
  session. A cloud session does not stop at the limit at all. It keeps working on cloud credits,
  which the owner does not want spent on a plan run.

## 0. Decisions (taken 2026-10-03 — do not re-litigate)

### 0.1 Plan changes and the pause file (step 1)

| # | Question | Decision |
|---|---|---|
| 1 | Pause file | `docs/plans/.runs/<run-id>.pause` (the runs dir; `PLAN_RUNNER_RUNS_DIR` in tests). It is one-shot. At each step boundary, the driver stops for it: it logs `paused`, removes the file, notifies and exits **5**. Running the driver again continues the run. A pause file found at start-up is stale: it is removed with a note. A `‖` due at the same boundary wins (exit 4); the pause file is then stale at the next start. |
| 2 | Exit code 5 | **5 = stopped between steps for the owner**: a pause file, or a plan sync that needs the owner. Exit 4 stays the `‖` checkpoint, and exit 3 stays a blocked step. |
| 3 | Sync source | `--sync-from REF`, default `main`. A ref under `refs/remotes/` (`origin/main`) is fetched first with `git fetch <remote> <branch>`. A failed fetch is a warning, and the sync uses the ref as it is. `--sync-from none` turns sync off. A cloud container uses `origin/main`, because the owner's edits arrive there by push and the container's local `main` never moves. |
| 4 | What is merged | The plan file only. Main's code would change what the gate and the diff checks measure in the middle of a run. |
| 5 | Merge base | The newer of `git merge-base REF HEAD` and the REF-side commit named by the `Plan-Synced-From: <sha>` trailer of the branch's newest sync commit. A trailer commit that is no longer an ancestor of REF is ignored. The plan changed on REF when the blob `REF:<plan>` differs from the blob `<base>:<plan>`. Evidence (desktop, `git merge-file` on a minimal case): a second sync gives 0 conflicts with this base and 3 with the fork point. |
| 6 | Same-spot insertions | A `**Shipped**` block written under step N and a new step added on REF right after step N land at the same line, so a plain three-way merge conflicts there. The conflict's base part is empty. Such a conflict is placed without stopping: the branch's lines first, a blank line if needed, then REF's. Only a conflict whose base part is empty qualifies. The merge runs `git merge-file -p --zdiff3 --marker-size=13`. |
| 7 | Any other conflict, or a broken merge | The merged text is checked before the commit. The Order line must parse, every step in it must have a heading whose tags are valid, and no `**Shipped**` line of the branch's copy may be lost. A real conflict or a failed check writes nothing to the worktree. The text goes to `docs/plans/.runs/<run-id>.plan-sync.conflict.md`. The driver logs `plan_sync_failed`, notifies, prints the one commit command that records a merge made by hand (with the trailer), and exits 5. |
| 8 | Clean merge | The driver writes the merged text over the worktree's plan and commits only that path as `docs(plan): sync <plan> from <REF> at <short sha>`, with the trailer `Plan-Synced-From: <full sha>`. It logs `plan_synced`. **The runner now makes this one kind of commit**, overriding contract §2.1 "the runner itself never commits". It still never pushes. |
| 9 | When it does not sync | The worktree's plan file has uncommitted changes (a `**Decision taken**` answer, a `**Decision needed**` block): skip with a note; a later boundary syncs. REF does not contain the plan: skip with a note once per run. A dry run never syncs, never pauses and never waits. |
| 10 | After a sync | The driver re-reads the Order line and walks it again from the top. The first unshipped step of the re-read Order line runs next, wherever it sits. An unshipped step id gone from the Order line never runs. A shipped step that leaves or moves changes nothing. A change to the running step or to a shipped step's text is not picked up as work: it merges as text and re-runs nothing. |
| 11 | `‖` and the frontier | A `‖` stops the run only at the frontier: the step to its left shipped in this invocation, or no later step of the Order line is shipped. So a `‖` added on REF behind the run's position does not stop it. This replaces `ran_prev`: the set of steps shipped in this invocation lets the walk restart from the top. |
| 12 | Step-diff checks | Unchanged. The sync commit is made before the next step's `START_SHA` is taken. It sits between two steps' ranges, so the tripwire, the `@Test` count, the gate and the judge never see it. The step prompt's commit list shows it. |
| 13 | Later real merge of main | Clean where the sync merged cleanly. At a same-spot insertion git asks once more, and the branch's side is right. When the branch carries a sync commit, the end-of-run message says so. |
| 14 | Start-up warning | The plan-differs warning fires only for an uncommitted edit of the plan in the main checkout (`git status --porcelain -- <plan>` in `ROOT`): those edits never reach a run. Commit them on REF. |

### 0.2 The usage limit (step 2)

| # | Question | Decision |
|---|---|---|
| 15 | Evidence | Three sources, all real, none of them the CLI's message text. **(a) The stream.** `--output-format stream-json --verbose` carries objects with `rate_limit_info`. Fields seen in a cloud session's transcript at 23:02 UTC: `status` (`"rejected"`), `rateLimitType` (`"five_hour"`), `resetsAt` (epoch seconds), `isUsingOverage`, `overageStatus`, `overageDisabledReason`, `unifiedWindows`. **(b) The result.** A limit turn ends `subtype: "success"`, `is_error: true`, `api_error_status: 429`. The desktop session read this from the CLI's code; nobody has seen it live. **(c) The transcript.** The session transcript holds an assistant entry with `error: "rate_limit"`, `apiErrorStatus: 429` and `quotaLimits.resetsAt` (epoch seconds). The desktop session found 27 real entries (CLI 2.1.263–2.1.278), and `resetsAt` matched the "resets 9:40pm" text. The transcript is `${PLAN_RUNNER_CLAUDE_HOME:-${CLAUDE_CONFIG_DIR:-$HOME/.claude}}/projects/*/<session-id>.jsonl`. |
| 16 | Output format | Sessions run with `--output-format stream-json --verbose`, written to `<result>.stream.jsonl`. The result file becomes the stream's last line whose `type` is `result`, so everything that reads result files today keeps working. |
| 17 | What a limit stop is | Any of: (a) the newest `rate_limit_info` in the stream has `status: "rejected"`; (b) the result is `is_error: true` with `api_error_status: 429`; (c) the transcript's last main-chain assistant entry has `error == "rate_limit"`. `pr_result_kind` gains the kind `usage_limit` for (b). |
| 18 | The cloud case | A cloud session keeps running past the limit on cloud credits. Two sessions were seen on 2026-10-03 working while `rejected` with overage rejected. One of them was a runner step session. So the driver **stops the session itself** as soon as its stream says `rejected`, on a desktop and in a cloud container alike. It sends SIGTERM to the `claude` process, and SIGKILL ten seconds later if it is still alive. The stream is checked every `USAGE_POLL_S` (10 s). |
| 19 | Reset time | The stream's `resetsAt`, else the transcript's `quotaLimits.resetsAt`, plus `USAGE_WAIT_SLACK_S` (90). With neither, wait `USAGE_WAIT_FALLBACK_S` (3600) and try. |
| 20 | Ceiling and cap | A reset more than `USAGE_WAIT_MAX_S` (21600, six hours) away is not waited for. That is a weekly limit: exit 3, naming the reset time. At most `USAGE_RESUME_MAX` (6) waits per step, shared by its step, nudge, review and judge sessions. Past the cap: exit 3. |
| 21 | The wait | Against the wall clock, in 60 s slices, so a suspended host does not oversleep. The pause file is checked in each slice; a pause during a wait exits 5. |
| 22 | Resume | The same session with `--resume <id>` and a fixed continue prompt. The budget is what is left of that session's budget, at least 1 USD. `--max-budget-usd` counts per invocation; a resumed session on the current build reports its cumulative cost (desktop evidence: nudge results in `.runs/`). A resume does not spend the step's nudge, and the nudge is still available afterwards. The session id comes from the stream's `system`/`init` line, or from the result. |
| 23 | No resumable session | A session stopped before its `init` line has no id. So does one whose resume ends without a result. The driver then starts a fresh session of the same kind. A fresh step session gets an `## Interrupted attempt` block in its prompt: the worktree holds an interrupted attempt's work, and the step keeps its `START_SHA`. |
| 24 | Which sessions | Every session the driver starts goes through one `run_session` wrapper: step, nudge, review and judge (the judge gets a `judge_args` builder next to `claude_args`). A judge that still fails after the wait gets no grade, logs `judge_failed`, and the plan goes on, as today. |
| 25 | Before a launch | When the newest known stream state is `rejected` and its `resetsAt` lies ahead, the driver waits before launching. "Newest known" is this run's newest stream, or, at start-up, the log's last `usage_limit` event. A cold start inside a rejected window learns it only from the first session's first `rate_limit_event` (see §4). |
| 26 | A re-run after a stop during a wait | A pause, a crash or Ctrl-C during a wait leaves the step's last limit event a `usage_limit` without a later `resumed`. A re-run then resumes that step or nudge session instead of launching a fresh one, after waiting out the reset if it still lies ahead. An interrupted review or judge is redone the way a re-run treats that step today. |
| 27 | Log, report, notification | `usage_limit`: step, session, kind, `resets_at` (epoch or `null`), `source` (`stream`, `result` or `transcript`), `wait_s` planned, the result file and the transcript path. Before it, the interrupted invocation's `result` event, so its cost is counted. `resumed`: step, session, kind, `waited_s`. One notification when a wait starts, naming the local reset time. `report.sh` gets a `wait` column in minutes, and counts a resumed session's cost once: per session id, each result adds its increase over that session's previous result. A total below the previous one is a per-invocation figure from an older build. |
| 28 | Ctrl-C | A session now runs in the background, because the driver polls its stream. A background job ignores SIGINT in a non-interactive shell. So the driver traps INT and TERM, stops its running session, logs `interrupted` and exits 130. |

## 1. Current state (verified 2026-10-03 at `4f42e3d`)

- **One fixed walk of the Order line.** `pr_order_tokens` runs once (`run-plan.sh:650`), and the
  `for tok in "${TOKENS[@]}"` loop (`:655–687`) walks it. `ran_prev` and `prev` carry the
  checkpoint rule (`pr_checkpoint_due`, `lib.sh:365–374`). `--to` breaks out at `:658`, and `--from`
  skips at `:674`.
- **Main's plan is only warned about.** `:638–641` compares the main checkout's working copy with
  `merge-base main HEAD` and says "merge main into the branch". The e2e scenario at `e2e.sh:282`
  pins that warning. Step 1 changes both (§0 14).
- **Sessions run synchronously with `--output-format json`.** `run_claude` (`:273–275`) blocks on
  `claude … > out 2> out.stderr`. `claude_args` (`:263–271`) builds the flags. The judge has its own
  `claude` call (`:483–486`).
- **An API error is a block.** `handle_result`'s `failed` arm (`:511–515`) prints why the runner does
  not retry and calls `stop_blocked`. The e2e scenario at `e2e.sh:218` pins a 429 `api-error` as
  blocked at once. Step 2 turns a 429 into a wait and keeps the block for every other API error.
- **Exit codes** are listed in the header (`:17–18`): 0, 1, 2, 3, 4.
- **`report.sh` sums every result's cost.** A resumed session that reports cumulative cost is
  counted twice: video-calls step 2 shows 15.05 instead of 7.82 (desktop evidence).
- **No headless usage-limit result exists in git.** The owner's desktop stop is
  `docs/plans/.runs/video-calls.step3.20261004-004454.result.json` on the owner's machine,
  gitignored. The driver printed `success, status null, $0.88` for it. `fixtures/result-api-error.json`
  carries a 429 that nobody has checked against a real one.
- **Step sessions may run the self-check.** `afd2940` added `selfcheck.sh` and `e2e.sh` to
  `ALLOWED_TOOLS`. Without that, every step of this plan would have been denied its own gate.
- **Probe**: pending until the reset at 23:50 UTC. The planning session fills this in before the run.

## 2. Design

### 2.1 The step boundary (step 1)

The real-run main loop becomes a walk over an index that can restart:

```
start-up   → remove a stale pause file · validate a hand-finished step (today's rule)
loop:
  walk     → the Order tokens from the top, today's rules plus the frontier rule (§0 11):
             --from skip · shipped skip · a due ‖ → exit 4 · **Decision needed** → exit 2 ·
             first step past --to → stop (exit 0) · first unshipped step → the next launch ·
             none left → done
  boundary → before that launch: a pause file → exit 5 · sync_plan (§0 3–10);
             a sync commit → load_tokens, walk again from the top
  act      → run_step; on validation the step joins SHIPPED_NOW; back to walk
```

`load_tokens` re-reads the Order line. A dry run keeps one pass with no boundary, and its output
does not change (`selfcheck.sh` pins it). "Nothing to do" and "plan complete" keep their messages
and exit 0.

`lib.sh` gains, each with `selfcheck.sh` cases:
- `pr_merge_inserts`: reads `git merge-file --zdiff3 --marker-size=13` output. A conflict with an
  empty base part becomes ours, a blank line if needed, then theirs. Any other conflict stays
  marked, and the exit code is 1.
- `pr_plan_check <plan> [<shipped-before>]`: the Order line parses, and every step in it has a heading
  whose tier and effort tags are valid. It reuses `pr_order_tokens`, `pr_step_heading`,
  `pr_tag_tier` and `pr_tag_effort`. With a second plan, no step shipped there is unshipped here.
- `pr_shipped_after <plan> <index> <tokens…>`: for the frontier rule.
- Reading a `Plan-Synced-From:` trailer out of a commit message. `lib.sh` never runs git.

### 2.2 Sessions and the usage limit (step 2)

`run_session` replaces the bare `run_claude` call in `launch`, `nudge`, `review_nudge` and
`judge_step`. Its callers keep their shape:

1. Start `claude … --output-format stream-json --verbose` in the background from the session's cwd
   (`exec`, so `$!` is the `claude` process). The stream goes to `<out>.stream.jsonl` and stderr to
   `<out>.stderr`.
2. Every `USAGE_POLL_S` while it runs, read the stream's newest limit state. On `rejected`, stop the
   session (§0 18).
3. When it has exited, write the stream's last `result` line to `<out>`. Leave `<out>` empty when
   there is none.
4. Classify (§0 17). A limit stop logs the interrupted result and `usage_limit`, renames the
   interrupted invocation's files to `<out>.limit<N>.*`, notifies, waits (§0 19–21), logs `resumed`
   and resumes (§0 22–23). Then it goes back to 1.
5. Past the ceiling or the cap it returns the stop reason. `handle_result` blocks with it, and the
   judge logs `judge_failed`. Otherwise it returns with `<out>` holding the last invocation's result.

`lib.sh` gains, each with `selfcheck.sh` cases and fixtures:
- `pr_stream_result <stream>`: the last `result` line.
- `pr_session_id <stream>`.
- `pr_stream_limit <stream>`: `status resetsAt rateLimitType` of the newest `rate_limit_info` at any
  depth, or nothing. It finds candidate lines with `grep -F '"rate_limit_info"'` and parses only the
  last one, because the stream carries every tool output and grows to megabytes.
- `pr_transcript_usage_limit <jsonl>`: exit 0 when the last main-chain assistant entry has
  `error == "rate_limit"`, and print `quotaLimits.resetsAt`. It reads with `jq -R 'fromjson?'` and no
  pipe that can stop early.
- `pr_fmt_duration`.
- `pr_cost_delta <total> <previous>`: a total below the previous one is a per-invocation figure.
- `pr_budget_left <budget> <spent>`.

New tunables, next to the others: `USAGE_POLL_S=10`, `USAGE_WAIT_SLACK_S=90`,
`USAGE_WAIT_FALLBACK_S=3600`, `USAGE_WAIT_MAX_S=21600`, `USAGE_RESUME_MAX=6`. `e2e.sh` sets them
small with `sed`, the way it sets `NOTIFY_CMD`, so no scenario sleeps long.

What stays: a spent budget is blocked at once. The nudge and escalation ladder is unchanged. A
`failed` result that is not a limit stop is blocked as today, with today's message.

## 3. Steps

### Step 1 — Pause file and plan sync at the step boundary (`feat(plan-runner):`) — skills: code-review; model: strong

- `run-plan.sh`: §2.1 and §0 1–14. `--sync-from REF|none` in the usage text and the flag parser.
  Exit 5 and "the runner commits nothing but a plan sync" in the header. `stop_paused` and
  `stop_sync`. The end-of-run hint of §0 13.
- `lib.sh`: the functions of §2.1.
- The `e2e.sh` stub learns the step id from `-n`, inserts its `**Shipped**` line at the end of its
  step's section (not at the end of the file), and can commit a plan edit on main mid-session.
  Cases, each seen red first:
  - a pause stops at the boundary with exit 5, the file is gone, and the next run continues;
  - a stale pause file at start-up is removed with a note;
  - a new step added on main right after the running step runs next, with no stop;
  - the next step's `launched.start` is the sync commit, which carries its trailer;
  - two syncs in one run, the second one clean;
  - a real edit conflict exits 5, writes the conflict file, and leaves the branch and the worktree
    untouched;
  - a merged plan with a step id that has no heading exits 5 and commits nothing;
  - a dirty plan file skips the sync, and the run goes on;
  - a `‖` added on main at the frontier stops with exit 4;
  - a `‖` added on main behind the frontier does not stop;
  - `git merge main` afterwards is clean for a cleanly merged sync;
  - `--sync-from none` never syncs; `--sync-from origin/main` fetches from a bare scratch remote.
  The plan-differs scenario at `e2e.sh:282` becomes the uncommitted-edit warning of §0 14.
- Docs in the same commit, per the rules above. The contract's §6 says the runner now commits a
  plan sync.

### Step 2 — Wait out a usage limit and resume the same session (`feat(plan-runner):`) — skills: code-review; model: max; budget: 40

- `run-plan.sh`: §2.2 and §0 15–28. `run_session`, `judge_args`, the pre-launch wait and the
  re-run resume in `run_step`, the INT/TERM trap. `handle_result`'s `failed` arm loses its advice
  line for a 429, because a 429 no longer reaches it.
- `lib.sh`: the functions of §2.2. `pr_result_kind`'s comment records what was confirmed, how and
  on which build.
- Fixtures: trimmed copies of the probe's stream (§1), one line per kind it showed;
  `stream-limit-rejected.jsonl` in the probe's line shape with §0 15 (a)'s fields;
  `stream-killed.jsonl` (an `init` line, no result); `result-usage-limit.json` (§0 15 (b), marked as
  read from the code); `transcript-usage-limit.jsonl` and `transcript-resumed.jsonl` (§0 15 (c), ids
  and text made up, field names as recorded there).
- `report.sh`: the `wait` column, and a resumed session's cost counted once (§0 27).
- The `e2e.sh` stub writes stream lines (an `init` line, an optional `rate_limit_event`, its result
  line) and a transcript under `PLAN_RUNNER_CLAUDE_HOME`. Existing behaviours keep working when
  their result line is the stream's only `result` line. The scratch driver runs with 0 s slack, a
  1 s fallback, a 1 s poll and 1 s slices. Cases, each seen red first:
  - a session whose stream says `rejected` and then keeps running is stopped by the driver within a
    poll, waits, and is resumed with `--resume <the same id>`. The step validates, and `waited_s` is
    logged;
  - a 429 result alone, and a transcript entry alone, each lead to the same wait and resume;
  - no reset time anywhere: the fallback wait;
  - the cap blocks with exit 3;
  - a reset days away blocks at once, with no resume call;
  - a nudge, a review session and a judge each resume their own session;
  - the nudge is still available after a resume;
  - a non-429 API error is still blocked at once (the old scenario at `e2e.sh:218`, with its status
    changed);
  - a pause file during the wait exits 5, and the re-run resumes the same session;
  - a session stopped before its `init` line is replaced by a fresh step session whose prompt has
    the `## Interrupted attempt` block;
  - a step that ends `done` while its last `rate_limit_event` says `rejected`: the next step's launch
    waits first (two-step mini plan);
  - Ctrl-C (SIGINT to the driver) during a session stops the stub `claude` and exits 130.
- `selfcheck.sh`: the helpers, the report row with its `wait` column, and a resumed session's cost
  counted once.
- Docs in the same commit, per the rules above. Also a `docs/GOTCHAS.md` entry on the cumulative cost
  of a resumed session, and one on cloud sessions that run past the usage limit on cloud credits.

### Step 3 — Review the whole branch (`refactor(plan-runner):`) — skills: simplify

- Run `/simplify` over the whole diff of this plan, from the branch's fork point (it is over 600
  lines). Re-run the self-check after its fixes.
- Commit only if `/simplify` changed something. Otherwise the **Shipped** line names step 2's code
  commit, and says so.
- Not this step's: the planning session compares `scripts/run-plan.sh docs/plans/video-calls.md
  --dry-run` under the old and the new driver after the run. A step session may not start the driver.

## 4. Known limits

- A run started cold inside a rejected window spends the first requests of one session before the
  first `rate_limit_event` arrives. When that event arrives is not documented. The one observed came
  three minutes into an interactive session.
- An interrupted review or judge session is redone, not resumed, by a re-run (§0 26).
- Main's code is never merged by the driver. A change to the running step or to a shipped step is
  not picked up as work.
- Revisit §0 18 if the CLI starts to wait at the limit by itself in print mode, as its interactive
  "Continue automatically at usage limit" setting does.
