# Cloud Container Setup (Claude Code on the Web)

The full Gradle test suite runs inside cloud containers, and all dependency hosts resolve.

A fresh container lacks four things. `.claude/hooks/session-start.sh` supplies all four automatically. If you hit any of these errors, the hook did not run — check `CLAUDE_CODE_REMOTE` and run `.claude/hooks/session-start.sh` by hand rather than working around it:

1. **No Android SDK (`SDK location not found`).** `scripts/install-android-sdk.sh` installs it and writes `sdk.dir` to `local.properties`.
2. **No `google-services.json`.** The plugin applies module-wide, so every variant fails at `processGoogleServices`. The hook writes a build-only placeholder.
3. **No UTF-8 locale (`Internal compiler error` on em-dash test names).** `.claude/settings.json` sets `LANG`/`LC_ALL`. Because the Gradle daemon inherits locale from the shell that started it, run `./gradlew --stop` before re-running if you exported `LANG` manually.
4. **Maven Central `429 Too Many Requests` through the proxy.** Gradle stops on 429 instead of falling back to the next repo, and Robolectric fails with "Failed to fetch maven artifact". The hook writes `~/.gradle/init.d/maven-central-mirror.gradle`, putting Google's mirror first.

Use the `firebase` flavor (`:app:testFirebaseDebugUnitTest`, `assembleFirebaseDebug`); bare `test` also builds `pocketbase`. Every test failure in a cloud container is real (`ApkDownloaderTest` no longer hits live DNS).
