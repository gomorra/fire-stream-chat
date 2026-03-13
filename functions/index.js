const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const admin = require("firebase-admin");
const { logger } = require("firebase-functions");

admin.initializeApp();

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
            const [callerSnap, calleeSnap] = await Promise.all([
                admin.firestore().collection("users").doc(callerId).get(),
                admin.firestore().collection("users").doc(calleeId).get()
            ]);

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
            const senderName = senderSnap.exists ? senderSnap.data().name : "New Message";

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
                    const receiverSnap = await admin.firestore().collection("users").doc(recipientId).get();
                    if (!receiverSnap.exists) {
                        logger.info(`Recipient ${recipientId} not found`);
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
                            mentions: mentionsStr
                            // Note: We don't send the message content here in the push payload
                            // because it is (or will be) end-to-end encrypted in the database.
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
