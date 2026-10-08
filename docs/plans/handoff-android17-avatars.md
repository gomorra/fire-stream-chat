# Handover: avatars don't load on Android 17

Status: worked around on the owner's phone, root cause not fixed in code. Written 2026-10-07 after two fixes that did not help. Read this before touching avatar code.

## Result: clearing the app's cache fixed it

On 2026-10-08 the owner cleared the app's cache (Settings → Apps → FireStream → Storage → Clear cache, not storage) on the Android 17 phone on v1.40.1. Every avatar loaded again. No logcat was captured before the clear, so the failing exception is unknown.

What this establishes:

- **The fault lived in `cacheDir`.** That folder holds Coil's disk cache (`image_cache`, keyed by the avatar URL) and OkHttp's `http_cache`. v1.40.1 deleted and re-downloaded the avatar files in `filesDir` and did not help, so the files there were not the fault.
- **Hypotheses A, B, C and D are ruled out as the standing cause.** The same avatar files, the same URLs and the same request load fine once the cache is empty.
- **The app never heals a bad cache entry by itself.** `FireStreamApp.newImageLoader` sets `respectCacheHeaders(false)`, so Coil serves a cached URL entry forever and never revalidates it. `AvatarImage` falls back from the file to the URL, and the URL load reads the same entry again. Other users with a bad entry stay stuck until they clear the cache.
- **What wrote the bad entry is unknown.** It may date from before the Android 17 update. No phone evidence exists for it.
- **The v1.39.1 and v1.40.1 explanations are wrong.** The CHANGELOG entries for both and the `ScaledImageDecoder` gotcha in `docs/GOTCHAS.md` blame the decoder and the `Android/media` folder. The code changes themselves are sound and stay.

Proposed follow-up, not built: when an avatar's URL load fails, retry it once with the disk cache read disabled (`diskCachePolicy(CachePolicy.WRITE_ONLY)`), so a fresh download replaces the bad entry. Regression seam: `avatarSourceAfterError` in `UserAvatar.kt` and `AvatarSourceTest`.

## Symptom

- On the owner's Android 17 phone, no avatar loads anywhere in the app: chat list, chat header, profile, group settings, calls, contacts.
- On Android 16 every avatar loads. Treat the account and server data as the same.
- Up to v1.39.0 each avatar was a **black circle**. From v1.39.1 on it is the **placeholder icon** where `UserAvatar` is used.
- The phone runs **v1.40.1**, delivered by the in-app updater, and avatars still do not show. Both fixes below are on the phone and neither helped.
- Message images load on both Android 16 and Android 17 (confirmed by the owner). So the shared image path works on Android 17: the Coil `ImageLoader`, the `OkHttpClient` and the default decoder. What fails is specific to avatars, either their files or their request.

## What was tried

### First fix, v1.39.1 (`226004e`)

The hypothesis was a black bitmap from a heavily subsampled `BitmapFactory` decode of full camera originals, the same pathology as the Shared Media grid (`ScaledImageDecoder` KDoc).

- `buildAvatarRequest` (`ui/components/UserAvatar.kt`) attaches `ScaledImageDecoder.Factory()`: `ImageDecoder` with a target size and a software bitmap.
- `resolveAvatarModel` uses the local file only if `isFile && canRead()`.
- `UserAvatar` shows its placeholder icon on error.
- `ProfileImageManager.saveLocalCopy` scales a new avatar to at most 1024 px before upload. Before this, avatars were uploaded as raw originals.

Result: black circle became placeholder icon. So the black circle was an **empty image**, not a black bitmap, and the load fails with both decoders.

### Second fix, v1.40.1 (`14de8fe`)

The hypothesis was that reading the cached file fails. The cache lived in `externalMediaDirs` (`Android/media/com.firestream.chat/profile_pictures/`), which goes through FUSE. There a file can pass `exists()` and `canRead()` and still fail to open, so the URL fallback never ran. That matches the earlier `EACCES` bug on `Pictures/` (CHANGELOG, "Older images now open fullscreen").

