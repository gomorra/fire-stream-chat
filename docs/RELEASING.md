# Releasing FireStream Chat

This document covers everything needed to cut a signed release: keystore generation, GitHub Actions secrets, and the tag-and-publish workflow that produces APKs the in-app updater can fetch.

## Overview

Releases are produced by `.github/workflows/release-apk.yml` on every push of a `v*` git tag (or via manual `workflow_dispatch`). The workflow:

1. Builds `firebase` and `pocketbase` release APKs.
2. Signs each APK with the release keystore stored in GitHub Secrets.
3. Computes SHA-256 for each APK.
4. Renders one update manifest per flavor (`latest-firebase.json`, `latest-pocketbase.json`).
5. Creates a GitHub Release with all four files attached.

The app fetches its manifest from a flavor-specific "latest" alias URL baked into `BuildConfig.UPDATE_MANIFEST_URL` at compile time:

- `https://github.com/<owner>/<repo>/releases/latest/download/latest-firebase.json`
- `https://github.com/<owner>/<repo>/releases/latest/download/latest-pocketbase.json`

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

### 3. Add five repository secrets

Settings → Secrets and variables → Actions → New repository secret:

| Secret name | Value |
|---|---|
| `RELEASE_KEYSTORE_B64` | Contents of `firestream-release.jks.b64` |
| `RELEASE_STORE_PASSWORD` | Keystore store password |
| `RELEASE_KEY_ALIAS` | `firestream` (or whatever alias you used) |
| `RELEASE_KEY_PASSWORD` | Key password |
| `GOOGLE_SERVICES_JSON_B64` | Base64-encoded contents of `app/google-services.json` (`base64 -w 0 app/google-services.json`). Required because the file is gitignored and the Firebase Gradle plugin fails the build without it. |

## Local release builds

To produce a signed release APK on your own machine (e.g. to verify the keystore works), add to `local.properties`:

```properties
releaseStoreFile=/absolute/path/to/firestream-release.jks
releaseStorePassword=...
releaseKeyAlias=firestream
releaseKeyPassword=...
```

Then `./gradlew assembleFirebaseRelease` produces a signed APK at `app/build/outputs/apk/firebase/release/`.

If none of these properties are set, release builds fall back to the debug keystore so `assembleRelease` still works for quick local checks. APKs built that way **cannot** in-place upgrade any installation produced by the CI keystore.

## Cutting a release

`versionName` is derived from `git describe --tags` — the tag IS the version, no source edit required. `versionCode` is derived from the commit count.

1. Verify `CHANGELOG.md` has an `[Unreleased]` section with entries.
2. Decide the new version (SemVer, per `CLAUDE.md` rules).
3. Rename the `[Unreleased]` heading to `[X.Y.Z] — YYYY-MM-DD` and add a fresh empty `[Unreleased]` block above it.
4. Commit, then tag and push both:

```bash
git commit -m "chore(release): vX.Y.Z"
git tag vX.Y.Z
git push origin main vX.Y.Z
```

The workflow runs against the tagged commit, where `versionName` resolves to `X.Y.Z` exactly (untagged builds carry a `-dev+<sha>` suffix so dev APKs can never masquerade as a release). Existing installs pick the new release up via the in-app updater within 24 hours, or immediately when the user taps "Check for updates" in Settings.

To build a "fake older" APK locally for testing the in-app updater, override at the command line:

```bash
./gradlew assembleFirebaseRelease -PversionCodeOverride=408 -PversionNameOverride=1.5.0
```

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
