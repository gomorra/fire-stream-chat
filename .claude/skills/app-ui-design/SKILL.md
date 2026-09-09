---
name: app-ui-design
description: Build or change Jetpack Compose UI in FireStream Chat. Use when adding or reworking a screen, composable, or shared UI component — chat bubbles, message lists, bottom sheets, dialogs, nav bars, settings rows, profile screens, media viewers — or when touching the theme, an animation, a gesture, insets, or touch targets. Carries this app's settled visual system and the Compose traps it has already paid for.
---

FireStream's visual system is **already decided**. This skill exists so a UI change lands inside it instead of re-inventing next to it. Write real Kotlin/Compose, match the surrounding file, and finish through the test gate.

## Before writing a line

1. Read the theme you're building on: `app/src/main/java/com/firestream/chat/ui/theme/` — `Color.kt`, `Type.kt`, `Shape.kt`, `Theme.kt`.
2. Read [`docs/GOTCHAS.md` § Compose / UI](../../../docs/GOTCHAS.md) — the traps below are the short form; that file has the full account.
3. For anything chat-, list-, or state-shaped, check [`docs/PATTERNS.md`](../../../docs/PATTERNS.md) for the named pattern before inventing one.
4. Cross-cutting feature? [`docs/FEATURE-MAP.md`](../../../docs/FEATURE-MAP.md) lists every file involved — cheaper than grepping.

## Settled — do not re-choose

These are not open design questions. Extend them; don't start a parallel system.

- **Color** — hand-built `LightColorScheme` / `DarkColorScheme` in `Theme.kt`, brand-fixed on FireOrange (light) / FsAccent (dark). **There is no dynamic color and that is deliberate** — never introduce `dynamicLightColorScheme` / `dynamicDarkColorScheme`. Use `MaterialTheme.colorScheme.*` tokens; a genuinely new brand color goes in `Color.kt` and gets a role in both schemes.
- **Dark-mode branching** — read `LocalIsDarkTheme` (`Theme.kt:19`), **never `isSystemInDarkTheme()`** inside a component. The app has a user-facing theme override, so the system flag and the active flag disagree; that gap is exactly why the CompositionLocal exists.
- **Typography** — Plus Jakarta Sans, full scale in `Type.kt` with explicit `lineHeight` on every style. Use the scale; don't set `fontFamily`/`fontSize` ad hoc in a composable.
- **Shape** — `Shapes` is 8 / 12 / 16 / 24dp (small/medium/large/extraLarge). Chat bubbles use the custom `BubbleTailShape` (`MessageBubble.kt`), not asymmetric corner rounding.

## Traps this repo has already paid for

- **Composable param-count ceiling (~15).** ART throws `VerifyError` **on first render**, not at compile time. Collapse callbacks into an `@Immutable *Callbacks` data class — see `MessageBubbleCallbacks` (`MessageBubble.kt:165`). Cost a chat-open crash (`00b15da`); `MessageBubbleSmokeTest` guards it now.
- **Local-vs-remote image model: synchronous `remember`, never `produceState`.** `remember(localUri) { File(it).takeIf { it.exists() && it.isFile && it.canRead() } }`. `produceState` renders one frame with the wrong source and left the cold-start spinner running (`ebd7b14`). `canRead()` matters: prior-install MediaStore files can exist and still EACCES.
- **IME insets.** Bottom-anchored screens need `adjustResize` **and** `Modifier.consumeWindowInsets(padding)` placed between `.padding(padding)` and `.imePadding()` (`a972533`). `ChatScreen` is the documented exception — it computes `imeOrPanelHeight` at measure time so the keyboard can slide over the always-mounted emoji panel; don't "simplify" it back to blanket `imePadding()`.
- **Chat lists use `reverseLayout = true` + `messages.asReversed()`** (`ChatScreen.kt`). Never reintroduce `snapshotFlow { ime }` → `scrollBy` coupling; that's the bug this replaced.
- **Freeze volatile list order in the presentation layer.** For lists that would reorder mid-interaction (emoji Recents), snapshot with `remember { list }` per open session and keep the flow live — no ViewModel `delay` debounce (`08fe2b1`).
- **`DateRangePicker` reports UTC midnights, not local days.** Re-anchor to the default zone before comparing against `Message.timestamp`; end-of-day is *next local midnight − 1ms*, not `+24h`. See `utcDayToLocalStart` / `utcDayToLocalEnd` in `ui/search/SearchFilterBar.kt`.
- **No literal `/` + `*` inside a KDoc** (e.g. a wildcard mime type) — Kotlin block comments nest and the file fails to compile pointing dozens of lines away. Say "a wildcard" in prose.

## Craft rules that still apply

- **Touch targets ≥ 48×48dp.** `Icon(Modifier.size(16.dp).clickable {})` gives a 16dp target — use `IconButton`, or center the icon in a 48dp `Box`.
- **Animate on the render thread.** `graphicsLayer { alpha = … }` over `Modifier.alpha(…)`; `Modifier.offset { IntOffset(x, 0) }` over `Modifier.offset(x.dp)` for scroll-linked motion. Never animate `size` or `padding` inside a list — it relayouts every frame.
- **Every `AnimatedVisibility` gets both `enter` and `exit`.** Bare fade reads as unfinished; `slideInVertically + fadeIn` reads as intentional. Reserve motion for meaningful state changes — screen entry, send/receive, success and error — not every transition.
- **Lazy lists need `key = { it.id }`.** Without keys Compose can't animate insert/remove and reuses composition wrongly. Use `contentPadding` for FAB clearance, never a trailing spacer item (it breaks `reverseLayout`).
- **`derivedStateOf` for scroll-derived booleans** so recomposition fires when the *result* flips, not on every offset change.
- **State hoisting + slot APIs.** Composables own only ephemeral UI state (focus, scroll); everything else comes from the ViewModel. Prefer a `@Composable () -> Unit` slot over another parameter — and note the param ceiling above makes this a correctness rule here, not just taste.
- **`modifier: Modifier = Modifier`** as the first optional parameter, applied to the outermost element.
- **Mark screen-private composables `internal`.**
- **`AsyncImage` needs `contentDescription`, a placeholder, and a locked height** (`Modifier.aspectRatio(...)` or a fixed size) or list rows jump as images resolve.
- **Prefer spacing, tonal surface, and type weight over dividers** for separation.

## Finishing

1. **Write a Robolectric Compose test** when the change carries logic or adds a composable variant — `app/src/test/`, `@RunWith(RobolectricTestRunner::class)` + `createComposeRule()`, `@Config(sdk = [29], application = android.app.Application::class)`. Canonical shape: `ui/chatlist/ChatListItemUiTest.kt`. There is no `androidTest/` source set. Skip tests for pure visual tweaks; a bug fix always gets its regression test first.
2. `./gradlew test` then `./gradlew assembleDebug` — both green before committing.
3. Commit immediately once green, code and tests together.
4. User-visible? Add a `CHANGELOG.md` entry under the `[UNRELEASED]` header and take the version bump (`changelog-release` skill).

If a UI decision here turns out to be genuinely new and reusable, it belongs in `docs/PATTERNS.md` with a pointer from `CLAUDE.md`; a host-independent trap belongs in `docs/GOTCHAS.md`. Not in this file, and not in local memory.