- The cache moved to `filesDir/profile_pictures`. `ProfileImageManager.deleteLegacyExternalCache()` deletes the old folder at app start (`FireStreamApp.onCreate`). The repositories download each avatar again because `fileExists` only looks in the new folder.
- `AvatarImage` (`UserAvatar.kt`) tries the local file, then the URL, then shows the placeholder. It logs each failure with its throwable under the logcat tag **`AvatarImage`**. `UserAvatar`, `ProfileScreen`, `GroupSettingsScreen` and `CallScreen` all use it.
- An unrelated bug was fixed too: `MediaFileManager.migrateOldStorage` moved the avatar cache into the public Pictures folder on every backfill run. It now skips `profile_pictures`.

Result: on v1.40.1 avatars still do not show. So either the URL load fails as well, or the file opens fine and the decode fails. The logcat (step 1 below) tells which.

## Facts from the code

These hold on v1.40.1 and decide how to read the phone results.

- **Release builds keep their log calls.** `app/proguard-rules.pro` has no `-assumenosideeffects` rule for `android.util.Log`, so the updater build logs under `AvatarImage`. R8 renames Coil's classes in stack traces, but platform exceptions and messages stay readable.
- **The placeholder icon means one of two things.** `AvatarImage` shows it after a failed load, which always logs an `AvatarImage` line. It also shows it when `avatarUrl` and `localAvatarPath` are both null, which logs nothing. A placeholder with no log line means no avatar data reached the UI.
- **The fullscreen viewer logs too.** `FullscreenImageViewer` logs a failed load under the tag `FullscreenImageViewer`. It shows "Failed to load" after a load error and "No image data" when it has no model. It uses Coil's default decoder and its own memory cache key.
- **Received message images use the same download path as avatars.** `MediaFileManager.fetch` and `ProfileImageManager.downloadAvatar` both make a plain GET through the same `OkHttpClient`, and both buckets are Firebase Storage. So if images received from someone else load on Android 17, fetching from Firebase Storage works on that phone.
- **The avatar request before v1.39.1 used Coil's default decoder** and still failed (the black circle). Its disk cache key is the URL, which is also Coil's default for a URL. So the avatar request differs from a message image request only in its memory cache key and, since v1.39.1, `ScaledImageDecoder`.

## Next steps, in order

1. **Read the logcat.** With the phone on USB, run `adb logcat -c`, then `adb logcat -v time -s AvatarImage FullscreenImageViewer skia ImageDecoder BitmapFactory > avatar-log.txt`, then open the chat list. Each failed avatar logs whether the local file or the URL failed, plus the exception. `skia` carries the platform's own decode errors. This is the single most useful piece of evidence. No session has seen it yet.
2. **Clear the app's cache once** (Android Settings → Apps → FireStream → Storage → Clear cache, not storage). Coil's disk cache (`cacheDir/image_cache`, keyed by avatar URL) could hold a bad entry that every URL load reads back.
3. **Upload a new avatar on the Android 17 phone** and see whether that one shows. Since v1.39.1, new uploads are re-encoded at 1024 px. Old avatars are raw camera originals. If only the new one shows, the problem is the old files' format, and hypothesis A below gains weight.
4. **Open an avatar fullscreen** (tap the big avatar on a profile). `FullscreenImageViewer` loads through `SubcomposeAsyncImage` with Coil's default decoder and shows a labelled error. If fullscreen works but small avatars don't, look at the avatar request (decoder, size, cache keys) rather than at the file.
5. **Inspect an avatar file.** A session cannot read Firestore or list Storage: there are no credentials in the container, and the build's `google-services.json` is a placeholder. The owner copies one `avatarUrl` (Firestore `users/<uid>`) or the Storage download link for `avatars/<uid>/profile.jpg`. `firebasestorage.googleapis.com` is reachable from a cloud session. Treat the `token=` part as a secret and keep it out of commits and logs.
6. **Ask the owner three questions.** Which app version is on the Android 16 device? An old version there may draw every avatar from its old local cache and never fetch a URL. Did avatars show on the Android 17 phone before its OS update? Do images *received* from someone else load on it, or only images it sent itself?

## Hypotheses still open

