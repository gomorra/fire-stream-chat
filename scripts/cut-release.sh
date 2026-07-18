#!/usr/bin/env bash
#
# cut-release.sh — automate the manual release flow documented in
# docs/RELEASING.md "Cutting a release".
#
# Usage:
#   scripts/cut-release.sh X.Y.Z [--dry-run]
#
# What it does:
#   1. Runs preflight checks (git repo, branch, clean tree, tag collision,
#      CHANGELOG header state).
#   2. Rewrites the CHANGELOG.md "## [UNRELEASED] [X.Y.Z] — ..." header to
#      "## [X.Y.Z] — YYYY-MM-DD".
#   3. Commits, tags vX.Y.Z, and pushes main + the tag.
#
# --dry-run runs every preflight check (which can still fail) but performs
# no mutation — it only prints what would happen.

set -euo pipefail

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

usage() {
    echo "Usage: $(basename "$0") X.Y.Z [--dry-run]" >&2
}

VERSION=""
DRY_RUN=0

for arg in "$@"; do
    case "$arg" in
        --dry-run)
            DRY_RUN=1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            echo "error: unknown flag '$arg'" >&2
            usage
            exit 1
            ;;
        *)
            if [[ -n "$VERSION" ]]; then
                echo "error: unexpected extra argument '$arg'" >&2
                usage
                exit 1
            fi
            VERSION="$arg"
            ;;
    esac
done

if [[ -z "$VERSION" ]]; then
    echo "error: missing version argument" >&2
    usage
    exit 1
fi

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "error: '$VERSION' is not a valid X.Y.Z version" >&2
    exit 1
fi

TAG="v$VERSION"

die() {
    echo "error: $*" >&2
    exit 1
}

info() {
    echo "-- $*"
}

run() {
    # Echo the mutation, and execute it unless --dry-run.
    echo "+ $*"
    if [[ "$DRY_RUN" -eq 0 ]]; then
        "$@"
    fi
}

# ---------------------------------------------------------------------------
# Preflight checks (always run, even under --dry-run)
# ---------------------------------------------------------------------------

info "Preflight checks"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
    || die "not inside a git repository"

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[[ "$CURRENT_BRANCH" == "main" ]] \
    || die "must be on branch 'main' (currently on '$CURRENT_BRANCH')"

if [[ -n "$(git status --porcelain)" ]]; then
    die "working tree is not clean — commit or stash changes before cutting a release"
fi

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    die "tag $TAG already exists locally"
fi

if [[ -n "$(git ls-remote --tags origin "refs/tags/$TAG" 2>/dev/null)" ]]; then
    die "tag $TAG already exists on origin"
fi

CHANGELOG="CHANGELOG.md"
[[ -f "$CHANGELOG" ]] || die "$CHANGELOG not found at repo root"

LINE_NO="$(grep -n -m1 '^## ' "$CHANGELOG" | cut -d: -f1 || true)"
[[ -n "$LINE_NO" ]] || die "no top-level '## ' section header found in $CHANGELOG"

HEADER_LINE="$(sed -n "${LINE_NO}p" "$CHANGELOG")"

if [[ ! "$HEADER_LINE" =~ ^\#\#\ \[UNRELEASED\]\ \[([0-9]+\.[0-9]+\.[0-9]+)\](.*)$ ]]; then
    if [[ "$HEADER_LINE" =~ ^\#\#\ \[([0-9]+\.[0-9]+\.[0-9]+)\] ]]; then
        die "top CHANGELOG.md section appears already released (no [UNRELEASED] prefix): '$HEADER_LINE'"
    fi
    die "top CHANGELOG.md section does not match the expected '## [UNRELEASED] [X.Y.Z] ...' header: '$HEADER_LINE'"
fi

FOUND_VERSION="${BASH_REMATCH[1]}"
REST="${BASH_REMATCH[2]}"

if [[ "$FOUND_VERSION" != "$VERSION" ]]; then
    die "CHANGELOG.md top section is for version [$FOUND_VERSION], expected [$VERSION] (the version you're cutting)"
fi

info "CHANGELOG.md top section confirmed: [UNRELEASED] [$VERSION]"

# Extract an existing "<sep> YYYY-MM-DD" suffix if present, preserving the
# separator character (the file consistently uses an em dash, "—").
SEP="—"
EXISTING_DATE=""
if [[ "$REST" =~ ^\ (.)\ ([0-9]{4}-[0-9]{2}-[0-9]{2})$ ]]; then
    SEP="${BASH_REMATCH[1]}"
    EXISTING_DATE="${BASH_REMATCH[2]}"
fi

TODAY="$(date +%Y-%m-%d)"
if [[ -n "$EXISTING_DATE" && "$EXISTING_DATE" == "$TODAY" ]]; then
    FINAL_DATE="$EXISTING_DATE"
else
    FINAL_DATE="$TODAY"
fi

NEW_HEADER="## [$VERSION] $SEP $FINAL_DATE"

info "Preflight OK. New header will be: $NEW_HEADER"

if [[ "$DRY_RUN" -eq 1 ]]; then
    info "--dry-run: no changes will be made"
fi

# ---------------------------------------------------------------------------
# Mutations
# ---------------------------------------------------------------------------

info "Rewriting CHANGELOG.md header"
echo "+ rewrite line $LINE_NO of $CHANGELOG:"
echo "    - $HEADER_LINE"
echo "    + $NEW_HEADER"
if [[ "$DRY_RUN" -eq 0 ]]; then
    TMP_FILE="$(mktemp)"
    awk -v line="$LINE_NO" -v newheader="$NEW_HEADER" \
        'NR==line { print newheader; next } { print }' "$CHANGELOG" > "$TMP_FILE"
    mv "$TMP_FILE" "$CHANGELOG"
fi

run git add CHANGELOG.md
# NOTE: must stay a plain single-line -m. Never a heredoc, never piped into
# git commit — a repo hook blocks heredoc commits and hangs otherwise.
run git commit -m "chore(release): $TAG"
run git tag "$TAG"
run git push origin main "$TAG"

# ---------------------------------------------------------------------------
# Epilogue (always printed, including under --dry-run)
# ---------------------------------------------------------------------------

echo
if [[ "$DRY_RUN" -eq 1 ]]; then
    info "Dry run complete — nothing was changed."
else
    info "Release $TAG pushed."
fi
info "release-apk.yml now builds the firebase flavor only on this tag push."
info "pocketbase is NOT built automatically — if pocketbase installs should get this"
info "release, run:"
echo
echo "    gh workflow run release-apk.yml -f tag=$TAG -f flavors=pocketbase"
echo
info "Until that runs, pocketbase installs' update-manifest URL 404s at $TAG."
info "Check progress in the Actions tab, or see docs/RELEASING.md for verification steps."
