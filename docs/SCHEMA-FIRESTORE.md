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
├── callVideoLine                               # true = this user's app takes a video line in a call offer; absent = it does not
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
└── messages/{messageId}                        # subcollection; id = client UUID (the Room row id)
    │                                           # for retryable sends, Firestore auto-id for poll/list/call/timer
    ├── senderId, content, type, status, timestamp
    ├── ciphertext, signalType                  # present when E2E encrypted (release builds)
    ├── mediaUrl, mediaThumbnailUrl, mediaWidth, mediaHeight
    ├── fileName, fileSize, mimeType            # DOCUMENT — plaintext, like mediaUrl (docs/plans/file-handling.md).
    │                                           # A STICKER and a GIF carry mimeType alone
    ├── stickerId, stickerPackId                # STICKER only — the SHA-256 of the sticker's bytes, and the pack it
    │                                           # was sent from (absent for favourites and loose stickers). Plaintext.
    │                                           # mediaUrl then points at the shared Storage object stickers/<id>.<ext>
    │                                           # A GIF or STICKER picked from Klipy has a mediaUrl on static.klipy.com,
    │                                           # static1.klipy.com or static2.klipy.com, and no stickerId. Nothing of it
    │                                           # is in Storage, and no device keeps a copy (KlipyUrls.isMedia)
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
    ├── latitude, longitude                     # LOCATION messages
    └── video                                   # CALL only — the call was started as video; absent = voice

calls/{callId}
├── callerId, calleeId, status, createdAt, endedAt, endReason
├── video                                      # how the call was started; absent = voice (an older app)
├── media: { userId → { camera, mic } }        # live state, each user writes their own entry; absent = camera off, mic on
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

stickerPacks/{packId}                          # one sticker pack's manifest; id = the pack's random UUID
├── ownerId                                    # the only user who may list or write it
├── name, publisher, kind                      # kind: "USER" | "INSTALLED" | "FAVOURITES" | "SAVED"
├── originPackId                               # INSTALLED only: the pack it was first copied from
├── importKey                                  # percent-encoded: its parts are joined by U+0000
├── sortOrder, createdAt, updatedAt            # updatedAt decides which side is newer on a restore
├── shownInRow                                 # own thumbnail in the picker's row; absent in an older manifest, then derived from importKey
└── stickers[]                                 # in pack order, at most 4000
    └── { id, format, width, height, animated, emojis[] }   # id = SHA-256 of the file; no url
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
- **Per-key maps** (`typingUsers`, `unreadCounts`, `readBy`, `deliveredTo`, a call's `media`): Updated via `FieldValue` dot-notation paths (e.g., `"unreadCounts.$userId"`, `"media.$userId.camera"`).
- **Encryption duality**: Messages store either `content` (plaintext — debug builds, or release builds where the user has opted out) or `ciphertext` + `signalType` (Signal-encrypted, release builds with E2E enabled). Never both.
- **List items live in a subcollection.** Each item mutation is a single-doc write under `lists/{listId}/items/{itemId}`; `itemCount` / `checkedCount` on the parent metadata doc are kept in sync via `FieldValue.increment()` in the same batch. A one-shot `migrateEmbeddedItemsIfNeeded` upgrade runs on first observe for legacy lists that still carry an embedded `items[]` array.
- **`callVideoLine` is a capability, written only as `true`.** An app that can take a video line in a call offer writes it when it starts and when an existing user signs in (`AuthRepository.announceCallVideoLine`). A new user document carries it from its creation. A caller reads the callee's field before it creates the call document (`CallRepository.createCall`) and offers the video line only on a stored `true`. Never write `false` and never remove the field: an app from before video calls crashes on an offer with a video line.
- **`firestore.rules` lists what a call document may hold.** A call is created with `video` or without it, and never with `media`. An update may change only the writer's own entry under `media`, as two booleans. `video` never changes. A new field needs the rules changed and deployed before an app writes it. Tests: `firestore-rules-tests/calls.test.js`.
- **A call's `media` is written only once both sides agreed on the video line.** An app from before video calls that placed the call applies the answer again on every change of an answered call document, and ends the call when that fails (`CallMediaPublisher`).
- **Subcollection isolation**: `blockedUsers`, `messages`, `items`, `history`, `callerCandidates`/`calleeCandidates` are subcollections — they don't appear in parent document reads.
- **A call document holds only the fields `firestore.rules` lists.** Only the caller and the callee read a call or its ICE candidates. An update may change only `status`, `endReason`, `endedAt`, `offer` and `answer`. Only the caller writes `offer` and `callerCandidates`, and only the callee writes `answer` and `calleeCandidates`. The status only moves forward: `ringing`, then `answered` or `declined`, then `ended`. A new field needs a rules change, a test in `firestore-rules-tests/`, and a rules deploy before the app version that writes it ships.
- **A sticker pack manifest is a backup and a share at once.** `stickerPacks/{packId}` is written by `StickerSyncWorker` for every pack of its owner, the favourites and the loose stickers included. Any signed-in user may `get` one by id, which is how **View pack** reads the pack a received sticker names. Only the owner may list or write (`firestore.rules`). A manifest names its stickers by hash and holds no url: a file is fetched from the Storage object `stickers/<id>.<ext>` and checked against that hash.

---

**See also:** [SCHEMA-ROOM.md](SCHEMA-ROOM.md) (local cache), [DOMAIN-MODELS.md](DOMAIN-MODELS.md) (Kotlin shape of these records), [CLOUD-FUNCTIONS.md](CLOUD-FUNCTIONS.md) (server triggers on these collections), [ARCHITECTURE.md](ARCHITECTURE.md) (overall architecture).
