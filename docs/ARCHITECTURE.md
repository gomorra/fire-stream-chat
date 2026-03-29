# FireStream Chat Spec and Architecture

This document provides a detailed specification and architectural overview of the **FireStream** application, a real-time messaging Android application built with modern Android development practices, end-to-end encryption, and a robust feature set resembling modern chat apps (e.g., WhatsApp, Signal).

## 1. Specification / Features

### Core Messaging

- **1-on-1 Chat**: Text messaging with real-time syncing.
- **Media Support**: Send and receive images, voice messages, and generic documents. Includes a fullscreen image viewer and voice media player with adjustable playback speed. Local-first media pipeline: images are compressed (`ImageCompressor`), stored locally (`MediaFileManager` at `filesDir/media/{chatId}/`), displayed immediately from local files, and uploaded to Firebase Storage with a progress overlay. `MediaBackfillWorker` runs on first launch to download existing media. Full quality opt-in via DataStore preference.
- **End-to-End Encryption (E2EE)**: All messages are encrypted natively on the client device using the **Signal Protocol** before transmission. Encryption is disabled in debug builds (`BuildConfig.DEBUG` guard) — messages are sent as plaintext to avoid key-loss issues during development.
- **Read Receipts Status**:
  - **Sent** (Single gray tick): Message reached the server.
  - **Delivered** (Double gray tick): Message reached the recipient's device.
  - **Read** (Double blue tick): Recipient opened and viewed the conversation.
  - **Privacy Control**: Users can disable read receipts (Bidirectional enforcement: if disabled by either user, both users see only up to the Delivered status).
- **Typing Indicators**: Real-time "typing..." status.

### Message Interactions

- **Reply**: Swipe-to-reply or long-press context menu to quote/reply to specific messages.
- **React**: Emoji reactions on messages (map of userId → emoji).
- **Forward**: Share messages to other active chats.
- **Star**: Bookmark messages.
- **Edit/Delete**: Edit a previously sent message or delete it entirely for both parties (soft-delete with `deletedAt` timestamp).
- **Message Info**: View exact delivery and read timestamps for participants.
- **Link Previews**: Automatic rich preview card generation for URLs included in messages.
- **Polls**: Create and vote on polls within group or individual chats. Supports multiple-choice, anonymous voting, and manual close.
- **Mentions**: `@username` and `@everyone` support in group chats. Mentioned users receive targeted notifications.

### Group Chats

- **Create Groups**: Multi-participant group chats with a name and avatar.
- **Group Roles**: Three-tier role system — `OWNER`, `ADMIN`, `MEMBER`.
- **Group Permissions**: Configurable per-chat policies controlling who can send messages, edit group info, add members, and create polls. Announcement Mode restricts sending to admins only.
- **Group Management**: Update group name, avatar, and description. Transfer ownership, promote/demote admins, remove members.
- **Invite Links & QR Codes**: Generate a shareable invite link and QR code for joining a group. Links can be revoked.
- **Member Approval**: Optionally require admin approval before new members join via invite link. Approval/rejection workflow for pending members.
- **Leave Group**: Any non-owner member can leave. Owners must transfer ownership first.

### Broadcast Lists

- **One-Way Messaging**: Send a message to multiple recipients simultaneously. Recipients receive the message as an individual chat — they cannot see other recipients.
- **Broadcast Chat Type**: Implemented as a distinct `ChatType.BROADCAST`. The chat list shows a campaign icon to distinguish broadcasts from regular chats.
- **Read Receipts Hidden**: No delivery/read tracking for broadcast messages.

### Voice Calls

- **1-to-1 Voice Calls**: Real-time audio calls using WebRTC.
- **Signaling**: Call state (ringing, connected, ended) is coordinated via Firestore. SDP offer/answer and ICE candidates are exchanged through dedicated call documents.
- **FCM Wake-Up**: Incoming calls trigger a high-priority FCM push notification so the callee's device wakes up even in the background.
- **Lock-Screen UI**: `CallActivity` is a separate Activity (not part of the NavHost) to support rendering on the lock screen.
- **In-Call Controls**: Mute microphone and toggle speakerphone.
- **Call Log**: Dedicated Calls tab (next to Chats in the bottom nav) shows a history of incoming, outgoing, and missed calls sourced from call-type messages in the Room database.

### Shared Lists

