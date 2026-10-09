// What the pushes about calls decide. Kept apart from index.js, so `npm test` can check it
// without firebase-admin.

// How long an incoming-call push stays deliverable. The callee's CallService ends an unanswered
// call after 30 s, so a push that arrives later would ring for a call that is over.
const RING_TTL_MS = 30 * 1000;

// Whether the people a call message is pushed to missed the call. Only the caller writes the
// message, so every recipient is a callee. A call counts as answered only if it connected, and
// only a connected call has a duration. A declined call was seen, not missed. Mirrors
// CallLogType.of in the app.
function isMissedCall(messageData) {
    if (messageData.type !== "CALL") return false;
    const connected = (messageData.duration || 0) > 0;
    return !connected && messageData.content !== "declined";
}

// The unread-count updates a new message makes for its recipients, each by `increment`. A call
// message counts only when it was missed: a call the recipient took or declined is not news.
function unreadUpdates(messageData, recipients, increment) {
    if (messageData.type === "CALL" && !isMissedCall(messageData)) return {};
    const updates = {};
    recipients.forEach(recipientId => {
        updates[`unreadCounts.${recipientId}`] = increment;
    });
    return updates;
}

// The extra push data for a call message. The app shows a missed-call notification only when
// the call connected for no time and was not declined, and it needs the duration to tell.
function callMessagePushData(messageData) {
    if (messageData.type !== "CALL") return {};
    return { callDurationSeconds: String(messageData.duration || 0) };
}

// Android options for the incoming-call push: delivered at once, and dropped once the call can
// no longer be ringing.
function incomingCallAndroidConfig() {
    return { priority: "high", ttl: RING_TTL_MS };
}

module.exports = {
    RING_TTL_MS,
    isMissedCall,
    unreadUpdates,
    callMessagePushData,
    incomingCallAndroidConfig,
};
