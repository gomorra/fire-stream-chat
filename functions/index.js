const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const admin = require("firebase-admin");
const { logger } = require("firebase-functions");

admin.initializeApp();

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

            // 4. Send push notification to each recipient
            const mentionsStr = Array.isArray(mentions) ? mentions.join(",") : "";

            await Promise.all(recipients.map(async (recipientId) => {
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

            return null;
        } catch (error) {
            logger.error("Error sending push notification:", error);
            return null;
        }
    }
);
