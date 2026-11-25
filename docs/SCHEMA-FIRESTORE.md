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
├── kyberPreKeyId, kyberPreKeyPublic, kyberPreKeySig
└── oneTimePreKeys/{preKeyId}                   # subcollection (owner-only in firestore.rules;
    └── preKeyPublic, createdAt                 # popped atomically by claimPreKeyBundle Cloud Function)

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
    ├── senderId, type, status, timestamp, isForwarded
    ├── ciphertext, signalType                  # 1:1 E2EE (firebase release): Signal-encrypted v1 MessageEnvelope JSON
    ├── editCiphertext, editSignalType          # 1:1 E2EE edits: Signal-encrypted MessageEnvelope(kind = EDIT)
    ├── content                                 # plaintext only (groups, broadcasts, debug builds, poll/list/call/timer)
    ├── mediaUrl, mediaThumbnailUrl, mediaWidth, mediaHeight   # plaintext only (hidden inside ciphertext for 1:1 E2EE)
    ├── fileName, fileSize, mimeType            # plaintext only (hidden inside ciphertext for 1:1 E2EE)
    ├── stickerId, stickerPackId                # plaintext only (hidden inside ciphertext for 1:1 E2EE;
    │                                           # blob still lives in shared stickers/<sha256>.<ext>;
    │                                           # Klipy URLs on static/static1/static2.klipy.com have no stickerId)
    ├── localUri, localThumbUri                 # NOT synced to Firestore — Room only
    ├── replyToId, isPinned, duration, isHd     # replyToId / duration / isHd hidden inside ciphertext for 1:1 E2EE
    ├── editedAt, deletedAt
    ├── reactions: { userId → emoji }
    ├── mentions[]                              # plaintext only (hidden inside ciphertext for 1:1 E2EE)
    ├── readBy: { userId → timestamp }
    ├── deliveredTo: { userId → timestamp }
    ├── emojiSizes: { charIndex → multiplier }  # plaintext only (hidden inside ciphertext for 1:1 E2EE)
    ├── pollData: { options[], isMultipleChoice, isClosed }
    ├── listId, listDiff: { added[], removed[], checked[], ... }
    ├── latitude, longitude                     # plaintext only (hidden inside ciphertext for 1:1 E2EE)
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
├── autoNameAllowed                            # whether the naming service may be asked about the pack's stickers; absent in an older manifest, then derived from importKey
└── stickers[]                                 # in pack order, at most 4000
    └── { id, format, width, height, animated, emojis[], names[], autoNameAllowed }
                                               # id = SHA-256 of the file; no url. names: at most 8 of 40 characters.
                                               # names and autoNameAllowed are absent in an older manifest.
                                               # What the naming service found is not in a manifest.

stickerNames/{stickerId}                       # one sticker's automatic name; id = SHA-256 of the sticker's file
├── status                                     # "pending" (a request) | "done" (answered) | "skipped" (the service cannot name it)
├── format                                     # the sticker's format, "WEBP". The service reads stickers/<id>.<ext> by it
├── requestedAt                                # server time of the request
├── nameEn, nameDe                             # done only: at most 80 characters each
├── words[]                                    # done only: search words in both languages, at most 16 of 40 characters
├── text                                       # done only: the text on the sticker, at most 128 characters, or ""
├── emojis[]                                   # done only: at most 3
├── model, namedAt                             # done only: written by the service, not read by the app
└── reason                                     # skipped only
```

### Realtime Database

```
/presence/{userId}
├── isOnline: Boolean
└── lastSeen: Long                             # ServerValue.TIMESTAMP via onDisconnect()
```

The RTDB presence path uses the `.info/connected` pattern: on connect, set `isOnline = true`; register `onDisconnect()` to set `isOnline = false` and `lastSeen = ServerValue.TIMESTAMP`. The `syncPresenceToFirestore` Cloud Function mirrors RTDB writes to Firestore `users/{userId}` with a `lastSeen` transaction guard to reject out-of-order invocations.

### Firebase Storage Layout

Access is enforced by `storage.rules` (deployed alongside `firestore.rules` via `firebase.json`):

```
avatars/{uid}/profile.jpg                       # signed-in get; owner create/update/delete (< 10 MB, image/*)
avatars/groups/{chatId}/group.jpg               # signed-in get; chat participant create/update (< 10 MB, image/*)
media/{chatId}/{storageId}.{ext|enc}            # chat participant get/create/update (< 100 MB); no client delete
                                                # 1:1 E2EE blobs are chunked AES-256-GCM .enc (application/octet-stream)