- **List Types**: Three list types — `CHECKLIST`, `SHOPPING`, and `GENERIC` (with bullet/number/dash/none style options).
- **List Items**: Each item has text, check state, optional quantity/unit fields, order index, and the ID of the user who added it.
- **Sharing**: Lists can be shared into one or more chats as a `LIST` message bubble. Recipients see the live list state and can interact with items.
- **Diff & Sync**: Client-side `ListDiff` accumulates mutations (add, update, reorder, check, remove, title change, type change). A 30-second debounce in `ListDetailViewModel` sends a single update bubble to all `sharedChatIds`.
- **History**: Every mutation is recorded via `ObserveListHistoryUseCase` / `FirestoreListHistorySource`. History is written fire-and-forget with a `SupervisorJob` scope so it never blocks the main flow.
- **Undo**: Removed items are filtered from the display via a `pendingRemoval` set; the actual `removeItem()` call fires only on snackbar dismiss.
- **Unshared Lists**: Lists that have been unshared from a chat show a locked-state indicator in the `ListBubble`.

### Organization & User Management

- **Local & Global Search**: Full-text search support to locate messages either within a specific conversation or globally across all chats.
- **Shared Media**: Dedicated screens in User Profile to browse shared images.
- **Online/Last Seen Presence**: Live presence backed by Firebase Realtime Database (`RealtimePresenceSource`). `AppLifecycleObserver` (process-level `DefaultLifecycleObserver` registered on `ProcessLifecycleOwner`) manages the online/offline transition — this eliminates false-offline flickers during Activity transitions (e.g. opening `CallActivity`, permission dialogs). `syncPresenceToFirestore` Cloud Function mirrors RTDB state to Firestore for abrupt-disconnect cases. Privacy controls exist to configure who can view the last seen status.
- **Profile Setup**: Phone-number authentication with profile creation (Display Name, Status Text, Avatar URL).
- **Blocking Mechanism**: Block/unblock users to prevent communication.
- **Share Intent**: External share intents (text or media) are routed through a `SharePickerScreen` so users can forward content into any chat.

---

## 2. Technology Stack

- **Platform**: Android
- **Language**: Kotlin
- **UI Toolkit**: Jetpack Compose
- **Architecture**: Clean Architecture + MVVM (Model-View-ViewModel) + UDF (Unidirectional Data Flow)
- **Dependency Injection**: Dagger Hilt
- **Local Database**: Room (SQLite) with Coroutines Flow for reactive updates
- **Preferences**: Jetpack DataStore (Preferences DataStore)
- **Backend Infrastructure**: Firebase Services
  - **Firestore**: Real-time NoSQL database for syncing encrypted payloads, user statuses, typing indicators, and call signaling.
  - **Firebase Authentication**: Phone authentication mechanism.
  - **Cloud Storage**: Hosting user avatars, images, and voice recordings.
  - **Cloud Functions**: Server-side triggers — push notifications on new messages (`sendPushNotification`) and incoming calls (`sendCallPushNotification`). Runtime: Node.js 20.
  - **Firebase Cloud Messaging (FCM)**: Reliable push notifications for background delivery wake-ups and incoming call alerts.
- **Cryptography**: `libsignal-android` for industry-standard Signal Protocol end-to-end encryption (including post-quantum Kyber pre-keys).
- **Real-Time Communication**: `stream-webrtc-android` for WebRTC-based voice calls.
- **Image Loading**: Coil
- **Concurrency**: Kotlin Coroutines & Flow

---

## 3. High-Level Architecture (Clean Architecture)

FireStream strictly adheres to Clean Architecture principles separating responsibilities into three distinct layers: **Domain**, **Data**, and **UI/Presentation**.

```mermaid
graph TD
    %% Define Layers
    subgraph UI_Layer [UI / Presentation Layer]
        UI[Jetpack Compose Screens]
        VM[ViewModels - UDF State Holders]
    end

    subgraph Domain_Layer [Domain Layer]
        UC[Use Cases - Business Logic]
        repoInt[Repository Interfaces]
        models[Domain Models - Entities]
    end

    subgraph Data_Layer [Data Layer]
        repoImpl[Repository Implementations]

        subgraph Local_Source [Local Data Sources]
            room[(Room Database)]
            dataStore(Preferences DataStore)
            signalStore(Signal Protocol Store)
        end

        subgraph Remote_Source [Remote Data Sources]
            firestore(Firebase Firestore)
            storage(Firebase Storage)
            auth(Firebase Auth)
            fcm(Firebase Cloud Messaging)
        end

        subgraph Call_Source [Call Infrastructure]
            webrtc(WebRTC Peer Connection)
            callService(CallService - Foreground)
            callState(CallStateHolder - Singleton)
        end
    end

    %% Define Relationships
    UI -->|Triggers Intent| VM
    VM -->|Observes State| UI

    VM -->|Executes| UC
    UC -->|Relies On| repoInt
    UC -->|Returns| models

    repoImpl -. Implements .-> repoInt
    repoImpl --> Local_Source
    repoImpl --> Remote_Source
    repoImpl --> Call_Source
```

