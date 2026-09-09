#!/usr/bin/env bash
# Installs the Android SDK this project builds against.
#
# Claude Code cloud containers ship a JDK and Gradle but no Android SDK, so
# every Gradle task that touches the Android plugin dies with "SDK location not
# found" before it ever reaches a dependency. Run this once per container —
# from the environment's setup script, or by hand — and `./gradlew test
# assembleDebug` works.
#
# Everything it downloads comes from dl.google.com, which the environment's
# network policy must allow (it does as of 2026-09-09).
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="11076708"   # cmdline-tools 13.0
COMPILE_SDK="35"                   # keep in sync with app/build.gradle.kts
BUILD_TOOLS="35.0.0"

if [ -x "$ANDROID_HOME/platforms/android-$COMPILE_SDK/android.jar" ] ||
   [ -d "$ANDROID_HOME/platforms/android-$COMPILE_SDK" ]; then
    echo "[android-sdk] platform $COMPILE_SDK already present at $ANDROID_HOME"
else
    echo "[android-sdk] installing to $ANDROID_HOME"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    curl -fsSL -o "$tmp/tools.zip" \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"

    export ANDROID_HOME
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
        "platform-tools" "platforms;android-$COMPILE_SDK" "build-tools;$BUILD_TOOLS"
fi

# Gradle finds the SDK through ANDROID_HOME or local.properties. local.properties
# is gitignored and survives for the life of the container, so write it too —
# a Gradle daemon started without our env still resolves.
if ! grep -qs '^sdk.dir=' local.properties 2>/dev/null; then
    echo "sdk.dir=$ANDROID_HOME" >> local.properties
    echo "[android-sdk] wrote sdk.dir to local.properties"
fi

echo "[android-sdk] ready: $ANDROID_HOME"
