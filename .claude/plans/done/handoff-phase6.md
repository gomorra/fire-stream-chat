Implement **Phase 6** of the image editor in this repo (FireStream Chat, Android/Kotlin/Compose). Phase 6 is the last phase of the plan and is **one commit**.

## Read first

`.claude/plans/image-editor.md` is the brief and is self-sufficient. **§3 "Phase 6"** is the deliverable list; it is governed by **§2.6** (editing a sent image means sending a *new* one — the decisive constraint), §2.4 (editors are full-screen overlays, not NavHost routes) and §2.1 (edits rasterize per screen into `cacheDir/edits/`). Do not re-derive or re-litigate any of it.

Each earlier phase's section carries a "departures … flagged for sign-off rather than settled" list written by the agent that built it. **41 such departures across Phases 1–5 are still unsigned** — read the ones you build on, since the human may have signed off, reversed or amended them.

Also read `CLAUDE.md` (build gate, post-step workflow, Key Conventions, Change Safety, Model Guidelines) and `docs/GOTCHAS.md`.

## Where things stand

Phases 1–5 are shipped. As of this handoff, `origin/main` is `f8977699` and local `main` carries four unpushed commits: `c6075c47` (5a, the picker extraction), `021b5b94` (5b, the overlay screen, v1.28.0), `136aaa33` (its CHANGELOG hash), `36ac5ad2` (a GOTCHAS note). Read `git log f8977699..HEAD` and the diffs rather than trusting this summary — the branch may have moved.

**Do not push, do not open a pull request, do not merge.** The user does that.

**One thing may already be in flight:** a background session (`claude agents` → "Image editor: 5b review + Phase 6") was launched to run `/code-review` on 5b and then start Phase 6. Check whether it has landed anything before you begin, or you will duplicate its work.

## What Phase 6 actually touches

The plan's three bullets, against the code as it stands:

### 1. An `onEdit` action in `FullscreenOverlayControls`

`ui/chat/FullscreenImageViewer.kt` already has exactly the pattern to copy. `onSaveToDownloads` is a **nullable** lambda threaded through three composables, and a null hides the control rather than greying it:

- `FullscreenImageViewer(imageUrl, localUri, onDismiss, onSaveToDownloads, snackbarHostState)` — the single-image branch (line ~73)
- `FullscreenImagePager(items, initialIndex, onDismiss, snackbarHostState, onPageChanged, onSaveToDownloads)` — the swipeable gallery (~116), whose `onSaveToDownloads` takes the `FullscreenMediaItem` **currently on screen**, not a fixed one
- `private BoxScope.FullscreenOverlayControls(onDismiss, onSaveToDownloads, snackbarHostState)` (~263)

Add `onEdit` the same shape and the same way. Opt-in per host is the point: the avatar and link-preview viewers must not get it.

Watch the parameter count on all three — this repo has a **~15-parameter / register-pressure Composable ceiling that ART enforces with a `VerifyError` on first render, not at compile time**, and Robolectric cannot see it (`docs/GOTCHAS.md`, `MessageBubbleCallbacks`). Adding two nullable lambdas to a viewer that already has five is where a bundle starts earning its keep. The dexdump recipe for checking a built APK with no device is in `docs/GOTCHAS.md`; `OverlayImageScreen` measured 95 against the 256 ceiling and `MessageBubble` is the app's tightest at 252.

### 2. Wiring in `ChatScreen`

`ui/chat/ChatScreen.kt` has **three** fullscreen branches, not two, and the plan's "both the pager and the single-image branch" predates the third:

- ~2224 `FullscreenImagePager` — the in-chat gallery, keyed on the tapped message
- ~2241 `FullscreenImageViewer` — the fallback for an image that is not part of the chat's media (a link-preview thumbnail, or a message deleted while open). Note it already gates its save on `req.canSaveToDownloads`, a flag on `ChatOverlaysState` (~line 25) set at the one call site that grants it (~1347). That flag is the precedent for whatever gates Edit.
- ~2265 `FullscreenImagePager` — the **search-results** gallery. Decide deliberately whether Edit belongs here; the plan does not mention it because it did not exist when the plan was written. Record the decision either way.