### 3.1 Domain Layer

The most isolated layer, containing enterprise-wide and application-specific business logic.

- **Models**: 18 plain Kotlin data classes (`Message`, `User`, `Chat`, `Contact`, `Poll`, `PollOption`, `CallState`, `CallLogEntry`, `CallSignalingData`, `IceCandidateData`, `GroupPermissions`, `GroupRole`, `ListData`, `ListItem`, `ListDiff`, `ListHistoryEntry`, `HistoryAction`, `MediaAttachment`, `SharedContent`, `MessageStatus`, `MessageType`, `ChatType`). Extracted from framework-specific models (like Room Entities or Firestore Snapshots).
- **Repository Interfaces**: 8 abstractions (`AuthRepository`, `CallRepository`, `ChatRepository`, `ContactRepository`, `ListRepository`, `MessageRepository`, `PollRepository`, `UserRepository`) dictating what required data operations are available without knowing _how_ they're implemented.
- **Use Cases**: Single-responsibility executors organized into `chat/`, `list/`, and `message/` subdirectories: `CheckGroupPermissionUseCase`, `SendListUpdateToChatsUseCase`, `SearchMessagesUseCase`.

### 3.2 Data Layer

The concrete implementation resolving the Repository Interfaces.

- **Local Sources**: Room DB handles the reactive caching. The app primarily drives the UI from Room via `Flow`.
- **Remote Sources**: Firebase services. The repository layer typically observes Firestore, writes modifications to Room, and the UI reacts to the Room changes.
- **Crypto Sources**: `SignalManager` and `SignalProtocolStoreImpl` orchestrate key generation, pre-key bundles, and encryption/decryption cycles transparently to the upper layers.
- **Media Infrastructure**: `MediaFileManager` (@Singleton) manages local media storage at `filesDir/media/{chatId}/{messageId}.{ext}` and gallery export via MediaStore (`Pictures/FireStream`). `ImageCompressor` (@Singleton) provides EXIF-aware compression with `inSampleSize` for memory-safe decode (1600px/80% JPEG default, full quality opt-in via DataStore). `MediaBackfillWorker` (WorkManager) runs a one-time job on first launch to download existing media, respecting `AutoDownloadOption` and network constraints.
- **Call Infrastructure**: `CallService` (foreground service) owns the WebRTC peer connection lifecycle. `CallStateHolder` (@Singleton) bridges the service to the UI via `StateFlow`. `CallActivity` is a separate Android Activity (not a NavHost destination) for lock-screen support.

### 3.3 UI / Presentation Layer

- **ViewModels**: Maintain view state (`StateFlow` of `UiState` data classes). Handle user intents and translate UI actions into domain use case executions.
- **Jetpack Compose Screens**: Declarative, composable functions rendering UI strictly based on the provided immutable `UiState`.
- **ChatScreen** is split into 23 focused files (`MessageBubble`, `VoiceMessagePlayer`, `LinkPreviewCard`, `FullscreenImageViewer`, `ForwardChatPicker`, `EmojiHandlerPanel`, `EmojiSearchData`, `PollBubble`, `CreatePollSheet`, `ListBubble`, `CreateListSheet`, `SharedMediaScreen`, `SharedMediaViewModel`, `ChatUtils`, `MessageInfoScreen`, `ChatScreen`, `ChatViewModel`, plus 6 manager classes — `ChatPollManager`, `ChatSearchManager`, `ChatMessageActions`, `ChatMessageSender`, `ChatMessageLoader`, `ChatInfoManager`), all with `internal` visibility. `ChatViewModel` is a thin orchestrator (~220 lines) that constructs and delegates to the 6 managers; all managers share a single `MutableStateFlow<ChatUiState>` reference.
- **Bottom navigation**: `MainScreen` (`ui/main/`) hosts a `HorizontalPager` with three tabs — Chats, Calls, and Lists. `BottomNavBar` and the swipe gesture live exclusively in `MainScreen`; individual tab screens (`ChatListScreen`, `CallsScreen`, `ListsScreen`) do **not** own the nav bar. The `CHAT_LIST` NavHost route renders `MainScreen`; the Calls and Lists tabs are internal pager state, not NavHost destinations.

