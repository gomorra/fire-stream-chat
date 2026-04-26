# Firestore & Realtime Database Schema

The backend data model lives in Firebase. Room ([SCHEMA-ROOM.md](SCHEMA-ROOM.md)) caches a subset locally; Firestore is the authoritative remote store.

### Firestore Collections

```
users/{userId}
├── uid, phoneNumber, displayName, avatarUrl, statusText
├── lastSeen, isOnline                          # mirrored from RTDB by Cloud Function
├── publicIdentityKey                           # Signal Protocol identity key
├── readReceiptsEnabled                         # privacy control
├── fcmToken                                    # push notification token
└── blockedUsers/{targetUserId}                 # subcollection
    └── blockedAt

keyBundles/{userId}                             # Signal Protocol pre-key bundles
├── registrationId, identityKey
├── signedPreKeyId, signedPreKeyPublic, signedPreKeySig
├── preKeyId, preKeyPublic
└── kyberPreKeyId, kyberPreKeyPublic, kyberPreKeySig

chats/{chatId}
├── type                                        # "INDIVIDUAL" | "GROUP" | "BROADCAST"
├── name, avatarUrl, description                # group metadata
├── participants[]                              # array of user IDs
├── admins[], owner, createdBy, createdAt
├── inviteLink, requireApproval, pendingMembers[]
├── typingUsers: { userId → timestamp }         # per-key atomic updates
├── unreadCounts: { userId → count }            # incremented by Cloud Function
├── lastMessageContent, lastMessageTimestamp, lastMessageSenderId
├── permissions                                 # embedded GroupPermissions object
│   ├── sendMessages, editGroupInfo, addMembers, createPolls
│   └── isAnnouncementMode
└── messages/{messageId}                        # subcollection
    ├── senderId, content, type, status, timestamp
    ├── ciphertext, signalType                  # present when E2E encrypted (release builds)
    ├── mediaUrl, mediaThumbnailUrl, mediaWidth, mediaHeight
    ├── localUri                                # NOT synced to Firestore — Room only
    ├── replyToId, isForwarded, isPinned, duration
    ├── editedAt, deletedAt
    ├── reactions: { emoji → ? }
    ├── mentions[]
    ├── readBy: { userId → timestamp }
    ├── deliveredTo: { userId → timestamp }
    ├── emojiSizes: { charIndex → multiplier }
    ├── pollData: { options[], isMultipleChoice, isClosed }
    ├── listId, listDiff: { added[], removed[], checked[], ... }
    └── latitude, longitude                     # LOCATION messages

calls/{callId}
├── callerId, calleeId, status, createdAt, endedAt, endReason
├── offer: { sdp, type }                       # WebRTC SDP offer
├── answer: { sdp, type }                      # WebRTC SDP answer
├── callerCandidates/{id}                      # subcollection — ICE candidates
│   └── sdpMid, sdpMLineIndex, sdp
└── calleeCandidates/{id}                      # subcollection — ICE candidates
    └── sdpMid, sdpMLineIndex, sdp

lists/{listId}
├── title, type, genericStyle
├── createdBy, createdAt, updatedAt
├── participants[]
├── sharedChatIds[]                            # which chats this list is shared into
├── itemCount, checkedCount                    # denormalized counts for the Lists tab
├── items/{itemId}                             # subcollection — one doc per list item
│   └── id, text, isChecked, quantity, unit, order, addedBy
└── history/{entryId}                          # subcollection — audit trail
    └── action, itemId, itemText, userId, userName, timestamp

inviteLinks/{token}
└── chatId, createdAt, createdBy               # maps invite token → chat
```

### Realtime Database

```
/presence/{userId}
├── isOnline: Boolean
└── lastSeen: Long                             # ServerValue.TIMESTAMP via onDisconnect()
```

The RTDB presence path uses the `.info/connected` pattern: on connect, set `isOnline = true`; register `onDisconnect()` to set `isOnline = false` and `lastSeen = ServerValue.TIMESTAMP`. The `syncPresenceToFirestore` Cloud Function mirrors RTDB writes to Firestore `users/{userId}` with a `lastSeen` transaction guard to reject out-of-order invocations.

### Key Patterns

- **Array mutations** (`participants`, `admins`, `pendingMembers`, `sharedChatIds`): Use `FieldValue.arrayUnion()` / `arrayRemove()` for atomic updates.
- **Per-key maps** (`typingUsers`, `unreadCounts`, `readBy`, `deliveredTo`): Updated via `FieldValue` dot-notation paths (e.g., `"unreadCounts.$userId"`).
- **Encryption duality**: Messages store either `content` (plaintext — debug builds, or release builds where the user has opted out) or `ciphertext` + `signalType` (Signal-encrypted, release builds with E2E enabled). Never both.
- **List items live in a subcollection.** Each item mutation is a single-doc write under `lists/{listId}/items/{itemId}`; `itemCount` / `checkedCount` on the parent metadata doc are kept in sync via `FieldValue.increment()` in the same batch. A one-shot `migrateEmbeddedItemsIfNeeded` upgrade runs on first observe for legacy lists that still carry an embedded `items[]` array.
- **Subcollection isolation**: `blockedUsers`, `messages`, `items`, `history`, `callerCandidates`/`calleeCandidates` are subcollections — they don't appear in parent document reads.

---

**See also:** [SCHEMA-ROOM.md](SCHEMA-ROOM.md) (local cache), [DOMAIN-MODELS.md](DOMAIN-MODELS.md) (Kotlin shape of these records), [CLOUD-FUNCTIONS.md](CLOUD-FUNCTIONS.md) (server triggers on these collections), [ARCHITECTURE.md](ARCHITECTURE.md) (overall architecture).
