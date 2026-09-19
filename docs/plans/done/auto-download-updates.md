# Auto-download app updates over Wi-Fi

## Context

FireStream ships via sideloaded APKs on GitHub Releases, consumed by an in-app
updater. Today the pipeline is **notify-only**: a periodic `UpdateCheckWorker`
(24h, already `NetworkType.UNMETERED`) finds a newer release and posts an
"update available" notification; the user must open Settings → *Check for
updates* and tap **Download**. The actual download (`ApkDownloadWorker`,
foreground DATA_SYNC, resumable, SHA-256-verified) and the tap-to-install
notification already exist.

**Goal:** when the user has opted in and the device is on an unmetered
(Wi-Fi) network, the newer APK downloads **automatically** in the background, so
it's already on-device when they choose to install. We are extending the
existing pipeline at two seams — we are **not** rebuilding download/verify/install.

**Scope note (Android constraint):** this is auto-**download**, not
auto-**install**. Android cannot silently install a sideloaded APK — the system
installer always prompts, and background activity-start rules forbid launching
it from a worker. So the flow ends at "downloaded → *Update ready, tap to
install* notification → user taps → system installer" (all existing machinery).

**Decisions (confirmed with user):**
- Preference **defaults OFF** (opt-in). Existing users keep today's behavior.
- Gate on **`NetworkType.UNMETERED`** (matches `scheduleUpdateCheck`; excludes
  metered Wi-Fi hotspots).
- When auto-download is on, **suppress the "update available" notification** and
  only surface the single "ready to install" notification after the download.

## Key existing code (reuse — do not reinvent)

- `data/local/PreferencesDataStore.kt` — boolean-preference pattern (key + Flow +
  setter). Mirror `sendImagesFullQuality` (lines ~53, 187–193).
- `data/repository/AppUpdateRepositoryImpl.kt` `downloadUpdate()` (lines 60–87) —
  enqueues `ApkDownloadWorker` with `NetworkType.CONNECTED`. Interface in
  `domain/repository/AppUpdateRepository.kt`.
- `data/worker/UpdateCheckWorker.kt` — the notify-only worker; `doWork()` (34–42)
  is the trigger seam. `@HiltWorker` + `@AssistedInject`.
- `ui/settings/SettingsViewModel.kt` — `observePreferences()` collector pattern +
  `viewModelScope` setters; `SettingsUiState` (add a field).
- `ui/settings/SettingsScreen.kt` — `SettingsToggleItem` (lines 831–848); Help
  section hosts the App Version + `UpdateRow`.
- `FireStreamApp.kt` `scheduleUpdateCheck()` — reference for the `UNMETERED`
  constraint idiom.

## Steps

### Step 1 — Add the `autoDownloadUpdates` boolean preference
`data/local/PreferencesDataStore.kt`. Add `booleanPreferencesKey("auto_download_updates")`,
`autoDownloadUpdatesFlow: Flow<Boolean>` (default **`false`**), and
`suspend fun setAutoDownloadUpdates(enabled: Boolean)`. Copy the
`sendImagesFullQuality` triple verbatim.

### Step 2 — Parameterize the download constraint
`domain/repository/AppUpdateRepository.kt` + `AppUpdateRepositoryImpl.kt`.
Change `downloadUpdate(update)` → `downloadUpdate(update, unmeteredOnly: Boolean = false)`.
In the impl, select `NetworkType.UNMETERED` when `unmeteredOnly` else
`NetworkType.CONNECTED` (rest of the request unchanged). Default keeps the
manual Settings path (`SettingsViewModel.downloadAndInstall`) on `CONNECTED` —
no caller change needed there.

### Step 3 — Auto-trigger the download from the check worker
`data/worker/UpdateCheckWorker.kt`. Inject `PreferencesDataStore`. In `doWork()`,
on `UpdateCheckResult.Available`:
- if `autoDownloadUpdatesFlow.first()` is **true** → call
  `appUpdateRepository.downloadUpdate(outcome.update, unmeteredOnly = true)`
  (enqueue only; ignore the returned Flow — `ApkDownloadWorker` runs
  independently and posts its own progress + "ready to install" notification).
  **Do not** post the "available" notification (decision 3).
- else → `postNotification(...)` (unchanged).
Return `Result.success()`. Note the download work carries its own `UNMETERED`
constraint, so if Wi-Fi drops before it starts, it defers rather than falling to
cellular.

