#!/usr/bin/env bash
# SessionStart hook — makes `./gradlew test|lint|assembleDebug` usable in a
# Claude Code cloud container.
#
# A fresh container has a JDK and the Gradle wrapper but three things missing
# that every Android task needs:
#   1. the Android SDK          → scripts/install-android-sdk.sh
#   2. a google-services.json   → placeholder written below (gitignored)
#   3. a UTF-8 locale           → set in .claude/settings.json, not here: the
#      Gradle daemon inherits its locale from the shell that STARTS it, so a
#      hook-local export would not reach it. Without it the Kotlin compiler
#      dies on test names containing an em dash (sun.jnu.encoding = ASCII).
#
# Only runs in remote sessions; local dev boxes already have all three.
set -euo pipefail

# Kill switch: set SKIP_GRADLE_PREWARM=1 in your Claude Code env to bypass.
if [ "${SKIP_GRADLE_PREWARM:-}" = "1" ]; then
    echo "[session-start] SKIP_GRADLE_PREWARM=1 — skipping."
    exit 0
fi

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"

./scripts/install-android-sdk.sh

# The google-services plugin is applied module-wide, so EVERY variant fails at
# processGoogleServices without this file — including pocketbase, which does not
# use Firebase at all (see TECH_DEBT.md). The real file is gitignored and holds
# no secret that isn't already shipped in the APK, but it is not in the repo, so
# remote sessions get a placeholder that satisfies the plugin and reaches no
# Firebase project. Never overwrite a real one.
if [ ! -f app/google-services.json ]; then
    echo "[session-start] writing placeholder google-services.json (build-only)"
    cat > app/google-services.json <<'JSON'
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "firestream-build-placeholder",
    "storage_bucket": "firestream-build-placeholder.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000000000000",
        "android_client_info": { "package_name": "com.firestream.chat" }
      },
      "oauth_client": [],
      "api_key": [ { "current_key": "AIzaSyPLACEHOLDER-BUILD-ONLY-NOT-A-REAL-KEY" } ],
      "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
    }
  ],
  "configuration_version": "1"
}
JSON
fi

# Warm the dependency cache. Flavor-qualified on purpose: with a `backend`
# flavor dimension there is no `compileDebugKotlin` task, so the unqualified
# name this hook used to call could never have succeeded.
echo "[session-start] warming the dependency cache (firebase flavor)…"
./gradlew :app:compileFirebaseDebugKotlin :app:compileFirebaseDebugUnitTestKotlin --quiet

echo "[session-start] done — ./gradlew :app:testFirebaseDebugUnitTest is ready."
