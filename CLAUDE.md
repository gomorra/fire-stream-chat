# FireStream Chat — Claude Code Instructions

@AGENTS.md

The sections below are Claude Code–specific additions on top of [AGENTS.md](AGENTS.md).

## Cloud Sessions & Hooks

- **Cloud containers (Claude Code on the web):** `.claude/hooks/session-start.sh` supplies the Android SDK, placeholder `google-services.json`, UTF-8 locale, and Maven Central mirror. If any of those fail, see [docs/CLOUD-CONTAINERS.md](docs/CLOUD-CONTAINERS.md) and run the hook by hand instead of working around it.
- **Pre/Post hooks:** `.claude/settings.json` wires `block-heredoc-commit.sh` (avoids VS Code askpass hangs) and `promote-memory.sh` (prompts routing machine-independent lessons into tracked docs).

## Model Guidelines

Plans do not include per-step model/effort tables:

- **Planning, and anything security-adjacent, concurrency-heavy, or architecture-defining** belongs on the strongest available model tier (currently Opus at `xhigh` for max and `high` for strong; mid is Opus at `medium`).
- **Everything else** runs on the current mid tier. The test and build gate enforces quality on small steps.
- **Spawned `Agent` calls:** choose each call's `model` by the same rule. State the model per sub-agent on launch and in the summary (forks inherit the parent's model).

Name tiers, not model version numbers.

## Plan Execution Workflow

### Execution order

Plans include an **Order** line: `→` sequential, `+` parallel, `‖` human checkpoint (e.g. `Order: 1 → 2 → 3+4 ‖ 5`). Only parallelize steps explicitly joined with `+`.

Step headings may carry tags: `skills: bug-hunt, code-review, simplify`, `model: max | strong | mid`, `effort: low | medium | high | xhigh | max`, `budget: <USD>`.

### Review tools (three non-overlapping scopes)

- **`bug-hunt`** (`.agents/skills/bug-hunt`) — **correctness bugs**: Signal/crypto races, concurrency, sync paths, multi-process lifecycle, security rules, destructive scripts. Run on every high-risk step and before cutting a release.
- **`code-review`** (`.agents/skills/code-review`) — **Standards + Spec** two-axis review. Run once per phase or before merging a feature branch.
- **`/simplify`** — **code quality only** (reuse, simplification, efficiency, altitude; applies its own fixes). Run on large (>600 lines) or cross-cutting diffs; re-run the build/test gate if it edits production code.

Invoke via the `Skill` tool rather than ad-hoc review prompts. `/code-review ultra` is user-triggered only; never schedule it in an automated step.

### Post-step checklist

After each significant step, run in order without waiting to be asked:
1. Write unit tests for non-trivial logic (and a reproducing regression test *before* any bug fix).
2. Run the build & test gate in [AGENTS.md](AGENTS.md#build--test-gate).
3. Run the step's tagged skills plus `bug-hunt` (if high-risk) or `code-review` (at phase boundary). Re-run step 2 if `/simplify` edited code.
4. `git commit` code and tests together once green.
5. Record any new trap, pattern, unverified device check, or deferred cleanup in the tracked doc listed under *Where learnings go* in [AGENTS.md](AGENTS.md).

### Plan runner (unattended terminal runs)

Run multi-step plans from a terminal (never inside an active Claude session):

```bash
scripts/run-plan.sh <plan-path> [--from N] [--to N] [--dry-run] [--cap <tier>] [--budget <USD>] [--variant <name>] [--base <ref>] [--sync-from <ref>|none] [--no-keep-awake]
```

Full contract, flags, pause/resume, and reporting live in [docs/plans/done/plan-runner.md](docs/plans/done/plan-runner.md) and `scripts/run-plan.sh --help`. Watch a live run with `scripts/plan-runner/watch.sh` and summarize runs with `scripts/plan-runner/report.sh <run-id>…`.

## Writing Docs

Applies to `AGENTS.md`, `CLAUDE.md`, `docs/`, KDoc, and `// region: AGENT-NOTE` blocks:

- **Describe the current state.** Keep historical diffs in git, not prose.
- **One rule per sentence.** Put the rule first, reason after.
- **Keep provenance out of rules.** Commit hashes belong in `CHANGELOG.md` or at the end of an entry.
- **Plain words.** Name the exact file, class, or command.
- **Rewrite on touch.** Tidy a section when editing it for another reason; skip repo-wide style passes.

## Toolchain Facts

- JVM target: 17 (`minSdk = 31`, `targetSdk = 35`, `compileSdk = 35`, Gradle 8.11.1, KSP, core library desugaring, version catalog `gradle/libs.versions.toml`).
- Package: `com.firestream.chat`.
- PocketBase backend (self-host flavor): `cd pocketbase && ./pocketbase serve --http=0.0.0.0:8090` (see `pocketbase/README.md`).
- Deploy Cloud Functions (firebase flavor): `cd functions && npm install && firebase deploy --only functions`.