### Step 4 — Settings toggle + ViewModel wiring
`SettingsViewModel.kt`: add `autoDownloadUpdates: Boolean = false` to
`SettingsUiState`; add a collector in `observePreferences()`; add
`fun setAutoDownloadUpdates(enabled: Boolean)` (`viewModelScope.launch` — matches
every existing Settings setter; the user is on-screen when they flip it).
`SettingsScreen.kt`: render a `SettingsToggleItem` in the **Help** section beside
the update row — title "Auto-download updates on Wi-Fi", subtitle e.g.
"Download new versions automatically when connected to Wi-Fi", bound to
`state.autoDownloadUpdates` / `viewModel::setAutoDownloadUpdates`.

### Step 5 — Tests
- `SettingsViewModelTest`: `setAutoDownloadUpdates(true/false)` writes the pref and
  the collector reflects it in `uiState` (established fake-pref pattern).
- `AppUpdateRepositoryImplTest`: if the existing test can assert the enqueued
  request's constraint, add a case that `unmeteredOnly = true` yields
  `NetworkType.UNMETERED` (and default yields `CONNECTED`). If WorkManager isn't
  test-initialized there, extract the `NetworkType` choice into a tiny pure
  helper and unit-test that instead.
- The worker→download wiring is verified end-to-end via the fake-old-version flow
  (below); it's thin conditional wiring and the `Available` notification path
  touches Android framework APIs (not JVM-unit-testable without Robolectric,
  which this repo doesn't use).

### Step 6 — Docs & versioning
- `CHANGELOG.md`: `Added` entry under the today-dated section (this is a `feat` →
  **minor** bump per CLAUDE.md), bold lead + commit hash.
- `docs/RELEASING.md`: one line noting updates now auto-download on Wi-Fi when the
  Settings toggle is enabled.
- `MEMORY.md`: record the new preference + the two extension seams.

**Order: 1 → 2 → 3+4 → 5 → 6** (3 and 4 both depend only on 1–2 and are
independent of each other).

## Model / effort per step

| Step | Model | Effort | Rationale |
|------|-------|--------|-----------|
| 1 Preference | Sonnet 4.6 | Low | Single-file, copy an existing boolean-pref triple. |
| 2 Constraint param | Sonnet 4.6 | Medium | Interface + impl; small but crosses the API boundary. |
| 3 Worker trigger | Opus 4.8 | Medium | Core new logic; WorkManager + conditional enqueue, the security-adjacent update-delivery path. |
| 4 Settings UI + VM | Sonnet 4.6 | Medium | Follows the established toggle/collector pattern across VM + Compose. |
| 5 Tests | Sonnet 4.6 | Medium | Extends existing `SettingsViewModelTest` / repo test patterns. |
| 6 Docs | Sonnet 4.6 | Low | CHANGELOG + docs + memory. |

## Edge cases / notes

- **KEEP vs manual override:** if an auto-download is enqueued but deferred
  (Wi-Fi dropped) and the user then taps *Download* in Settings, `downloadUpdate`
  uses `ExistingWorkPolicy.KEEP`, so the pending `UNMETERED` job wins and the
  manual `CONNECTED` request is dropped — i.e. the manual tap won't force it onto
  cellular until Wi-Fi returns. Accepted (avoids restart-from-byte-0; document as
  a minor known limitation, don't switch to REPLACE).
- **Storage:** APKs land in `cacheDir/apk_updates/`; the OS can evict them and
  `ApkDownloader` already wipes stale-version files — no new cleanup needed.
- **No new permissions.** `INTERNET`, `POST_NOTIFICATIONS`, `REQUEST_INSTALL_PACKAGES`,
  the FGS entries, and the `SystemForegroundService` `dataSync` manifest override
  all already exist.
- **Room:** no schema change → no `AppDatabase` version bump.

## Verification

1. `./gradlew test` (esp. `:app:testFirebaseDebugUnitTest`) — green.
2. `./gradlew assembleDebug` — clean.
3. **End-to-end (fake old version)** per `docs/RELEASING.md`: build/install an APK
   with `-PversionCodeOverride=<older> -PversionNameOverride=<older>` so a real
   published release looks newer.
   - In Settings, enable **Auto-download updates on Wi-Fi**; confirm the toggle
     persists across app restart (DataStore).
   - On Wi-Fi, trigger `UpdateCheckWorker` immediately instead of waiting 24h:
     `adb shell cmd jobscheduler run -f com.firestream.chat <jobId>` or temporarily
     enqueue a one-time `UpdateCheckWorker` in a debug hook. Observe the
     `ApkDownloadWorker` foreground download notification appear **without** first
     tapping Download, and no "update available" notification.
   - On completion: a single "Update ready — tap to install" notification; tapping
     opens the system installer.
   - Toggle **off** + re-run: only the "update available" notification, no
     background download (today's behavior preserved).
   - Metered check: connect to a metered hotspot with the toggle on → the download
     stays deferred (UNMETERED constraint).
