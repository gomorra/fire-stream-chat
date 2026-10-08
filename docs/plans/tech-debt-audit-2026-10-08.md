# Tech-debt audit — 2026-10-08

Evidence for `docs/plans/tech-debt-paydown.md`. Every claim was checked against the code at
`9247d4b` with the full git history (1,055 commits, tags to v1.40.2). Line numbers are from that
commit. Five read-only verification passes on the strongest tier covered the data layer, the UI
layer, calls/stickers/build, exception handling and the architecture rules, and size/tests/pins.
The dex register counts and the lint run were measured, not estimated.

Step 1 of the plan applies §A and §B to `TECH_DEBT.md`. The other steps cite this file for the facts
behind their specs.

## Measurements

| Signal | Value at `9247d4b` |
|---|---|
| `TECH_DEBT.md` entries | 57. On main's first-parent history since 2026-04-11: 66 added, 9 closed. 27 on 2026-09-07. |
| Production Kotlin | 392 files, 65.5k lines (42.3k on 2026-09-08: +55 % in 30 days). Tests: 242 files, 42.1k lines. |
| Largest files | `ChatScreen.kt` 2,885 (its composable is 2,420 lines, `:232–2651`); `MessageRepositoryImpl.kt` 1,693; `MessageBubble.kt` 1,575; `SettingsScreen.kt` 1,276; `AdjustImageScreen.kt` 1,113; `ImagePreviewScreen.kt` 1,078; `ImageEditRasterizer.kt` 908; `ChatViewModel.kt` 820 |
| Fastest growth, 30 days | `ImagePreviewScreen.kt` +614 (+132 %); `ChatScreen.kt` +411; `ChatViewModel.kt` +316; `MessageBubble.kt` +253; `MessageRepositoryImpl.kt` +357 since its 2026-09-11 extraction |
| Dex registers (ceiling 255) | `MessageBubble` **251**; `ChatScreen$39.invoke` 201; `ChatListItem` 198; `PollBubble` 185; `ProfileScreen$2.invoke` 182; `FileMessageBubble` 180; `ChatScreen` 174. Measured with build-tools 35 `dexdump` on `app-firebase-debug.apk` (24 dex files, 25,970 app methods, one second). |
| Cancellation | 217 catch-all or `runCatching` sites and 84 `resultOf` calls. 159 swallow a cancellation (75 raw sites, 84 `resultOf`) in 30 files. `data/outbox` has none. A text heuristic a ratchet can use (each `runCatching` and `resultOf`, plus each catch-all with no `CancellationException` clause before it and no `rethrowIfCancellation()`, `ensureActive()` or `throw e` in its block) counts ≈227 in ≈53 files. |
| Lint | `:app:lintFirebaseDebug` crashes in `RememberInComposition` (Compose runtime) and `NullSafeMutableLiveData` (lifecycle): "Found class … but interface was expected". The build disables `NonNullableMutableLiveData`, an id that does not exist. Lint is not in CI and `abortOnError = false`. |
| Room | `exportSchema = false` in `AppDatabase` (v31) and `SignalDatabase`. `fallbackToDestructiveMigration()`. Six `AppDatabase` bumps since 2026-09-11. |
| Pending on-device checks | `docs/BACKLOG.md`: 37 entries, 126 checks. Oldest: baseline profile (2026-04-24) and the Signal database split (2026-04-26). |
| Toolchain | AGP 8.7.3, Kotlin 2.1.0, KSP 2.1.0-1.0.29, Room 2.6.1, Hilt 2.53.1, lifecycle 2.8.7, Robolectric 4.14.1, Gradle 8.11.1, compileSdk 35, Compose BOM 2026.02.01. No Dependabot or Renovate. |

## Gates that are off or broken

