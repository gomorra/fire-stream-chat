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
import com.firestream.chat.ui.chat.SharedMediaScreen
import com.firestream.chat.ui.chatlist.ArchivedChatsScreen
import com.firestream.chat.ui.contacts.ContactsScreen
import com.firestream.chat.ui.profile.ProfileScreen
import com.firestream.chat.ui.settings.SettingsScreen
import com.firestream.chat.ui.group.GroupSettingsScreen
import com.firestream.chat.ui.group.CreateGroupScreen
import com.firestream.chat.ui.broadcast.CreateBroadcastScreen
import com.firestream.chat.ui.lists.ListDetailScreen
import com.firestream.chat.ui.lists.SharedListsScreen
import com.firestream.chat.ui.main.MainScreen
import com.firestream.chat.ui.share.SharePickerScreen
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
    // Bottom nav tabs (no longer a separate nav route — handled by MainScreen tab state)
    // const val CALLS = "calls"
    // Phase 5 routes
    const val GROUP_SETTINGS = "group_settings/{chatId}"
    const val CREATE_BROADCAST = "create_broadcast"
    const val CREATE_GROUP = "create_group"
    const val SHARE_PICKER = "share_picker"
    const val SHARED_MEDIA = "shared_media/{chatId}"
    const val LIST_DETAIL = "list_detail/{listId}?autoFocus={autoFocus}"
    const val SHARED_LISTS = "shared_lists/{chatId}"

    fun otp(verificationId: String, phoneNumber: String) =
        "otp/$verificationId/$phoneNumber"

    fun chat(chatId: String, recipientId: String) =
        "chat/$chatId/$recipientId"

    fun messageInfo(messageId: String, chatId: String) =
        "message_info/$messageId/$chatId"

    fun userProfile(userId: String) = "user_profile/$userId"

    fun groupSettings(chatId: String) = "group_settings/$chatId"
    fun sharedMedia(chatId: String) = "shared_media/$chatId"
    fun listDetail(listId: String, autoFocus: Boolean = false) = "list_detail/$listId?autoFocus=$autoFocus"
    fun sharedLists(chatId: String) = "shared_lists/$chatId"
}

@Composable
fun FireStreamNavGraph(
    initialChatId: String? = null,
    initialSenderId: String? = null,
    isShareIntent: Boolean = false
) {
    val navController = rememberNavController()
    val messageInfoHolder = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Message?>(null)
    }
    val participantsHolder = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
    }

    val pendingChatId = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(initialChatId) }
    val pendingSenderId = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(initialSenderId) }
    val pendingShare = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(isShareIntent) }

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
            LaunchedEffect(pendingChatId.value, pendingSenderId.value, pendingShare.value) {
                if (pendingShare.value) {
                    pendingShare.value = false
                    navController.navigate(Routes.SHARE_PICKER)
                } else {
                    val chatId = pendingChatId.value
                    val senderId = pendingSenderId.value
                    if (chatId != null && senderId != null) {
                        pendingChatId.value = null
                        pendingSenderId.value = null
                        navController.navigate(Routes.chat(chatId, senderId))
                    }
                }
            }
            MainScreen(
                onChatClick = { chatId, recipientId ->
                    navController.navigate(Routes.chat(chatId, recipientId)) {
                        launchSingleTop = true
                    }
                },
                onNewChatClick = { navController.navigate(Routes.CONTACTS) },
                onNewGroupClick = { navController.navigate(Routes.CREATE_GROUP) },
                onNewBroadcastClick = { navController.navigate(Routes.CREATE_BROADCAST) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onMessageClick = { chatId, recipientId ->
                    navController.navigate(Routes.chat(chatId, recipientId)) {
                        launchSingleTop = true
                    }
                },
                onListClick = { listId ->
                    navController.navigate(Routes.listDetail(listId)) {
                        launchSingleTop = true
                    }
                },
                onListCreated = { listId ->
                    navController.navigate(Routes.listDetail(listId, autoFocus = true)) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("chatId") { type = NavType.StringType },
                navArgument("recipientId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
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
                },
                onGroupSettingsClick = { navController.navigate(Routes.groupSettings(chatId)) },
                onSharedMediaClick = { navController.navigate(Routes.sharedMedia(chatId)) },
                onSharedListsClick = { navController.navigate(Routes.sharedLists(chatId)) },
                onListClick = { listId ->
                    navController.navigate(Routes.listDetail(listId)) {
                        launchSingleTop = true
                    }
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

        // Phase 5: Group Settings
        composable(
            route = Routes.GROUP_SETTINGS,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
        ) {
            GroupSettingsScreen(
                onBackClick = { navController.popBackStack() },
                onAddMemberClick = { navController.navigate(Routes.CONTACTS) }
            )
        }

        // Shared Media
        composable(
            route = Routes.SHARED_MEDIA,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
        ) {
            SharedMediaScreen(onBackClick = { navController.popBackStack() })
        }

        // Phase 5: Create Group
        composable(Routes.CREATE_GROUP) {
            CreateGroupScreen(
                onGroupCreated = { chatId ->
                    navController.navigate(Routes.chat(chatId, "")) {
                        popUpTo(Routes.CHAT_LIST)
                    }
                },
                onBackClick = { navController.popBackStack() }
            )
        }

        // Phase 5.5: Create Broadcast
        composable(Routes.CREATE_BROADCAST) {
            CreateBroadcastScreen(
                onBroadcastCreated = { chatId ->
                    navController.navigate(Routes.chat(chatId, "")) {
                        popUpTo(Routes.CHAT_LIST)
                    }
                },
                onBackClick = { navController.popBackStack() }
            )
        }

        // Share target
        composable(Routes.SHARE_PICKER) {
            SharePickerScreen(
                onDone = { chatId, recipientId ->
                    if (chatId != null) {
                        navController.navigate(Routes.chat(chatId, recipientId ?: "")) {
                            popUpTo(Routes.CHAT_LIST)
                        }
                    } else {
                        navController.navigate(Routes.CHAT_LIST) {
                            popUpTo(Routes.CHAT_LIST) { inclusive = true }
                        }
                    }
                },
                onBackClick = { navController.popBackStack() }
            )
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

        // List Detail
        composable(
            route = Routes.LIST_DETAIL,
            arguments = listOf(
                navArgument("listId") { type = NavType.StringType },
                navArgument("autoFocus") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val autoFocus = backStackEntry.arguments?.getBoolean("autoFocus") ?: false
            ListDetailScreen(
                autoFocus = autoFocus,
                onBackClick = { navController.popBackStack() },
                onShareToChat = { chatId, recipientId ->
                    navController.navigate(Routes.chat(chatId, recipientId)) {
                        launchSingleTop = true
                    }
                }
            )
        }

        // Shared Lists
        composable(
            route = Routes.SHARED_LISTS,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
        ) {
            SharedListsScreen(
                onBackClick = { navController.popBackStack() },
                onListClick = { listId ->
                    navController.navigate(Routes.listDetail(listId)) {
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
