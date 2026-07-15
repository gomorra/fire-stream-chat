package com.firestream.chat.ui.chat

/**
 * Decides when ChatScreen may compose the message list.
 *
 * The first population of the list is the heaviest frame the screen produces
 * (every visible bubble + `animateItem()` appearance animations). Composing it
 * while the navigation slide is still running drops frames mid-animation, so
 * the list waits for the enter transition to settle. Two exceptions:
 * - content is never hidden once it exists: [hadMessagesAtEntry] is true on
 *   pop-return (the ViewModel already holds messages) and bypasses the gate,
 * - while the repository is still loading there is nothing to compose anyway.
 *
 * The caller must treat a `true` result as sticky — the transition leaves its
 * settled state again when the screen exits, and the list must not swap back
 * to a spinner mid-exit.
 */
internal fun shouldComposeMessageList(
    isLoading: Boolean,
    enterTransitionSettled: Boolean,
    hadMessagesAtEntry: Boolean,
): Boolean = !isLoading && (enterTransitionSettled || hadMessagesAtEntry)