stickers/{sha256}.{webp|tgs}                    # signed-in get/create-only (< 2 MB); no listing, update, or delete
backups/{uid}/                                  # strictly owner-only (request.auth.uid == uid)
├── manifest.json                               # application/json (< 10 MB); outer header + AAD-bound encryptedMetaB64
├── db_<timestamp>_<hex>.enc                    # application/octet-stream (< 512 MB); gzipped & encrypted AppDatabase
└── legacy-media/{keyGen}/{messageId}.enc       # application/octet-stream (< 100 MB); pre-E2EE media, keyGen = 16-hex SHA-256(salt)
```

### Key Patterns

- **Array mutations** (`participants`, `admins`, `pendingMembers`, `sharedChatIds`): Use `FieldValue.arrayUnion()` / `arrayRemove()` for atomic updates.
- **Per-key maps** (`typingUsers`, `unreadCounts`, `readBy`, `deliveredTo`, a call's `media`): Updated via `FieldValue` dot-notation paths (e.g., `"unreadCounts.$userId"`, `"media.$userId.camera"`).
- **Encryption duality**: 1:1 messages in `firebase` release builds are always Signal-encrypted (`ciphertext` + `signalType` carrying a v1 `MessageEnvelope`, plus `editCiphertext` + `editSignalType` for edits) and omit plaintext `content` and metadata. Their chat preview carries only a generic type placeholder (`"Message"`, `"📷 Photo"`, etc.). Group, broadcast, debug-build, and structured (`POLL`/`LIST`/`CALL`/`TIMER`) messages write plaintext `content`. Never both.
- **List items live in a subcollection.** Each item mutation is a single-doc write under `lists/{listId}/items/{itemId}`; `itemCount` / `checkedCount` on the parent metadata doc are kept in sync via `FieldValue.increment()` in the same batch. A one-shot `migrateEmbeddedItemsIfNeeded` upgrade runs on first observe for legacy lists that still carry an embedded `items[]` array.
- **`callVideoLine` is a capability, written only as `true`.** An app that can take a video line in a call offer writes it when it starts and when an existing user signs in (`AuthRepository.announceCallVideoLine`). A new user document carries it from its creation. A caller reads the callee's field before it creates the call document (`CallRepository.createCall`) and offers the video line only on a stored `true`. Never write `false` and never remove the field: an app from before video calls crashes on an offer with a video line.
- **`firestore.rules` lists what a call document may hold.** A call is created with `video` or without it, and never with `media`. An update may change only the writer's own entry under `media`, as two booleans. `video` never changes. A new field needs the rules changed and deployed before an app writes it. Tests: `firestore-rules-tests/calls.test.js`.
- **A call's `media` is written only once both sides agreed on the video line.** An app from before video calls that placed the call applies the answer again on every change of an answered call document, and ends the call when that fails (`CallMediaPublisher`).
- **Subcollection isolation**: `blockedUsers`, `messages`, `items`, `history`, `callerCandidates`/`calleeCandidates` are subcollections — they don't appear in parent document reads.
- **A call document holds only the fields `firestore.rules` lists.** Only the caller and the callee read a call or its ICE candidates. An update may change only `status`, `endReason`, `endedAt`, `offer` and `answer`. Only the caller writes `offer` and `callerCandidates`, and only the callee writes `answer` and `calleeCandidates`. The status only moves forward: `ringing`, then `answered` or `declined`, then `ended`. A new field needs a rules change, a test in `firestore-rules-tests/`, and a rules deploy before the app version that writes it ships.
- **A sticker pack manifest is a backup and a share at once.** `stickerPacks/{packId}` is written by `StickerSyncWorker` for every pack of its owner, the favourites and the loose stickers included. Any signed-in user may `get` one by id, which is how **View pack** reads the pack a received sticker names. Only the owner may list or write (`firestore.rules`). A manifest names its stickers by hash and holds no url: a file is fetched from the Storage object `stickers/<id>.<ext>` and checked against that hash.
- **A sticker's automatic name belongs to the sticker, not to a user.** `stickerNames/{stickerId}` is one document per sticker file. A phone asks by creating it with `status: "pending"`, `format` and `requestedAt`, and nothing else: no user, no pack, nothing a person typed. The naming service answers with the Admin SDK. Every phone that holds the sticker reads the same answer, so a sticker is named once. A phone reads one document at a time and looks before it asks (`StickerNameSync`). `firestore.rules` allows a signed-in user a `get` and that one `create`. It refuses every `list`, because a sticker id is all it takes to fetch the sticker's file, and every `update` and `delete`, so no client can forge an answer. The app checks an answer before it stores it (`StickerNameAnswer`): one field over a limit drops all of it. Tests: `firestore-rules-tests/stickerNames.test.js`.

---

**See also:** [SCHEMA-ROOM.md](SCHEMA-ROOM.md) (local cache), [DOMAIN-MODELS.md](DOMAIN-MODELS.md) (Kotlin shape of these records), [CLOUD-FUNCTIONS.md](CLOUD-FUNCTIONS.md) (server triggers on these collections), [ARCHITECTURE.md](ARCHITECTURE.md) (overall architecture).
