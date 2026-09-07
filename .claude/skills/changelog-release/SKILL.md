---
name: changelog-release
description: Version-bump rules, CHANGELOG section placement, backlog pruning, and the release-cutting procedure for FireStream Chat. Use when deciding which version bump a commit warrants, when placing or upgrading an [UNRELEASED] CHANGELOG header, when a shipped change may close a docs/BACKLOG.md item, when tagging a vX.Y.Z release, or when a changelog/release CI gate fails.
---

# Changelog versioning & releases

## Versioning

**Principle:** any commit that lands a CHANGELOG entry gets a version bump. The converse also holds — if the commit doesn't warrant a CHANGELOG entry (doc-only, test-only, CI/tooling, internal refactors with no release-visible effect), it doesn't bump. Bump severity follows the conventional-commit prefix:

| Prefix | Bump | Example |
|--------|------|---------|
| `feat:` | minor | `1.2.3` → `1.3.0` |
| `fix:` | patch | `1.2.3` → `1.2.4` |
| `refactor:` (when it lands a Refactored entry) | patch | `1.2.3` → `1.2.4` |
| `feat!:` or `BREAKING CHANGE:` | major | `1.2.3` → `2.0.0` |
| `chore:`, `docs:`, `test:`, `ci:` (no CHANGELOG entry) | none | — |

**Section placement.** If the top `## [UNRELEASED] [X.Y.Z] — YYYY-MM-DD` section is already dated today, append to it — and if this commit's bump is higher-severity than the section's current version, upgrade the version in the header in place (e.g. a `feat` landing on a `## [UNRELEASED] [1.2.4] — today` section promotes it to `## [UNRELEASED] [1.3.0] — today`). Otherwise insert a fresh `## [UNRELEASED] [X.Y.Z] — YYYY-MM-DD` section (version and date decided per this commit's bump).

**Prune the backlog in the same commit.** A CHANGELOG entry under `Added` or `Changed` often closes an item in `docs/BACKLOG.md`. When it does, **delete that item in the same commit that lands the entry** — do not leave it for later. Partial closes lose only the bullets that shipped; the item stays with a one-line note on what remains (that is why "Report users & messages (3.3)" and "Live location sharing (4.5)" read the way they do). `BACKLOG.md` deliberately keeps no shipped-work list, so an item left behind after it ships is the file's only failure mode — and the one that rotted its predecessor twice.

**`versionCode` is auto-derived** from `git rev-list --count HEAD` at Gradle configure time — never edit it by hand. Same with `BuildConfig.GIT_SHA` / `COMMIT_TIMESTAMP`.

**At release time** (once store tagging begins): drop the `[UNRELEASED] ` prefix from the top section's header, leaving `## [X.Y.Z] — YYYY-MM-DD`.

**Two-tier CHANGELOG gating:**
- **Push-time nudge** — `.github/workflows/changelog-check.yml` fails pushes and PRs that touch **production** code under `app/src/**` or `functions/**` without updating `CHANGELOG.md`. Test source sets (`app/src/test*/`, `app/src/androidTest*/`, including flavor variants) are **auto-exempt**, so test-only changes never need an entry. Apply the `no-changelog` label to bypass for other exempt PRs (refactors with no user-visible effect).
- **Release-time gate (authoritative)** — `.github/workflows/release-apk.yml` fails a `vX.Y.Z` tag build unless `CHANGELOG.md` has a matching `## [X.Y.Z]` section. This is the real backstop: nothing ships undocumented, even if it slipped past the push nudge.

## Cutting a release

**Before you run the script: sweep the backlog.** Read the CHANGELOG section about to be released against `docs/BACKLOG.md` and delete anything that shipped, committing that first. This is the backstop for whatever slipped past the per-commit rule above, and it has to happen *first* — `scripts/cut-release.sh` commits, tags, and pushes in one shot, so there is no pause afterwards in which to fix it.

Distribution is sideload-only, via GitHub Releases + the in-app updater; **`versionName` is auto-derived from `git describe --tags`**, so the tag is the single source of truth. To cut a release: drop the `[UNRELEASED] ` prefix from the top CHANGELOG header, commit, tag `vX.Y.Z`, push. `scripts/cut-release.sh` is the preferred scripted entry point for this. Full keystore/secrets/flavor detail in [`docs/RELEASING.md`](docs/RELEASING.md).

