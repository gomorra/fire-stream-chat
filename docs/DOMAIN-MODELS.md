# Domain Models

Kotlin data classes in `domain/model/` — the framework-free core types used by repositories, use cases, and ViewModels.

### Chat

```kotlin
data class Chat(
    val id: String,
    val type: ChatType,          // INDIVIDUAL | GROUP | BROADCAST
    val name: String?,
    val avatarUrl: String?,
    val participants: List<String>,
    val lastMessage: Message?,
    val unreadCount: Int,
    val createdAt: Long,
    val createdBy: String?,
    val admins: List<String>,
    val typingUserIds: List<String>,
    // Organisation
    val isPinned: Boolean,
    val isArchived: Boolean,
    val muteUntil: Long,         // 0 = not muted, Long.MAX_VALUE = always muted
    // Group management
    val description: String?,
    val inviteLink: String?,
    val requireApproval: Boolean,
    val pendingMembers: List<String>,
    // Permissions
    val owner: String?,
    val permissions: GroupPermissions
)
```

### Message

```kotlin
data class Message(
    val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val type: MessageType,       // TEXT | IMAGE | VIDEO | VOICE | DOCUMENT | POLL | CALL | LIST | LOCATION
    val mediaUrl: String?,
    val mediaThumbnailUrl: String?,
    val localUri: String?,                   // local file path for media (offline-first)
    val mediaWidth: Int?,                    // original image width for aspect ratio
    val mediaHeight: Int?,                   // original image height for aspect ratio
    val status: MessageStatus,   // SENDING | SENT | DELIVERED | READ | FAILED
    val replyToId: String?,
    val timestamp: Long,
    val editedAt: Long?,
    val reactions: Map<String, String>,   // userId → emoji
    val isForwarded: Boolean,
    val duration: Int?,                   // voice message seconds
    val isStarred: Boolean,
    val readBy: Map<String, Long>,        // userId → timestamp (group chats)
    val deliveredTo: Map<String, Long>,   // userId → timestamp (group chats)
    val pollData: Poll?,
    val mentions: List<String>,           // userIds + "everyone"
    val deletedAt: Long?,
    val emojiSizes: Map<Int, Float>,       // character index → size multiplier (0.8–2.5)
    val listId: String?,                   // associated shared list ID
    val listDiff: ListDiff?,               // list mutation summary for LIST messages
    val isPinned: Boolean,
    val latitude: Double?,                // location sharing
    val longitude: Double?                // location sharing
)
```

### GroupRole / GroupPermissions

```kotlin
enum class GroupRole { OWNER, ADMIN, MEMBER }

data class GroupPermissions(
    val sendMessages: GroupRole = GroupRole.MEMBER,
    val editGroupInfo: GroupRole = GroupRole.ADMIN,
    val addMembers: GroupRole = GroupRole.ADMIN,
    val createPolls: GroupRole = GroupRole.MEMBER,
    val isAnnouncementMode: Boolean = false   // true = only admins can send
)
```

### Poll

```kotlin
data class Poll(
    val question: String,
    val options: List<PollOption>,
    val isMultipleChoice: Boolean,
    val isAnonymous: Boolean,
    val isClosed: Boolean
)

data class PollOption(
    val id: String,
    val text: String,
    val voterIds: List<String>
)
```

### ListDiff

```kotlin
data class ListDiff(
    val added: List<String>,
    val removed: List<String>,
    val checked: List<String>,
    val unchecked: List<String>,
    val edited: List<String>,
    val titleChanged: String?,
    val deleted: Boolean,
    val unshared: Boolean,            // list was unshared from a chat
    val shared: Boolean               // list was shared into a chat
)
```

### ListData / ListItem

```kotlin
enum class ListType { CHECKLIST, SHOPPING, GENERIC }
enum class GenericListStyle { BULLET, NUMBER, DASH, NONE }

data class ListItem(
    val id: String,
    val text: String,
    val isChecked: Boolean,
    val quantity: String?,
    val unit: String?,
    val order: Int,
    val addedBy: String
)

data class ListData(
    val id: String,
    val title: String,
    val type: ListType,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val participants: List<String>,
    val items: List<ListItem>,
    val sharedChatIds: List<String>,
    val genericStyle: GenericListStyle
)
```

### CallLogEntry

```kotlin
enum class CallDirection { OUTGOING, INCOMING, MISSED }

data class CallLogEntry(
    val callId: String,
    val remoteUserId: String,
    val remoteName: String,
    val remoteAvatarUrl: String?,
    val direction: CallDirection,
    val timestamp: Long,
    val durationSeconds: Int?
)
```

### CallState

```kotlin
sealed interface CallState {
    data object Idle : CallState
    data class OutgoingRinging(callId, calleeId, calleeName, calleeAvatarUrl) : CallState
    data class IncomingRinging(callId, callerId, callerName, callerAvatarUrl) : CallState
    data class Connecting(callId, remoteUserId, remoteName, remoteAvatarUrl) : CallState
    data class Connected(callId, remoteUserId, remoteName, remoteAvatarUrl, startTime) : CallState
    data class Ended(callId, reason: EndReason) : CallState
}

enum class EndReason { HANGUP, REMOTE_HANGUP, DECLINED, TIMEOUT, ERROR }

// In-call UI controls (exposed by CallStateHolder)
data class CallUiControls(val isMuted: Boolean, val isSpeakerOn: Boolean)
```

### SdpData / IceCandidateData / CallSignalingData

Defined in `domain/model/CallSignalingData.kt`:

```kotlin
data class SdpData(val sdp: String, val type: String)
data class IceCandidateData(val sdpMid: String, val sdpMLineIndex: Int, val sdp: String)
data class CallSignalingData(val callId: String, val callerId: String, val calleeId: String, val status: String)
```

---

**See also:** [SCHEMA-ROOM.md](SCHEMA-ROOM.md) (how these map to Room rows), [SCHEMA-FIRESTORE.md](SCHEMA-FIRESTORE.md) (how they map to Firestore docs), [ARCHITECTURE.md](ARCHITECTURE.md) (overall architecture).
