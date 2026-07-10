package com.firestream.chat.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListPendingActionTest {

    private fun resolve(
        pendingShare: Boolean = false,
        pendingOpenSettings: Boolean = false,
        pendingFocusUpdate: Boolean = false,
        pendingChatId: String? = null,
        pendingSenderId: String? = null,
        pendingFromNotification: Boolean = false,
        pendingListId: String? = null,
        restoreDecisionComplete: Boolean = true,
        navigatedFromThisEntry: Boolean = false,
    ) = resolveChatListPendingAction(
        pendingShare = pendingShare,
        pendingOpenSettings = pendingOpenSettings,
        pendingFocusUpdate = pendingFocusUpdate,
        pendingChatId = pendingChatId,
        pendingSenderId = pendingSenderId,
        pendingFromNotification = pendingFromNotification,
        pendingListId = pendingListId,
        restoreDecisionComplete = restoreDecisionComplete,
        navigatedFromThisEntry = navigatedFromThisEntry,
    )

    @Test
    fun `share wins over everything`() {
        val action = resolve(
            pendingShare = true,
            pendingOpenSettings = true,
            pendingChatId = "c1",
            pendingSenderId = "r1",
            pendingListId = "l1",
        )
        assertEquals(ChatListPendingAction.OpenSharePicker, action)
    }

    @Test
    fun `settings wins over chat and list`() {
        val action = resolve(
            pendingOpenSettings = true,
            pendingFocusUpdate = true,
            pendingChatId = "c1",
            pendingSenderId = "r1",
            pendingListId = "l1",
        )
        assertEquals(ChatListPendingAction.OpenSettings(focusUpdate = true), action)
    }

    @Test
    fun `pending chat wins over list and is never a clear`() {
        // Regression (defect A): the old code cleared the restore target on
        // every chat-list composition — including the cold-start pass-through
        // that was about to navigate into the restored chat, wiping the saved
        // scroll position and the target itself.
        val action = resolve(
            pendingChatId = "c1",
            pendingSenderId = "r1",
            pendingFromNotification = true,
            pendingListId = "l1",
        )
        assertEquals(
            ChatListPendingAction.OpenChat(chatId = "c1", recipientId = "r1", fromNotification = true),
            action,
        )
    }

    @Test
    fun `list detail when no chat is pending`() {
        val action = resolve(pendingListId = "l1")
        assertEquals(ChatListPendingAction.OpenListDetail("l1"), action)
    }

    @Test
    fun `no clear while the restore decision is still in flight`() {
        // Regression (race): with the DataStore read still pending, the old
        // code could clear the restore target before the read completed, so
        // nothing ever restored.
        val action = resolve(restoreDecisionComplete = false)
        assertEquals(ChatListPendingAction.None, action)
    }

    @Test
    fun `no clear during the exit-transition window after navigating away`() {
        // After consuming a pending chat the effect re-runs with nulled keys
        // while the chat list is still composed during the exit animation —
        // clearing then would wipe the scroll keys the chat is about to read.
        val action = resolve(navigatedFromThisEntry = true)
        assertEquals(ChatListPendingAction.None, action)
    }

    @Test
    fun `clear fires when genuinely resting on the list`() {
        val action = resolve()
        assertEquals(ChatListPendingAction.ClearRestoreTarget, action)
    }

    @Test
    fun `partial chat ids fall through to list detail`() {
        val action = resolve(pendingChatId = "c1", pendingSenderId = null, pendingListId = "l1")
        assertEquals(ChatListPendingAction.OpenListDetail("l1"), action)
    }

    @Test
    fun `partial chat ids with nothing else pending fall through to clear`() {
        val action = resolve(pendingChatId = "c1", pendingSenderId = null)
        assertEquals(ChatListPendingAction.ClearRestoreTarget, action)
    }
}
