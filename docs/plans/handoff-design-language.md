# Handover: the design-language discussion and the theme repair

Status: open. Written 2026-10-10. The theme repair is built on a branch and waits for the owner's test on the phone. The design discussion waits for that verdict. Delete this file when a plan in `docs/plans/` replaces it.

## What the owner asked for

1. A review of the app's design language. The owner likes it because it is clean and minimal, and expects room for small and large improvements: fonts, colours, more Material 3, or another language.
2. A discussion of changes in effort classes.
3. Afterwards at least three throwaway prototypes, either on a page or in the app behind a switch.

Three things the owner said that bind the work:

- **The app is normally run in dark mode.** Show, render and judge dark first.
- **The `app-ui-design` skill's "Settled — do not re-choose" list does not limit this discussion.** How the skill is written is itself open. The same holds for "no dynamic color" and "all styles in Plus Jakarta Sans" in `docs/plans/app-improvements.md`.
- **"Do the repair work, and only this. Then I will test it and decide if this is the new default."** Nothing else was started.

The owner dictates by voice. Answer in short sentences, give an example per option, and put a recommendation with every question.

## The theme repair

- Worktree `.claude/worktrees/theme-repair`, branch `worktree-theme-repair`, based on `ce259f88`.
- `8d2f598a` is the code, its tests and the CHANGELOG entry. `97e19d76` adds the doc notes.
- The gate was green on the branch: `./gradlew test assembleDebug`, 2,779 firebase tests.
- The APK is at `app/build/outputs/apk/firebase/debug/app-firebase-debug.apk` in the worktree, versionCode 1138. The phone and the emulator hold 1135, so it installs as an upgrade. **It was not installed.** The owner installs it.
- Not merged, not pushed. `main` has moved since the branch was cut. Expect a CHANGELOG conflict on merge: both sides add under `[UNRELEASED] [1.42.0]`.
- Nothing ran on a device. The checklist is in the branch's `docs/BACKLOG.md`, "The completed theme".

It is step 27 of `docs/plans/app-improvements.md`, done by hand and out of order. The plan file is untouched and has no `**Shipped**` block for it.

What it changes:

- `Theme.kt` sets all 36 colour roles in both schemes. `Type.kt` sets all 15 styles. `ColorSchemeTest` and `TypographyTest` guard both, with WCAG ratios.
- Dark: dialogs and menus are `FsSurface2`, sheets `FsSurface`, raised things `FsSurface3`.
- Light: `primary` is `FireOrangeDark`, `secondaryContainer` a light warm neutral, and dialogs stay near white so orange text keeps 4.5:1.
- `BUBBLE_METADATA_ALPHA = 0.8f` in `MessageBubble.kt` is the one opacity for time and status in every bubble.

Judgement calls the owner may want changed. Each is one line to reverse:

| Call | Where | Before |
|---|---|---|
| Tertiary is neutral, so two banners and the jump-to-reaction button are grey | `tertiary*` in `Theme.kt` | Material's pink |
| The dark snackbar is a raised dark card with an orange action | `inverse*` in `DarkColorScheme` | a light lavender card |
| The chosen AM/PM half is tinted orange | `TimePicker` colors in `SnoozeOptions.kt` | followed tertiary |
| The selected bottom-nav icon takes `onPrimaryContainer` | `BottomNavBar.kt` | `primary`, 1.43:1 in light |
| Dialog titles are 21 sp | `headlineSmall` in `Type.kt` | 24 sp Roboto |
| `FireOrangeDark` is `#C54C0E` | `Color.kt` | plan decision D-8 says `#C94E0E`, which gives 4.39:1 on `#FAFAFA` |

Left out of step 27 on purpose:

- The new icon for a read message. The icon is the owner's choice.
- The catalogue re-record. The catalogue is step 26.

Step 28, the system bars following the in-app theme, is not done either.

If the owner accepts the repair: merge the branch into `main`, write step 27's `**Shipped**` block with the two items above still open, and correct the hex in D-8. If the owner rejects it, drop the branch and the worktree.

## A finding the owner has not decided

**The app renders every text in one font weight.** `plus_jakarta_sans.ttf` is one variable font. `Font(resId, weight)` does not set its weight axis, so Medium, SemiBold and Bold all render as Regular. A Roborazzi render confirmed it, and confirmed that the fix works.

- The fix is four lines in `Type.kt`: `variationSettings = FontVariation.Settings(FontVariation.weight(n))` per `Font` entry, with `@OptIn(ExperimentalTextApi::class)`.
- It makes every title, chat name and button label heavier. It was not applied.
- Side effect of the repair: button labels look lighter than before, because Roboto had real weights.
- Recorded in the branch's `TECH_DEBT.md` ("Plus Jakarta Sans renders in one weight") and `docs/GOTCHAS.md`.

Real weights are the single biggest lever on the look. Treat the choice as part of the design discussion.

## The discussion so far

The design language today: almost no colour (neutral surfaces, orange as the only accent, grey own bubbles), flat, small even type (12, 13 and 15 sp), stock Material 3 components underneath.

