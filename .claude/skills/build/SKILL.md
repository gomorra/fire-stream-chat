---
name: build
description: Execute an entire implementation plan end-to-end, running each step with the recommended model and effort. Use when the user says /build [plan-name] to implement a full plan.
user-invocable: true
disable-model-invocation: false
model: opus
argument-hint: [plan-name] [step] | [count]
---

Execute a full implementation plan, orchestrating each step with the correct model and effort.

## Argument Parsing

`$ARGUMENTS` can be one of:

| Form | Example | Meaning |
|------|---------|---------|
| _(empty)_ | `/build` | Resume current session plan, or list 5 newest per source |
| `<count>` | `/build 10` | List the N most recent plans from each location |
| `<plan-name>` | `/build my-plan` | Execute all steps of the plan |
| `<plan-name> <step>` | `/build my-plan 5.2` | Execute only that one step |

**Detecting the form:**
- If `$ARGUMENTS` is empty → Phase 0 with default count 5
- If `$ARGUMENTS` is a plain integer (e.g. `10`) → Phase 0 with that count
- If `$ARGUMENTS` contains a space (e.g. `my-plan 5.2`) → split on first space; first token = plan name, second = step filter
- Otherwise → plan name, run all steps

## Procedure

### Phase 0 — No Argument

If `$ARGUMENTS` is empty or blank:

**Check for a current session plan first** — look in the conversation context or MEMORY.md for a plan that is currently in progress (has steps marked "Not started" or partially done). If one is found, treat it as if the user had typed `/build <that-plan-name>` and proceed directly to Phase 1.

**If no current plan is found** — list the 5 most recent plans per source:
1. Run: `ls -lt ~/.claude/plans/*.md .claude/plans/*.md 2>/dev/null`
2. Take the top 5 from each source independently, label with `[project]` or `[global]`.
3. Display as a numbered table (interleaved by mtime):

   ```
   Available plans — showing 5 per source (newest first):

   |  # | Plan | Source |
   |----|------|--------|
   |  1 | plan-name-one  | global  |
   |  2 | plan-name-two  | project |
   ...
   ```
   If no plans exist in either location, say "No plans found."
4. **Stop.** Do not proceed to Phase 1.

### Phase 1 — Parse the Plan (read once, use throughout)

1. **Locate the plan** — check `.claude/plans/<plan-name>.md` first; if not found, check `~/.claude/plans/<plan-name>.md`. Use whichever exists. If neither exists, report "Plan not found" and stop.
2. **Extract the step table** — for each step, capture:
   - Step number, task description, file paths
   - Recommended Model (Opus / Sonnet / Haiku) and Effort (High / Medium / Low)
3. **Apply step filter** — if a specific step was given, keep only that step.
4. **Extract the execution order** — look for an **Order** line:
   - `→` = sequential, `+` = parallel
   - No Order line → numeric order, all sequential. Never infer parallelism.
   - Step filter active → ignore Order, run just the one step.
5. **Display the execution plan** with a status table.

### Phase 2 — Execute Steps in Order

Walk the execution order left-to-right. Before executing, check if consecutive sequential steps can be **batched** (see batching rules below). Then for each position (single step, batch, or parallel group), run the appropriate lifecycle.

#### Batching Rules

Consecutive sequential steps may be combined into a single agent call when **all** of these hold:
- Same recommended model (e.g., all Sonnet)
- All effort = Low
- Max 3 steps per batch
- No step in the batch appears in a parallel group

When batching, the agent implements all batched steps in one call, followed by one simplify review, one test+build, and one commit covering all batched steps. Display the batch as:
> **Steps X, Y, Z (batched) — Model: [model] / Effort: Low**

If any condition fails, run each step individually.

#### Step Lifecycle (per step or batch)

**a. Display step header:**
> **Step X — Model: [model] / Effort: [effort]**

**b. Spawn implementation agent** — use `Agent` tool with `model` set per the plan:
- Pass the **full step description** from the plan (or all batched step descriptions) and **file paths to modify** (not file contents — the agent reads them itself)
- Effort guides depth: High = thorough + edge cases + tests, Medium = follow patterns, Low = targeted change
- The agent prompt MUST include: "Use Glob/Grep/Read tools for file search and content search. Do NOT use grep, find, cat, or head via Bash."

**c. Spawn simplify review agent** — always `model: "sonnet"`:
> Review all code changed since the last commit for quality and fix issues.
>
> 1. Run `git diff --name-only HEAD` to find changed files. Read each one.
> 2. Fix: bugs, dead code, duplication (3+ sites), N+1/performance, misleading names, missing `remember`/`derivedStateOf` in Compose, magic strings.
> 3. Do NOT change: formatting, imports, unchanged code, comments/docstrings, impossible error handling.
> 4. Report each fix in one line; note deferrals with reason.

**d. Test + build** — run as a single Gradle invocation:
- `./gradlew test assembleDebug` — tests and build in one JVM, reuses compilation. Both must pass.

**e. Commit** with a descriptive message following the repo's commit style.

**f. Verify + update:** `git log -1 --oneline`, update status table (pending → done).

#### Parallel groups (steps joined by `+`)

1. For each step in the group, run the step lifecycle in an Agent with `isolation: "worktree"`
2. Launch all agents in a **single message** (parallel tool calls)
3. Wait for all to complete
4. Merge worktree branches sequentially into main
5. If merge conflicts: fall back to running conflicting steps sequentially on main
6. Verify all commits, update status table

#### Between steps

- If a step fails after 2 fix attempts, **stop** and report with the current status table
- Do NOT skip failed steps

### Phase 3 — Wrap Up

After all steps complete:
1. **Final simplify review (4+ steps only)** — if the plan has 4 or more steps, spawn a Sonnet agent on the full phase diff (`git diff [first-commit]..HEAD`). Skip for plans with fewer than 4 steps.
2. Update MEMORY.md with plan completion status, key patterns, deferred items
3. Display summary:
   ```
   Plan [plan-name] complete.
   Plan file: [path to the plan .md file]
   Order: [order line]
   Steps: [N] / [N] succeeded
   Commits: [list of commit hashes + messages]
   ```

## Error Recovery

- **Test failure:** Fix in-place, re-test, re-commit (up to 2 retries per step)
- **Build failure:** Same as test failure
- **Step produces no changes:** Log a warning and skip to next step
- **Merge conflict (parallel):** Abort parallel, re-run conflicting steps sequentially
- **User interruption:** Report plan file path, which steps are done and which remain

## Examples

```
/build                          → resume current session plan, or list 5 newest per source
/build 10                       → list 10 newest plans per source
/build my-plan                  → run all steps of my-plan
/build my-plan 5.2              → run only step 5.2 of my-plan
```