`pendingMedia` is `rememberSaveable` state in `ChatScreen` (~334) and is what the preview screen renders from (~2311). Pushing a one-item batch is `pendingMedia = listOf(PendingMedia(uri, "image/jpeg"))` — the same shape the camera branch uses at ~820.

`PendingMedia`'s primary constructor is `(originalUri: Uri, mimeType: String, caption, isHd, editHistory, editCursor)`. **`originalUri` is the field that must never be written to** — the whole revert model rests on it, so the copy you hand it has to be a file the editor is free to treat as the untouched pick.

### 3. Getting the bytes

§2.6: if the displayed image has no local file yet, download it first behind a spinner, then copy it into the edit cache.

`ChatViewModel.saveImageToDownloads(localUri, mediaUrl, mimeType)` (~484) already does the resolve half of this and is the model to follow: prefer `localUri` when the file exists, else `mediaFileManager.downloadAndSave(chatId, "download_…", mediaUrl)`. `MediaFileManager.downloadAndSave` de-duplicates in-flight downloads by `messageId` and returns the existing file when there is one, so it is safe to call twice.

The **copy into the edit cache is the part that does not exist yet** and is worth thinking about rather than improvising: the sent image lives in the app's own media directory, and handing that path straight to `PendingMedia.originalUri` would point the editor's revert at a file the gallery and the message bubble also read. `ImageEditRasterizer` owns `cacheDir/edits/` and its lifecycle (`discard`, `sweepStale`, the byte budget) and is the single allowlisted `data` class the UI may import (`UI_ALLOWED_DATA_IMPORTS` in `ArchitectureTest`). Decide whether the copy belongs there or in `MediaFileManager`, and say why in the plan's Phase 6 section.

### 4. The download-button audit the phase bundles

§3 asks for it explicitly: the download button is absent from the group-avatar and share-preview viewers, "an inconsistency rather than a decision". The other call sites are `ui/profile/ProfileScreen.kt` (~440, ~452), `ui/share/SharePickerScreen.kt` (~222), `ui/chatlist/ChatListScreen.kt` (~308) and `ui/group/GroupSettingsScreen.kt` (~532). Resolve it as a decision — either wire it or write down why not.

## No HD toggle in the fullscreen viewer

§2.6 is explicit: you cannot un-compress a photo that arrived compressed, and offering the control would imply otherwise. `ImagePreviewScreen` shows the HD pill from its own state, so check what a one-item batch opened this way actually renders.

## Build gate

    ./gradlew :app:testFirebaseDebugUnitTest     # 1203 tests as of 36ac5ad2, 0 failures
    ./gradlew :app:assembleFirebaseDebug

Use the flavor-qualified tasks; bare `test` also builds the unmaintained pocketbase flavor.

**This host's test JVM is flaky and it looks exactly like real failures.** Two forms recur: a `SIGSEGV` in the fork that reports dozens of failures with it, and an `ExceptionInInitializerError` out of Robolectric's native graphics that fails an entire `@GraphicsMode(NATIVE)` class. Both came back fully green on `./gradlew --stop` and a re-run with the same code. **Never edit code in response to a failure you have not reproduced on a fresh daemon.** Maven Central also rate-limits (429) on a cold cache; retry with ~45–60 s backoff.

## Workflow traps on this host