- **A. The old avatar files are a format Android 17 fails to decode.** They are raw camera originals, possibly Ultra HDR JPEGs with a gainmap, or HEIC. Message images are re-encoded by `ImageCompressor` before sending, which would explain why they load on Android 17 while old avatars do not. Check by downloading an avatar (`avatars/<uid>/profile.jpg` in Firebase Storage; the URL is the user's `avatarUrl`) and inspecting it, e.g. `exiftool -a -G1 file.jpg | grep -i -E "hdrgm|gainmap|MPF|MPImage"`. If this holds, the fix is a decode fallback in `ScaledImageDecoder` (for example decode without the gainmap, or fall back to `BitmapFactory`), or re-encoding avatars server side or on next upload. Prediction: fullscreen fails too, the log shows an `ImageDecoder` or `skia` decode error, and a newly uploaded avatar shows.
- **B. The URL load fails, not the file.** The avatar URLs are Firebase Storage download URLs with `?token=`, fetched by Coil through the app's `OkHttpClient`. The logcat in step 1 decides this. Prediction: the `AvatarImage` line says "from the URL" with an HTTP code or an `IOException`, and fullscreen fails the same way.
- **C. Something in the avatar request itself.** Message images load through the same `ImageLoader` (`FireStreamApp.newImageLoader`) on Android 17, so Coil itself works. The differences in the avatar request are: `memoryCacheKey`/`diskCacheKey` set to the URL, `ScaledImageDecoder`, and `crossfade`. This is the weakest hypothesis, because the request failed before `ScaledImageDecoder` existed and its disk cache key equals Coil's default. Prediction: fullscreen works while small avatars fail.
- **D. No avatar data reaches the UI on that phone.** `avatarUrl` and `localAvatarPath` are both null in its Room rows. Prediction: no `AvatarImage` log lines at all, and fullscreen shows "No image data" or does not open.

## Code map

- `app/src/main/java/com/firestream/chat/ui/components/UserAvatar.kt`: `resolveAvatarModel`, `avatarCacheKey`, `buildAvatarRequest`, `AvatarImage`, `UserAvatar`
- `app/src/main/java/com/firestream/chat/ui/components/ScaledImageDecoder.kt`: the `ImageDecoder`-based Coil decoder
- `app/src/main/java/com/firestream/chat/data/util/ProfileImageManager.kt`: cache folder, download, scaled upload copy, legacy cleanup
- `app/src/main/java/com/firestream/chat/data/repository/{User,Chat,Contact}RepositoryImpl.kt`: when an avatar is downloaded and `localAvatarPath` written
- `app/src/main/java/com/firestream/chat/FireStreamApp.kt`: the app-wide Coil `ImageLoader`
- Tests: `AvatarRequestTest`, `AvatarSourceTest`, `ResolveAvatarModelTest`, `AvatarCacheKeyTest`, `ProfileImageManagerTest`, `MediaFileManagerTest`, `UserRepositoryImplAvatarCacheTest`

## Release side issues found on the way

- **The in-app updater only works while the repo is public.** `BuildConfig.UPDATE_MANIFEST_URL` is `https://github.com/gomorra/fire-stream-chat/releases/latest/download/latest-firebase.json`, fetched anonymously. While the repo was private, GitHub answered 404 and `UpdateManifestSource` treated that as "no release published", so the app said it was up to date. The repo is public again, and v1.40.1 arrived through the updater. If it goes private again, a separate public `gomorra/fire-stream-chat-releases` repo for the release assets was proposed. Either way the updater should stop reporting a 404 as "up to date".
- **Cloud sessions can now release.** `scripts/cut-release.sh X.Y.Z --tag-in-ci` pushes only the release commit. Dispatching `release-apk.yml` on `main` with `tag=vX.Y.Z` (GitHub connector, `actions_run_trigger` → `run_workflow`) creates the tag and builds. v1.40.1 was released this way. Details are in `docs/RELEASING.md` § *Releasing without pushing a tag*.
- **A proposed speed-up for dispatched releases, not applied.** Add `cache-read-only: true` to the `setup-gradle` step in `release-apk.yml`. A dispatch on `main` spent 2 min 18 s saving the Gradle cache, which a tag run skips.
- **Tag `v1.39.1` does not exist.** Its release commit `ab3d21a` is on `main` and there is no 1.39.1 release. Probably harmless; noted in case versionName derivation looks odd.
