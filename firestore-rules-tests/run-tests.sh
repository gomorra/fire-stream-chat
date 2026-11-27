#!/usr/bin/env bash
# Runs the Firestore + Storage rules test suite under the Firebase emulators.
# firebase-tools requires Java 21+, while the host default JDK is often 17 for
# Gradle 8.11.1 — auto-select a local JDK 21+ when needed.
set -euo pipefail

java_major_version() {
  local bin="$1"
  [ -x "$bin" ] || return 1
  local raw
  raw=$("$bin" -version 2>&1 | head -n 1 || true)
  if [[ "$raw" =~ \"([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  return 1
}

current_major="$(java_major_version "$(command -v java || true)" || echo 0)"
if [ "$current_major" -lt 21 ]; then
  candidates=()
  [ -n "${JAVA21_HOME:-}" ] && candidates+=("$JAVA21_HOME")
  candidates+=(
    /opt/android-studio/jbr
    /usr/lib/jvm/java-21-openjdk
    /usr/lib/jvm/java-21-openjdk-amd64
    /usr/lib/jvm/temurin-21-jdk
    /usr/lib/jvm/zulu-21
  )
  for dir in /usr/lib/jvm/*21* /usr/lib/jvm/*22* /usr/lib/jvm/*23* /usr/lib/jvm/*24* /usr/lib/jvm/*25*; do
    [ -d "$dir" ] && candidates+=("$dir")
  done

  selected=""
  for home in "${candidates[@]}"; do
    ver="$(java_major_version "$home/bin/java" || echo 0)"
    if [ "$ver" -ge 21 ]; then
      selected="$home"
      break
    fi
  done

  if [ -z "$selected" ]; then
    echo "Error: firebase-tools emulators require Java 21+, found Java ${current_major}." >&2
    echo "Install JDK 21+ or set JAVA21_HOME to a JDK 21+ directory." >&2
    exit 1
  fi

  export JAVA_HOME="$selected"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

exec npx firebase emulators:exec --only firestore,storage --project demo-firestream "node --test"
