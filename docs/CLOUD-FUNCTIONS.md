# Firebase Cloud Functions

Four functions in `functions/index.js` (Node.js 20 runtime):

### `sendPushNotification`

- **Trigger**: Firestore document creation at `chats/{chatId}/messages/{messageId}`
- Gets all chat participants, filters out the sender
- Sends concurrent FCM data messages to all recipients
- Increments per-user `unreadCounts.{userId}` on the chat document
- **FCM Payload**: `chatId`, `senderId`, `senderName`, `messageId`, `chatType`, `chatName`, `mentions` (comma-separated user IDs)

### `sendReactionPushNotification`

- **Trigger**: Firestore document update at `chats/{chatId}/messages/{messageId}`
- Diffs `before.reactions` vs `after.reactions` **before any Firestore reads** and exits early unless a reaction was added or its emoji changed (the trigger also fires for DELIVERED/READ status updates); reaction removals never notify
- **Recipients**: `INDIVIDUAL` chats → all participants except the reactor (so reacting to your *own* message still notifies the other person); `GROUP`/`BROADCAST` → only the author of the reacted-to message, never the reactor themself
- Applies the same blocked-user and `fcmToken` checks as `sendPushNotification`; does **not** increment `unreadCounts`
- **FCM Payload**: `type: "reaction"`, `chatId`, `senderId` (reactor), `senderName`, `messageId`, `messageAuthorId`, `emoji`, `chatType`, `chatName` — deliberately no message content (ciphertext in release builds)

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

---

**See also:** [SCHEMA-FIRESTORE.md](SCHEMA-FIRESTORE.md) (the collections these functions watch), [ARCHITECTURE.md](ARCHITECTURE.md) (overall architecture).
