# Releasing FireStream Chat

This document covers everything needed to cut a signed release: keystore generation, GitHub Actions secrets, and the tag-and-publish workflow that produces APKs the in-app updater can fetch.

## Overview

Releases are produced by `.github/workflows/release-apk.yml` on every push of a `v*` git tag, or by a manual `workflow_dispatch`. A dispatch on `main` for a tag that does not exist yet creates the tag first (see [Releasing without pushing a tag](#releasing-without-pushing-a-tag)). The workflow:

1. Builds the selected flavor(s) — `firebase` only by default; pocketbase requires manual dispatch (see [Selecting flavors](#selecting-flavors)).
2. Signs each APK with the release keystore stored in GitHub Secrets.
3. Computes SHA-256 for each APK.
4. Renders one update manifest per built flavor (`latest-firebase.json` and/or `latest-pocketbase.json`).
5. Creates a GitHub Release with the resulting files attached.

The app fetches its manifest from a flavor-specific "latest" alias URL baked into `BuildConfig.UPDATE_MANIFEST_URL` at compile time:

- `https://github.com/<owner>/<repo>/releases/latest/download/latest-firebase.json`
- `https://github.com/<owner>/<repo>/releases/latest/download/latest-pocketbase.json`

> Because the in-app updater reads `releases/latest/download/latest-<flavor>.json`, a release that only ships one flavor leaves the other flavor's installs without a manifest *at that tag* — the URL 404s until you publish a release that includes that flavor. Plan flavor coverage per release accordingly.

## One-time keystore setup

> An APK signed by keystore A cannot upgrade an installation signed by keystore B. Once you commit to a CI keystore, every future signed build must use the same one — losing it means every user has to uninstall + reinstall.

### 1. Generate the keystore

```bash
keytool -genkey -v \
  -keystore firestream-release.jks \
  -alias firestream \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

Pick a strong store password and key password. Keep the keystore file out of git (it's already covered by `*.jks` / `*.keystore` in `.gitignore`).

Back up the keystore file and both passwords to a password manager **before** continuing. There is no recovery path.

### 2. Encode for GitHub Secrets

```bash
base64 -w 0 firestream-release.jks > firestream-release.jks.b64
```

### 3. Add the repository secrets

Settings → Secrets and variables → Actions → New repository secret:

| Secret name | Value |
|---|---|
| `RELEASE_KEYSTORE_B64` | Contents of `firestream-release.jks.b64` |
| `RELEASE_STORE_PASSWORD` | Keystore store password |
| `RELEASE_KEY_ALIAS` | `firestream` (or whatever alias you used) |
| `RELEASE_KEY_PASSWORD` | Key password |
| `GOOGLE_SERVICES_JSON_B64` | Base64-encoded contents of `app/google-services.json` (`base64 -w 0 app/google-services.json`). Required because the file is gitignored and the Firebase Gradle plugin fails the build without it. |
| `KLIPY_API_KEY` | The KLIPY app key from the Partner Panel. Optional. A release built without it has no GIFs tab and no online stickers. |

The first five are required. The KLIPY key ends up inside the APK, where it can be read out.

## Local release builds

To produce a signed release APK on your own machine (e.g. to verify the keystore works), add to `local.properties`:

```properties
releaseStoreFile=/absolute/path/to/firestream-release.jks
releaseStorePassword=...
releaseKeyAlias=firestream
releaseKeyPassword=...
klipyApiKey=...
```

`klipyApiKey` is optional and switches on the GIFs tab and the online stickers, in debug builds too.

Then `./gradlew assembleFirebaseRelease` produces a signed APK at `app/build/outputs/apk/firebase/release/`.

> **Important:** When these credentials are present, **debug builds are also signed with the release keystore**. This ensures `installDebug` → release update (via the in-app updater) works without "App not installed" failures. Without these credentials, both build types fall back to the default debug keystore — but APKs built that way cannot in-place upgrade any installation produced by the CI keystore. After adding the credentials for the first time, uninstall the existing debug-signed app and reinstall.

## Cutting a release

`versionName` is derived from `git describe --tags` — the tag IS the version, no source edit required. `versionCode` is derived from the commit count.

Confirm `X.Y.Z` is the version you intend to ship (bump per the SemVer rules in `CLAUDE.md` if the last CHANGELOG entry under-bumped it), then run:

```bash
./scripts/cut-release.sh X.Y.Z
```

Add `--dry-run` to see exactly what it would do (including preflight failures) without touching anything:

```bash
./scripts/cut-release.sh X.Y.Z --dry-run
```

### What the script does / manual equivalent

The script automates the flow below — read this if something fails, or if you need to do it by hand:

1. **Preflight** — fails fast, before touching anything, unless all of these hold:
   - you're inside the repo, on branch `main`, with a clean working tree
   - local `main` holds every commit on `origin/main`. The script fetches to check. If it is behind, pull or merge first.
   - tag `vX.Y.Z` doesn't already exist, locally or on `origin`
   - `CHANGELOG.md`'s top section is exactly `## [UNRELEASED] [X.Y.Z] — YYYY-MM-DD` with entries — the version and date live in this combined working header, decided per-commit as entries land (see `CLAUDE.md` Changelog section). If the version in the header doesn't match `X.Y.Z`, or the `[UNRELEASED] ` prefix is already gone, the script says so and stops.
2. **Rewrite the header** — drops the `[UNRELEASED] ` prefix, leaving `## [X.Y.Z] — YYYY-MM-DD` (today's date, preserving the em dash style already in the file).
3. **Commit, tag, push:**

   ```bash
   git commit -m "chore(release): vX.Y.Z"
   git tag vX.Y.Z
   git push --atomic origin main vX.Y.Z
   ```

   The push is atomic so the tag never reaches `origin` without the branch. A tag on its own starts the release build from a commit that is not on `origin/main`.

The workflow runs against the tagged commit, where `versionName` resolves to `X.Y.Z` exactly (untagged builds carry a `-dev+<sha>` suffix so dev APKs can never masquerade as a release). Existing installs pick the new release up via the in-app updater within 24 hours, or immediately when the user taps "Check for updates" in Settings. Users who enable **Settings → Auto-download updates on Wi-Fi** (opt-in, off by default) have the new APK downloaded automatically in the background when the daily check runs on an unmetered network, so it's already on-device and only the tap-to-install step remains.

By default a tag push builds **firebase only**. To include pocketbase, see [Selecting flavors](#selecting-flavors).

### Releasing without pushing a tag

A Claude Code cloud session can push to `main` but cannot push tags. It releases in two steps instead:

```bash
./scripts/cut-release.sh X.Y.Z --tag-in-ci
gh workflow run release-apk.yml --ref main -f tag=vX.Y.Z
```

The first command makes the same release commit and pushes only `main`. The second starts the workflow; a session without `gh` uses the GitHub connector's `run_workflow` with the same ref and input. The workflow then creates `vX.Y.Z` itself and builds it.

The workflow creates the tag only when all of these hold. Otherwise it fails before building:

- the dispatch ran on `main`
- `main`'s head commit is named `chore(release): vX.Y.Z`
- `CHANGELOG.md`'s top section at that commit is the released `## [X.Y.Z] — …` header

So dispatch right after the push, before anything else lands on `main`. If something did land, the release commit is no longer the head and the workflow refuses. Create the tag on the release commit by hand, or cut the next version.

A tag the workflow pushes with `GITHUB_TOKEN` starts no other workflow, so the tag push trigger does not build a second time. A dispatch for a tag that already exists builds that tag, as before.

To build a "fake older" APK locally for testing the in-app updater, override at the command line:

```bash
./gradlew assembleFirebaseRelease -PversionCodeOverride=408 -PversionNameOverride=1.5.0
```

## Selecting flavors

The workflow accepts a `flavors` input on manual `workflow_dispatch` runs:

| Trigger | Flavors built |
|---|---|
| Tag push (`git push origin vX.Y.Z`) | `firebase` only |
| `workflow_dispatch` with no `flavors` input | `firebase` only |
| `workflow_dispatch` with `flavors=pocketbase` | `pocketbase` only |
| `workflow_dispatch` with `flavors=firebase,pocketbase` | both |

The `flavors` input is a comma-separated list; case-sensitive substring match against `firebase` and `pocketbase`.

### Adding pocketbase to an existing release

The tag must already exist (the workflow checks out the tagged commit and verifies HEAD matches). Then:

```bash
gh workflow run release-apk.yml -f tag=vX.Y.Z -f flavors=pocketbase
# or to (re-)build both flavors
gh workflow run release-apk.yml -f tag=vX.Y.Z -f flavors=firebase,pocketbase
```

`softprops/action-gh-release` preserves prior assets when re-uploading to the same release tag, so the existing firebase APK and manifest stay attached and the pocketbase artifacts are added alongside them.

You can also trigger this from the GitHub UI: **Actions → Release APK → Run workflow**, then fill in the tag and flavors fields.

## Verifying a release locally

After the workflow finishes, you can sanity-check the manifest and APK without installing:

```bash
curl -sSL https://github.com/<owner>/<repo>/releases/latest/download/latest-firebase.json | jq .
curl -sSLO https://github.com/<owner>/<repo>/releases/latest/download/firestream-firebase-release-vX.Y.Z.apk
sha256sum firestream-firebase-release-vX.Y.Z.apk
```

The reported SHA must match the `sha256` field in the manifest.

## Troubleshooting

- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE` on update** — the new APK was signed with a different keystore than the installed one. Either uninstall the existing app (loses local data) or rebuild with the correct keystore.
- **Workflow can't decode keystore** — ensure `RELEASE_KEYSTORE_B64` was created with `base64 -w 0` (no line wraps).
- **Manifest says version A but APK is B** — the workflow renders the manifest from the same `versionCode` / `versionName` it just built. If they disagree, the release was assembled from one commit and the manifest from another; re-run the workflow.
