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

Both variants ran on 2026-09-20 from `plan-base/call-audio-routes` (`04bfe9f0`), a first, then b.
Raw data: `docs/plans/.runs/call-audio-routes-{a,b}.*` (gitignored). "Measured" below is read from
the log, the result files or the branches; everything else is marked as a reading.

### The table (`report.sh`, measured)

| step | a: config | usd | turns | min | tests | judge h/m/l | b: config | usd | turns | min | tests | judge h/m/l |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | fable/low | 3.27 | 58 | 5 | 1546→1545 | 0/3/3 | opus/medium+fable | 7.38 | 87 | 12 | 1546→1545 | 0/0/5 |
| 2 | fable/low | 2.68 | 40 | 5 | 1545→1558 | 0/1/3 | opus/medium+fable | 4.66 | 39 | 8 | 1545→1560 | 0/1/4 |
| 3 | opus/xhigh | — | — | — | 1558→1572 | 0/0/5 | opus/xhigh | 17.33 | 110 | 28 | 1560→1582 | 0/1/4 |
| 4 | fable/low | 2.47 | 37 | 3 | 1572→1577 | 0/1/4 | opus/medium+fable | 5.90 | 44 | 9 | 1582→1591 | 0/0/3 |
| 5 | fable/low | 1.81 | 25 | 1 | 1577→1577 | not judged (docs) | opus/medium+fable | 4.92 | 48 | 6 | 1591→1591 | not judged (docs) |
| **mid steps (1, 2, 4, 5)** | | **10.23** | **160** | **14** | +17 | 0/5/10 | | **22.86** | **218** | **35** | +23 | 0/1/12 |

Every step in both variants validated on its first attempt: 0 nudges, 0 escalations, 0 missed
skills, every judged step `meets_spec` with `tests_adequate: true`. The old Sonnet/high reference
(step 1 only): $3.62, 103 turns, 16 min, one nudge, 6 denials.

- **Cost:** b costs 2.2× a on the four steps that differ, takes 2.5× the minutes and 1.4× the
  turns. That is one of the gross differences this protocol said four pairs can separate.
- **Where b's money goes:** Opus $13.32, the Fable advisor $9.54 (42%) — 8 consults, about $1.19
  each. b's Opus share alone is already 1.3× a's whole cost, but that is not a clean "Opus medium
  without advisor" figure: it includes the turns the advice caused.
- **a's step 3 has no numbers.** The `claude` process died of host memory corruption (see
  Interventions); the step was finished by hand in the resumed session. b's $17.33 / 110 turns /
  28 min is the only clean figure for opus/xhigh on this step.
- **The judge** cost $12.95 (a) and $16.50 (b) — more than variant a's steps. Its cap was raised
  from $5 to $7 (`JUDGE_BUDGET_USD`, uncommitted) before b started; b's step 3 grade cost $6.70 and
  would not have fitted the old cap. The cap is a hard stop the judge never sees, so the change
  does not touch how a and b were graded.
- **Denials:** a 1, b 5; none needed the human. Two of b's are the write to
  `.claude/skills/app-ui-design/SKILL.md`, which the runner's permissions refuse in both variants —
  a never tried it.

### Interventions

- **a: one `blocked`, not counted.** Step 3, 2026-09-20 01:47 CEST: the `claude` binary aborted on a
  libstdc++ assertion, one of 25 core dumps across unrelated programs (Chrome, Firefox, VS Code,
  Steam, plasmashell, six Gradle JVMs) after a resume from suspend at 23:06. Fixed by a reboot; zero
  core dumps since. It is not counted against a because the step running was `model: strong` —
  opus/xhigh, the same configuration in both variants — so it says nothing about Fable at `low`.
  This is a departure from the literal rule 1; the user signed it off.
- a's steps 1 and 2 also ran on the degraded host: step 2's own summary reports two Gradle daemon
  SIGSEGVs before a green run, so a's $2.68 / 40 turns for that step includes retries. The bias
  runs against a.
- **b: none** — nothing in the log, and the user confirmed nothing was done by hand.
- The same by-hand item falls out of both variants and is a verdict on the plan and the runner's
  permissions, not on either configuration: `SKILL.md:49` still prescribes `@Config(sdk = [29])`.

### The judge's findings, read by hand

No `high` finding in either variant. `medium`: a 5, b 2 (one of b's is on step 3, the constant
step). Each was checked in the code **on both branches**:

