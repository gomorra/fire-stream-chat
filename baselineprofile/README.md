# :baselineprofile

Macrobenchmark-driven baseline profile generator for the `:app` module.

The generated profile (`app/src/main/baselineProfiles/baseline-prof.txt`) is
committed to source control and packaged into the APK via the
`profileinstaller` runtime dependency. On install, ART AOT-compiles the listed
classes/methods so the first startup and first navigation run native instead of
interpreted — which is what removes the cold-start stutter on the first slide
transitions.

## Regenerating the profile

### Prerequisites

**One-time Firebase console setup.** The generator needs to log in, and Firebase
Phone Auth can't receive SMS in a test harness. Add a fictional test number:

1. [Firebase Console](https://console.firebase.google.com) → FireStream Chat
   project → Authentication → Sign-in method → Phone
2. Scroll to **Phone numbers for testing** → Add phone number
3. Enter: `+49 1511 2345678` (or any unused fictional number)
4. Fixed OTP: `123456`
5. Save

The generator hardcodes this number and OTP. If you pick a different pair,
update `BaselineProfileGenerator.kt` to match.

Fictional test numbers bypass SMS but create a real Firebase Auth user. That
user accumulates state (chat history, profile) across regeneration runs —
occasionally wipe it from the Firebase Console if it grows unwieldy.

### Running the generator

1. Connect a physical Android device or start an emulator.
   - Known-good emulator config (per project memory):
     `ANDROID_SERIAL=emulator-5554 emulator @Medium_Phone_API_36.1 -gpu swiftshader_indirect`
2. Run:
   ```bash
   ./gradlew :app:generateBaselineProfile
   ```
3. The profile is written to:
   - `app/src/main/baselineProfiles/baseline-prof.txt`
   - `app/src/main/baselineProfiles/startup-prof.txt`
4. Commit both files.
5. Build release to verify the profile gets packaged:
   ```bash
   ./gradlew assembleRelease
   unzip -l app/build/outputs/apk/release/app-release.apk | grep baseline
   ```
   The output must list `assets/dexopt/baseline.prof` and `baseline.profm`.

### When to regenerate

Baseline profiles drift when hot startup paths change. Regenerate when:

- A new screen is added on the startup path (onboarding, launcher, etc.)
- Major library bumps: Compose, Navigation, Firebase, Hilt, Room
- Quarterly, as a maintenance task

## Why opt-in generation?

`app/build.gradle.kts` sets `baselineProfile { automaticGenerationDuringBuild =
false }`. Without this, every `assembleRelease` would try to run the generator
on a connected device, which would fail CI and slow down local release builds.
Profile regeneration is an explicit `generateBaselineProfile` invocation.
