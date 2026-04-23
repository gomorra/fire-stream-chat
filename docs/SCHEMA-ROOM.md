# Database Entity Schema (Room)

Local Room database schema — tables, columns, and relationships. Room is the single source of truth for the UI; Firestore and RTDB sync into it.

```mermaid
erDiagram
    users {
        String uid PK
        String phoneNumber
        String displayName
        String avatarUrl
        String statusText
        Long lastSeen
        Boolean isOnline
        String publicIdentityKey
        Boolean readReceiptsEnabled
    }

    chats {
        String id PK
        String type
        String name
        String avatarUrl
        Long createdAt
        String createdBy
        String admins
        String owner
        Boolean isPinned
        Boolean isArchived
        Long muteUntil
        String description
        String inviteLink
        Boolean requireApproval
        String pendingMembers
        String permissions
        String lastMessageId
        String lastMessageContent
        Long lastMessageTimestamp
    }

    messages {
        String id PK
        String chatId FK
        String senderId FK
        String content
        String type
        String status
        String mediaUrl
        String mediaThumbnailUrl
        String localUri
        Int mediaWidth
        Int mediaHeight
        Long timestamp
        Long editedAt
        Boolean isStarred
        Boolean isForwarded
        Boolean isPinned
        String reactionsJSON
        String replyToId
        Int duration
        String readByJSON
        String deliveredToJSON
        String pollDataJSON
        String mentionsJSON
        Long deletedAt
        String emojiSizesJSON
        String listId
        String listDiffJSON
    }

    contacts {
        String uid PK
        String phoneNumber
        String displayName
        String avatarUrl
        Boolean isRegistered
    }

    lists {
        String id PK
        String title
        String type
        String createdBy
        Long createdAt
        Long updatedAt
        String participantsJSON
        String itemsJSON
        String sharedChatIdsJSON
        String genericStyle
    }

    signal_identities {
        String address PK
        String identityKey
        String direction
        String verifiedStatus
    }

    signal_sessions {
        String address PK
        String deviceId PK
        String sessionRecord
    }

    signal_prekeys {
        Int preKeyId PK
        String preKeyRecord
    }

    signal_signed_prekeys {
        Int signedPreKeyId PK
        String signedPreKeyRecord
    }

    signal_kyber_prekeys {
        Int kyberPreKeyId PK
        String kyberPreKeyRecord
        Boolean isLastResort
    }

    signal_sender_keys {
        String distributionId PK
        String address PK
        String senderKeyRecord
    }

    chats ||--o{ messages : "contains"
    users ||--o{ messages : "sends"
    users ||--o{ contacts : "has"
    users ||--o{ lists : "owns"
```

_The seven Signal tables (`signal_identities`, `signal_sessions`, `signal_prekeys`, `signal_signed_prekeys`, `signal_kyber_prekeys`, `signal_sender_keys`, `signal_trusted_identities`) preserve the persistent cryptographic state required by the Signal Protocol, including post-quantum Kyber pre-keys._

---

**See also:** [SCHEMA-FIRESTORE.md](SCHEMA-FIRESTORE.md) (remote source of truth), [DOMAIN-MODELS.md](DOMAIN-MODELS.md) (the Kotlin data classes these rows map to), [ARCHITECTURE.md](ARCHITECTURE.md) (overall architecture).
