<!--
Prompt template for the fresh review session the driver starts when a step's `done` result is
valid except for skills its diff requires (scripts/run-plan.sh, review_nudge). It replaces
resuming the step session for this case: a resumed session re-reads its whole context on every
turn — the 2026-09-14 resume cost as much as the step it repaired.

Placeholders: {{PLAN_PATH}} {{STEP}} {{STEP_HEADING}} {{BRANCH}} {{START_SHA}} {{TIER}}
  {{MISSING}}      the skills to run, comma-separated
  {{SCHEMA_PATH}}  repo-relative path of step-result.schema.json
-->

**Step {{STEP}}** of the plan `{{PLAN_PATH}}` is implemented and committed on `{{BRANCH}}` as
`{{START_SHA}}..HEAD`. The driver's check of that diff requires skills the step session did not
run: **{{MISSING}}**. Run them. You are a fresh headless session started by
`scripts/run-plan.sh`; nobody is watching and nobody can answer a question.

Step heading: `{{STEP_HEADING}}`

1. Read `CLAUDE.md` (review tools, post-step workflow, Change Safety) and the step's section in
   the plan, including its `**Approach**` and `**Shipped**` blocks.
2. Invoke each missing skill on `{{START_SHA}}..HEAD`. Reviewer sub-agents must not run on a tier
   above **{{TIER}}**; note each one's model.
3. Act on the findings you can confirm against the code. If that changes code: re-run the full
   gate, then commit the fix as a new commit with the plan's prefix — never amend the step's code
   commit, the `**Shipped**` line names its hash. A finding only the human can settle goes into
   the step's departures line; a finding outside the plan's scope goes to `docs/BACKLOG.md`,
   `TECH_DEBT.md` or `docs/GOTCHAS.md`.
4. Add the skills and the reviewer models to the step's `**Shipped**` line, add a departures line
   for anything you changed, and commit that as a new `docs(plan):` commit.

Rules: never `git push`, never touch `main`, never `./gradlew --stop`, never leave this worktree,
never feed `git commit` from a HEREDOC (chained `-m` flags instead), ask no questions with any
tool. One plain command per Bash call — no loops, no `$(…)` or `<(…)`, no `find -exec`, no `;`
chains; the allowlist denies those silently.

End with exactly one JSON object, nothing after it, valid against `{{SCHEMA_PATH}}`: `status`
`done` (or `blocked` with a `blockedKind` when the gate cannot be made green again), `step`
`"{{STEP}}"` (a string), `commit` the hash the `**Shipped**` line names, `skills.run` the skills you ran here,
`reviewerModels`, `question` and `advisorConsults` `null` unless they apply, and a `summary` of
what the review found and what you changed.
