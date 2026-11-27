# Reliable Background Backups, Wi-Fi Enforcement, and Resumability

Status: approved by the owner on 2026-11-27.

## Problem

1. **Manual "Back up now" bypasses WorkManager and has no foreground service.** `BackupViewModel.backupNow()` runs `BackupManager.runBackup()` directly in `appScope.launch`, relying on `view.keepScreenOn = true` while `BackupScreen` stays open. Switching apps or locking the screen lets Android Doze/throttle or kill the process, and leaving Wi-Fi silently fails over to mobile data despite the advisory UI text.
2. **Interruptions lose all legacy-media upload progress.** `manifest.json` is written only at Stage 7. If a backup is stopped mid-Stage 6 (Wi-Fi loss, WorkManager 10-minute background cap, process kill), the next run re-encrypts and re-uploads every legacy media blob from scratch.
3. **Cancellation leaks network traffic.** `FirebaseBackupSource.uploadLegacyMediaBlob`, `downloadLegacyMediaBlob`, and `uploadManifest` call `.await()` instead of hooking `invokeOnCancellation { task.cancel() }`.
4. **First backups are slow.** Stage 2 (legacy downloads) and Stage 6 (legacy encrypt + upload) run sequentially 1-by-1.
5. **No Cancel control.** Users cannot cancel an active or waiting backup from `BackupScreen` or the notification.

## Decisions

Agreed with the owner on 2026-11-27:

- **Network policy:** Wi-Fi (`NetworkType.UNMETERED`) by default for both manual and scheduled backups, with a user-facing **"Back up over mobile data"** toggle (default off) in *Settings → Chat Backup*.
- **Notifications:** Promote both manual and scheduled `BackupWorker` runs to a `dataSync` foreground service with a live progress notification (degrading gracefully via `tryPromoteForeground` if Android 12+ refuses background FGS start on a scheduled run).

## Design

### 1. DataStore preferences & resumable upload ledger (`PreferencesDataStore.kt`)
- Add `backupOverMobileDataFlow` / `setBackupOverMobileData(allowed: Boolean)` (default `false`).
- Add a local uploaded legacy-media ledger keyed by `keyGen`:
  - `backup_pending_legacy_key_gen: String`
  - `backup_pending_legacy_ids: Set<String>`
- `recordPendingUploadedLegacyId(keyGen, messageId)` appends atomically under DataStore's edit lock (resetting the set if `keyGen` changes on passphrase rotation).
- Cleared on successful `manifest.json` commit (inside `NonCancellable`) and in `clearBackupPreferences()` on sign-out. No Room schema migration required.

### 2. Cancellable Firebase Storage transfers (`FirebaseBackupSource.kt`)
- Extract a shared `awaitCancellable(task, cancelMessage)` helper built on `suspendCancellableCoroutine` with `cont.invokeOnCancellation { task.cancel() }` and use it for `uploadManifest`, `uploadLegacyMediaBlob`, and `downloadLegacyMediaBlob` (matching `uploadDbBlob` / `downloadDbBlob`).

### 3. Reordered, parallel, resumable `BackupManager.kt`
- **Sweep stale temp files** (`backup_snapshot_*`, `restore_db_*`, `legacy_enc_*`, `rekey_*`) at backup startup inside `operationMutex`.
- **Upload legacy media *before* DB snapshot & upload:**
  1. `SYNCING_CHATS`
  2. `DOWNLOADING_LEGACY_MEDIA` — bounded `Semaphore(3)` concurrency
  3. `UPLOADING_LEGACY_MEDIA` — merges `previousManifest.legacyMediaIds` with the local `keyGen` ledger; bounded `Semaphore(3)` concurrency; persists each uploaded `messageId` into the ledger inside `NonCancellable`
  4. `SNAPSHOTTING_DB` → `ENCRYPTING_DB` → `UPLOADING_DB` → `FINALIZING` (`manifest.json` commit + clear ledger)
  - Moving legacy upload before DB upload eliminates orphan `db_*.enc` blobs when paused mid-media-upload and avoids re-snapshotting/re-uploading the DB on every Wi-Fi pause/resume.