| finding | flagged on | a | b | reading |
|---|---|---|---|---|
| `CLAUDE.md:61` still says `minSdk = 29` | a, step 1 | stale | fixed | real difference |
| `SKILL.md:49` still pins `sdk = [29]` | a, step 1 | stale | stale | same state on both; b tried, was denied, and told the human twice; a patched the plan's step-4 note instead |
| CHANGELOG entry without a version bump | a, step 1 | `1.34.0` kept although `v1.34.0` is tagged | `2.0.0` | real difference, on top of a pre-existing stale header on the base |
| "pick honoured through churn" test has no churn | a, step 2 | `resolve(phone, phone, …)` | `previousAvailable = phone + BLUETOOTH, available = phone` | real difference |
| CHANGELOG claims "the screen now also stays on" — nothing keeps it on | a, step 4 | false user-visible claim | no such claim | real difference |
| `DOMAIN-MODELS.md:180` documents the old `CallUiControls` | b, step 2 | stale | stale | same state on both, flagged only on b — judge noise |
| `audioSessionLock` KDoc says it guards `audioRouter`; `selectAudioRoute` reads it unlocked | b, step 3 | n/a (`by lazy` router, no such field) | real | real, but on the constant opus/xhigh step |

Struck for what is identical on both branches, the mid steps stand at **a 4, b 0**. The judge was
inconsistent once in each direction, so there is no sign it favoured its own model; the four that
remain were confirmed by reading the code, not taken from the judge. None of the four is a defect
in production logic: two are stale or wrong documentation, one is a test that does not test what
its name says, one is a false sentence in the CHANGELOG.

### The judgment calls the reference run made

- **Version bump.** The plan says "recommendation: minor is enough … the skill decides". b invoked
  the skill and took `2.0.0`, as the Sonnet run had; a reported "the bump stayed minor (1.34.0)",
  which is no bump at all — `v1.34.0` is already tagged, the base's `[UNRELEASED] [1.34.0]` header
  was stale before either run. Whether this app goes to 2.0.0 over a minSdk raise is the user's call.
- **The deleted test** is the same one in both (`pre-S devices skip the canScheduleExactAlarms check
  entirely`) — dead once minSdk is 31. Not a difference.
- **`CLAUDE.md`**: b updated the `minSdk` line, a did not. With the file loaded into every session,
  updating it is the right call.
- **Roborazzi baselines.** Both found that `verifyRoborazzi` fails on a bubble colour that predates
  the bump. a re-recorded the four PNGs (the plan allows that only for font/antialias noise), b left
  them and wrote a `TECH_DEBT.md` entry. Both disclosed it; b is closer to the plan's wording.
- Both left a fifth `VERSION_CODES.S` branch in `OutboxScheduler.runsExpedited`; only a logged it.

### Variant b's advisor

The consults happened: `advisorConsults` is 2 on each of the four mid steps, and every one of those
results bills `claude-fable-5-1` (measured). That they changed the work is the executor's own
account and has no counterfactual: step 2 — headset-only scoping of rule 1, a totality guard for an
empty device list, a mute-preservation test; step 4 — the route control pulled out of an untestable
private composable; step 5 — an invented roadmap number removed before the commit; step 1 — a wrong
commit hash in the Shipped block. b also wrote more tests on every code step (+15 / +9 against
+13 / +5).

### Follow-up: variant c — Fable at `medium`, steps 1 and 2 only (`--to 2`)

Run on 2026-09-20 after a and b, same base, same judge. Asked whether one effort rung removes a's
did-not-check-its-work findings for less than b's advisor costs.

| steps 1 + 2 | a: fable/low | c: fable/medium | b: opus/medium + fable |
|---|---|---|---|
| usd | 5.95 | 4.93 (+1.20 host-caused nudge = 6.13) | 12.04 |
| turns | 98 | 112 (+23 nudge) | 126 |
| judge `medium`, raw | 4 | 1 | 1 |
| `medium` after the hand-read | 3 | 0 | 0 |
| tests added | +12 | +14 | +14 |

- All three of a's real misses on these steps are gone in c (measured on the branch): `CLAUDE.md:61`
  says `minSdk = 31`, the CHANGELOG header is `2.0.0`, and the churn test has a case where the device
  set really changes (`previous = phone + WIRED_HEADSET, available = phone`).
- c's one `medium` — the four Roborazzi baselines re-recorded for a colour change, not antialias
  noise — is something a did too and was not flagged for. Judge noise for the third time, so it is
  struck. `SKILL.md:49` and `DOMAIN-MODELS.md:180` are stale on c as on a and b.
- **The nudge is host-caused and not counted.** The driver's own gate run died in `dexBuilder` with
  "Out of space in CodeCache" in a Gradle daemon that had been alive through b and the void runs;
  the same command was green twice afterwards. The nudge session diagnosed it and committed a
  `ReservedCodeCacheSize=512m` line to the repo's `gradle.properties` — out of scope, and without
  effect on this host, whose `~/.gradle/gradle.properties` overrides it.
- Two earlier starts of c were void and are kept in `docs/plans/.runs/void-c-{1,2}/`: the first
  ran the gate with `run_in_background`, ended its turn to wait, and was forced to report `blocked`
  by the headless harness (escalated to `high`, stopped by hand, $3.72 + $0.78 spent); the second was
  stopped at one minute for the account's usage limit. Since then the step prompt forbids background
  Gradle runs — the one difference between the prompt c ran under and the one a and b had.
- Minutes are not compared for c: the host was also running a game.

Reading: on these two steps `medium` cost about what `low` did (within noise; less, leaving the
nudge out) and matched b's finding count at about 40% of b's price. Two steps, one run.

