package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.domain.model.User
import org.json.JSONObject

/**
 * Field-name remap between the PocketBase `users` schema and the [User]
 * domain model. Lives in one place so [PocketBaseAuthSource.getUserDocument]
 * (which still produces a Firestore-shaped Map<String, Any> for the
 * AuthRepository surface) and [PocketBaseUserSource] stay in sync.
 *
 * PB → domain mappings:
 *   phone        → phoneNumber
 *   name         → displayName
 *   avatar_url   → avatarUrl
 *   status_text  → statusText
 *
 * `lastSeen`/`isOnline`/`publicIdentityKey` are not stored on the `users`
 * record in v0 — `lastSeen` and `isOnline` come from the `presence` collection
 * via [PocketBasePresenceSource.observeOnlineStatus]; `publicIdentityKey` is
 * empty because the pocketbase flavor has Signal disabled.
 */
internal object PbUserMapper {

    fun fromRecord(record: JSONObject): User = User(
        uid = record.optString("id"),
        phoneNumber = record.optString("phone"),
        displayName = record.optString("name"),
        avatarUrl = record.optString("avatar_url").takeIf { it.isNotEmpty() },
        statusText = record.optString("status_text").ifEmpty { "Hey there! I'm using FireStream" },
        readReceiptsEnabled = true
    )

    /** Translate a domain-shaped update map (Firestore field names) to the PB schema. */
    fun toPbUpdates(updates: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(updates.size)
        for ((k, v) in updates) {
            val pbKey = when (k) {
                "phoneNumber" -> "phone"
                "displayName" -> "name"
                "avatarUrl" -> "avatar_url"
                "statusText" -> "status_text"
                "fcmToken" -> "fcm_token"
                // Drop fields the v0 schema doesn't have (isOnline, lastSeen,
                // publicIdentityKey, readReceiptsEnabled, localAvatarPath).
                "isOnline", "lastSeen", "publicIdentityKey",
                "readReceiptsEnabled", "localAvatarPath" -> continue
                else -> k
            }
            out[pbKey] = v
        }
        return out
    }
}
