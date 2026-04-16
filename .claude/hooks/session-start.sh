#!/usr/bin/env bash
# SessionStart hook — primes the Gradle dependency cache so ./gradlew test /
# lint / assembleDebug work during the session.
#
# Claude Code sandboxes have network access only during the SessionStart phase;
# subsequent tool calls cannot reach dl.google.com / Google Maven. Without this
# hook, the first ./gradlew invocation fails to resolve the Android Gradle
# Plugin and any Maven dependency.
#
# Only runs in remote Claude Code sessions. Local dev environments are left
# alone so interactive builds aren't slowed down.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"

echo "[session-start] priming Gradle wrapper + AGP…"
./gradlew help --quiet

# Resolve and cache everything needed to compile production code and unit
# tests. After this, `./gradlew test`, `assembleDebug`, and `lint` can run
# without network access.
echo "[session-start] compiling main + unit-test sources to warm the dep cache…"
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --quiet

echo "[session-start] done."
