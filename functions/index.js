const { onDocumentCreated, onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { onValueWritten } = require("firebase-functions/v2/database");
const admin = require("firebase-admin");
const { logger } = require("firebase-functions");

admin.initializeApp();

// Mirrors RTDB presence changes to Firestore.
// This is the fallback for abrupt disconnects (power off, crash) where the Android
// onStop() lifecycle method never fires. Firebase RTDB runs onDisconnect() server-side
// in those cases, writing to RTDB — this function propagates that to Firestore.
exports.syncPresenceToFirestore = onValueWritten(
    { ref: "/presence/{userId}" },
    async (event) => {
        const userId = event.params.userId;
        const presence = event.data.after.val();
        if (!presence) return null;

        const isOnline = presence.isOnline === true;
        const lastSeen = presence.lastSeen || Date.now();

        // Use a transaction to prevent a stale write (e.g. reordered invocations on
        // multi-device or rapid connect/disconnect) from overwriting a newer value.
        const userRef = admin.firestore().collection("users").doc(userId);
        await admin.firestore().runTransaction(async (tx) => {
            const snap = await tx.get(userRef);
            const existing = snap.exists ? (snap.data().lastSeen || 0) : 0;
            // Only write if this event is newer than what Firestore already has
            if (lastSeen >= existing) {
                tx.update(userRef, { isOnline, lastSeen });
            }
        });
        return null;
    }
);

exports.sendCallPushNotification = onDocumentCreated(
    "calls/{callId}",
    async (event) => {
        const callData = event.data.data();
        const callId = event.params.callId;

        if (callData.status !== "ringing") return null;

        const callerId = callData.callerId;
        const calleeId = callData.calleeId;

        logger.info(`Incoming call ${callId} from ${callerId} to ${calleeId}`);

        try {
            const [callerSnap, calleeSnap, blockedSnap] = await Promise.all([
                admin.firestore().collection("users").doc(callerId).get(),
                admin.firestore().collection("users").doc(calleeId).get(),
                admin.firestore().collection("users").doc(calleeId)
                    .collection("blockedUsers").doc(callerId).get()
            ]);

            if (blockedSnap.exists) {
                logger.info(`Callee ${calleeId} has blocked caller ${callerId}, skipping call notification`);
                return null;
            }

            if (!calleeSnap.exists) {
                logger.error(`Callee ${calleeId} not found`);
                return null;
            }

            const fcmToken = calleeSnap.data().fcmToken;
            if (!fcmToken) {
                logger.info(`Callee ${calleeId} has no FCM token`);
                return null;
            }

            const callerData = callerSnap.exists ? callerSnap.data() : {};
            const callerName = callerData.displayName || "Unknown";
            const callerAvatarUrl = callerData.avatarUrl || "";

            const payload = {
                token: fcmToken,
                data: {
                    type: "incoming_call",
                    callId: callId,
                    callerId: callerId,
                    callerName: callerName,
                    callerAvatarUrl: callerAvatarUrl
                },
                android: {
                    priority: "high"
                }
            };

            const response = await admin.messaging().send(payload);
            logger.info(`Call push sent to ${calleeId}:`, response);
            return null;
        } catch (error) {
            logger.error("Error sending call push notification:", error);
            return null;
        }
    }
);

// Returns the reaction entries (userId → emoji) that are new or changed in
// `after` compared to `before`. Removed reactions are ignored — removing a
// reaction must not notify anyone.
function diffAddedReactions(before, after) {
    const added = {};
    for (const [userId, emoji] of Object.entries(after || {})) {
        if ((before || {})[userId] !== emoji) {
            added[userId] = emoji;
        }
    }
    return added;
}

exports.sendReactionPushNotification = onDocumentUpdated(
    "chats/{chatId}/messages/{messageId}",
    async (event) => {
        // This trigger fires on every message update (status changes like
        // DELIVERED/READ included), so bail out before any Firestore reads
        // unless a reaction was actually added or changed.
        const before = event.data.before.data();
        const after = event.data.after.data();
        const addedReactions = diffAddedReactions(before.reactions, after.reactions);
        if (Object.keys(addedReactions).length === 0) return null;

        const chatId = event.params.chatId;
        const messageId = event.params.messageId;
        const messageAuthorId = after.senderId;

        try {
            const chatSnap = await admin.firestore().collection("chats").doc(chatId).get();
            if (!chatSnap.exists) {
                logger.error(`Chat ${chatId} not found`);
                return null;
            }
            const chatData = chatSnap.data();
            const participants = chatData.participants || [];
            const chatType = chatData.type || "INDIVIDUAL";

            await Promise.all(Object.entries(addedReactions).map(async ([reactorId, emoji]) => {
                // 1:1 chats: always notify the other person, even for a
                // reaction on the reactor's own message. Groups/broadcasts:
                // only the message author, and never the reactor themself.
                const recipients = chatType === "INDIVIDUAL"
                    ? participants.filter(id => id !== reactorId)
                    : [messageAuthorId].filter(id => id && id !== reactorId);
                if (recipients.length === 0) return;

                const reactorSnap = await admin.firestore().collection("users").doc(reactorId).get();
                const reactorName = reactorSnap.exists ? reactorSnap.data().displayName : "Someone";

                await Promise.all(recipients.map(async (recipientId) => {
                    try {
                        const [receiverSnap, blockedSnap] = await Promise.all([
                            admin.firestore().collection("users").doc(recipientId).get(),
                            admin.firestore().collection("users").doc(recipientId)
                                .collection("blockedUsers").doc(reactorId).get()
                        ]);
                        if (!receiverSnap.exists) {
                            logger.info(`Recipient ${recipientId} not found`);
                            return;
                        }
                        if (blockedSnap.exists) {
                            logger.info(`Recipient ${recipientId} has blocked reactor ${reactorId}, skipping notification`);
                            return;
                        }

                        const fcmToken = receiverSnap.data().fcmToken;
                        if (!fcmToken) {
                            logger.info(`Recipient ${recipientId} has no FCM token saved`);
                            return;
                        }

                        // No messageContent on purpose: in release builds the
                        // content field holds Signal ciphertext.
                        const payload = {
                            token: fcmToken,
                            data: {
                                type: "reaction",
                                chatId: chatId,
                                senderId: reactorId,
                                senderName: reactorName || "Someone",
                                messageId: messageId,
                                messageAuthorId: messageAuthorId || "",
                                emoji: emoji,
                                chatType: chatType,
                                chatName: chatData.name || ""
                            },
                            android: {
                                priority: "high"
                            }
                        };

                        const response = await admin.messaging().send(payload);
                        logger.info(`Reaction push sent to ${recipientId}:`, response);
                    } catch (err) {
                        logger.error(`Error sending reaction push to ${recipientId}:`, err);
                    }
                }));
            }));
            return null;
        } catch (error) {
            logger.error("Error sending reaction push notification:", error);
            return null;
        }
    }
);

exports.sendPushNotification = onDocumentCreated(
    "chats/{chatId}/messages/{messageId}",
    async (event) => {
        const messageData = event.data.data();
        const chatId = event.params.chatId;
        const senderId = messageData.senderId;
        const mentions = messageData.mentions || [];

        logger.info(`New message in chat ${chatId} from ${senderId}`);

        try {
            // 1. Get the chat document to find the participants
            const chatSnap = await admin.firestore().collection("chats").doc(chatId).get();
            if (!chatSnap.exists) {
                logger.error(`Chat ${chatId} not found`);
                return null;
            }

            const chatData = chatSnap.data();
            const participants = chatData.participants || [];
            const chatType = chatData.type || "INDIVIDUAL";

            // 2. Get sender's name for the notification
            const senderSnap = await admin.firestore().collection("users").doc(senderId).get();
            const senderName = senderSnap.exists ? senderSnap.data().displayName : "New Message";

            // 3. Identify recipients (all participants except the sender)
            const recipients = participants.filter(id => id !== senderId);
            if (recipients.length === 0) {
                logger.info("No recipients found (could be a self-chat)");
                return null;
            }

            // 4. Increment per-user unread counts + send push notifications concurrently
            const unreadUpdates = {};
            recipients.forEach(recipientId => {
                unreadUpdates[`unreadCounts.${recipientId}`] = admin.firestore.FieldValue.increment(1);
            });
            const unreadPromise = admin.firestore().collection("chats").doc(chatId).update(unreadUpdates);

            const mentionsStr = Array.isArray(mentions) ? mentions.join(",") : "";
            const pushPromise = Promise.all(recipients.map(async (recipientId) => {
                try {
                    const [receiverSnap, blockedSnap] = await Promise.all([
                        admin.firestore().collection("users").doc(recipientId).get(),
                        admin.firestore().collection("users").doc(recipientId)
                            .collection("blockedUsers").doc(senderId).get()
                    ]);
                    if (!receiverSnap.exists) {
                        logger.info(`Recipient ${recipientId} not found`);
                        return;
                    }
                    if (blockedSnap.exists) {
                        logger.info(`Recipient ${recipientId} has blocked sender ${senderId}, skipping notification`);
                        return;
                    }

                    const fcmToken = receiverSnap.data().fcmToken;
                    if (!fcmToken) {
                        logger.info(`Recipient ${recipientId} has no FCM token saved`);
                        return;
                    }

                    const payload = {
                        token: fcmToken,
                        data: {
                            chatId: chatId,
                            senderId: senderId,
                            senderName: senderName || "New Message",
                            messageId: event.params.messageId,
                            chatType: chatType,
                            chatName: chatData.name || "",
                            mentions: mentionsStr,
                            messageType: messageData.type || "TEXT",
                            messageContent: messageData.content || ""
                        },
                        android: {
                            priority: "high"
                        }
                    };

                    const response = await admin.messaging().send(payload);
                    logger.info(`Push sent to ${recipientId}:`, response);
                } catch (err) {
                    logger.error(`Error sending push to ${recipientId}:`, err);
                }
            }));

            await Promise.all([unreadPromise, pushPromise]);
            return null;
        } catch (error) {
            logger.error("Error sending push notification:", error);
            return null;
        }
    }
);
