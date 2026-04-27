// region: AGENT-NOTE
// Responsibility: Firestore I/O for `chats/{chatId}` + `inviteLinks/{token}`
//   collections. Group metadata, participants, admins, owner, invite links,
//   pending-member approval, typing indicators, unread-count reads.
// Owns: Listener registrations on `chats/*`. Permission map serialisation.
// Collaborators: ChatRepositoryImpl (only caller).
// Don't put here: messages subcollection (FirestoreMessageSource),
//   per-user blocked list (FirestoreUserSource subcollection), call signalling
//   (FirestoreCallSource).
// endregion

package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.ChatSource
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.GroupPermissions
import com.firestream.chat.domain.model.GroupRole
import com.firestream.chat.domain.model.Message
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val COL_CHATS = "chats"
private const val COL_INVITE_LINKS = "inviteLinks"

/**
 * Firestore-backed data source for the `chats` and `inviteLinks` collections.
 *
 * Encapsulates every `firestore.collection("chats")...` call that was previously
 * scattered across `ChatRepositoryImpl` — the repository now only coordinates
 * Room, authentication, avatar caching, and exception handling. Document →
 * [Chat] mapping ([mapChat]) also lives here so domain-model construction
 * isn't split between the two layers.
 */
@Singleton
class FirestoreChatSource @Inject constructor(
    private val firestore: FirebaseFirestore
) : ChatSource {
    // ── Observers ────────────────────────────────────────────────────────────

    override fun observeChatsForUser(uid: String): Flow<List<Chat>> = callbackFlow {
        val listener: ListenerRegistration = firestore
            .collection(COL_CHATS)
            .whereArrayContains("participants", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Don't close on transient errors — just skip this emission.
                    // Closing would propagate an exception that kills callers.
                    return@addSnapshotListener
                }
                val chats = snapshot?.documents?.mapNotNull { doc ->
                    doc.data?.let { mapChat(doc.id, it, uid) }
                } ?: emptyList()
                trySend(chats)
            }
        awaitClose { listener.remove() }
    }

    override fun observeTypingUsers(chatId: String): Flow<List<String>> = callbackFlow {
        val listener: ListenerRegistration = firestore
            .collection(COL_CHATS).document(chatId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val typingUsers = (snapshot?.get("typingUsers") as? Map<*, *>)
                    ?.entries
                    ?.filter { (_, v) ->
                        val ts = v as? Long ?: return@filter false
                        System.currentTimeMillis() - ts < 10_000
                    }
                    ?.mapNotNull { it.key as? String }
                    ?: emptyList()
                trySend(typingUsers)
            }
        awaitClose { listener.remove() }
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    override suspend fun getChat(chatId: String, currentUid: String): Chat? {
        val doc = firestore.collection(COL_CHATS).document(chatId).get().await()
        val data = doc.data ?: return null
        return mapChat(doc.id, data, currentUid)
    }

    /** Finds an existing INDIVIDUAL chat for the given sorted participant pair. */
    override suspend fun findIndividualChat(participants: List<String>, currentUid: String): Chat? {
        val snapshot = firestore.collection(COL_CHATS)
            .whereEqualTo("type", ChatType.INDIVIDUAL.name)
            .whereEqualTo("participants", participants)
            .get().await()
        if (snapshot.isEmpty) return null
        val doc = snapshot.documents.first()
        val data = doc.data ?: return null
        return mapChat(doc.id, data, currentUid)
    }

    /** Returns the document id of the chat, or `null` if the link is invalid. */
    override suspend fun getChatIdForInviteToken(token: String): String? {
        val linkDoc = firestore.collection(COL_INVITE_LINKS).document(token).get().await()
        if (!linkDoc.exists()) return null
        return linkDoc.getString("chatId")
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /** Creates a new chat document and returns the generated doc id. */
    override suspend fun createChat(data: Map<String, Any?>): String {
        val docRef = firestore.collection(COL_CHATS).add(data).await()
        return docRef.id
    }

    override suspend fun updateChatFields(chatId: String, updates: Map<String, Any?>) {
        if (updates.isEmpty()) return
        firestore.collection(COL_CHATS).document(chatId).update(updates).await()
    }

    override suspend fun deleteChatDocument(chatId: String) {
        firestore.collection(COL_CHATS).document(chatId).delete().await()
    }

    override suspend fun addToArrayField(chatId: String, field: String, value: Any) {
        firestore.collection(COL_CHATS).document(chatId)
            .update(field, FieldValue.arrayUnion(value))
            .await()
    }

    override suspend fun removeFromArrayField(chatId: String, field: String, value: Any) {
        firestore.collection(COL_CHATS).document(chatId)
            .update(field, FieldValue.arrayRemove(value))
            .await()
    }

    /** Best-effort typing update — callers should catch and ignore failures. */
    override suspend fun setTyping(chatId: String, uid: String, isTyping: Boolean) {
        val update = if (isTyping) {
            mapOf("typingUsers.$uid" to System.currentTimeMillis())
        } else {
            mapOf("typingUsers.$uid" to FieldValue.delete())
        }
        firestore.collection(COL_CHATS).document(chatId).update(update).await()
    }

    override suspend fun resetUnreadCount(chatId: String, uid: String) {
        firestore.collection(COL_CHATS).document(chatId)
            .update("unreadCounts.$uid", 0)
            .await()
    }

    // ── Invite links ─────────────────────────────────────────────────────────

    override suspend fun setInviteLink(chatId: String, token: String, createdBy: String) {
        val batch = firestore.batch()
        batch.update(
            firestore.collection(COL_CHATS).document(chatId),
            "inviteLink", token
        )
        batch.set(
            firestore.collection(COL_INVITE_LINKS).document(token),
            mapOf(
                "chatId" to chatId,
                "createdAt" to System.currentTimeMillis(),
                "createdBy" to createdBy,
            )
        )
        batch.commit().await()
    }

    override suspend fun clearInviteLink(chatId: String, existingToken: String?) {
        val batch = firestore.batch()
        batch.update(
            firestore.collection(COL_CHATS).document(chatId),
            "inviteLink", FieldValue.delete()
        )
        if (existingToken != null) {
            batch.delete(firestore.collection(COL_INVITE_LINKS).document(existingToken))
        }
        batch.commit().await()
    }

    override suspend fun getExistingInviteToken(chatId: String): String? {
        val doc = firestore.collection(COL_CHATS).document(chatId).get().await()
        return doc.getString("inviteLink")
    }

    // ── Batched operations ───────────────────────────────────────────────────

    override suspend fun approveMember(chatId: String, userId: String) {
        val batch = firestore.batch()
        val chatRef = firestore.collection(COL_CHATS).document(chatId)
        batch.update(chatRef, "pendingMembers", FieldValue.arrayRemove(userId))
        batch.update(chatRef, "participants", FieldValue.arrayUnion(userId))
        batch.commit().await()
    }

    override suspend fun leaveGroupPromotingAdmin(chatId: String, leavingUid: String, newAdminUid: String) {
        val batch = firestore.batch()
        val chatRef = firestore.collection(COL_CHATS).document(chatId)
        batch.update(chatRef, "participants", FieldValue.arrayRemove(leavingUid))
        batch.update(chatRef, "admins", FieldValue.arrayRemove(leavingUid))
        batch.update(chatRef, "admins", FieldValue.arrayUnion(newAdminUid))
        batch.commit().await()
    }

    /**
     * Removes [leavingUid] from both `participants` and `admins` in a single
     * atomic update. For the ordinary non-last-admin path of [leaveGroup].
     */
    override suspend fun leaveGroupRemovingSelf(chatId: String, leavingUid: String) {
        firestore.collection(COL_CHATS).document(chatId).update(
            mapOf(
                "participants" to FieldValue.arrayRemove(leavingUid),
                "admins" to FieldValue.arrayRemove(leavingUid)
            )
        ).await()
    }

    override suspend fun transferOwnership(chatId: String, newOwnerId: String) {
        val batch = firestore.batch()
        val chatRef = firestore.collection(COL_CHATS).document(chatId)
        batch.update(chatRef, "owner", newOwnerId)
        batch.update(chatRef, "admins", FieldValue.arrayUnion(newOwnerId))
        batch.commit().await()
    }

    // ── Mapping (shared with repository) ─────────────────────────────────────

    fun mapChat(id: String, data: Map<String, Any?>, currentUserId: String): Chat {
        val lastMessageContent = data["lastMessageContent"] as? String
        val lastMessageTimestamp = data["lastMessageTimestamp"] as? Long
        val lastMessageSenderId = data["lastMessageSenderId"] as? String

        val lastMessage = if (lastMessageContent != null && lastMessageTimestamp != null && lastMessageSenderId != null) {
            Message(
                id = "",
                chatId = id,
                senderId = lastMessageSenderId,
                content = lastMessageContent,
                timestamp = lastMessageTimestamp
            )
        } else null

        val perUserUnread = (data["unreadCounts"] as? Map<*, *>)
            ?.get(currentUserId) as? Long

        val typingUserIds = (data["typingUsers"] as? Map<*, *>)
            ?.entries
            ?.filter { (_, v) ->
                val ts = v as? Long ?: return@filter false
                System.currentTimeMillis() - ts < 10_000
            }
            ?.mapNotNull { it.key as? String }
            ?: emptyList()

        return Chat(
            id = id,
            type = try {
                ChatType.valueOf(data["type"] as? String ?: "INDIVIDUAL")
            } catch (_: Exception) {
                ChatType.INDIVIDUAL
            },
            name = data["name"] as? String,
            avatarUrl = data["avatarUrl"] as? String,
            participants = (data["participants"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            lastMessage = lastMessage,
            unreadCount = perUserUnread?.toInt() ?: (data["unreadCount"] as? Long)?.toInt() ?: 0,
            createdAt = data["createdAt"] as? Long ?: 0L,
            createdBy = data["createdBy"] as? String,
            admins = (data["admins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            typingUserIds = typingUserIds,
            description = data["description"] as? String,
            inviteLink = data["inviteLink"] as? String,
            requireApproval = data["requireApproval"] as? Boolean ?: false,
            pendingMembers = (data["pendingMembers"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            owner = data["owner"] as? String,
            permissions = mapPermissions(data["permissions"])
        )
    }

    private fun mapPermissions(raw: Any?): GroupPermissions {
        val data = raw as? Map<*, *> ?: return GroupPermissions()
        return GroupPermissions(
            sendMessages = runCatching { GroupRole.valueOf(data["sendMessages"] as String) }.getOrDefault(GroupRole.MEMBER),
            editGroupInfo = runCatching { GroupRole.valueOf(data["editGroupInfo"] as String) }.getOrDefault(GroupRole.ADMIN),
            addMembers = runCatching { GroupRole.valueOf(data["addMembers"] as String) }.getOrDefault(GroupRole.ADMIN),
            createPolls = runCatching { GroupRole.valueOf(data["createPolls"] as String) }.getOrDefault(GroupRole.MEMBER),
            isAnnouncementMode = data["isAnnouncementMode"] as? Boolean ?: false
        )
    }
}
