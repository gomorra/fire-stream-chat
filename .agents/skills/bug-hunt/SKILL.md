---
name: bug-hunt
description: "Correctness-focused audit for high-risk diffs: crypto/Signal ratchets, concurrency/locks, sync paths, Firestore/Storage security rules, Room migrations, multi-process Android lifecycle, and destructive scripts. Use on risky steps before committing or before cutting a release."
---

Perform a **relentless** correctness audit of a diff (default: `git diff` / staged + unstaged, or a commit range the user names). Hunt real runtime bugs, data loss, security holes, and crashes — leave style and formatting to `code-review` and tooling.

## Process

1. **Pin the diff:** read the full diff (`git diff`, `git diff --cached`, or `git diff <base>...HEAD`) and every modified file's surrounding invariants (`// region: AGENT-NOTE`, callers, and tests).
2. **Audit every applicable risk area below** (skip areas the diff does not touch):
   - **Crypto & privacy:** ratchet races, lock ordering, partial-failure state leaves, nonce/AAD reuse, plaintext metadata leaks to Firestore/Storage/logs/notifications, raw `.enc` URLs reaching Coil/MediaPlayer/downloaders without decryption.
   - **Concurrency & coroutines:** missing per-key locks across parallel entry points (listener + sync + push), `catch (e: Exception)` swallowing `CancellationException`, missing `withContext(NonCancellable)` on critical persist/cleanup steps, `@ApplicationScope` vs `viewModelScope` disposal races.
   - **Offline & network resilience:** Firestore/RTDB writes awaited before optimistic local inserts, missing ack timeouts on `set().await()` / `waitForPendingWrites()`, long-running upload/download loops lacking per-item retry on transient network drops.
   - **Room & migrations:** schema change without version bump + `Migration(n, n + 1)` + exported JSON + `AppDatabaseMigrationTest`; `MessageRecord` upsert clobbering local-only columns; WAL snapshot vs raw file copy.
   - **Android multi-process & lifecycle:** any `android:process=":..."` component instantiating `FireStreamApp` where `Hilt_FireStreamApp` injects fields eagerly before `onCreate()` can return early (missing `FirebaseInitProvider` in non-main processes); `BroadcastReceiver` / `FCMService` wakelock ending before async work completes; foreground service manifest types.
   - **AOSP vs JVM test gaps:** `org.json.JSONObject.optString` returning literal `"null"` on device while JVM tests mock/mask it; Robolectric passing while ART dex verifier (`VerifyError` register pressure) or real `Parcelable`/intent flags fail; `canRead()` vs `exists()` on shared storage.
   - **Security rules & destructive scripts:** Firestore/Storage rule bypasses (list vs get, missing ownership/participant checks, unbounded sizes/fields, missing emulator tests); host scripts mutating one backend (e.g. Firestore) before preflight-checking permissions on all targets (e.g. Storage delete).
3. **Verify each suspected bug against the code** before reporting it — cite exact `file:line`, trigger sequence, and concrete impact. Drop speculation.

## Output

Report findings ordered by severity (**Critical**, **High**, **Medium**), each with:
- **Location:** `path:line`
- **Failure scenario:** exact sequence that triggers the bug
- **Fix:** minimal concrete change + regression test to add

If no real correctness bugs are found, say so explicitly and list the risk areas verified. Keep the report under 600 words.
