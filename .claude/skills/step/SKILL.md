---
name: step
description: Execute a single step from an implementation plan with the recommended model and effort. Use when the user says /step [plan-name] [step-number] to implement one step of a plan.
user-invocable: true
disable-model-invocation: false
model: opus
argument-hint: [plan-name] [step-number]
---

Execute a single implementation step from a plan file.

**Arguments:** `$ARGUMENTS[0]` = plan file name (without `.md`), `$ARGUMENTS[1]` = step number

## Procedure

1. **Read the plan** — check `.claude/plans/$ARGUMENTS[0].md` first; if not found, check `~/.claude/plans/$ARGUMENTS[0].md`.
2. **Find step $ARGUMENTS[1]** in the plan. Extract:
   - Task description
   - Recommended **Model** and **Effort**
   - File paths listed for the step
3. **Display the step header:**
   > **Step $ARGUMENTS[1] — Model: [model] / Effort: [effort]**
4. **Spawn the implementation agent** with the recommended model:
   - Pass **file paths only** (not file contents) — the agent reads them itself
   - The agent prompt MUST include:
     - The full step description from the plan
     - The list of file paths to modify
     - Effort level (guides depth: High = thorough + edge cases + tests, Medium = follow patterns, Low = minimal targeted change)
   - Use `isolation: "worktree"` only if the plan explicitly marks steps as parallel
5. **After the implementation agent completes**, spawn a **simplify review agent** with `model: "sonnet"`:

   > Review all code changed since the last commit for quality and fix issues.
   >
   > 1. Run `git diff --name-only HEAD` to find changed files. Read each one.
   > 2. Fix: bugs, dead code, duplication (3+ sites), N+1/performance, misleading names, missing `remember`/`derivedStateOf` in Compose, magic strings.
   > 3. Do NOT change: formatting, imports, unchanged code, comments/docstrings, impossible error handling.
   > 4. Report each fix in one line; note deferrals with reason.

6. **Test + build + commit** (run sequentially):
   - `./gradlew test` — must pass
   - `./gradlew assembleDebug` — must be clean
   - Create a git commit following the repo's commit style
   - Update MEMORY.md with what was done

## Error Handling

- If tests fail: read the failure, fix, re-test (up to 2 retries)
- If build fails: same as test failure
- If the simplify agent made changes: re-run tests and build before committing
