#!/usr/bin/env bash
# Fails when CHANGELOG.md's top section says `## [UNRELEASED] [X.Y.Z]` and the tag vX.Y.Z already
# exists: that section has shipped. scripts/cut-release.sh drops the prefix before it tags, so
# this only happens to a tag made some other way — and the next commit then appends to a released
# section and upgrades its version in place, which is how 1.34.0's entries ended up under 1.35.0
# on 2026-09-20. Run by .github/workflows/changelog-check.yml; needs the repo's tags.
#
#   scripts/check-changelog-header.sh [CHANGELOG.md]
set -euo pipefail

changelog=${1:-"$(git rev-parse --show-toplevel)/CHANGELOG.md"}
header=$(grep -m1 '^## \[' "$changelog" || true)

if [[ ! "$header" =~ ^\#\#\ \[UNRELEASED\]\ \[([0-9]+\.[0-9]+\.[0-9]+)\] ]]; then
    echo "top CHANGELOG section is not [UNRELEASED] — nothing to check: '${header:-no section}'"
    exit 0
fi
version=${BASH_REMATCH[1]}

if git rev-parse --verify --quiet "refs/tags/v$version" >/dev/null; then
    echo "::error::CHANGELOG.md's top section is '[UNRELEASED] [$version]', but tag v$version already exists."
    echo "That section has shipped. Drop the '[UNRELEASED] ' prefix from its header and open a new"
    echo "'## [UNRELEASED] [X.Y.Z] — YYYY-MM-DD' section above it for what landed since the tag."
    exit 1
fi
echo "[UNRELEASED] [$version] — no tag v$version yet. ✓"
