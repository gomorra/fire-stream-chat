---
name: app-ui-design
description: Design polished, distinctive Android UI with Jetpack Compose and Material3. Use this skill when the user asks to build or improve an Android screen, Compose composable, Material3 component, mobile layout, app UI, chat bubble, message list, settings page, bottom sheet, dialog, navigation bar, top app bar, FAB, snackbar, chip, card, profile screen, onboarding flow, splash screen, dark mode theme, color scheme, typography system, shape system, Compose animation, transition, gesture interaction, swipe-to-dismiss, pull-to-refresh, or any other Android/Compose UI element. Also use for theme customization, density-aware layouts, system bar handling, touch target sizing, and visual hierarchy in mobile interfaces.
license: Complete terms in LICENSE.txt
---

This skill guides creation of distinctive, production-grade Android UI using Jetpack Compose and Material3. Implement real working Kotlin/Compose code with exceptional attention to aesthetic details and platform-native feel.

The user provides Android UI requirements: a screen, component, or visual system to build or improve. They may include context about the app's purpose, audience, or existing theme.

## Mobile Design Thinking

Before coding, commit to a clear aesthetic direction:

- **Platform identity**: Material3 is the foundation, not the ceiling. Use its color system and motion choreography as scaffolding, then push past defaults with custom shapes, type scales, and tonal surfaces.
- **Touch-first**: Every interactive element must be at least 48×48dp. Thumb zones matter — primary actions belong in the bottom 40% of the screen. Never make users stretch.
- **Information density**: Mobile screens are small and tall. Use vertical rhythm deliberately. Group related content with spacing rather than dividers. Visual hierarchy (weight, size, color) beats structural chrome.
- **Context and tone**: A messaging app should feel warm and immediate. A finance app should feel precise and calm. A fitness app should feel energetic. Match the aesthetic to the emotional job.
- **Differentiation**: What makes this screen UNFORGETTABLE? A distinctive shape language, an unexpected color moment, a motion that feels alive — pick one thing and nail it.

**CRITICAL**: Choose a clear conceptual direction and execute it with precision. Polished restraint and expressive richness both work — the key is intentionality.

## Color & Theme

- **Dominant accent**: One strong brand color drives the palette. Everything else supports it. Use `MaterialTheme.colorScheme` tokens throughout — never hardcode hex values in composables.
- **Dark mode as first-class**: Design for both light and dark simultaneously. Use `dynamicColorScheme` where available; provide a handcrafted fallback `darkColorScheme` that isn't just inverted light colors. Surface hierarchy (`surface` → `surfaceVariant` → `surfaceContainerHigh`) is more nuanced in dark mode.
- **Semantic tokens over raw colors**: `onPrimary` for text on primary containers, `outline` for borders, `error` for destructive actions. Reserve custom colors for brand moments.
- **Surface tinting**: M3's `surfaceTint` gives depth without shadows. Use `tonalElevation` on `Card` and `Surface` to layer content naturally.
- **Custom ColorScheme extension**: Add brand-specific colors via a `CompositionLocal` or extension property rather than parallel theming systems.

```kotlin
// Extending the color scheme for brand colors
val ColorScheme.brandFire: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFF6B35) else Color(0xFFE84E0E)
```

## Typography

- **Weight hierarchy**: Display/Headline for hero moments, Title for section leaders, Body for reading, Label for metadata and chips. Don't collapse these tiers.
- **Size tiers**: Headline sizes 28–32sp for primary screen titles, Body 14–16sp, Label 11–12sp. Stay within the M3 type scale unless there's a strong reason to deviate.
- **Custom font pairing**: Use a distinctive display font for headings and a refined sans-serif for body. Load via `FontFamily` with `Font()` resources. Avoid system-default Roboto for brand-facing text.
- **Line height and spacing**: Set `lineHeight` explicitly on `TextStyle` — Compose defaults are tight. 1.4–1.5× line height for body, 1.1× for headlines.
- **Emoji sizing**: When emojis appear in text, they inherit text size. For standalone emoji display, use a dedicated composable with explicit `fontSize` — never rely on line height to contain them.

## Shape & Surface

- **Consistent corner system**: Adopt one shape scale app-wide: small=8dp (chips, text fields), medium=12dp (cards, dialogs), large=16dp (bottom sheets), extraLarge=24dp (full-screen panels). Deviate intentionally, not accidentally.
- **Asymmetric shapes for chat bubbles**: Sent messages use `RoundedCornerShape(topStart=16.dp, topEnd=16.dp, bottomStart=16.dp, bottomEnd=4.dp)`; received messages mirror it. This detail signals platform craft.
- **`clip` vs `background`**: Use `Modifier.clip(shape).background(color)` — not `background(color, shape)` — when the shape must clip child content (images, ripples). Use `background(color, shape)` only for purely decorative fills.
- **Surface vs Box**: Prefer `Surface` when you need elevation, tonal color, or click semantics. Use `Box` for pure layout composition without visual treatment.

## Animation & Motion

