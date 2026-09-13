# Handoff — image editor, Phase 1

Prompt used to seed an implementation session, kept because a link is easier to pass
around than a transcript. Written 2026-09-09.

**Status: not started.** A background agent was seeded with this and stopped ~7 minutes
later before writing anything — it had only read the plan and the relevant sources. Both
trees are clean at `af02358`; no feature code exists on this branch.

---

## Task

Implement **Phase 1** of [`.claude/plans/image-editor.md`](image-editor.md).

**Read the plan first.** It is the product of a long design conversation and is
self-sufficient: six phases, every decision with its rejected alternatives and rationale,
traps, tests, and the docs to update. Do not re-derive or re-litigate those decisions.
§2.5 (per-image HD) and §2.7 (undo/redo cursor, layer visibility) govern Phase 1
specifically.

Phase 1 is the `ImageEditToolbar` in `ImagePreviewScreen`; `PendingMedia` gaining
`originalUri` / `isHd` / `editHistory` / `editCursor` with its `ListSaver` extended;
`MessageRepository.sendMediaMessage` gaining `isHd: Boolean? = null` with the DataStore
fallback; the HD bottom sheet with estimated sizes; and download. The plan lists the
exact files and the exact tests.

## Branch

Work lands on `claude/image-modification-share-screen-fxsldt`. If you are in a git
worktree you will be on a branch of your own — that is fine, and do **not** try to check
out the target branch: git refuses to check out one branch in two worktrees, so it will
only fail. Commit your own branch; it fast-forwards into the target afterwards.

## Mockups

<https://claude.ai/code/artifact/f21151fb-7d70-4f98-99b9-52a0bd816541> — row A is the
chosen direction. The `.dc.html` sources are **not** on disk; extract them from that
published page (the `design` skill) only if the canvas itself needs changing.

## Build gate — it works, run it

A note in `CLAUDE.md` used to claim cloud sessions cannot run Gradle. That was a
misdiagnosis (a missing Android SDK) and is corrected. Verified 2026-09-09:

```
./gradlew :app:testFirebaseDebugUnitTest     # 910 tests
./gradlew :app:assembleFirebaseDebug
```

Use the flavor-qualified tasks — bare `test` also builds the pocketbase flavor, which is
unmaintained and not needed. Exactly **one** test fails, only behind the sandbox proxy,
and it is not yours: `ApkDownloaderTest` *"unresolvable host maps to friendly No internet
connection message"* expects a DNS failure and the proxy answers 403 on CONNECT.
**Any other failure is real.**

## Environment

`.claude/hooks/session-start.sh` provisions what a fresh container lacks: the Android SDK
(`scripts/install-android-sdk.sh`), a build-only placeholder `app/google-services.json`
(gitignored — the google-services plugin is applied module-wide, so every variant needs
one), and `LANG`/`LC_ALL` come from `.claude/settings.json`. If a build dies with
`SDK location not found`, or with a bare `Internal compiler error` (a locale problem —
see *Build tooling* in [`docs/GOTCHAS.md`](../../docs/GOTCHAS.md)), the hook did not run:
run the installer by hand rather than working around it. The repo's default Gradle memory
settings were verified sufficient on a 4-CPU / 15 GB container — do not add JVM tuning.

## Workflow

Follow `CLAUDE.md`'s post-step workflow strictly: write the tests, `./gradlew test`,
`./gradlew assembleDebug`, then commit **immediately** once green without waiting to be
asked — one commit carrying code and its tests together. Phase 1 is user-visible, so it
needs a `CHANGELOG.md` entry and a version bump. A hook blocks `git commit` with a
heredoc; use chained `-m` flags. Do not open a pull request unless asked.

## Open items — for the human, not to be fixed silently

- No PR has been opened.
- `.kotlin/errors/*.log` are tracked build artefacts committed at some point in the past.
  A `.gitignore` rule covers new ones; the tracked files were deliberately left alone.
- `CLAUDE.md`'s workflow step 6 says to update `MEMORY.md`, but that file is **gitignored**
  (`.gitignore:34`) and machine-local, so it will not exist in a fresh container. Skip the
  step there and put anything durable in the plan or `GOTCHAS.md`.

## Suggested skills

Call the `Skill` tool for these:

- **`changelog-release`** — the Phase 1 version-bump decision and CHANGELOG placement, and
  again as each later phase lands.
- **`app-ui-design`** — while building the toolbar and HD sheet, so the Compose /
  Material3 work matches the app's existing density, shapes and touch targets.
- **`tdd`** — optional; the saver round-trip and `isHd`-precedence tests are a natural
  red-green-refactor.
- **`code-review`** — before declaring Phase 1 done. The plan's own rule is to run it on
  any diff touching the send path, which Phase 1 does.
- **`simplify`** — only if a trigger from `CLAUDE.md` applies; it likely will not here.