---

## 4. End-to-End Encryption Flow

The messaging pipeline uses the Signal Protocol. Below is the sequence describing how sending and receiving an encrypted message works.

```mermaid
sequenceDiagram
    autonumber
    actor Alice
    participant App_A as Alice's App
    participant Firestore
    participant FCM
    participant App_B as Bob's App
    actor Bob

    Alice->>App_A: Types msg & Hits Send
    App_A->>Firestore: Fetches Bob's PreKey Bundle (if session missing)
    App_A->>App_A: Encrypts message using Signal Protocol
    App_A->>App_A: Saves unencrypted msg to Local Room DB
    App_A->>Firestore: Uploads Encrypted Payload

    Firestore-->>FCM: Triggers Cloud Function
    FCM-->>App_B: Delivers High Priority Push Notification (Data payload)

    App_B->>Firestore: Fetches new encrypted payloads
    App_B->>App_B: Decrypts message using Signal Protocol
    App_B->>App_B: Saves unencrypted msg to Local Room DB
    App_B->>Firestore: Marks Message as "Delivered"

    Bob->>App_B: Opens Chat Screen
    App_B->>Firestore: Marks Message as "Read" (if receipts enabled)
```

> **Debug builds**: Encryption is bypassed — `MessageRepositoryImpl` calls `sendPlainMessage()` instead of the encrypted path. This avoids key-loss issues during development.

---

## 5. Voice Call Signaling Flow

Voice calls use WebRTC for media and Firestore for signaling.

```mermaid
sequenceDiagram
    autonumber
    actor Alice
    participant App_A as Alice's App
    participant Firestore
    participant FCM
    participant App_B as Bob's App
    actor Bob

    Alice->>App_A: Taps phone icon in ChatScreen
    App_A->>Firestore: Creates /calls/{callId} (status=ringing)
    Firestore-->>FCM: Triggers sendCallPushNotification
    FCM-->>App_B: High-priority FCM wakes device

    App_B->>App_B: Launches CallActivity (lock-screen)
    Bob->>App_B: Taps Answer
    App_B->>Firestore: Updates call status = answered

    App_A->>Firestore: Sends SDP Offer
    App_B->>Firestore: Sends SDP Answer
    App_A->>Firestore: Sends ICE Candidates
    App_B->>Firestore: Sends ICE Candidates

    App_A->>App_A: WebRTC Connected
    App_B->>App_B: WebRTC Connected

    Alice->>App_A: Taps End Call
    App_A->>Firestore: Updates call status = ended (HANGUP)
    App_B->>App_B: CallService teardown
```

### Call Architecture Details

- **`CallService`** (foreground service): Owns the `PeerConnection` lifecycle, ICE negotiation, and audio stream management.
- **`CallStateHolder`** (@Singleton): Exposes `StateFlow<CallState>` and `StateFlow<CallUiControls>`. Bridges `CallService` ↔ UI without binding to the service.
- **`CallActivity`** (separate Activity): Not a NavHost route. Launched via Intent. Supports lock-screen rendering.
- **`CallState`** (sealed interface): `Idle | OutgoingRinging | IncomingRinging | Connecting | Connected | Ended(EndReason)`.

---

## 6. Offline-First Data Synchronization

The application relies heavily on Room as the **Single Source of Truth**. The UI very rarely reads directly from Firestore; it reads from Room Dao `Flow` streams.

```mermaid
classDiagram
    class UI {
        +collect(messagesFlow)
    }
    class ViewModel {
        +val uiState: StateFlow
    }
    class UseCase {
        +execute(): Flow
    }
    class Repository {
        +getMessages(): Flow
    }
    class LocalDatabase {
        <<Room>>
        +getMessagesFlow()
        +insertOrUpdate()
    }
    class RemoteDatabase {
        <<Firestore>>
        +addSnapshotListener()
    }

    RemoteDatabase --|> Repository : 1. Realtime Updates
    Repository --|> LocalDatabase : 2. Save Data & Decrypt
    LocalDatabase --|> Repository : 3. Emit Flow updates
    Repository --|> UseCase : 4. Map to Domain
    UseCase --|> ViewModel : 5. Pass to State
    ViewModel --|> UI : 6. Render UI
```

