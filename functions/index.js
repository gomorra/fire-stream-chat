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

            // 2. Identify the receiver (the participant who is NOT the sender)
            const receiverId = participants.find(id => id !== senderId);
            if (!receiverId) {
                logger.info("No receiver found (could be a self-chat)");
                return null;
            }

            // 3. Get the receiver's FCM token from their User document
            const receiverSnap = await admin.firestore().collection("users").doc(receiverId).get();
            if (!receiverSnap.exists) {
                logger.error(`Receiver user ${receiverId} not found`);
                return null;
            }

            const fcmToken = receiverSnap.data().fcmToken;
            if (!fcmToken) {
                logger.info(`Receiver ${receiverId} has no FCM token saved`);
                return null;
            }

            // 4. Get sender's name for the notification
            const senderSnap = await admin.firestore().collection("users").doc(senderId).get();
            const senderName = senderSnap.exists ? senderSnap.data().name : "New Message";

            // 5. Build and send the push notification payload
            const payload = {
                token: fcmToken,
                data: {
                    chatId: chatId,
                    senderId: senderId,
                    senderName: senderName || "New Message",
                    messageId: event.params.messageId
                    // Note: We don't send the message content here in the push payload 
                    // because it is (or will be) end-to-end encrypted in the database.
                    // The client app will wake up, sync the encrypted content, and display it.
                },
                // Optional: Android specific high-priority config
                android: {
                    priority: "high"
                }
            };

            const response = await admin.messaging().send(payload);
            logger.info("Successfully sent message:", response);

            return null;
        } catch (error) {
            logger.error("Error sending push notification:", error);
            return null;
        }
    }
);