- **Composable parameter check.** `checkComposableParamCount` (`app/build.gradle.kts:297–387`) counts `>` as a closing bracket (`:336–337`, `:357–358`). The `>` of a lambda's `->` ends the scan, so parameters after the first lambda-typed one are not counted. It passes `EditableProfileField` (17 parameters, `ProfileScreen.kt:523`), `EditableGroupField` (16, `GroupSettingsScreen.kt:567`), `MainScreen` (12), `CaptionBar` (11) and `ChatScreen` (11). Its comment cites a local memory file no cloud session can read.
- **Dex register ceiling.** `docs/GOTCHAS.md` gives the `dexdump` check. Nothing runs it. Robolectric cannot see a dex verifier failure and R8 hides it in release.
- **Screenshot suite.** The four `MessageBubbleScreenshotTest` baselines show the orange bubble the theme dropped. `verifyRoborazzi*` runs nowhere. `e3b25489` (text scale) and `da2eaf18` (bubble geometry) changed what they capture.
- **`ask-simplify.sh`.** CLAUDE.md calls it the pre-commit hook. It was never registered in `.claude/settings.json`.
- **Runner tripwire.** `PR_TRIPWIRE_DIRS` (`scripts/plan-runner/lib.sh:15`) forces `/code-review` for `data/crypto data/worker di`. CLAUDE.md also names concurrency and the sync path. `data/outbox` and `data/call` are not on the list.
- **`MessageRepositoryImpl` AGENT-NOTE.** States "~1340 LOC" and a planned 1,100-line ceiling (`:39–40`). The file has 1,693 lines. No test enforces any size.
- **`ArchitectureTest` send rule.** It matches the parameter name `recipientId` exactly, so `recipientIds` passes on spelling alone.
- **Vendored skills.** `code-review` and `triage` (from mattpocock/skills, `skills-lock.json`) read `docs/agents/issue-tracker.md`, which does not exist.

## Security (Firestore rules and functions)

Line numbers are in `firestore.rules` and `functions/index.js`. Whether the deployed rules match the
repo cannot be checked from here.

| Finding | Evidence |
|---|---|
| The users update rule also admits requests with no signed-in user. | `firestore.rules:23–25`. The presence mirror it cites writes through `admin.firestore()` (`index.js:27–33`), which bypasses rules, so nothing needs the clause. |
| Any participant may rewrite any chat field and any message. | `:42–45` (participants, admins, ownership); `:54–55` (content, `senderId`). Group roles are enforced only by the client. |
| Invite links: anyone signed in may create or delete one; joining looks refused. | `:122–126`. `ChatRepositoryImpl.kt:227/234` reads the chat and adds to `participants` as a non-participant, which `:40–45` deny. Not confirmed on an emulator. |
| ICE candidates are readable by any signed-in user, and call documents are never deleted. | `:70–77`. Candidates carry IP addresses. No delete path, no TTL policy. |
| Unread counts rise before the block check. | `index.js:246–250` runs before `:255–267`. |
| Storage and Realtime Database rules are not in the repo. | `firebase.json` names Firestore rules and functions only. |

## §A — Verdict for every entry in `TECH_DEBT.md`

Verdicts: **delete** (fixed or moot), **rewrite** (true, but the text is stale), **step N** (this plan
fixes it; the entry stays, rewritten, until that step deletes it), **BACKLOG** (the fix is a feature;
move it to `docs/BACKLOG.md`), **decline** (move to *Declined* with the reason), **keep**, **→ VC n**
(belongs to `docs/plans/video-calls.md` step n). *Added* is the first date the entry appears on main.
Watch paths are relative to `app/src/main/java/com/firestream/chat/` unless they start with a repo
directory. Step 2 checks every path.