---

## 7. Real-Time Status & Read Receipts Algorithm

Tracking message delivery involves an interplay between Android background services (FCM), foreground composables, and strict privacy logic.

```mermaid
stateDiagram-v2
    [*] --> SENDING : Locally enqueued
    SENDING --> SENT : Successfully uploaded to Firestore
    SENDING --> FAILED : Network/Encryption Error

    SENT --> DELIVERED : Recipient's FCM or Foreground app receives Payload
    DELIVERED --> READ : Recipient opens Chat Screen

    state "Privacy Check (Read Receipts)" as PrivacyCheck {
        direction LR
        Check: Are both Sender & Receiver receipts ENABLED?
        Check --> Yes: Output = Blue Ticks
        Check --> No: Output = Gray Ticks (Stops at Delivered visually)
    }

    READ --> PrivacyCheck : UI evaluates how to render
```

### Status Implementation Details

1. **SENT**: Assigned after a successful `firestore.document(id).set(...)` call.
2. **DELIVERED**: Triggered via two vectors:
   - **Background**: `FCMService` intercepts a data push, extracts `messageId`, and updates Firestore status to `DELIVERED`.
   - **Foreground**: `ChatListViewModel` or `ChatViewModel` processes the Firestore snapshot and marks pending messages as `DELIVERED`.
3. **READ**: Updated when the recipient enters `ChatScreen`. `ChatViewModel` checks `PreferencesDataStore` (local) and the `User` document (remote) to confirm both parties consent via `readReceiptsEnabled`. Read receipts are always hidden for `BROADCAST` chats.
4. **Group tracking**: `readBy: Map<String, Long>` and `deliveredTo: Map<String, Long>` track per-recipient timestamps for group messages.

---

## 8. Database Entity Schema (Room)

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

## 9. Domain Models

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
    val type: MessageType,       // TEXT | IMAGE | VIDEO | VOICE | DOCUMENT | POLL | CALL | LIST
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
    val isPinned: Boolean
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
```

---

## 10. Screen Navigation Architecture

The application uses a single `NavHost` in `MainActivity` for all routes except `CallActivity`, which is launched via Intent for lock-screen support. The `CHAT_LIST` route renders `MainScreen`, which hosts a `HorizontalPager` with Chats, Calls, and Lists tabs — the Calls and Lists tabs are internal pager state, not NavHost routes.

```mermaid
graph TD
    %% Auth Flow
    Login[LoginScreen] -->|Already Logged In| Main[MainScreen - 3 tabs]
    Login -->|OTP Sent| Otp[OtpScreen]
    Otp -->|Existing User| Main
    Otp -->|New User| ProfileSetup[ProfileSetupScreen]
    ProfileSetup -->|Profile Complete| Main
    Settings[SettingsScreen] -->|Sign Out| Login

    %% Main Tabs (internal pager state)
    Main -->|Chats tab| ChatList[ChatListScreen]
    Main -->|Calls tab| CallsLog[CallsScreen - call log]
    Main -->|Lists tab| Lists[ListsScreen]

    %% From ChatList
    ChatList -->|Settings| Settings
    ChatList -->|New Chat| Contacts[ContactsScreen]
    ChatList -->|New Group| CreateGroup[CreateGroupScreen]
    ChatList -->|New Broadcast| CreateBroadcast[CreateBroadcastScreen]
    ChatList -->|Open Chat| Chat[ChatScreen]

    %% From Settings
    Settings -->|Starred Messages| StarredMessages[StarredMessagesScreen]
    Settings -->|Archived Chats| ArchivedChats[ArchivedChatsScreen]
    Settings -->|View Profile| Profile[ProfileScreen]

    %% From Archived Chats
    ArchivedChats -->|Open Chat| Chat

    %% Chat Connective Flows
    Contacts -->|Contact Selected| Chat
    CreateGroup -->|Group Created| Chat
    CreateBroadcast -->|Broadcast Created| Chat

    Chat -->|Message Info| MessageInfo[MessageInfoScreen]
    Chat -->|View Profile| Profile
    Chat -->|Group Settings| GroupSettings[GroupSettingsScreen]
    Chat -->|Voice Call| CallActivity[CallActivity - separate Activity]
    Chat -->|Shared Media| SharedMedia[SharedMediaScreen]
    Chat -->|Shared Lists| SharedLists[SharedListsScreen]

    GroupSettings -->|Add Member| Contacts

    %% Lists flows
    Lists -->|Open List| ListDetail[ListDetailScreen]
    SharedLists -->|Open List| ListDetail

    %% Share Intent
    ShareIntent[External Share Intent] -->|chatId| SharePicker[SharePickerScreen]
    SharePicker -->|Chat Selected| Chat
