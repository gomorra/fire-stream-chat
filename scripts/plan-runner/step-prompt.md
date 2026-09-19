<!--
Step prompt template for the plan runner (docs/plans/done/plan-runner.md).
scripts/run-plan.sh fills the {{PLACEHOLDERS}} and passes the result as the prompt of one
headless `claude -p` session per step. Everything below the line is what the step session reads.

Placeholders:
  {{PLAN_PATH}}     repo-relative plan path, e.g. docs/plans/call-audio-routes.md
  {{PLAN_NAME}}     plan file stem, e.g. call-audio-routes
  {{STEP}}          step number from the Order line
  {{STEP_HEADING}}  the step's full heading line, tags included
  {{TIER}}          the tier this session runs at: max | strong | mid
  {{TAGGED_TIER}}   the tier the plan tagged (same as TIER unless --cap lowered it)
  {{SKILLS_FLOOR}}  comma-separated skills from the heading's `skills:` tag, or "none"
  {{BRANCH}}        the plan branch, e.g. plan/call-audio-routes
  {{BASE}}          git merge-base main HEAD at launch
  {{COMMITS}}       output of `git log --oneline {{BASE}}..HEAD`, or "(none yet)"
  {{SCHEMA_PATH}}   repo-relative path of step-result.schema.json
  {{TRIPWIRE_RULES}} the driver's diff tripwire as bullet text (lib.sh pr_tripwire_rules)
  {{ADVISOR_BLOCK}} the "## Advisor" section when the run attaches an advisor model, else empty
  {{ATTEMPT_BLOCK}} the "## Earlier attempt" section on an escalated re-run, else empty
-->

Implement **step {{STEP}}** of the plan `{{PLAN_PATH}}` in this worktree, on branch `{{BRANCH}}`.
You are a headless session started by `scripts/run-plan.sh`; nobody is watching and nobody can
answer a question. Your last message must be the JSON result described at the end.

Step heading: `{{STEP_HEADING}}`
Tier: {{TIER}} (tagged: {{TAGGED_TIER}}). Mandatory skills (the floor): {{SKILLS_FLOOR}}.

## Where things stand

Commits on this branch since it left `main` (`{{BASE}}`):

```
{{COMMITS}}
```
{{ATTEMPT_BLOCK}}
## Read first, in this order

1. `CLAUDE.md` — build gate, post-step workflow, Key Conventions, Change Safety, Model Guidelines.
2. `{{PLAN_PATH}}`, all of it: the §0 decisions table (settled, do not re-litigate), the design,
   every earlier step's `**Shipped**` block (later steps build on the code as it is, not as the
   plan first described it), your step's section, and any `**(step-N …)**` annotations earlier
   steps left in it. Line numbers in the plan were verified when it was written — re-verify
   against the code before editing.
3. `docs/GOTCHAS.md`, and `docs/FEATURE-MAP.md` if the step spans four or more packages.
4. If the step touches `app/src/main/java/com/firestream/chat/ui/`, load the `app-ui-design`
   skill **before** writing any Compose code.

## Approach — before the first edit

Write an `**Approach**` block under your step's heading in the plan, five to ten lines: the files
you will touch and in what order, the tests you will add, and anything in the step's spec that the
code as it stands contradicts. If what you found contradicts a §0 decision or a §2 design point,
stop with `needs_decision` now — before the implementation turns are spent, not after. The block
stays in the plan, above the `**Shipped**` line, and lands with the `docs(plan):` commit.
{{ADVISOR_BLOCK}}
## Skills — floor, intent, re-decision

- The mandatory skills above come from the plan and are not up for evaluation. They go into
  `skills.intended` and must appear in `skills.run`.
- **Before touching code**, decide which further skills are worth running for this step — one
  reason each — and add them to `intended`. An empty addition is legitimate for a small step.
- **After the gate is green**, re-decide against the real diff (`git diff --stat`, paths touched,
  whether a bug fix got its regression test). You may add skills. You may drop one you added
  yourself only with a reason in `skipped`; never a mandatory one.
