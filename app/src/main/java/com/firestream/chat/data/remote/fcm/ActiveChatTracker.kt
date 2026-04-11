package com.firestream.chat.data.remote.fcm

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which chat (if any) the user is currently viewing in the foreground.
 * Used by [FCMService] to suppress notifications for the active chat — messages
 * arriving while the user is reading them should not vibrate, beep, or post
 * a heads-up notification.
 */
@Singleton
class ActiveChatTracker @Inject constructor() {

    private val activeChatId = AtomicReference<String?>(null)

    fun setActive(chatId: String) {
        activeChatId.set(chatId)
    }

    // Takes chatId to avoid a race when chat A closes after chat B has already
    // opened — only clears if the held id still matches.
    fun clearActive(chatId: String) {
        activeChatId.compareAndSet(chatId, null)
    }

    fun isActive(chatId: String): Boolean = activeChatId.get() == chatId
}
