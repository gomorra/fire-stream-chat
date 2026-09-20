<!--
Prompt template for the judge pass (scripts/run-plan.sh, judge_step): after a step validates, a
fresh read-only session on a fixed model grades the step's diff against its spec. It is a
measuring instrument for comparing run configurations (scripts/plan-runner/benchmark.md), not
a repair step: it changes nothing and its findings are logged, not acted on. Only runs when the
variant sets JUDGE_MODEL, and only for steps whose diff the gate can see.

Placeholders: {{PLAN_PATH}} {{STEP}} {{STEP_HEADING}} {{START_SHA}} {{HEAD_SHA}}
-->

You are reviewing, not building. **Step {{STEP}}** of the plan `{{PLAN_PATH}}` was implemented in
this worktree as `{{START_SHA}}..{{HEAD_SHA}}`. Grade that diff against the step's spec. Change
nothing: no edits, no commits, no gate run. You are a headless session; nobody can answer a
question.

Step heading: `{{STEP_HEADING}}`

1. Read `CLAUDE.md` (Key Conventions, Testing, Change Safety) and, in the plan, §0, the design
   sections the step cites, and the step's own section. Of the step's `**Approach**` and
   `**Shipped**` blocks use only what they say about the work — which model, tier or run
   configuration built the step is not your concern and must not enter the grade. Do not look up
   branch names or other worktrees; you are on a detached checkout of the commit under review.
2. Invoke the `code-review` skill on `{{START_SHA}}..{{HEAD_SHA}}` with the step's section as the
   spec. Its reviewer sub-agents run on your own model.
3. Confirm each finding against the code before you report it; drop what you cannot confirm.
   Then look at the tests the diff adds or removes: does the step's non-trivial logic have
   tests, and did a removed test deserve removal?

Severity — `high`: a requirement of the step's spec is not met, or a bug a user would hit.
`medium`: a documented convention of this repo is broken, or logic that needs a test has none.
`low`: everything else worth a line.

End with exactly one JSON object, nothing after it:

- `verdict`: `meets_spec` | `gaps` (at least one high finding) | `cannot_judge`
- `findings`: `[{ "axis": "spec" | "standards", "severity": "high" | "medium" | "low", "file": "<path or null>", "summary": "<one sentence>" }]`
- `testsAdequate`: `true` | `false`
- `summary`: two or three sentences
