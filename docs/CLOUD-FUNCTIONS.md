# Firebase Cloud Functions

Five functions in `functions/index.js` (Node.js 20 runtime):

### `sendPushNotification`

- **Trigger**: Firestore document creation at `chats/{chatId}/messages/{messageId}`
- Gets all chat participants, filters out the sender
- Sends concurrent FCM data messages to all recipients
- Increments per-user `unreadCounts.{userId}` on the chat document. A `CALL` message counts only when the recipient missed the call: it never connected (no `duration`) and was not declined.
- Pushes every `CALL` message, so the callee's app stores it and its Calls tab lists the call. The app shows a notification only for a missed call.
- **FCM Payload**: `chatId`, `senderId`, `senderName`, `messageId`, `chatType`, `chatName`, `mentions` (comma-separated user IDs), `messageType`, `messageContent`; a `CALL` message adds `callDurationSeconds`

### `sendReactionPushNotification`

- **Trigger**: Firestore document update at `chats/{chatId}/messages/{messageId}`
- Diffs `before.reactions` vs `after.reactions` **before any Firestore reads** and exits early unless a reaction was added or its emoji changed (the trigger also fires for DELIVERED/READ status updates); reaction removals never notify
- **Recipients**: `INDIVIDUAL` chats → all participants except the reactor (so reacting to your *own* message still notifies the other person); `GROUP`/`BROADCAST` → only the author of the reacted-to message, never the reactor themself
- Applies the same blocked-user and `fcmToken` checks as `sendPushNotification`; does **not** increment `unreadCounts`
- **FCM Payload**: `type: "reaction"`, `chatId`, `senderId` (reactor), `senderName`, `messageId`, `messageAuthorId`, `emoji`, `chatType`, `chatName` — deliberately no message content (ciphertext in release builds)

### `sendCallPushNotification`

- **Trigger**: Firestore document creation at `calls/{callId}` where `status == "ringing"`
- Fetches caller and callee user documents
- Sends a high-priority FCM data message to the callee. FCM keeps it for 30 s (`android.ttl`), as long as the call rings, so a phone that comes online later is not woken for a call that is over.
- Skips the push when the callee has blocked the caller or has no `fcmToken`
- **FCM Payload**: `type: "incoming_call"`, `callId`, `callerId`, `callerName`, `callerAvatarUrl`, `video`
- `video` is the string `"true"` or `"false"`, from the call document's `video` field. A document without the field is a voice call.
- The app also reads `video` from the call document. A deployed function that does not send it yet only delays the *video call* label on the ring.

### `getTurnCredentials`

- **Trigger**: a callable function (`onCall`), in the default region. The app calls it through `FirebaseIceServerSource`.
- Rejects a caller who is not signed in with `unauthenticated`.
- Posts `{"ttl": 86400}` to Cloudflare's `…/v1/turn/keys/<key id>/credentials/generate-ice-servers` with the API token as bearer.
- **Returns**: `{ iceServers }`, as Cloudflare sent it: the STUN and TURN URLs of the relay and a login that is good for a day.
- Any failure on Cloudflare's side is answered with `unavailable`. The app then runs the call on STUN only.
- **Secrets**: `CLOUDFLARE_TURN_KEY_ID` and `CLOUDFLARE_TURN_API_TOKEN`, both `defineSecret`. Set them with `firebase functions:secrets:set <name>` before the deploy. The deploy fails while one is missing.
- It logs the HTTP status of a failed request and nothing else. The token, the request and the answer are never logged, because the answer is a credential.
- Every signed-in user can ask for a login, as often as they like. Nothing limits it. See `docs/BACKLOG.md` § *The relay hands a login to every signed-in user*.

### `syncPresenceToFirestore`

- **Trigger**: Firebase Realtime Database write at `/presence/{userId}`
- Mirrors `isOnline` and `lastSeen` fields to the matching Firestore `users/{userId}` document
- Uses a `lastSeen` transaction guard to reject out-of-order invocations (Cloud Functions can be delivered out of sequence)
- Handles abrupt disconnects that RTDB `onDisconnect()` catches but the app never explicitly wrote back to Firestore

### Tests

`functions/callPush.js` holds what the call pushes decide: which call is missed, which unread counts rise, the call push's Android options. `cd functions && npm test` runs `functions/test/` with Node's built-in test runner, and `.github/workflows/functions.yml` runs it when `functions/` changes.

---

**See also:** [SCHEMA-FIRESTORE.md](SCHEMA-FIRESTORE.md) (the collections these functions watch), [ARCHITECTURE.md](ARCHITECTURE.md) (overall architecture).
