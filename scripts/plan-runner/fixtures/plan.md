# Fixture plan for scripts/plan-runner/selfcheck.sh

Not a real plan. Exercises the Order grammar (→, +, ‖, trailing prose), heading tags,
Shipped / Decision blocks and section boundaries.

**Order: 1 → 2 → 3+4 ‖ 5 → 6** (prose after the closing stars must be ignored; ‖ = checkpoint)

## 0. Decisions (signed off never)

| Question | Decision |
|---|---|
| Fixture | Yes |

## 3. Steps

### Step 1 — Already done (`docs:`) — skills: none
Body of step one.

**Shipped** `abc1234` (2026-09-13) — tier: mid. skills: none. Reviewer models: none.
Departures (for sign-off): none

### Step 2 — Tagged step (`feat(x):`, no CHANGELOG) — skills: code-review, simplify; model: strong; budget: 12
Body of step two. A sentence mentioning skills: in prose is not a tag because tags live in headings.

### Step 3 — Waiting on the human (`fix:`)
Body.

**Decision needed** — left or right?
- Option A
- Option B
Recommendation: A.

### Step 4 — Max tier (`fix:`) — model: max
Body.

### Step 5 — Untagged
The word **Shipped** inside prose, not at line start, must not count as shipped.

### Step 6 — UI step — skills: app-ui-design
Body.

### Step 7 — Shipped with skills (`feat:`) — skills: code-review
Body.

**Shipped** `0123abc` (2026-09-13) — tier: strong, tagged max. skills: code-review, simplify. Reviewer models: simplify: opus, opus, sonnet.
Departures (for sign-off): one thing.

## 4. Verification

**Shipped** `deadbee` — sits under a `##` heading, not under a step, so it belongs to no step.