- The driver re-checks your diff mechanically and rejects a `done` result that misses a skill these
  rules require, so apply them yourself at the re-decision:
{{TRIPWIRE_RULES}}
- Reviewer sub-agents spawned by `/simplify` or `/code-review` must not run on a tier above
  **{{TIER}}**. Report every sub-agent's model in `reviewerModels`.
- `/simplify` that changes code re-runs the gate before the commit. `/code-review` findings you
  can act on, act on; findings only the human can settle become a decision (below).
- `/code-review ultra` is user-triggered and billed: never invoke it.

## Decisions

Decide routine things yourself and record them as departures; stop if the answer would change a
§0 decision or a §2 design point of the plan; never ask a question interactively.

To stop: write a `**Decision needed**` block under your step's heading in the plan — the question,
the options, your recommendation — commit nothing, leave the work in progress in the worktree, and
end with `status: needs_decision`. The human resumes this session to answer.

If a `**Decision taken**` block already sits under your step, it is the human's answer to a
question an earlier attempt asked: honour it, fold it into the `**Shipped**` departures line, and
do not ask again.

## Carrying insight forward

Before you write the `**Shipped**` line, re-read the remaining steps of the plan and annotate any
whose spec your work has changed — a method that must now be deleted, a path the review found, a
test that must exist — in that step's section, marked `**(step-{{STEP}})**` or
`**(step-{{STEP}} /code-review)**`. A finding outside the plan's scope goes to `docs/BACKLOG.md`,
`TECH_DEBT.md` or `docs/GOTCHAS.md`, as CLAUDE.md routes it, never into the plan.

## Finish the step

Follow CLAUDE.md's post-step workflow, then land the step as two commits:

1. **The code commit**: code and its tests, green on the full gate, with the plan's commit prefix.
   A user-visible step also carries its CHANGELOG entry; invoke the `changelog-release` skill for
   the bump decision.
2. **The `docs(plan):` commit**: the `**Shipped**` block under your step's heading, naming the
   code commit's hash, and (for a user-visible step) the CHANGELOG entry's hash. Shape:

   ```
   **Shipped** `<code commit hash>` (<YYYY-MM-DD>) — tier: {{TIER}}<, tagged {{TAGGED_TIER}} if capped>. skills: <run list or none>. Reviewer models: <or none>.
   Departures (for sign-off): <one line each, or "none">
   ```

Rules that hold throughout:

- Never `git push`, never touch `main`, never `./gradlew --stop`, never leave this worktree.
- Never feed `git commit` from a HEREDOC — a repo hook blocks it and the turn is wasted. Use chained
  `-m` flags, one per paragraph.
- One plain command per Bash call: no `for`/`while` loops, no `$(…)` or `<(…)`, no `find -exec`,
  no `;` chains. Bash runs behind a prefix allowlist; every call of those shapes in earlier runs was
  denied, silently, and the turn was wasted. Use the Grep and Glob tools to look across files.
- Do not update local memory (`MEMORY.md` does not exist here); route facts to the tracked docs.
- Do not ask questions with any tool; the decision rule above is the only way to involve the human.
- Do not skip or delete a failing test; fix the cause. If the gate cannot be made green, end with
  `status: blocked`, `blockedKind: gate`, and say why in `summary`.

## The result

End with exactly one JSON object, nothing after it, valid against `{{SCHEMA_PATH}}`:

- `status`: `done` | `needs_decision` | `blocked`
- `step`: {{STEP}}
- `commit`: the code commit's hash when `done`, else `null`
- `skills`: `{ "intended": [...], "run": [...], "skipped": [{ "skill", "reason" }] }`
- `reviewerModels`: one string per review skill that spawned sub-agents, e.g. `"simplify: opus, opus, sonnet"`
- `question`: the decision text when `needs_decision`, else `null`
- `blockedKind`: when `blocked`, one of `gate` (the work or the gate could not be finished — the
  driver may re-run the step once at higher effort), `spec` (the step cannot be built as
  specified), `environment` (tooling, permissions, network); else `null`
- `advisorConsults`: how many times you consulted the advisor, or `null` when none is attached
- `summary`: two or three sentences — what was built, what departed from the plan, what the human should look at