- A pre-commit hook **blocks `git commit` with a heredoc**, and it matches the whole Bash call — so a call containing *both* a heredoc and a `git commit` is refused even when the two are unrelated. Use chained `-m` flags, and keep heredocs in a separate call. The same hook refuses piping `git commit` into `tail`/`head`/`sed`.
- **Never `git add -A`.** A previous session swept four unrelated edits from a concurrent process into a commit that way (`compileSdk`/`targetSdk` 35→36 and hard-coded `versionCode`/`versionName` over the git-derived values `CLAUDE.md` says must never be hand-edited). Reverted in `2c4674a4`. Stage named paths.
- A CHANGELOG entry cites the commit hash it describes, which a commit cannot know about itself. Land the code commit with the entry hash-less, then a small `docs(changelog): reference the … commit` follow-up. Invoke `changelog-release` for the bump. The working header is `## [UNRELEASED] [1.28.0] — 2026-09-10`; a `feat:` landing on a section already dated today upgrades it in place rather than opening a second `[UNRELEASED]` section, which would break `scripts/cut-release.sh`.
- **Prune `docs/BACKLOG.md` in the same commit** as any entry that closes one of its items.
- `MEMORY.md` is a local-only symlink into the auto-memory store and is never committed. Route durable facts to `docs/GOTCHAS.md`, `docs/PATTERNS.md`, `docs/BACKLOG.md` or `TECH_DEBT.md`; the `.claude/hooks/promote-memory.sh` hook raises this on any write into the store.
- Extend `docs/FEATURE-MAP.md` — the *Image / Media Pipeline* table's entry-point line gains the viewer hop — and record your departures in the plan's own Phase 6 section the way Phases 1–5 did.
- Per `CLAUDE.md`'s Model Guidelines, **state which model each spawned `Agent` runs on**, at launch and when summarising. Opus hit a session rate limit during the Phase 5 session and one sub-agent died of it; Sonnet carried the rest.

## Be honest about what a user can reach

**Nothing in Phases 3, 4, 5a or 5b has been on hardware.** `docs/BACKLOG.md` § *Pending on-device verification* is the tracked source of truth and carries four blocks — **extend it, never replace it**. Phase 4's most important item is still open: whether a flattened blur actually redacts what the preview showed as covered, which is a privacy failure rather than a cosmetic one if it does not.

**The user has offered to run device passes.** Take them up on it and write the steps out rather than assuming. Phase 6 is a good one to ask for, because its whole risk is a path — tap a sent photo, edit, send — that no Robolectric test exercises end to end.

## On-device state

`emulator-5554` had a **release** build of Phases 1–4 installed (`1.25.0-dev+2c4674a4`, versionCode 729); it predates 5a and 5b, so it does not contain the overlay screen.

- **Do not install a debug build without asking.** The user declined this once, for good reason: before `8522232c` the debug build crash-looped on chat open, and that fix has still only ever been verified by register count read from the dex, never on hardware.
- Release builds cannot use `run-as`, so the route for inspecting a flattened file is the preview screen's **Save to Downloads** button, which lands the image in shared storage where `adb pull` reaches it. A 12 MP test photo is at `/sdcard/Pictures/phase4-bigphoto.jpg` (4000×3000, fine checkerboard centre, coloured corner markers).
- `versionCode` derives from `git rev-list --count HEAD`, so a build from an older commit refuses to install over a newer one. `adb install -r -d` allows the downgrade and keeps app data.
- **The chat list holds the user's real conversations — never press Send, and treat the first row as the user's own name.** Phase 6's happy path ends at a send button, so stop short of it.

## Housekeeping the user has been asked about and not answered

Three stale files sit untracked in the tree: `phase3-handoff.md`, `phase3-system-prompt.md`, and `.claude/plans/handoff-phase5.md`. Ask before removing any of them. `.claude/plans/image-editor.md` stays either way — and once Phase 6 lands, the whole plan is a candidate for `.claude/plans/done/`.

## Suggested skills

Call the `Skill` tool for these:

- **`app-ui-design`** — before touching `FullscreenOverlayControls`. It carries this app's settled Compose system and its paid-for traps.
- **`changelog-release`** — the bump decision and CHANGELOG placement.
- **`code-review`** — before declaring Phase 6 done. It has earned its keep on every phase: a §2.7 semantics bug on Phase 1, two real bugs on Phase 2, three on Phase 3 including the double-centring that put every crop handle a finger-width off the photo, and a missing `BackHandler` on 5a. **Note that 5b never received it** — if the background session has not done it by the time you start, do it before adding to that code.
- **`simplify`** — only if a `CLAUDE.md` trigger applies. Phase 6 is a wiring change and probably does not trigger it; say so rather than running it by reflex.