- **In-app cancellation without breaking periodic schedule:** Track the active `runBackup()` child job so `cancelBackup()` cancels both queued/running manual WorkManager work *and* any currently executing scheduled run (letting `BackupWorker` detect user-initiated cancellation and return `Result.success()` to keep the Daily/Weekly schedule intact).
- Expose `enqueueManualBackup()`, `cancelBackup()`, `setBackupOverMobileData(allowed)`, and `manualBackupWorkState: Flow<ManualBackupWorkState>` on `BackupRepository` (keeping `ui/` free of `data/worker/` imports per Konsist `ArchitectureTest`).

### 4. Unified WorkManager scheduling & foreground notifications (`BackupScheduler.kt`, `BackupWorker.kt`)
- **Two unique work names:**
  - `encrypted_chat_backup` — periodic (`Daily` / `Weekly`), requires charging + `UNMETERED` (or `CONNECTED` if `allowMobileData`).
  - `encrypted_chat_backup_manual` — one-time manual run, no charging requirement, `UNMETERED` (or `CONNECTED` if `allowMobileData`).
- **Live constraint updates on toggle:**
  - Turning *"Back up over mobile data"* **on** while manual work is `ENQUEUED`/`BLOCKED` replaces it with `NetworkType.CONNECTED` so it starts immediately.
  - Turning it **off** while manual work is `ENQUEUED`/`BLOCKED`/`RUNNING` replaces it with `NetworkType.UNMETERED` so cellular uploads pause immediately and wait for Wi-Fi.
  - Also updates the periodic schedule (when `frequency != OFF`) with the new network constraint.
- **Foreground service + progress notifications (`BackupWorker`):**
  - Uses `WorkerForeground.kt` (`tryPromoteForeground` + `dataSyncForegroundInfo` + `ensureLowImportanceChannel` on channel `fire_stream_backup`).
  - Collects `backupRepository.backupProgress` alongside `runBackup()` and updates the notification with `NotificationManager.notify` (avoiding repeated `setForeground` calls from background on Android 12+).
  - Manual runs include a **Cancel** notification action (`WorkManager.createCancelPendingIntent(id)`); scheduled runs omit it so tapping notification Cancel never unschedules Daily/Weekly backups.
  - Rethrows `CancellationException` when stopped by WorkManager (Wi-Fi loss / 10-min cap) so WorkManager automatically re-queues; returns `Result.failure()` on manual errors (immediate UI error card) and retries up to 3× on scheduled transient failures.

### 5. UI updates (`BackupViewModel.kt`, `BackupScreen.kt`)
- Remove `view.keepScreenOn` and the *"Keep FireStream open on Wi-Fi"* warning.
- Route `backupNow()` through `backupRepository.enqueueManualBackup()`.
- Render a **Waiting for Wi-Fi…** (or *Waiting for internet connection…*) card with a **Cancel** button whenever manual work is queued on network constraints.
- Add a **Cancel** button to `BackupProgressCard`.
- Add the **"Back up over mobile data"** switch row (enabled even while running/waiting so users can unblock a paused backup immediately).
- Update automatic backup summary labels to reflect Wi-Fi vs. Wi-Fi or mobile data.

## Verification

- Unit tests: `BackupManagerTest` (resumable ledger across cancellation, keyGen rotation isolation, bounded concurrency, stale temp sweep, `cancelBackup`), `BackupSchedulerTest` (manual enqueue, mobile-data constraints, live constraint update, cancelManual), `BackupWorkerTest` (manual vs. scheduled retry policy, foreground promotion fallback, cancellation semantics), `BackupViewModelTest` (enqueue, waiting state, cancel, mobile-data toggle, completion snackbar).
- Robolectric Compose UI tests: `BackupScreensUiTest` (waiting-for-Wi-Fi card, cancel button, mobile-data toggle, updated schedule copy).
- Gate: `./gradlew :app:testFirebaseDebugUnitTest assembleFirebaseDebug` + `./gradlew test`.
