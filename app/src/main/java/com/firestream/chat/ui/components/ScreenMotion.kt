package com.firestream.chat.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * The motion a screen arrives and leaves with: an iOS spring-style curve
 * (equivalent to `UIView.animate`'s default) over half a second, sliding in from
 * the end and back out the same way.
 *
 * It lives here rather than in `navigation/NavGraph.kt`, which owns the NavHost's
 * four transition lambdas, because a panel mounted *over* a screen has to move
 * exactly as one that was pushed — the chat picker is the same panel whether the
 * share intent routed to it or a long-press slid it over the conversation, and a
 * different curve or an added fade is the difference a user reads as two
 * different things. The bezier is allocated once at file init, not per
 * navigation.
 */
internal val ScreenSlideEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/** How long that slide takes. `NavGraph` runs a couple of heavier routes slower. */
internal const val SCREEN_SLIDE_DURATION_MS = 500

/** Sliding in from the end, as a pushed screen does. */
internal fun screenSlideIn(): EnterTransition = slideInHorizontally(
    initialOffsetX = { fullWidth -> fullWidth },
    animationSpec = tween(SCREEN_SLIDE_DURATION_MS, easing = ScreenSlideEasing),
)

/** Sliding back out the way it came. */
internal fun screenSlideOut(): ExitTransition = slideOutHorizontally(
    targetOffsetX = { fullWidth -> fullWidth },
    animationSpec = tween(SCREEN_SLIDE_DURATION_MS, easing = ScreenSlideEasing),
)
