package com.firestream.chat.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class ScrollPos(val chatId: String, val index: Int, val offset: Int)

enum class AppTheme { SYSTEM, LIGHT, DARK }

enum class AutoDownloadOption { WIFI_ONLY, ALWAYS, NEVER }

enum class NotificationSound { DEFAULT, SILENT }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fire_stream_prefs")

@Singleton
class PreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Appearance
    private val themeKey = stringPreferencesKey("app_theme")

    // Privacy
    private val readReceiptsKey = booleanPreferencesKey("read_receipts")
    private val lastSeenKey = booleanPreferencesKey("last_seen_visible")
    private val screenSecurityKey = booleanPreferencesKey("screen_security")

    // Notifications
    private val messageNotificationsKey = booleanPreferencesKey("message_notifications")
    private val groupNotificationsKey = booleanPreferencesKey("group_notifications")
    private val notificationSoundKey = stringPreferencesKey("notification_sound")
    private val vibrationKey = booleanPreferencesKey("vibration")

    // Mention-only notifications (group chats)
    private val mentionOnlyNotificationsKey = booleanPreferencesKey("mention_only_notifications")

    // Storage
    private val autoDownloadKey = stringPreferencesKey("auto_download")
    private val sendImagesFullQualityKey = booleanPreferencesKey("send_images_full_quality")

    // Emoji recents
    private val recentEmojisKey = stringPreferencesKey("recent_emojis")

    // Lists sort option
    private val listSortOptionKey = stringPreferencesKey("list_sort_option")

    // Pinned list IDs
    private val pinnedListIdsKey = stringPreferencesKey("pinned_list_ids")

    // Last open chat (restore on launch)
    private val lastChatIdKey = stringPreferencesKey("last_chat_id")
    private val lastRecipientIdKey = stringPreferencesKey("last_recipient_id")

    // Last bottom-nav tab (restore across relaunches)
    private val lastTabIndexKey = intPreferencesKey("last_tab_index")

    // Last chat scroll position (restore across process death)
    private val lastChatScrollChatIdKey = stringPreferencesKey("last_chat_scroll_chatid")
    private val lastChatScrollIndexKey = intPreferencesKey("last_chat_scroll_index")
    private val lastChatScrollOffsetKey = intPreferencesKey("last_chat_scroll_offset")

    // Last open list detail
    private val lastOpenListIdKey = stringPreferencesKey("last_open_list_id")

    // --- Theme ---

    val appThemeFlow: Flow<AppTheme> = context.dataStore.data.map { prefs ->
        runCatching { AppTheme.valueOf(prefs[themeKey] ?: AppTheme.SYSTEM.name) }
            .getOrDefault(AppTheme.SYSTEM)
    }

    suspend fun setAppTheme(theme: AppTheme) {
        context.dataStore.edit { prefs ->
            prefs[themeKey] = theme.name
        }
    }

    // --- Privacy ---

    val readReceiptsFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[readReceiptsKey] ?: true
    }

    suspend fun setReadReceipts(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[readReceiptsKey] = enabled }
    }

    val lastSeenVisibleFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[lastSeenKey] ?: true
    }

    suspend fun setLastSeenVisible(visible: Boolean) {
        context.dataStore.edit { prefs -> prefs[lastSeenKey] = visible }
    }

    val screenSecurityFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[screenSecurityKey] ?: false
    }

    suspend fun setScreenSecurity(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[screenSecurityKey] = enabled }
    }

    // --- Notifications ---

    val messageNotificationsFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[messageNotificationsKey] ?: true
    }

    suspend fun setMessageNotifications(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[messageNotificationsKey] = enabled }
    }

    val groupNotificationsFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[groupNotificationsKey] ?: true
    }

    suspend fun setGroupNotifications(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[groupNotificationsKey] = enabled }
    }

    val notificationSoundFlow: Flow<NotificationSound> = context.dataStore.data.map { prefs ->
        runCatching { NotificationSound.valueOf(prefs[notificationSoundKey] ?: NotificationSound.DEFAULT.name) }
            .getOrDefault(NotificationSound.DEFAULT)
    }

    suspend fun setNotificationSound(sound: NotificationSound) {
        context.dataStore.edit { prefs -> prefs[notificationSoundKey] = sound.name }
    }

    val vibrationFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[vibrationKey] ?: true
    }

    suspend fun setVibration(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[vibrationKey] = enabled }
    }

    // --- Mention-only notifications ---

    val mentionOnlyNotificationsFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[mentionOnlyNotificationsKey] ?: false
    }

    suspend fun setMentionOnlyNotifications(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[mentionOnlyNotificationsKey] = enabled }
    }

    // --- Storage ---

    val autoDownloadFlow: Flow<AutoDownloadOption> = context.dataStore.data.map { prefs ->
        runCatching { AutoDownloadOption.valueOf(prefs[autoDownloadKey] ?: AutoDownloadOption.WIFI_ONLY.name) }
            .getOrDefault(AutoDownloadOption.WIFI_ONLY)
    }

    suspend fun setAutoDownload(option: AutoDownloadOption) {
        context.dataStore.edit { prefs -> prefs[autoDownloadKey] = option.name }
    }

    val sendImagesFullQualityFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[sendImagesFullQualityKey] ?: false
    }

    suspend fun setSendImagesFullQuality(fullQuality: Boolean) {
        context.dataStore.edit { prefs -> prefs[sendImagesFullQualityKey] = fullQuality }
    }

    // --- Emoji recents ---

    val recentEmojisFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[recentEmojisKey] ?: return@map emptyList()
        raw.split(",").filter { it.isNotEmpty() }
    }

    // --- Last open chat ---

    val lastChatIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[lastChatIdKey]
    }

    val lastRecipientIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[lastRecipientIdKey]
    }

    suspend fun setLastOpenChat(chatId: String, recipientId: String) {
        context.dataStore.edit { prefs ->
            prefs[lastChatIdKey] = chatId
            prefs[lastRecipientIdKey] = recipientId
        }
    }

    suspend fun clearLastOpenChat() {
        context.dataStore.edit { prefs ->
            prefs.remove(lastChatIdKey)
            prefs.remove(lastRecipientIdKey)
            prefs.remove(lastChatScrollChatIdKey)
            prefs.remove(lastChatScrollIndexKey)
            prefs.remove(lastChatScrollOffsetKey)
        }
    }

    // --- Last chat scroll position ---
    // The chatId fence guards against restoring a stale offset into the wrong chat
    // if the user switches chats before the offset write for the previous chat lands.

    val lastChatScrollFlow: Flow<ScrollPos?> = context.dataStore.data.map { prefs ->
        val chatId = prefs[lastChatScrollChatIdKey] ?: return@map null
        val index = prefs[lastChatScrollIndexKey] ?: return@map null
        val offset = prefs[lastChatScrollOffsetKey] ?: 0
        ScrollPos(chatId, index, offset)
    }

    suspend fun setLastChatScroll(chatId: String, index: Int, offset: Int) {
        context.dataStore.edit { prefs ->
            prefs[lastChatScrollChatIdKey] = chatId
            prefs[lastChatScrollIndexKey] = index
            prefs[lastChatScrollOffsetKey] = offset
        }
    }

    // --- Last open list detail ---

    val lastOpenListIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[lastOpenListIdKey]
    }

    suspend fun setLastOpenListId(id: String) {
        context.dataStore.edit { prefs -> prefs[lastOpenListIdKey] = id }
    }

    suspend fun clearLastOpenListId() {
        context.dataStore.edit { prefs -> prefs.remove(lastOpenListIdKey) }
    }

    // --- Last bottom-nav tab ---

    val lastTabIndexFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[lastTabIndexKey] ?: 0).coerceIn(0, 2)
    }

    suspend fun setLastTabIndex(index: Int) {
        context.dataStore.edit { prefs -> prefs[lastTabIndexKey] = index.coerceIn(0, 2) }
    }

    // --- Lists sort ---

    val listSortOptionFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[listSortOptionKey] ?: "MODIFIED"
    }

    suspend fun setListSortOption(option: String) {
        context.dataStore.edit { prefs -> prefs[listSortOptionKey] = option }
    }

    // --- Pinned lists ---

    val pinnedListIdsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[pinnedListIdsKey] ?: return@map emptySet()
        raw.split(",").filter { it.isNotEmpty() }.toSet()
    }

    suspend fun setPinnedListIds(ids: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[pinnedListIdsKey] = ids.joinToString(",")
        }
    }

    suspend fun addRecentEmoji(emoji: String) {
        context.dataStore.edit { prefs ->
            val current = (prefs[recentEmojisKey] ?: "")
                .split(",").filter { it.isNotEmpty() }.toMutableList()
            current.remove(emoji)
            current.add(0, emoji)
            prefs[recentEmojisKey] = current.take(40).joinToString(",")
        }
    }
}