| # | Entry (TECH_DEBT.md line) | Added | Verdict | Severity | Trigger | Watch | Evidence and notes |
|---|---|---|---|---|---|---|---|
| 1 | `IncomingMessageProcessor` (:9) | 2026-04-11 | step 19; rewrite | risk | touch | `data/repository/MessageRepositoryImpl.kt` | Stale: the function is `getMessages` (`:389`); `reconcileRawMessage` is 86 lines (`:467–552`); decrypt→upsert is now tested (`MessageRepositoryPushReconcileTest`, `MessageRepositorySyncDecryptTest`); the link to `/root/docs/plans/graceful-mixing-plum.md` is dead. Review #7 inverts it: collapse the two reconcile copies. |
| 2 | Listener has no upper bound (:24) | 2026-09-07 | BACKLOG | quality | event | — | Paging is a feature. Merge into BACKLOG "Performance & pagination (6.4)" with B16. |
| 3 | WorkManager-only sends (:34) | 2026-09-12 | decline | quality | event | `data/outbox/OutboxScheduler.kt` | A design decision of the offline outbox (§0), with a measurement trigger (BACKLOG item 8, never run). The dead API 29/30 arm (`OutboxScheduler.kt:110`, test `:110–111`) goes in step 6. |
| 4 | `ChatScreen.kt` four-way split (:44) | 2026-04-11 | step 17; rewrite | quality | touch | `ui/chat/ChatScreen.kt` | Fired 2026-05-04 (`1a9c9360` crossed 1,800 lines); 2,885 now. Rewrite to review #10: cut where one output crosses the seam (media pick), keep the rest as one file. Absorbs #17. The claim that `ChatScreen` uses `ImagePicker` is false. |
| 5 | `testReleaseUnitTest` (:59) | 2026-04-24 | delete | — | — | — | Release unit tests are disabled since `44bcd83b` (2026-06-10). Also fix the comment at `.github/workflows/ci.yml:77–78` that cites this entry. |
| 6 | Sync-path coverage (:72) | 2026-04-24 | rewrite | risk | event | `data/repository/ListRepositoryImpl.kt`, `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirestoreListSource.kt` | The tripwire half shipped (`cb796cb0`, 2026-06-10). The emulator half remains. Step 16 builds a rules emulator harness in `functions/`; record that a Kotlin sync harness is still missing. |
| 7 | `MessageColumns` shim (:86) | 2026-09-12 | decline (plan §0 #9) | quality | — | — | Fired twice (`d9745552`, `0f70776a`); 34 → 39 members. Each column costs one interface line; the ~10 edits in 7 files per column come from the mapper chain (review #12), which dropping the shim does not fix. |
| 8 | `outboxAttempts` two facts (:96) | 2026-09-12 | step 9 | risk | touch | `data/local/dao/MessageDao.kt`, `data/outbox/OutboxSender.kt`, `data/worker/OutboxWorker.kt` | Fired twice (bumps 28→29, 30→31). Code: `MessageDao.kt:68`, `OutboxSender.kt:164–165`, `OutboxWorker.kt:56/74/125`. Risk: a run cancelled by a lost constraint spends an attempt. |
| 9 | Three per-key mutexes (:106) | 2026-09-12 | step 13 (lists); rewrite | quality | touch | `data/repository/ListRepositoryImpl.kt`, `data/crypto/SignalManager.kt` | `KeyedMutex` now also serves `StickerUploads.kt:32`. The `SignalManager` half stays. |
| 10 | `ChatCommand` `@Composable` in domain (:116) | 2026-06-12 | step 15 | quality | touch | `domain/command/ChatCommand.kt` | Fired 2026-07-19 (`a3669f54`, `.remind`) and 2026-07-27. It is the widget slot `ChatCommandWidget.Render` (`:16–24`), not an icon slot. |
| 11 | `CallNotificationManager` → `CallActivity` (:126) | 2026-06-12 | → VC 2; rewrite | quality | touch | `data/call/CallNotificationManager.kt` | Fired 2026-10-08 (`aec1caa4`). The import is needed only for two constants (`:134`); the intent already names the class as a string (`:179`). |
| 12 | `SpeechRecognizerManager` teardown (:136) | 2026-07-10 | step 15 | risk | touch | `data/util/SpeechRecognizerManager.kt` | Fired: `3b30b8f7` edited it with the suite green. The "Gradle cannot run" precondition no longer holds. |
| 13 | `MainActivity` no `onNewIntent` (:146) | 2026-07-10 | delete | — | — | — | Fixed in `239f0a9a` (2026-07-19), `MainActivity.kt:134–138`. See B22 for the related `CLEAR_TASK`. |
| 14 | Mime normalisation (:156) | 2026-09-07 | step 17; rewrite | risk | touch | `ui/chat/ChatScreen.kt`, `ui/chat/SendFileSheet.kt`, `data/share/ShareContentResolver.kt`, `data/outbox/OutboxFiles.kt` | Fired: the consumer changed in `d9745552` and `5a98ac49`. Producers are now `ChatViewModel.kt:236–238`, `OutboxFiles.kt:64/96`, `DocumentFiles.kt:201`, `SendFileSheet.kt:80`. |
| 15 | Preview pager↔zoom contract (:166) | 2026-09-07 | step 18 | quality | touch | `ui/chat/ImagePreviewScreen.kt`, `ui/chat/FullscreenImageViewer.kt` | Trigger met (five viewer hosts, three pager call sites). Four more fixes since: `534f7ad5`, `4c42e10f`, `9e8c4d0e`, `01c0289f`. |
| 16 | Filter chip starts the backfill (:176) | 2026-09-07 | step 12 | risk | touch | `ui/chat/ChatViewModel.kt` | `ChatViewModel.kt:403–409` → `MessageRepositoryImpl.kt:1594`. The backfill now also fetches GIFs, stickers and documents up to 100 MB that the photo grid never shows. |
| 17 | `ChatScreen` lacks `rememberImagePicker` (:186) | 2026-09-07 | delete (merged into #4) | — | — | — | No second multi-select screen exists. |
| 18 | Chat title copies (:196) | 2026-09-08 | step 11 | risk | touch | `ui/chatlist/ChatListItem.kt`, `ui/search/GlobalSearchLabels.kt`, `ui/lists/ListShareSheet.kt`, `ui/components/ChatTargets.kt` | Fired 2026-09-18. The blank-name hole (`ChatListItem.kt:66–70`) is reached only by a blank name written outside this client. Five copies: `GlobalSearchLabels.kt:32–42`, `ChatTargets.kt:41–44`, `ListShareSheet.kt:56–59`, `ChatInfoManager.kt:152`, `GroupSettingsScreen.kt:235`. |
| 19 | `getChats()` for read-only screens (:208) | 2026-09-08 | rewrite | quality | touch | `data/repository/ChatRepositoryImpl.kt` | Six consumer classes, seven call sites: `GlobalSearchViewModel:99`, `ListsViewModel:227`, `ListDetailViewModel:82`, `CallsViewModel:123/170`, `SharePickerViewModel:67`, `ChatInfoManager:240`. |
| 20 | Edited photos capped at 4096 px (:218) | 2026-09-09 | BACKLOG | quality | event | — | A product decision tied to `docs/plans/image-editor.md` §5. Move with #49. |
| 21 | Stale Roborazzi baselines (:228) | 2026-09-20 | step 5 | quality | touch | `app/src/test/snapshots` | Fired 2026-10-03. The test KDoc names pre-flavor task names. |
| 22 | `CallAudioRouter` monitor (:238) | 2026-09-20 | → VC 1; rewrite | quality | touch | `data/call/CallAudioRouter.kt` | Premise gone since `eeb706ae`: every router call runs on the main thread, so the lock guards nothing. Its KDoc (`:24–27`) is wrong. The fix is to drop the lock. VC step 1 still cites the removed `audioSessionLock`. |
| 23 | Voice player prepares on main (:248) | 2026-10-03 | step 15 | risk | touch | `ui/chat/VoiceMessagePlayer.kt` | `VoiceMessagePlayer.kt:85–89`. Goes with B19. |
| 24 | File-handling leftovers (:258) | 2026-10-03 | keep | quality | touch | `ui/chat/FileMessageBubble.kt`, `ui/chat/SendFileSheet.kt`, `data/outbox/OutboxFiles.kt` | Unchanged since `c9c26e43`. |
| 25 | Plan-runner leftovers (:272) | 2026-10-04 | keep | quality | touch | `scripts/run-plan.sh`, `scripts/plan-runner/lib.sh`, `scripts/plan-runner/report.sh` | Accurate. |
| 26 | Sticker 1.39.0 leftovers (:287) | 2026-10-04 | step 14 | risk | release, touch | `data/sticker` | "Before the next release" was missed four times: 1.39.1 (untagged), v1.40.0, v1.40.1, v1.40.2. All five items are still present (`StickerMaker.kt:57/70`, `StickerLibrarySync.kt:61–63`, `StickerRepositoryImpl.kt:234–235`, `:358–367` with `StickerDao.kt:167–174`, `LottieThumbnails`). |
| 27 | Calls — known problems (:302) | 2026-10-08 | rewrite, bullet by bullet | security | touch | `data/call/CallService.kt`, `data/repository/CallRepositoryImpl.kt`, `firestore.rules`, `functions/index.js` | Map each bullet: (a) `CallSession` → VC 1; (b) catch-alls → step 8; (c) `Connecting` with no timeout → unplanned; (d) rules → step 16 (and VC 6); (e) push TTL → VC 2, "New message" for group call logs → VC 7; (f) refused service start → unplanned (VC 8 copies it); (g) pocketbase call button → B24; (h) ring-timeout race → unplanned, S (`CallService.kt:307`, `:444–447`); (i) Recents → VC 4a covers only the docked case; (j) `CallState.Placing` → VC 2/4 fire it; (k) labels → decide in VC 4. |
| 28 | UI→data allowlist (Declined, :325) | 2026-06-12 | rewrite | quality | touch | `app/src/test/java/com/firestream/chat/architecture/ArchitectureTest.kt` | 26 classes in 23 files, not "24"/"25 in 19". |
| 29 | Split the repositories (Declined, :335) | 2026-04-11 | rewrite | quality | — | — | `ChatRepository` 28 methods; `MessageRepository` 35 plus `uploadProgress`. |
| 30–35 | The six PocketBase entries (:351–401) | 2026-04-28 | BACKLOG | quality | event | — | One BACKLOG item, "PocketBase flavor parity". `pocketbase/` is untouched since 2026-04-28. The `MessageSource` entry is stale: 17 throws, and its "snackbar" claim is false (see B24). |
| 36 | APK chunked downloads (:411) | 2026-04-30 | decline | quality | — | — | The entry argues against it already. |
| 37 | APK install when backgrounded (:421) | 2026-04-30 | delete | — | — | — | Fixed in `b98ef543` (2026-05-01). |
| 38 | Timer dual schedule (:431) | 2026-05-04 | delete | — | — | — | Fixed in `1a9c9360`, the day the entry was written. |
| 39 | Shared media omits video (:441) | 2026-07-18 | delete | — | — | — | `SharedMediaViewModel` was deleted in `b594d7c8`; the Videos chip shows videos. |
| 40 | No image thumbnails (:451) | 2026-07-22 | BACKLOG | quality | event | — | A feature. `MediaAttachment` is unused (B21). |
| 41 | "Other participant" copies (:461) | 2026-07-25 | step 11 | risk | touch | twelve sites (Notes) | Twelve inline copies: `ChatListViewModel:113`, `ChatListItem:66`, `ArchivedChatsScreen:93`, `ChatListScreen:93,:344`, `CallsViewModel:102`, `GlobalSearchLabels:20`, `ChatUtils:136`, `ListsViewModel:231`, `ListDetailViewModel:86`, `ListShareSheet:57,:91`. Becomes a bug at VC step 7: "call back" from a group call log rings an arbitrary member. |
| 42 | Unsent row keeps a wrong target (:473) | 2026-10-03 | delete | — | — | — | Moot: the destructive bumps in v1.37.0 and v1.38.0 wiped every such row. |
| 43 | Unknown chat type stored as 1:1 (:483) | 2026-10-03 | keep | risk | event | `data/local/entity/ChatEntity.kt` | Accurate. |
| 44 | Failed timer shows retry (:493) | 2026-10-03 | step 15 | bug | touch | `ui/chat/ChatScreen.kt` | `ChatScreen.kt:1540`, refused at `MessageRepositoryImpl.kt:791`. The KDoc type list (`MessageRepository.kt:61–62`) also covers VIDEO, STICKER and GIF. |
| 45 | Broadcast recipients from the screen (:503) | 2026-10-03 | rewrite | risk | touch | `data/repository/MessageRepositoryImpl.kt`, `ui/chat/ChatInfoManager.kt` | Fan-out failures are logged and dropped (`MessageRepositoryImpl.kt:1093–1096`) while the bubble shows SENT. Its trigger names the chat-partner plan (step 11); re-trigger it to "a second caller, or E2E on by default". |
| 46 | Link previews in RAM only (:513) | 2026-09-08 | keep | quality | event | `data/remote/LinkPreviewSource.kt` | A second in-memory copy at `GlobalSearchViewModel.kt:47`. |
| 47 | `SingleFlight` not adopted (:523) | 2026-09-08 | step 12 | risk | touch | `data/util/MediaFileManager.kt`, `data/util/ProfileImageManager.kt` | Fired at every touch (five and two). Both copies lack the waiter-cancellation check `docs/GOTCHAS.md` requires. `StickerDownloads.kt:50` already uses `SingleFlight`. |
| 48 | Three backfill builders (:533) | 2026-09-12 | step 12 | quality | touch | `FireStreamApp.kt`, `ui/settings/SettingsViewModel.kt`, `data/worker/MediaBackfillScheduler.kt` | Fired (`SettingsViewModel` 09-18, 10-03; `FireStreamApp` 10-04, 10-07). |
| 49 | Resize presets capped at 1600 (:543) | 2026-09-10 | BACKLOG | quality | event | — | Move with #20. Paths moved to `OutboxSender.kt:363/:406`. |
| 50 | `google-services` on pocketbase (:572) | 2026-09-09 | delete | — | — | — | The premise is wrong: the pocketbase flavor uses Firebase Auth and FCM (`AppModule.kt:147,151`, `PocketBaseAuthSource.kt:55–57`). The proposed fix would crash it. Correct the matching comment in `.claude/hooks/session-start.sh:34–36` (step 3). |
| 51 | Overlay `Path` per frame (:595) | 2026-09-10 | keep | quality | event | `ui/chat/imageedit/OverlayPainter.kt` | Accurate. |
| 52 | Sticker files never deleted (:620) | 2026-10-04 | keep | risk | event | `data/sticker` | Accurate. |
| 53 | Sticker object vs its name (:642) | 2026-10-04 | rewrite | security | event | `data/sticker` | Half the trigger is void: `6d07c460` removed Cloud Functions from the sticker plan. |
| 54 | `StickerDao` public writes (:664) | 2026-10-04 | rewrite | quality | touch | `data/local/dao/StickerDao.kt` | Also `deleteItemsOf` and `deletePackRow`. |
| 55 | Sticker backup limits (:685) | 2026-10-04 | keep | risk | event | `data/sticker` | Sign-out drops unsynced packs. |
| 56 | Pack id can be claimed (:718) | 2026-10-04 | keep | security | event | `firestore.rules` | Step 16's emulator harness makes its rule testable. |
| 57 | `ListRepositoryImplRaceTest` flakes (:747) | 2026-10-04 | step 13 | quality | touch | `data/repository/ListRepositoryImpl.kt` | Cause confirmed: `ListRepositoryImpl.kt:64`'s own scope runs on a real thread; the test does not pin it, the tripwire test does (`:131`). |

## §B — New entries found by the audit

Step 1 adds each one that is still open when it runs. An entry a later step fixes is still recorded:
the register shows what is true until the fix lands.

| # | Entry | Severity | Fixed by | Evidence |
|---|---|---|---|---|
| B1 | The users update rule admits requests with no signed-in user | security | by hand (plan "Do now"), else step 16 | Security table above. Record it only if it is still open. |
| B2 | Any participant may rewrite any chat field and any message | security | step 16 | `firestore.rules:42–45, 54–55` |
| B3 | Invite links: open create/delete; join looks refused | security | step 16 | `firestore.rules:122–126`; `ChatRepositoryImpl.kt:227/234` |
| B4 | Call documents and ICE candidates are never deleted | security | keep (owner: a Firestore TTL policy) | No delete path. Candidates carry IP addresses. |
| B5 | Unread counts rise before the block check | bug | step 16 | `functions/index.js:246–267` |
| B6 | Storage and Realtime Database rules live only in the console | security | by hand, then step 16 | `firebase.json` |
| B7 | Every `AppDatabase` bump wipes local-only state | risk | steps 7 and 9 | `DatabaseModule.kt:35`. Unsent messages, stars, reminders, local copies, the sticker library. |
| B8 | `resultOf` and 75 raw catch-alls swallow cancellation | risk | step 8 | Measurements. Examples: `ChatRepositoryImpl.kt:68` (an outdated sync pass runs on), `CallRepositoryImpl.kt:44–143` (a cancelled lookup ends the call as an error), `MediaBackfillWorker.kt:75` with `MediaFileManager.kt:120–122` (a waiter rethrows another caller's cancellation), `PocketBaseChatSource.kt:100/115` (cancellation becomes "Chat not found"). |
| B9 | `MessageBubble` is at 251 of 255 dex registers | risk | step 10 | Measurements. The next change to it can crash every debug build on chat open. |
| B10 | The composable parameter check misreads `->` | risk | step 5 | Gates section. |
| B11 | Lint crashes and gates nothing | quality | step 4 | Measurements. |
| B12 | Three receive paths can decrypt the same message | risk | step 19 | `MessageRepositoryImpl.kt:467` and `:1365`. Recorded only in `offline-outbox.md:551–555` and `BACKLOG.md:429–431`. Blocks E2E on by default. |
| B13 | Two list writes merge without the per-list lock | risk | step 13 | `ListRepositoryImpl.kt:476–485` (`fetchAndCacheList`), `:417–418` (`getSharedListsForChat`). The class of `eed7519`. |
| B14 | The list sync loop keeps the old user after sign-out | risk | step 13 | `ListRepositoryImpl.kt:86–113`, `AuthRepositoryImpl.kt:163–171` |
| B15 | An avatar download writes in place | risk | step 12 | `ProfileImageManager.kt:53–62`. A truncated file passes `fileExists()` (`ChatRepositoryImpl.kt:61–63`). |
| B16 | Chat-list start and refresh re-read every chat's full history | quality | BACKLOG (with #2) | `ChatListViewModel.kt:99–103/240`, `FirestoreMessageSource.kt:167–172` |
| B17 | `MediaBackfillWorker` swallows cancellation and gates stickers | quality | step 12 | `MediaBackfillWorker.kt:75`; the repository exempts stickers (`MessageRepositoryImpl.kt:150`). `isOnWifi` is copied twice (`:1688`, worker `:82`). |
| B18 | Gallery asks for media permission before the photo picker | bug | step 15 | `ChatScreen.kt:2216–2229, :981`; `AndroidManifest.xml:13–14`. The picker needs no permission and the answer is ignored. |
| B19 | Your own queued voice note does not play | bug | step 15 | `MessageBubble.kt:1243–1246` passes only `mediaUrl`; `VoiceMessagePlayer.kt:79`. Whether voice notes auto-download (`MessageRepositoryImpl.kt:140–142`) is a product question for BACKLOG. |
| B20 | The slice fence checks names, not writes | quality | keep (with review #6) | `ArchitectureTest.kt:135–141, :312`. `ChatPollManager` writes `session` three times. |
| B21 | Dead code | quality | step 6 | `CommandChip` (`CommandChip.kt:22`), `TypingRow` (`TypingRow.kt:38`), five pass-throughs (`ChatViewModel.kt:701–705`), `MediaAttachment`, `ContactDao.deleteAll`, `UserDao.deleteUser`, `ListDao.updateItems`, `ListDao.syncForUser`, `PocketBaseRealtime.unsubscribe`, the dead arm at `OutboxScheduler.kt:110` with its test, the catalog entry `compose-ui-test` (`gradle/libs.versions.toml:79`). |
| B22 | Message-notification taps clear the task | quality | keep (VC 4a must not copy it) | `FCMService.kt:253`; the comment at `MainActivity.kt:129–133` claims `SINGLE_TOP`. |
| B23 | `.remind` downcasts its widget | quality | step 15 | `ChatScreen.kt:1911–1938` |
| B24 | The pocketbase flavor crashes on unimplemented paths | bug (that flavor) | keep | `NotImplementedError` is an `Error`, so it escapes `resultOf` (`ResultExt.kt:21`), e.g. on edit (`ChatMessageActions.kt:58–61`). The call button throws on `appScope`, which has no handler (`CallViewModel.kt:69`, `CoroutineScopeModule.kt:25`). |
| B25 | Risky classes with no tests | risk | keep (rule: the first change adds the first test) | `FCMService` (279 lines), `FirestoreListSource` (393), `FirestoreCallSource` (150), `ReminderAlarmScheduler` (153), `ApkDownloadWorker` (201), `WebPagePreviewCapture` (517); `CallService` (782) is entry #27. |
| B26 | The `recipientId` rule passes `recipientIds` | quality | step 6 | Gates section. |
| B27 | A stale size ceiling in an AGENT-NOTE | quality | step 6 | Gates section. |
| B28 | The composer's text state is written at nine sites, and again in the caption bar | quality | step 20 | Review #4. Seven locals at `ChatScreen.kt:254–285`; `messageText` written at nine sites, three of them identical clear blocks (`:1697`, `:1905`, `:1983`); `CaptionBar` at `ImagePreviewScreen.kt:682–800`. |
| B29 | The image editors reach the rasterizer through seven pass-throughs | quality | keep (event: the next host that needs the editors outside the chat) | Review #5. `ChatViewModel.kt:453–519`, built at `ChatScreen.kt:2600`. Stickers step 8 could not mount Draw or Overlay in the sticker maker for this reason (`stickers-and-gifs.md:645, :685`). |
| B30 | The repository still sequences five outbox collaborators per send | quality | keep (touch: `MessageRepositoryImpl.kt` `queueSend`/`enqueueSend`) | Review #8, still valid: `queueSend` (`:326`), `enqueueSend` (`:375`); `OutboxSender` grew from 418 to 498 lines; 14 of 20 repository test classes verify collaborators (160 calls). |
| B31 | The send and action managers are pass-throughs around one template | quality | keep (needs a decision on the slice-ownership pattern) | Review #6. `ChatMessageSender` (240 lines) and `ChatMessageActions` (217) are not on the slice-fence roster; `ChatViewModel` has 87 functions, about 56 of them forwards; the isSending template appears 8 times in 4 files. |
