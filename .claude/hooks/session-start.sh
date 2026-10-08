#!/usr/bin/env bash
# SessionStart hook — makes `./gradlew test|lint|assembleDebug` usable in a
# Claude Code cloud container.
#
# A fresh container has a JDK and the Gradle wrapper but four things missing:
#   1. the Android SDK          → scripts/install-android-sdk.sh
#   2. a google-services.json   → placeholder written below (gitignored)
#   3. a UTF-8 locale           → set in .claude/settings.json, not here: the
#      Gradle daemon inherits its locale from the shell that STARTS it, so a
#      hook-local export would not reach it. Without it the Kotlin compiler
#      dies on test names containing an em dash (sun.jnu.encoding = ASCII).
#   4. a way past Maven Central's rate limit → a Gradle init script written
#      below into ~/.gradle/init.d/, outside the repo. Through the session's
#      proxy, Maven Central sometimes answers 429 for long stretches, and Gradle
#      stops at a 429 instead of trying the next repository.
#
# Only runs in remote sessions; local dev boxes and CI have none of these problems.
set -euo pipefail

# Kill switch: SKIP_GRADLE_PREWARM=1 in your Claude Code env skips this whole
# hook, not just the cache warm-up. Nothing below runs: no Android SDK install,
# no placeholder google-services.json, and no Maven Central mirror script. A
# mirror script that an earlier start wrote stays in ~/.gradle/init.d/ and is
# still used.
if [ "${SKIP_GRADLE_PREWARM:-}" = "1" ]; then
    echo "[session-start] SKIP_GRADLE_PREWARM=1 — skipping the whole hook, Maven Central mirror included."
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

# Google's mirror of Maven Central goes first, ahead of every repository the
# build declares, for Gradle's downloads and for the android-all jars Robolectric
# fetches itself while tests run. A 404 there (an androidx or Firebase artifact)
# falls through to the next repository. Rewritten on every start, so an edit here
# reaches containers that already have the file.
INIT_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/init.d"
mkdir -p "$INIT_DIR"
echo "[session-start] writing the Maven Central mirror init script"
cat > "$INIT_DIR/maven-central-mirror.gradle" <<'GRADLE'
// Written by .claude/hooks/session-start.sh in Claude Code cloud sessions only.
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'

beforeSettings { settings ->
    settings.pluginManagement.repositories {
        maven { url mirror; name 'MavenCentralMirror' }
    }
    settings.dependencyResolutionManagement.repositories {
        maven { url mirror; name 'MavenCentralMirror' }
    }
}

allprojects {
    tasks.withType(Test).configureEach {
        systemProperty 'robolectric.dependency.repo.url', mirror
        systemProperty 'robolectric.dependency.repo.id', 'maven-central-mirror'
    }
}
GRADLE

# Warm the dependency cache. Flavor-qualified on purpose: with a `backend`
# flavor dimension there is no `compileDebugKotlin` task, so the unqualified
# name this hook used to call could never have succeeded.
echo "[session-start] warming the dependency cache (firebase flavor)…"
./gradlew :app:compileFirebaseDebugKotlin :app:compileFirebaseDebugUnitTestKotlin --quiet

echo "[session-start] done — ./gradlew :app:testFirebaseDebugUnitTest is ready."
