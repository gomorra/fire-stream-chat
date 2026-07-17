package com.firestream.chat.ui.chat

/**
 * Decides when ChatScreen may show the message area (list or empty state).
 *
 * Content-first: the list appears as soon as the messages have loaded — it is
 * never held back for a navigation transition. The only extra wait beyond
 * [isLoading] is the cross-process persisted scroll read, and only on the
 * process-death restore path: the initial scroll target must be known BEFORE
 * the list first composes with data so the first visible frame is already at
 * the right position (no populate-then-jump). That read is prefetched in
 * ChatViewModel.init and resolves in parallel with the Room message load, so
 * in practice it never delays the reveal.
 *
 * Fast paths that skip the wait: notification opens always land on the newest
 * message, and a SavedStateHandle index (same-process re-entry) is synchronous.
 */
internal fun isChatContentReady(
    isLoading: Boolean,
    fromNotification: Boolean,
    hasSavedScrollIndex: Boolean,
    persistedScrollResolved: Boolean,
): Boolean = !isLoading && (fromNotification || hasSavedScrollIndex || persistedScrollResolved)
