package com.firestream.chat.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.firestream.chat.domain.model.Message
import com.firestream.chat.ui.auth.LoginScreen
import com.firestream.chat.ui.auth.OtpScreen
import com.firestream.chat.ui.auth.ProfileSetupScreen
import com.firestream.chat.ui.chat.ChatScreen
import com.firestream.chat.ui.chat.MessageInfoScreen
import com.firestream.chat.ui.chatlist.ArchivedChatsScreen
import com.firestream.chat.ui.chatlist.ChatListScreen
import com.firestream.chat.ui.contacts.ContactsScreen
import com.firestream.chat.ui.profile.ProfileScreen
import com.firestream.chat.ui.settings.SettingsScreen
import com.firestream.chat.ui.starred.StarredMessagesScreen

object Routes {
    const val LOGIN = "login"
    const val OTP = "otp/{verificationId}/{phoneNumber}"
    const val PROFILE_SETUP = "profile_setup"
    const val CHAT_LIST = "chat_list"
    const val CHAT = "chat/{chatId}/{recipientId}"
    const val CONTACTS = "contacts"
    const val MESSAGE_INFO = "message_info/{messageId}/{chatId}"
    // Phase 2 routes
    const val SETTINGS = "settings"
    const val USER_PROFILE = "user_profile/{userId}"
    const val STARRED_MESSAGES = "starred_messages"
    const val ARCHIVED_CHATS = "archived_chats"

    fun otp(verificationId: String, phoneNumber: String) =
        "otp/$verificationId/$phoneNumber"

    fun chat(chatId: String, recipientId: String) =
        "chat/$chatId/$recipientId"

    fun messageInfo(messageId: String, chatId: String) =
        "message_info/$messageId/$chatId"

    fun userProfile(userId: String) = "user_profile/$userId"
}

@Composable
fun FireStreamNavGraph(
    initialChatId: String? = null,
    initialSenderId: String? = null
) {
    val navController = rememberNavController()
    val messageInfoHolder = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Message?>(null)
    }
    val participantsHolder = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
    }

    NavHost(
        navController = navController,
        startDestination = Routes.LOGIN
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onOtpSent = { verificationId, phoneNumber ->
                    navController.navigate(Routes.otp(verificationId, phoneNumber))
                },
                onAlreadyLoggedIn = {
                    navController.navigate(Routes.CHAT_LIST) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.OTP,
            arguments = listOf(
                navArgument("verificationId") { type = NavType.StringType },
                navArgument("phoneNumber") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val verificationId = backStackEntry.arguments?.getString("verificationId") ?: ""
            val phoneNumber = backStackEntry.arguments?.getString("phoneNumber") ?: ""
            OtpScreen(
                verificationId = verificationId,
                phoneNumber = phoneNumber,
                onVerified = { isNewUser ->
                    if (isNewUser) {
                        navController.navigate(Routes.PROFILE_SETUP) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Routes.CHAT_LIST) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Routes.PROFILE_SETUP) {
            ProfileSetupScreen(
                onProfileComplete = {
                    navController.navigate(Routes.CHAT_LIST) {
                        popUpTo(Routes.PROFILE_SETUP) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.CHAT_LIST) {
            LaunchedEffect(Unit) {
                if (initialChatId != null && initialSenderId != null) {
                    navController.navigate(Routes.chat(initialChatId, initialSenderId))
                }
            }
            ChatListScreen(
                onChatClick = { chatId, recipientId ->
                    navController.navigate(Routes.chat(chatId, recipientId)) {
                        launchSingleTop = true
                    }
                },
                onNewChatClick = {
                    navController.navigate(Routes.CONTACTS)
                },
                onSettingsClick = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("chatId") { type = NavType.StringType },
                navArgument("recipientId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val recipientId = backStackEntry.arguments?.getString("recipientId") ?: ""
            ChatScreen(
                onBackClick = { navController.popBackStack() },
                onMessageInfoClick = { message, participants ->
                    messageInfoHolder.value = message
                    participantsHolder.value = participants
                    navController.navigate(Routes.messageInfo(message.id, message.chatId))
                },
                onProfileClick = { userId ->
                    navController.navigate(Routes.userProfile(userId))
                }
            )
        }

        composable(Routes.CONTACTS) {
            ContactsScreen(
                onContactClick = { chatId, recipientId ->
                    navController.navigate(Routes.chat(chatId, recipientId)) {
                        popUpTo(Routes.CHAT_LIST)
                    }
                },
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.MESSAGE_INFO,
            arguments = listOf(
                navArgument("messageId") { type = NavType.StringType },
                navArgument("chatId") { type = NavType.StringType }
            )
        ) {
            val message = messageInfoHolder.value
            if (message != null) {
                MessageInfoScreen(
                    message = message,
                    participants = participantsHolder.value,
                    onBackClick = { navController.popBackStack() }
                )
            }
        }

        // Phase 2: Settings
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onStarredMessagesClick = { navController.navigate(Routes.STARRED_MESSAGES) },
                onArchivedChatsClick = { navController.navigate(Routes.ARCHIVED_CHATS) },
                onProfileClick = { userId -> navController.navigate(Routes.userProfile(userId)) },
                onSignedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Phase 2: User Profile
        composable(
            route = Routes.USER_PROFILE,
            arguments = listOf(navArgument("userId") { type = NavType.StringType })
        ) {
            ProfileScreen(onBackClick = { navController.popBackStack() })
        }

        // Phase 2: Starred Messages
        composable(Routes.STARRED_MESSAGES) {
            StarredMessagesScreen(onBackClick = { navController.popBackStack() })
        }

        // Phase 2: Archived Chats
        composable(Routes.ARCHIVED_CHATS) {
            ArchivedChatsScreen(
                onChatClick = { chatId, recipientId ->
                    navController.navigate(Routes.chat(chatId, recipientId)) {
                        launchSingleTop = true
                    }
                },
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
