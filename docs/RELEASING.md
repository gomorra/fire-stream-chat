# Releasing FireStream Chat

This document covers everything needed to cut a signed release: keystore generation, GitHub Actions secrets, and the tag-and-publish workflow that produces APKs the in-app updater can fetch.

## Overview

Releases are produced by `.github/workflows/release-apk.yml` on every push of a `v*` git tag (or via manual `workflow_dispatch`). The workflow:

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

> **Important:** When these credentials are present, **debug builds are also signed with the release keystore**. This ensures `installDebug` → release update (via the in-app updater) works without "App not installed" failures. Without these credentials, both build types fall back to the default debug keystore — but APKs built that way cannot in-place upgrade any installation produced by the CI keystore. After adding the credentials for the first time, uninstall the existing debug-signed app and reinstall.

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

By default a tag push builds **firebase only**. To include pocketbase, see the next section.

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