`docs/reviews/2026-10-08-app-review.md` §4 and §5 already list the repair-level findings, and steps 26–31 of `app-improvements` fix them. Don't repeat them. That plan finishes the current language. It does not ask whether the language is right.

Weak spots at the language level, beyond that plan:

1. Light and dark are two designs. Dark is warm grey throughout. Light mixes cool grey-blue text with warm beige bubbles.
2. Little type hierarchy: three styles share 15 sp, and titles are small.
3. One font does titles and message text. Plus Jakarta Sans is wide.
4. Icons are heavy: 330 filled against 21 outlined.
5. No shape idea: twelve corner sizes, 76 written as literals.
6. No motion idea: each animation picks its own timing.

Effort classes offered:

- **Class 1, tune.** Theme values only. About a day. Can be switched live in the app. About 75 corner sizes and 100 colours are written directly into screens and would not follow.
- **Class 2, restyle.** Single components: icons, bubbles, top bars, dividers. About a week. Same files as plan steps 29–30.
- **Class 3, new language.** Every screen. Weeks.

Directions offered, one line each:

- **A — Calm, finished.** Today's look, tuned: warm light theme, clearer type steps, outlined icons, fewer lines.
- **B — Material 3 Expressive.** Tinted surfaces, big round shapes, large titles, bouncy motion. Needs a newer Material library: `material3` 1.4.0 has no Expressive classes (checked in the jar).
- **C — Ink.** Near monochrome, orange only for unread and send, no bubble for incoming messages, small corners, a title font with character.
- **D — Night.** Pure black background, hairlines for separation, brighter orange. Class 1.
- **E — Ember.** Warm brown-black surfaces, cream text, a deep burnt-orange tint on own bubbles. Class 1 plus the bubbles.
- **F — Layers.** Same colours, more depth: floating pill bars with blur, the chat scrolls under them. Class 2 to 3.

Recommendations given: A plus pieces of B is the likely winner. Try A, D and E in the app with a switch, because they are cheap and about dark mode. Draw B, C and F on a page first.

Questions asked and **not yet answered**:

1. How far may it go? Recommended: all of class 1 and a few items of class 2.
2. What must stay? Assumed: orange as the single accent, grey own bubbles, the dark theme's mood, no wallpaper colours.
3. Are these the right directions, and is "page first, then app" fine?

Effort level recommended to the owner: medium for the repair and for wiring an in-app switch, high for the prototypes.

## The prototypes

Built 2026-10-10 on branch `prototype/design-language`, worktree `.claude/worktrees/prototype-design-language`, commit `2ca73a80`. The branch starts at the theme repair's tip and is throwaway. Never merge it.

- **The page** is `docs/prototypes/design-language.html` on that branch, published at https://claude.ai/artifact/46wQGJHdjZETBL6wkEzcji. It draws Today and A to F as three screens each: chat list, chat, settings with a dialog. It has a dark and light switch and a "Real font weights" box. Today opens with the box off, as the app renders now.
- **The in-app switch** is `ui/theme/PrototypeLook.kt` on that branch. A floating pill at the top of every screen of a debug build steps through Today, A, D and E. "Aa" turns real font weights on. A tap on the name folds the pill into a dot. Nothing is saved, so a restart is back on Today.
- The switch changes theme values only: the colour scheme, the type scale and the own-bubble fill (`ownBubbleColor()`, read by `MessageBubble`, `PollBubble` and `ListBubble`). D's hairlines and A's outlined icons are on the page only.
- The APK is at `app/build/outputs/apk/firebase/debug/app-firebase-debug.apk` in the prototype worktree, versionCode 1139. It contains the theme repair. **It was not installed and has not run on a device.**

## Next steps

0. Get the owner's pick from the page and the APK. Then fold the chosen parts into a plan in `docs/plans/` and delete this file.

1. Wait for the owner's verdict on the repair. Apply what they ask to change, on the branch, through the gate.
2. Ask about the font weights with the picture in mind.
3. Get answers to the three questions.
4. Build the prototypes. Load the `prototype` and `app-ui-design` skills first. Dark first.

## Working facts

- **Rendering without a device works.** A Robolectric test with `@GraphicsMode(NATIVE)` and `captureRoboImage("build/gallery/x.png")` renders real components, fonts included. Run it with `./gradlew :app:testFirebaseDebugUnitTest --tests '<class>' -Proborazzi.test.record=true`. Draw dialogs, sheets and menus as a `Surface` with `AlertDialogDefaults.containerColor`, `BottomSheetDefaults.ContainerColor` or `MenuDefaults.containerColor`, because the real ones open their own window. The throwaway gallery used for the repair was deleted before the commit.
- **A fresh worktree needs** `local.properties` and `app/google-services.json` copied in. The signing path in `local.properties` is absolute, so a worktree build carries the same key as the installed app.
- **Other sessions share this machine.** Plan runners were live during this work. Never run `./gradlew --stop`. A Gradle daemon vanished once during an APK rebuild, and the retry worked.
- **A Bash call that holds both a heredoc and `git commit` is refused by a hook.** Edit files in one call and commit in the next, with chained `-m` flags.
