package com.firestream.chat.navigation

/**
 * What the chat-list hub should do with the currently pending launch state.
 * Resolved by [resolveChatListPendingAction]; NavGraph's CHAT_LIST effect only
 * consumes the pending flags and performs the navigation side effects.
 */
internal sealed interface ChatListPendingAction {
    data object OpenSharePicker : ChatListPendingAction
    data class OpenSettings(val focusUpdate: Boolean) : ChatListPendingAction
    data class OpenChat(
        val chatId: String,
        val recipientId: String,
        val fromNotification: Boolean,
    ) : ChatListPendingAction
    data class OpenListDetail(val listId: String) : ChatListPendingAction
    data object ClearRestoreTarget : ChatListPendingAction
    data object None : ChatListPendingAction
}

/**
 * Priority: share > settings > chat > list detail > clear-restore-target.
 *
 * The clear is the subtle branch. The persisted last-open-chat (and its scroll
 * position) may only be cleared when the user is genuinely *resting* on the
 * chat list — it must never fire:
 * - before the launch restore decision has been made ([restoreDecisionComplete]
 *   false: the DataStore read may still be in flight, and clearing now would
 *   race it and wipe the restore target it is about to read), or
 * - during the exit-transition window after this chat-list entry already
 *   navigated somewhere ([navigatedFromThisEntry] true: the effect re-runs
 *   with consumed-null pendings while the screen animates out, and clearing
 *   then would wipe the scroll position the destination chat is about to
 *   restore).
 */
internal fun resolveChatListPendingAction(
    pendingShare: Boolean,
    pendingOpenSettings: Boolean,
    pendingFocusUpdate: Boolean,
    pendingChatId: String?,
    pendingSenderId: String?,
    pendingFromNotification: Boolean,
    pendingListId: String?,
    restoreDecisionComplete: Boolean,
    navigatedFromThisEntry: Boolean,
): ChatListPendingAction = when {
    pendingShare -> ChatListPendingAction.OpenSharePicker

    pendingOpenSettings -> ChatListPendingAction.OpenSettings(focusUpdate = pendingFocusUpdate)

    pendingChatId != null && pendingSenderId != null -> ChatListPendingAction.OpenChat(
        chatId = pendingChatId,
        recipientId = pendingSenderId,
        fromNotification = pendingFromNotification,
    )

    pendingListId != null -> ChatListPendingAction.OpenListDetail(pendingListId)

    restoreDecisionComplete && !navigatedFromThisEntry -> ChatListPendingAction.ClearRestoreTarget

    else -> ChatListPendingAction.None
}
