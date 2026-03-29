---
name: simplify-review
description: Review changed code for reuse, quality, and efficiency, then fix any issues found. Always runs on Sonnet with medium effort. Use this as the quality gate after implementing a feature step.
user-invocable: true
disable-model-invocation: false
model: sonnet
effort: medium
---

Review all recently changed code for quality, then fix any issues found.

## Procedure

1. **Identify changed files** — run `git diff --name-only HEAD` (unstaged + staged) and `git diff --name-only --cached` to find all modified files.
2. **Read each changed file** completely.
3. **Review for these issues** (in priority order):
   - **Bugs:** Logic errors, off-by-one, null safety, race conditions
   - **Dead code:** Unused imports, unreachable branches, unused variables/functions
   - **Duplication:** Copy-pasted logic that should be extracted (only if used 3+ times)
   - **N+1 / performance:** Sequential operations that should be parallel, unnecessary recomposition in Compose
   - **Naming:** Misleading names, inconsistent conventions
   - **Missing `remember`/`derivedStateOf`:** Compose-specific: expensive computations in composition that should be memoized
   - **Stringly-typed values:** Magic strings that should use enum/constant references
4. **Fix issues directly** — edit files to resolve each issue. Do NOT just report them.
5. **Do NOT change:**
   - Code you didn't modify in this session (unless it's a direct bug)
   - Formatting, whitespace, import ordering
   - Adding comments, docstrings, or type annotations to unchanged code
   - Error handling for impossible scenarios
6. **Report** what you fixed (one line per fix) and what you deferred with reason.

## Scope

- Only review files changed since the last commit (or since session start if no commits yet)
- If called with a diff range (e.g., from `/build`), review that specific range
- Keep the review focused — this is a quality gate, not a full codebase audit