```

### Navigation Routes

| Route              | Arguments                    | Description                        |
| ------------------ | ---------------------------- | ---------------------------------- |
| `LOGIN`            | —                            | Phone number entry                 |
| `OTP`              | verificationId, phoneNumber  | OTP verification                   |
| `PROFILE_SETUP`    | —                            | Initial profile creation           |
| `CHAT_LIST`        | —                            | Main screen (renders `MainScreen`) |
| `CHAT`             | chatId, recipientId          | Chat conversation                  |
| `CONTACTS`         | —                            | Contact list for new chat          |
| `MESSAGE_INFO`     | messageId, chatId            | Delivery/read timestamps           |
| `SETTINGS`         | —                            | App settings                       |
| `USER_PROFILE`     | userId                       | User profile view                  |
| `STARRED_MESSAGES` | —                            | Bookmarked messages                |
| `ARCHIVED_CHATS`   | —                            | Archived conversations             |
| `GROUP_SETTINGS`   | chatId                       | Group admin screen                 |
| `CREATE_GROUP`     | —                            | Group creation                     |
| `CREATE_BROADCAST` | —                            | Broadcast list creation            |
| `SHARE_PICKER`     | —                            | External share target              |
| `SHARED_MEDIA`     | chatId                       | Shared images gallery for a chat   |
| `LIST_DETAIL`      | listId, autoFocus            | List editing / detail view         |
| `SHARED_LISTS`     | chatId                       | Lists shared into a specific chat  |

---

## 11. Package Layout

```
com.firestream.chat/
├── data/
│   ├── call/                    # WebRTC infrastructure
│   │   ├── CallService.kt       # Foreground service — owns PeerConnection
│   │   ├── CallStateHolder.kt   # @Singleton state bridge (service ↔ UI)
│   │   ├── CallNotificationManager.kt
│   │   └── WebRtcPeerConnectionFactory.kt
│   ├── crypto/
│   │   ├── SignalManager.kt
│   │   └── SignalProtocolStoreImpl.kt
│   ├── local/
│   │   ├── dao/                 # ChatDao, ContactDao, ListDao, MessageDao, SignalDao, UserDao
│   │   ├── entity/              # 5 core (Chat, Contact, List, Message, User) + 6 Signal entities + SignalTrustedIdentity
│   │   ├── AppDatabase.kt
│   │   ├── Converters.kt
│   │   └── PreferencesDataStore.kt
│   ├── util/
│   │   ├── ImageCompressor.kt   # EXIF-aware compression, memory-safe decode
│   │   └── MediaFileManager.kt  # Local media storage & gallery export
│   ├── worker/
│   │   └── MediaBackfillWorker.kt # WorkManager job to backfill local media
│   ├── remote/
│   │   ├── fcm/FCMService.kt
│   │   └── firebase/            # FirebaseAuthSource, FirestoreCallSource,
│   │                            # FirestoreListSource, FirestoreListHistorySource,
│   │                            # FirestoreMessageSource, FirestoreUserSource,
│   │                            # FirebaseKeySource, FirebaseStorageSource,
│   │                            # RealtimePresenceSource, LinkPreviewSource
│   ├── repository/              # AuthRepositoryImpl, CallRepositoryImpl,
│   │                            # ChatRepositoryImpl, ContactRepositoryImpl,
│   │                            # ListRepositoryImpl, MessageRepositoryImpl,
│   │                            # PollRepositoryImpl, PollMapper,
│   │                            # UserRepositoryImpl
│   └── share/
│       ├── SharedContentHolder.kt
│       └── ShareContentResolver.kt
├── di/                          # AppModule, DatabaseModule, CryptoModule, NetworkModule
├── domain/
│   ├── model/                   # Chat, Message, User, Contact, Poll, PollOption,
│   │                            # CallState, CallLogEntry, CallSignalingData, IceCandidateData,
│   │                            # GroupPermissions, GroupRole, ListData, ListItem, ListDiff,
│   │                            # ListHistoryEntry, HistoryAction, MediaAttachment,
│   │                            # SharedContent, MessageStatus, MessageType, ChatType
│   ├── repository/              # AuthRepository, CallRepository, ChatRepository, ContactRepository,
│   │                            # ListRepository, MessageRepository, PollRepository, UserRepository
│   ├── usecase/
│   │   ├── chat/                # CheckGroupPermissionUseCase
│   │   ├── list/                # SendListUpdateToChatsUseCase
│   │   └── message/             # SearchMessagesUseCase
│   └── util/MentionParser.kt
├── navigation/NavGraph.kt
├── ui/
│   ├── auth/                    # Login, Otp, ProfileSetup, AuthViewModel
│   ├── broadcast/               # CreateBroadcastScreen, CreateBroadcastViewModel
│   ├── call/                    # CallActivity, CallScreen, CallViewModel, CallControlButton
│   ├── calls/                   # CallsScreen, CallsViewModel (call log tab)
│   ├── chat/                    # ChatScreen, ChatViewModel (orchestrator),
│   │                            # ChatPollManager, ChatSearchManager, ChatMessageActions,
│   │                            # ChatMessageSender, ChatMessageLoader, ChatInfoManager,
│   │                            # MessageBubble, VoiceMessagePlayer, LinkPreviewCard,
│   │                            # FullscreenImageViewer, ForwardChatPicker,
│   │                            # EmojiHandlerPanel, EmojiSearchData,
│   │                            # PollBubble, CreatePollSheet, ListBubble, CreateListSheet,
│   │                            # SharedMediaScreen, SharedMediaViewModel,
│   │                            # MessageInfoScreen, ChatUtils
│   ├── chatlist/                # ChatListScreen, ChatListViewModel, ChatListItem,
│   │                            # ArchivedChatsScreen
│   ├── components/UserAvatar.kt
│   ├── contacts/                # ContactsScreen, ContactsViewModel
│   ├── group/                   # CreateGroupScreen, CreateGroupViewModel,
│   │                            # GroupSettingsScreen, GroupSettingsViewModel,
│   │                            # QrCodeGenerator
│   ├── lists/                   # ListsScreen, ListsViewModel, ListDetailScreen,
│   │                            # ListDetailViewModel, SharedListsScreen,
│   │                            # SharedListsViewModel, AvatarStack,
│   │                            # ListContextSheet, ListShareSheet
│   ├── main/                    # MainScreen (HorizontalPager — Chats/Calls/Lists tabs),
│   │                            # BottomNavBar
│   ├── profile/                 # ProfileScreen, ProfileViewModel
│   ├── settings/                # SettingsScreen, SettingsViewModel
│   ├── share/                   # SharePickerScreen, SharePickerViewModel
│   ├── starred/                 # StarredMessagesScreen, StarredMessagesViewModel
│   └── theme/                   # Color, Shape, Theme, Type
├── AppLifecycleObserver.kt      # Process-level lifecycle — drives RTDB online/offline presence
├── FireStreamApp.kt
└── MainActivity.kt
```

---

## 12. Firebase Cloud Functions

Three functions in `functions/index.js` (Node.js 20 runtime):

### `sendPushNotification`

- **Trigger**: Firestore document creation at `chats/{chatId}/messages/{messageId}`
- Gets all chat participants, filters out the sender
- Sends concurrent FCM data messages to all recipients
- Increments per-user `unreadCounts.{userId}` on the chat document
- **FCM Payload**: `chatId`, `senderId`, `senderName`, `messageId`, `chatType`, `chatName`, `mentions` (comma-separated user IDs)

### `sendCallPushNotification`

- **Trigger**: Firestore document creation at `calls/{callId}` where `status == "ringing"`
- Fetches caller and callee user documents
- Sends a high-priority FCM data message to the callee
- **FCM Payload**: `type: "call"`, `callId`, `callerId`, `callerName`, `callerAvatarUrl`

### `syncPresenceToFirestore`

- **Trigger**: Firebase Realtime Database write at `/presence/{userId}`
- Mirrors `isOnline` and `lastSeen` fields to the matching Firestore `users/{userId}` document
- Uses a `lastSeen` transaction guard to reject out-of-order invocations (Cloud Functions can be delivered out of sequence)
- Handles abrupt disconnects that RTDB `onDisconnect()` catches but the app never explicitly wrote back to Firestore
