Implement **Phase 5** of the image editor in this repo (FireStream Chat, Android/Kotlin/Compose). Phase 5 is **two commits**: 5a is a pure refactor, 5b is the feature on top.

## Read first

`.claude/plans/image-editor.md` is the brief and is self-sufficient. **§3 "Phase 5"** is the deliverable list; it is governed by §2.1 (rasterize per screen), §2.3 (`ImageFitMapper`, geometry normalized to the image), §2.4 (editors are full-screen overlays, NOT NavHost routes), §2.7 (undo/redo and layer visibility at two levels), §2.8 (one picker, four hosts, host-declared tabs) and §4 (Traps). Do not re-derive or re-litigate any of it.

Also read `CLAUDE.md` (build gate, post-step workflow, Key Conventions, Change Safety, Model Guidelines) and `docs/PATTERNS.md#image-edits-rasterize-per-screen-overlay-geometry-is-normalized`.

## Where things stand

Phases 1-4 are **shipped and merged to main** (PR #59, merge commit f4f4c255). Work from main; there is no outstanding branch. Read `git log 76e76d8e..HEAD` and the diffs rather than trusting this summary. Each phase's departures are written up in its own section of the plan and flagged for sign-off — read those blocks; the human may have signed off, reversed or amended them.

### What Phase 4 built that Phase 5 must reuse rather than reinvent

- **`ui/chat/imageedit/EditorChrome.kt`** is the shared editor shell, extracted when the draw screen turned out to be the same shape as the adjust screen: `EditTopBar` (with an `EditTopBarLabels` bundle and a `middleContent` slot), `EditToolButton`, `EditFailureBanner`, `EditFlattenScrim`, `editTint`, and an `EditorChrome` object holding the layout heights. The overlay screen is a third screen of that same shape — use these, do not write a fourth top bar.
- **`ImageEditServices`** (`ui/chat/imageedit/ImageEditServices.kt`) is the `@Immutable` bundle of ViewModel-supplied capabilities, now seven: `estimateSendSize`, `editStepExists`, `discardEditSteps`, `probeSource`, `renderPreview`, `pixelate`, `rasterize`. Phase 5's services go **in this bundle**, never as new `ImagePreviewScreen` parameters.
- **`ImagePreviewScreen` hosts editors through a private `Editor { ADJUST, DRAW }` enum and an `EditTarget`** (keyed on `originalUri`, because an item's index can change under the editor and its `uri` moves as steps land), with one `landEdit` -> `discardEditSteps` join and one saver. Add `OVERLAY` to that enum; do not add a third parallel state variable.
- **`liveSteps: () -> Set<Uri>`** is read at Done, not captured. Pass every batch item's current `uri`. `rasterize`'s third argument has no default on purpose.
- **`domain/util/StrokeGeometry.kt`** is the precedent for Phase 5's placement/scale/rotation arithmetic: pure, Android-free, JVM-testable, in `domain/` because **both** renderers need it — the Compose preview and the `android.graphics` flatten are two separate implementations and the shared arithmetic is the only thing keeping them in agreement. Overlay geometry has the same problem. `CropGeometry.kt` and `ImageFitMapper.kt` are the same seam.
- **`RasterOp` lives in `domain/util/ImageEditGeometry.kt`** with `Rotate`, `Flip`, `Straighten`, `Crop`, `Resize`, `Strokes`. `Overlays` is deliberately NOT declared — its output contract is Phase 5's decision alongside the UI, exactly as `Strokes` was Phase 4's. Adding a subtype stays local because the sealed hierarchy is one file. **No new `UI_ALLOWED_DATA_IMPORTS` entry is needed or permitted**; the budget is spent on `ImageEditRasterizer`, imported by `ChatViewModel` alone.
- **Callbacks bundles**: `AdjustCallbacks`, `DrawCallbacks`, `MessageBubbleCallbacks`. Write `OverlayCallbacks` for the same reason — see the ceiling note below.

### Two hard-won traps that will bite Phase 5 specifically

1. **`pointerInput` does not restart when a value it reads changes, unless that value is a key — and making it one tears the detector down mid-gesture.** This shipped as a real bug in Phase 3's crop frame: the gesture read a frame one whole gesture out of date, so corners could be dragged exactly once and the frame could never be moved bodily. Fixed in `9169ce31` with `rememberUpdatedState`. Phase 5 is drag/scale/rotate over a selection — the selected object and its transform are exactly this shape. Read `9169ce31` before writing the manipulation gestures.
2. **A composable is rejected by ART for total register pressure, not just parameter count, and neither the tests nor a release build can see it.** `MessageBubble` threw `VerifyError` on chat open at 296 registers (`8522232c`); Robolectric runs on the JVM which has no dex verifier, and R8 optimises the release build under the ceiling, so every test passed while every debug build crashed. `docs/GOTCHAS.md` now carries both the rule and a `dexdump` recipe that reads the register count straight out of a built APK with no device. The overlay screen — selection state, two drag handles, a picker panel and four tabs — is the most likely place in this feature to hit it. **Run that check on `OverlayImageScreen` before you call 5b done.**

### The 5a refactor is where a regression hides

§4 names the four behaviours that are easy to lose moving `EmojiHandlerPanel` (~700 lines, three existing call sites): the frozen `sessionRecents` order, the long-press drag-to-size gesture with its row-sibling fade, the quick-reactions row that only `EmojiMode.REACTION` shows, and the backspace key that only `TEXT_INPUT` shows. All four are emoji-*tab* concerns, not shell concerns. 5a ships with **no CHANGELOG entry and no version bump**; 5b gets both.

## Build gate

    ./gradlew :app:testFirebaseDebugUnitTest     # 1140 tests as of the Phase 4 merge, 0 failures
    ./gradlew :app:assembleFirebaseDebug

Use the flavor-qualified tasks; bare `test` also builds the unmaintained pocketbase flavor.

**This host's test JVM is flaky and it looks exactly like real failures.** Two forms were seen repeatedly: a `SIGSEGV` in the fork that reports dozens of failures with it, and `ExceptionInInitializerError` out of Robolectric's native graphics that fails an entire `@GraphicsMode(NATIVE)` class. In both cases `./gradlew --stop` and a re-run came back fully green with the same code. **Never edit code in response to a failure you have not reproduced on a fresh daemon.** Maven Central also rate-limits (429) on a cold cache; retry with ~45-60s backoff.

## Workflow

Follow `CLAUDE.md`'s post-step workflow strictly: write the tests, run both gate commands, commit **immediately** once green without waiting to be asked, one commit carrying code and its tests together.

- A pre-commit hook **blocks `git commit` with a heredoc**, and it matches the whole Bash call. Use chained `-m` flags.
- **Never `git add -A` in this repo without reading the staged diff first.** Doing so in this session swept four unrelated edits made by a concurrent process into a bug-fix commit — `compileSdk`/`targetSdk` 35->36 and hard-coded `versionCode`/`versionName` over the git-derived values `CLAUDE.md` says must never be hand-edited. Reverted in `2c4674a4`. Stage named paths.
- A CHANGELOG entry cites the commit hash it describes, which a commit cannot know about itself. Land the code commit with the entry hash-less, then a small `docs(changelog): reference the ... commit` follow-up. Invoke `changelog-release` for the bump decision. Note the working header is currently `## [UNRELEASED] [1.27.0]`.
- **Be honest about what a user can actually reach**, and log anything you cannot confirm under `docs/BACKLOG.md` § *Pending on-device verification* rather than implying the phase is finished.
- Extend `docs/FEATURE-MAP.md` as §6 of the plan requires — the picker becomes a cross-cutting feature of its own and earns its own table — and record departures in the plan's own Phase 5 section the way Phases 1-4 did. `docs/BACKLOG.md` §4.6 needs the rewording §6 asks for once the picker shell lands.
- `MEMORY.md` is gitignored and will not exist — skip workflow step 6. Durable notes go in the plan, `docs/GOTCHAS.md` or `TECH_DEBT.md`.
- **Do not open a pull request and do not merge.** The user creates the PR.
- Per `CLAUDE.md`'s Model Guidelines, state which model each spawned `Agent` runs on, at launch and when summarising.

## On-device state

`emulator-5554` is attached and has a **release** build installed (`1.25.0-dev+2c4674a4`, versionCode 729) containing Phases 1-4. Two things about it:

- **Do not install a debug build without asking.** The user declined this once, for good reason: before `8522232c` the debug build crash-looped on chat open. That fix is verified only by register count read from the dex — **it has never been confirmed on hardware**, and doing so is still an open item.
- Release builds cannot use `run-as`, so the extraction route for inspecting a flattened file is the preview screen's **Save to Downloads** button, which lands the image in shared storage where `adb pull` reaches it. A 12 MP test photo is at `/sdcard/Pictures/phase4-bigphoto.jpg` (4000x3000, fine checkerboard centre, coloured corner markers).
- `versionCode` derives from `git rev-list --count HEAD`, so a build from an older commit will refuse to install over a newer one. `adb install -r -d` allows the downgrade and keeps app data. **The chat list holds the user's real conversations — never press Send, and treat the first row as the user's own name.**

`docs/BACKLOG.md` § *Pending on-device verification* carries unchecked blocks for Phases 2, 3 and 4 — extend it, do not replace it. Phase 4's six items are all still open, including the one the plan calls the check that matters most: whether the flattened file actually redacts what the preview showed as covered.

## Suggested skills

Call the `Skill` tool for these:

- **`app-ui-design`** — before writing `OverlayImageScreen.kt` and the picker shell. It carries this app's settled Compose system and its paid-for traps.
- **`tdd`** — the placement, scale and rotation arithmetic and the 15-degree snapping are natural red-green. Put the pure maths in an Android-free file in `domain/` so a JVM test can reach it, and keep bitmap round-trips behind Robolectric with `@GraphicsMode(NATIVE)`.
- **`changelog-release`** — the bump decision and CHANGELOG placement for 5b (5a gets neither).
- **`code-review`** — before declaring each of 5a and 5b done. It has earned its keep on every phase: a §2.7 semantics bug on Phase 1, two real bugs on Phase 2, three on Phase 3 including the double-centring that put every crop handle a finger-width off the photo.
- **`simplify`** — only if a `CLAUDE.md` trigger applies. The gesture/state-machine trigger plausibly does for 5b.