### Follow-up: step 3 from identical code (`--base 8678ab31 --to 3`)

The hardest step (`model: strong`: router + `CallService` wiring), re-run twice from the commit
where variant a's step 2 ended — the code a's own step 3 started from. b's step 3 started from b's
own step 2 and is listed for cost only.

| step 3 | start | usd | turns | min | diff | tests | judge h/m/l |
|---|---|---|---|---|---|---|---|
| a: opus/xhigh (crashed, finished by hand) | `8678ab31` | — | — | — | 617 lines | +14 | 0/0/5 |
| s3-opus-high | `8678ab31` | 12.53 | 91 | 20 | 523 lines | +15 | 0/1/4 |
| s3-fable-high | `8678ab31` | 10.54 | 83 | 19 | 377 lines | +8 | 0/1/4 |
| b: opus/xhigh | b's step 2 | 17.33 | 110 | 28 | 704 lines | +22 | 0/1/4 |

Both re-runs validated first pass, no nudge, both floor skills run. Of fable-high's $10.54, $3.77
is Opus and $0.75 Sonnet — the reviewers its skills spawned.

- **fable-high's `medium` is a real logic bug** (read in the code): after a refused
  `setCommunicationDevice`, `evaluate()` returns before `previousAvailable` advances, so the headset
  keeps counting as newly appeared; a later tap on Speaker resolves to Bluetooth again and
  `if (target != userPick) userPick = null` throws the tap away. Its headline departure is a design
  call the other three arms did not make: the policy is fed the last *requested* route, not the
  OS-reported one.
- **opus-high's `medium` is judge noise**: two bug fixes without a regression test (`CallService`
  has no JVM test). The identical `requestAudioFocus` guard without a test is a `low` on a.
- **Both `high` arms leave a stale route after a headset disconnect** (read in the code:
  opus-high's listener does `?: return` on a null device, fable-high publishes the new `available`
  with the old `current`), which keeps the proximity lock released while the audio is back on the
  earpiece. The judge filed it as `low` on both. a's xhigh run found exactly this through
  `/code-review` and pinned it with regression tests (`a disconnect the OS reports as no device stops
  publishing the vanished route`, and two more).
- b's `medium` (an unlocked read of `audioRouter`) stands as a real bug on an xhigh run.

Reading: Fable at `high` is not better than Opus at `high` on this step — one real bug against
none, the smallest diff and the fewest tests, for $2 less, which one run cannot tell from noise.
Opus `high` against `xhigh`: about $5 (28%) cheaper than b's run, from different start code; the one
substantive thing separating the efforts is the stale route, handled by a's xhigh run and missed by
both `high` runs. One run per arm, and a's is the crashed, hand-finished one.

### Decision

_Signed off by the user on 2026-09-20: the host crash is not counted; the mid tier becomes Fable at
`medium` without an advisor; b's branch is the one merged._

**Between a and b, as the rule reads**, with the host crash not counted: rule 1 ties at zero,
rule 2 ties on `high` at zero and goes to **b on `medium`** (4 to 0 after the hand-read, 5 to 1
raw), and rule 3 — cost, where a wins 2.2 to 1 — is never reached.

**With the follow-ups, the default is neither a nor b: Fable at `medium`, no advisor, for the
mid tier; Opus at `xhigh` stays for the strong tier.** On the two steps it ran, c ties b on
interventions and on findings and costs 40% of it, so the same rule picks c over b at rule 3. What
that leaves open: c never ran steps 4 and 5 (step 4 is where a wrote its false CHANGELOG sentence),
so this is a default set on half the evidence a and b have, to be checked against the next plan's
log. The strong tier is unchanged because neither re-run beat it: Fable at `high` shipped a real
bug Opus at `high` did not, and both `high` runs missed the defect a's `xhigh` run caught, for a
saving of about $5 on a step that is tagged `strong` because a miss there is expensive.

**Fallback: Opus at `high`, no advisor** (`variants/mid-opus-high.env`). The account's weekly limit
allows only half of its usage on Fable, so a plan cannot always run its mid steps there. Opus goes
one rung above Fable because in b Opus at `medium` was clean only with the advisor beside it. No
arm measured Opus at `high` on a mid step; the first plan run with it is that measurement.

What the a-against-b verdict rests on: four steps, one run each, a gap of four findings of which
none is a code defect, bought for $3.16 more per mid step and 2.5× the wall-clock time (the minutes
are soft: the host was also running a game during some runs). Whether the advisor or Opus itself
closed that gap was never measured — an Opus-alone arm was considered and dropped, because on steps
1 and 2 Opus at `medium` was already billed $7.25 inside b against c's $4.93 for the same finding
count, so it could at best tie c at a higher price.

The judge graded the same state differently across branches four times (`SKILL.md`,
`DOMAIN-MODELS.md`, the re-recorded baselines, the untested `requestAudioFocus` guard), once or
more in each direction. Its counts are a pointer to what to read, not a score.
