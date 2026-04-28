/// <reference path="../pb_data/types.d.ts" />

// Triggered after a new `messages` record is created. Posts an FCM HTTP v1
// data notification to each recipient's `fcm_token`. Mirrors the Firebase
// `sendPushNotification` cloud function payload so the Android FCM service
// (which already handles incoming pushes for the firebase flavor) treats the
// PB-flavor message identically.
//
// ## Token strategy (v0)
//
// FCM HTTP v1 requires an OAuth2 access token minted from the service account
// via the JWT-bearer grant — which needs RS256 signing. PocketBase v0.22's
// `$security.createJWT` only supports HS256/384/512 (HMAC, symmetric); there
// is no RSA signer in Goja. Two implications:
//
//   * Token minting is **out-of-band**: an operator runs
//     `gcloud auth print-access-token` (or an equivalent sidecar) and exports
//     `FCM_ACCESS_TOKEN`. Tokens last 1 h.
//   * If the token is missing or expired, this hook **logs and skips** the
//     push — it does NOT crash the message-create transaction. The message
//     itself is already saved; SSE delivery to live clients is unaffected.
//
// Automating the token refresh (sidecar / scheduled job) is tracked in
// TECH_DEBT.md under "PocketBase push: automate FCM access-token refresh".
//
// ## Failure handling
//
// Per recipient, errors are logged and swallowed. We never throw out of the
// hook — that would 500 the message-create request and the client would
// retry, duplicating the message. Push is best-effort.

const FCM_TIMEOUT_SEC = 10
const OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token"

onRecordAfterCreateRequest((e) => {
  const projectId = $os.getenv("FCM_PROJECT_ID")
  const accessToken = $os.getenv("FCM_ACCESS_TOKEN")

  if (!projectId) {
    console.log("[push_on_message] FCM_PROJECT_ID not set — skipping push")
    return
  }
  if (!accessToken) {
    console.log("[push_on_message] FCM_ACCESS_TOKEN not set — skipping push (mint via 'gcloud auth print-access-token')")
    return
  }

  const message = e.record
  const messageId = message.id
  const chatId = message.get("chat_id")
  const senderId = message.get("sender_id")

  let chat
  try {
    chat = $app.dao().findRecordById("chats", chatId)
  } catch (err) {
    console.log("[push_on_message] chat " + chatId + " not found: " + err)
    return
  }

  // participants is a JSON array column.
  const participantsRaw = chat.get("participants")
  const participants = parseStringArray(participantsRaw)
  const recipients = participants.filter((id) => id && id !== senderId)
  if (recipients.length === 0) {
    return // self-chat or empty group
  }

  // Sender display name for the notification body.
  let senderName = "New Message"
  try {
    const sender = $app.dao().findRecordById("users", senderId)
    const name = sender.get("name")
    if (name) senderName = name
  } catch (err) {
    // sender deleted between message-create and hook — fall through with default
  }

  const chatType = chat.get("type") || "INDIVIDUAL"
  const chatName = chat.get("name") || ""
  const messageType = message.get("type") || "TEXT"
  const messageContent = message.get("content") || ""

  const fcmUrl = "https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"

  for (const recipientId of recipients) {
    let recipient
    try {
      recipient = $app.dao().findRecordById("users", recipientId)
    } catch (err) {
      continue // recipient deleted — skip
    }

    const fcmToken = recipient.get("fcm_token")
    if (!fcmToken) continue

    const payload = {
      message: {
        token: fcmToken,
        data: {
          chatId: String(chatId),
          senderId: String(senderId),
          senderName: senderName,
          messageId: String(messageId),
          chatType: String(chatType),
          chatName: String(chatName),
          mentions: "",  // v0 schema has no mentions field on messages
          messageType: String(messageType),
          messageContent: String(messageContent),
        },
        android: {
          priority: "HIGH",
        },
      },
    }

    try {
      const res = $http.send({
        url: fcmUrl,
        method: "POST",
        body: JSON.stringify(payload),
        headers: {
          "Authorization": "Bearer " + accessToken,
          "Content-Type": "application/json",
        },
        timeout: FCM_TIMEOUT_SEC,
      })
      if (res.statusCode >= 400) {
        console.log("[push_on_message] FCM " + res.statusCode + " for " + recipientId + ": " + res.raw)
      }
    } catch (err) {
      console.log("[push_on_message] FCM send to " + recipientId + " threw: " + err)
    }
  }
}, "messages")

// participants is stored as a JSON array; PB record.get returns it as the
// underlying types.JsonArray (Go slice) which Goja exposes as a length-keyed
// object. Coerce to a plain string[] regardless of which shape we see.
function parseStringArray(raw) {
  if (!raw) return []
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw)
      return Array.isArray(parsed) ? parsed.map(String) : []
    } catch (e) {
      return []
    }
  }
  if (typeof raw.length === "number") {
    const out = []
    for (let i = 0; i < raw.length; i++) {
      if (raw[i] != null) out.push(String(raw[i]))
    }
    return out
  }
  return []
}
