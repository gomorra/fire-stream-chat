package com.firestream.chat.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.firestream.chat.domain.model.ChatFontSize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ScrollPos(val chatId: String, val index: Int, val offset: Int)

enum class AppTheme { SYSTEM, LIGHT, DARK }

enum class AutoDownloadOption { WIFI_ONLY, ALWAYS, NEVER }

enum class VideoQualityOption(val targetHeight: Int) { DATA_SAVER(480), STANDARD(720), HIGH(1080) }

enum class NotificationSound { DEFAULT, SILENT }

enum class DictationLanguage(val tag: String) { GERMAN("de-DE"), ENGLISH("en-US") }

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
    private val e2eEncryptionKey = booleanPreferencesKey("e2e_encryption_enabled")

    // Notifications
    private val messageNotificationsKey = booleanPreferencesKey("message_notifications")
    private val groupNotificationsKey = booleanPreferencesKey("group_notifications")
    private val notificationSoundKey = stringPreferencesKey("notification_sound")
    private val vibrationKey = booleanPreferencesKey("vibration")

    // The user's "Not now" on the prompt for the full-screen notification access
    private val fullScreenAccessPromptDismissedKey = booleanPreferencesKey("full_screen_access_prompt_dismissed")

    // Mention-only notifications (group chats)
    private val mentionOnlyNotificationsKey = booleanPreferencesKey("mention_only_notifications")

    // Storage
    private val autoDownloadKey = stringPreferencesKey("auto_download")
    private val sendImagesFullQualityKey = booleanPreferencesKey("send_images_full_quality")
    private val keepOriginalImagesKey = booleanPreferencesKey("keep_original_images")
    private val videoQualityKey = stringPreferencesKey("video_quality")

    // App updates
    private val autoDownloadUpdatesKey = booleanPreferencesKey("auto_download_updates")

    // Chat
    private val dictationLanguageKey = stringPreferencesKey("dictation_language")
    private val chatFontSizeKey = floatPreferencesKey("chat_font_size_sp")

    // Emoji recents
    private val recentEmojisKey = stringPreferencesKey("recent_emojis")

    // Sticker recents
    private val recentStickerIdsKey = stringPreferencesKey("recent_sticker_ids")

    // Stickers deleted from the library
    private val deletedStickerIdsKey = stringSetPreferencesKey("deleted_sticker_ids")

    // The WhatsApp sticker folder at the last import
    private val whatsAppImportedUntilKey = longPreferencesKey("whatsapp_stickers_imported_until")

    // Klipy
    private val klipyCustomerIdKey = stringPreferencesKey("klipy_customer_id")
    private val klipyNoticeAcceptedKey = booleanPreferencesKey("klipy_notice_accepted")

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

    // Debug builds ignore this — MessageWriter's BuildConfig.DEBUG build gate
    // forces plaintext regardless.
    val e2eEncryptionEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[e2eEncryptionKey] ?: false
    }

    suspend fun setE2eEncryptionEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[e2eEncryptionKey] = enabled }
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

    // --- Full-screen notification access ---

    /** True once the user said *Not now* to the prompt. The prompt then stays away for good. */
    val fullScreenAccessPromptDismissedFlow: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[fullScreenAccessPromptDismissedKey] ?: false }
        .distinctUntilChanged()

    suspend fun setFullScreenAccessPromptDismissed() {
        context.dataStore.edit { prefs -> prefs[fullScreenAccessPromptDismissedKey] = true }
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

    /**
     * Whether the local copy of an image this user sends is the untouched input
     * rather than the encoding that went to the backend. Independent of HD: HD
     * decides what the recipient gets, this what stays on the phone — a photo
     * taken with the in-app camera has no other copy anywhere.
     */
    val keepOriginalImagesFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keepOriginalImagesKey] ?: false
    }

    suspend fun setKeepOriginalImages(keep: Boolean) {
        context.dataStore.edit { prefs -> prefs[keepOriginalImagesKey] = keep }
    }

    val videoQualityFlow: Flow<VideoQualityOption> = context.dataStore.data.map { prefs ->
        runCatching { VideoQualityOption.valueOf(prefs[videoQualityKey] ?: VideoQualityOption.STANDARD.name) }
            .getOrDefault(VideoQualityOption.STANDARD)
    }

    suspend fun setVideoQuality(option: VideoQualityOption) {
        context.dataStore.edit { prefs -> prefs[videoQualityKey] = option.name }
    }

    // --- App updates ---

    // Opt-in: when on and the device is on an unmetered (Wi-Fi) network, a newer
    // release APK downloads automatically in the background. Defaults off so
    // existing users keep the notify-only behavior.
    val autoDownloadUpdatesFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[autoDownloadUpdatesKey] ?: false
    }

    suspend fun setAutoDownloadUpdates(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[autoDownloadUpdatesKey] = enabled }
    }

    // --- Chat ---

    val dictationLanguageFlow: Flow<DictationLanguage> = context.dataStore.data.map { prefs ->
        runCatching { DictationLanguage.valueOf(prefs[dictationLanguageKey] ?: DictationLanguage.GERMAN.name) }
            .getOrDefault(DictationLanguage.GERMAN)
    }

    suspend fun setDictationLanguage(language: DictationLanguage) {
        context.dataStore.edit { prefs -> prefs[dictationLanguageKey] = language.name }
    }

    val chatFontSizeFlow: Flow<Float> = context.dataStore.data.map { prefs ->
        (prefs[chatFontSizeKey] ?: ChatFontSize.DEFAULT_SP)
            .coerceIn(ChatFontSize.MIN_SP, ChatFontSize.MAX_SP)
    }

    suspend fun setChatFontSize(sizeSp: Float) {
        context.dataStore.edit { prefs ->
            prefs[chatFontSizeKey] = sizeSp.coerceIn(ChatFontSize.MIN_SP, ChatFontSize.MAX_SP)
        }
    }

    // --- Emoji recents ---

    val recentEmojisFlow: Flow<List<String>> = recentsFlow(recentEmojisKey)

    /** A most-recent-first list kept as one comma-joined string, so no value may hold a comma. */
    private fun recentsFlow(key: Preferences.Key<String>): Flow<List<String>> = context.dataStore.data.map { it.recents(key) }

    private fun Preferences.recents(key: Preferences.Key<String>): List<String> =
        this[key]?.split(",")?.filter { it.isNotEmpty() }.orEmpty()

    /** Moves [value] to the front of the list under [key] and keeps the newest [cap]. */
    private suspend fun pushRecent(key: Preferences.Key<String>, value: String, cap: Int) {
        context.dataStore.edit { prefs ->
            prefs[key] = (listOf(value) + (prefs.recents(key) - value)).take(cap).joinToString(",")
        }
    }

    // --- Sticker recents ---

    /** Ids of the stickers sent most recently, newest first. Device-only. */
    val recentStickerIdsFlow: Flow<List<String>> = recentsFlow(recentStickerIdsKey)

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

    suspend fun addRecentEmoji(emoji: String) = pushRecent(recentEmojisKey, emoji, cap = 40)

    suspend fun addRecentSticker(stickerId: String) = pushRecent(recentStickerIdsKey, stickerId, cap = 30)

    /**
     * Forgets the sticker recents, the deleted stickers and the mark of the last
     * WhatsApp import. They are the signed-in user's, and the next user's
     * library may hold the same stickers, or none of the folder's.
     */
    suspend fun clearStickerLists() {
        context.dataStore.edit { prefs ->
            prefs.remove(recentStickerIdsKey)
            prefs.remove(deletedStickerIdsKey)
            prefs.remove(whatsAppImportedUntilKey)
        }
    }

    // --- The WhatsApp sticker folder at the last import ---

    /**
     * The newest `lastModified` the WhatsApp sticker folder held when an import
     * from it last finished, or 0 before the first one. A file newer than this
     * is new to the user. Device-only.
     */
    suspend fun whatsAppImportedUntil(): Long = context.dataStore.data.first()[whatsAppImportedUntilKey] ?: 0L

    /** Moves the mark forward to [lastModified]. It never moves back. */
    suspend fun markWhatsAppImported(lastModified: Long) {
        context.dataStore.edit { prefs ->
            if (lastModified > (prefs[whatsAppImportedUntilKey] ?: 0L)) prefs[whatsAppImportedUntilKey] = lastModified
        }
    }

    // --- Stickers deleted from the library ---

    /**
     * The ids of the stickers the user deleted from the library. A WhatsApp
     * import leaves them out. Device-only, and not a Room table: a version bump
     * would empty it.
     */
    suspend fun deletedStickerIds(): Set<String> = context.dataStore.data.first()[deletedStickerIdsKey].orEmpty()

    /** Remembers [stickerIds] as deleted and takes them out of the sticker recents. */
    suspend fun rememberDeletedStickers(stickerIds: Collection<String>) {
        if (stickerIds.isEmpty()) return
        val ids = stickerIds.toSet()
        context.dataStore.edit { prefs ->
            prefs[deletedStickerIdsKey] = prefs[deletedStickerIdsKey].orEmpty() + ids
            val recents = prefs.recents(recentStickerIdsKey)
            if (recents.any { it in ids }) prefs[recentStickerIdsKey] = (recents - ids).joinToString(",")
        }
    }

    /** Forgets that [stickerIds] were deleted. For a sticker that was added again on purpose. */
    suspend fun forgetDeletedStickers(stickerIds: Collection<String>) {
        if (stickerIds.isEmpty()) return
        val ids = stickerIds.toSet()
        context.dataStore.edit { prefs ->
            val deleted = prefs[deletedStickerIdsKey].orEmpty()
            if (deleted.any { it in ids }) prefs[deletedStickerIdsKey] = deleted - ids
        }
    }

    // --- Klipy ---

    /**
     * The id Klipy's requests carry as `customer_id`: a random UUID, made on
     * first use. It is never the account's uid or anything derived from it.
     */
    suspend fun klipyCustomerId(): String {
        // Read first: every request asks, and only the first one has to write.
        context.dataStore.data.first()[klipyCustomerIdKey]?.let { return it }
        var id = ""
        context.dataStore.edit { prefs ->
            id = prefs[klipyCustomerIdKey] ?: UUID.randomUUID().toString().also { prefs[klipyCustomerIdKey] = it }
        }
        return id
    }

    /** Forgets the Klipy id, so the next user of this device gets one of their own. */
    suspend fun clearKlipyCustomerId() {
        context.dataStore.edit { prefs -> prefs.remove(klipyCustomerIdKey) }
    }

    /** Whether the first-use notice of the GIFs tab and the online stickers was accepted on this device. */
    val klipyNoticeAcceptedFlow: Flow<Boolean> =
        context.dataStore.data.map { it[klipyNoticeAcceptedKey] ?: false }.distinctUntilChanged()

    suspend fun setKlipyNoticeAccepted() {
        context.dataStore.edit { prefs -> prefs[klipyNoticeAcceptedKey] = true }
    }
}