- **Enter/exit pairs**: Every `AnimatedVisibility` needs both an `enter` and `exit` spec. Default fade looks unfinished; `slideInVertically + fadeIn` feels intentional.
- **State-driven transitions**: Use `animateFloatAsState`, `animateColorAsState`, `animateDpAsState` for property transitions. Wrap related properties in `updateTransition` when they must animate together.
- **Infinite for status indicators**: `rememberInfiniteTransition` for pulse, shimmer, typing indicators. Always cancel via `LaunchedEffect` lifecycle, not manually.
- **Gesture-driven**: `Modifier.pointerInput` + `detectDragGestures` for swipe-to-dismiss, `AnchoredDraggable` for bottom sheets. Pair with `animateFloatAsState(targetValue, spring())` for physically realistic snapping.
- **Performance guardrails**:
  - Animate `graphicsLayer { alpha = ... }` not `Modifier.alpha(...)` — stays on the render thread
  - Use offset lambda `Modifier.offset { IntOffset(x, 0) }` not `Modifier.offset(x.dp)` for scroll-linked animations
  - Avoid animating `size` or `padding` in tight lists — it triggers layout passes on every frame

```kotlin
// Correct: render-thread animation
val alpha by animateFloatAsState(if (visible) 1f else 0f)
Box(Modifier.graphicsLayer { this.alpha = alpha })

// Avoid in lists: triggers layout
Box(Modifier.alpha(alpha))
```

## Component Design Patterns

- **State hoisting**: Push all state up to the ViewModel or caller. Composables own only ephemeral UI state (focus, scroll position). This makes components testable and reusable.
- **Slot APIs**: For complex components, expose `leadingContent: (@Composable () -> Unit)? = null` slots rather than proliferating parameters. Mirrors how `TopAppBar` and `ListItem` work in M3.
- **`internal` visibility**: Mark composables that are screen-private as `internal`. Only expose what crosses module boundaries. Prevents accidental reuse that creates coupling.
- **Modifier convention**: Always accept `modifier: Modifier = Modifier` as the first non-required parameter. Apply it to the outermost layout element, not an inner one. Never merge external modifiers with internal layout modifiers using `then` in the wrong order.
- **`remember` and `derivedStateOf`**: Wrap expensive computations in `remember(key)`. Use `derivedStateOf` when the derived value should only trigger recomposition when the _result_ changes, not when any input changes.

```kotlin
// Only recomposes when the derived boolean flips, not on every scroll offset change
val showFab by remember { derivedStateOf { scrollState.value < 100 } }
```

## Lists & Scrolling

- **Item keys**: Always provide `key = { item.id }` in `LazyColumn`/`LazyRow`. Without keys, Compose can't animate insertions/removals and reuses composition incorrectly.
- **Content padding**: Use `contentPadding = PaddingValues(bottom = 80.dp)` on `LazyColumn` to prevent FAB overlap. Never add a spacer as the last item — it breaks `reverseLayout`.
- **Sticky headers**: `stickyHeader {}` blocks must be lightweight — they paint on every scroll frame.
- **Pull-to-refresh**: Use `PullToRefreshBox` (M3 1.3+) or `rememberPullToRefreshState`. Connect to ViewModel `isRefreshing` state via `LaunchedEffect`.

## Images & Media

- **Coil `AsyncImage`**: Always provide `contentDescription`, `contentScale = ContentScale.Crop` for avatars, and a `placeholder` + `error` drawable. Without placeholders, list items jump in height.
- **Aspect ratio**: Use `Modifier.aspectRatio(16f/9f)` on media containers so height is locked before the image loads. Prevents layout shift.
- **Avatar pattern**: Clip to `CircleShape`, provide a fallback `Box` with the user's initial letter on `MaterialTheme.colorScheme.primaryContainer` when the image URL is null.

## Anti-Patterns to Avoid

- **Naked Material defaults**: `Button(onClick = {}) { Text("OK") }` ships as-is. Every default component needs at least shape, color, or typography customization to feel owned.
- **Hardcoded colors**: `Color(0xFF...)` in composables breaks dark mode and theming. Use `MaterialTheme.colorScheme.*` or a `CompositionLocal`.
- **Tiny touch targets**: `Icon(modifier = Modifier.size(16.dp).clickable { })` — the tap target is 16dp. Wrap in a `Box(Modifier.size(48.dp))` with centered content, or use `IconButton`.
- **Pixel-perfect mindset**: Don't fight density. Use `dp` for layout, `sp` for text, `Dp.toPx()` only in `DrawScope` or `Modifier.layout`. Never hardcode px values.
- **Animation carpet-bombing**: Animating every state transition creates noise. Reserve motion for meaningful state changes: screen entry, success/error feedback, content loading.
- **Divider abuse**: A `HorizontalDivider` between every list item is visual clutter. Use spacing, tonal backgrounds, and typography weight to separate content instead.
- **Ignoring system UI**: Always use `WindowCompat.setDecorFitsSystemWindows(window, false)` and `Modifier.windowInsetsPadding(WindowInsets.systemBars)` or `imePadding()`. Keyboard overlap and status-bar color bugs are the most visible polish failures.

## Putting It Together

1. **Understand context**: app tone, user's emotional job, existing color/type constraints
2. **Pick one memorable direction**: shape language, motion personality, or color moment
3. **Design for dark mode from the start**: don't retrofit it
4. **Build components state-hoisted and slot-based** so they compose cleanly
5. **Animate sparingly and correctly**: render-thread path, meaningful triggers
6. **Check touch targets, insets, and keyboard handling** before calling it done

Remember: the difference between a generic Compose screen and a great one is usually three things: custom shape language, deliberate typography hierarchy, and one motion that feels alive. Execute those three with precision.
