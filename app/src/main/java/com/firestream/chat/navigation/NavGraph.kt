// region: AGENT-NOTE
// Responsibility: Single NavHost for the whole app + the `Routes` object that
//   defines every route string and helper. CallActivity is the only screen
//   *not* hosted here (separate Activity for lock-screen support).
//   `CHAT_LIST` renders MainScreen, which hosts a HorizontalPager — Calls and
//   Lists tabs are pager state, not NavHost destinations.
// Owns: Route constants, route arg helpers (Routes.chat / Routes.otp /
//   Routes.messageInfo / Routes.userProfile), NavHost transition timing.
//   Launch-restore navigations (last-open chat/list restore, login auto-redirect)
//   snap without animation — see isLaunchRestoreNavigation; the slide is
//   reserved for user-initiated navigation.
// Collaborators: every UI screen package; PreferencesDataStore for
//   first-launch routing.
// Don't put here: Bottom navigation (lives in MainScreen), per-screen state
//   (lives in ViewModels), call-screen wiring (CallActivity).
// Adding a route: add a constant to Routes, a helper if it takes args, and a
//   composable() block here. Never construct route strings manually at call sites.
// endregion

package com.firestream.chat.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.Message
import com.firestream.chat.ui.auth.LoginScreen
import com.firestream.chat.ui.auth.OtpScreen
import com.firestream.chat.ui.auth.ProfileSetupScreen
import com.firestream.chat.ui.broadcast.CreateBroadcastScreen
import com.firestream.chat.ui.chat.ChatScreen
import com.firestream.chat.ui.chat.MessageInfoScreen
import com.firestream.chat.ui.chat.SharedMediaScreen
import com.firestream.chat.ui.chatlist.ArchivedChatsScreen
import com.firestream.chat.ui.contacts.ContactsScreen
import com.firestream.chat.ui.group.CreateGroupScreen
import com.firestream.chat.ui.group.GroupSettingsScreen
import com.firestream.chat.ui.lists.ListDetailScreen
import com.firestream.chat.ui.lists.ListDetailViewModel
import com.firestream.chat.ui.lists.SharedListsScreen
import com.firestream.chat.ui.main.MainScreen
import com.firestream.chat.ui.profile.ProfileScreen
import com.firestream.chat.ui.settings.SettingsScreen
import com.firestream.chat.ui.share.SharePickerScreen
import com.firestream.chat.ui.starred.StarredMessagesScreen
import kotlinx.coroutines.flow.first

// iOS spring-style easing (equivalent to UIView.animate defaultCurve). Shared by all
// four NavHost transition lambdas so the bezier is allocated once at file init, not
// on every navigation event.
private val NavSlideEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private const val NAV_SLIDE_DURATION_MS = 500
private const val NAV_SLIDE_DURATION_MS_SLOW = 600

// Routes whose enter/exit transitions run at the slower duration. Checked against
// both initialState and targetState so the pair stays symmetric — leaving-screen
// and entering-screen always animate for the same length.
private val SlowTransitionRoutes = setOf(Routes.CHAT, Routes.LIST_DETAIL)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navSlideDuration(): Int =
    if (initialState.destination.route in SlowTransitionRoutes ||
        targetState.destination.route in SlowTransitionRoutes
    ) NAV_SLIDE_DURATION_MS_SLOW else NAV_SLIDE_DURATION_MS

// A navigation performed by the app itself on launch (last-screen restore,
// login auto-redirect) must snap into place — the slide is reserved for
// user-initiated navigation. Only forward transitions check this; backing out
// of a restored screen is a user action and animates normally.
internal fun isLaunchRestoreNavigation(
    initialRoute: String?,
    targetRoute: String?,
    targetRestoredArg: Boolean,
): Boolean = targetRestoredArg ||
    (initialRoute == Routes.LOGIN && targetRoute == Routes.CHAT_LIST)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isLaunchRestore(): Boolean =
    isLaunchRestoreNavigation(
        initialRoute = initialState.destination.route,
        targetRoute = targetState.destination.route,
        targetRestoredArg = targetState.arguments?.getBoolean("restored") == true,
    )

object Routes {
    const val LOGIN = "login"
    const val OTP = "otp/{verificationId}/{phoneNumber}"
    const val PROFILE_SETUP = "profile_setup"
    const val CHAT_LIST = "chat_list"
    const val CHAT = "chat/{chatId}/{recipientId}?fromNotification={fromNotification}&restored={restored}"
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
    const val LIST_DETAIL = "list_detail/{listId}?autoFocus={autoFocus}&restored={restored}"
    const val SHARED_LISTS = "shared_lists/{chatId}"

    fun otp(verificationId: String, phoneNumber: String) =
        "otp/$verificationId/$phoneNumber"

    fun chat(
        chatId: String,
        recipientId: String,
        fromNotification: Boolean = false,
        restored: Boolean = false,
    ) = "chat/$chatId/$recipientId?fromNotification=$fromNotification&restored=$restored"

    fun messageInfo(messageId: String, chatId: String) =
        "message_info/$messageId/$chatId"

    fun userProfile(userId: String) = "user_profile/$userId"

    fun groupSettings(chatId: String) = "group_settings/$chatId"
    fun sharedMedia(chatId: String) = "shared_media/$chatId"
    fun listDetail(listId: String, autoFocus: Boolean = false, restored: Boolean = false) =
        "list_detail/$listId?autoFocus=$autoFocus&restored=$restored"
    fun sharedLists(chatId: String) = "shared_lists/$chatId"
}

@Composable
fun FireStreamNavGraph(
    initialChatId: String? = null,
    initialSenderId: String? = null,
    isShareIntent: Boolean = false,
    openSettings: Boolean = false,
    focusUpdate: Boolean = false,
    preferencesDataStore: PreferencesDataStore? = null
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
    // Only true when the chat route originates from a notification tap (MainActivity
    // intent extras), not from the last-open-chat restore path. Consumed once and reset.
    val pendingFromNotification = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(initialChatId != null) }
    val pendingListId = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val pendingOpenSettings = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(openSettings) }
    val pendingFocusUpdate = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(focusUpdate) }
    // Separate state read by the Settings composable block. Set to true just
    // before navigating, then consumed inside SettingsScreen.
    val settingsFocusUpdate = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    // Restore last open chat when no deep link, share intent, or settings
    // launch is pending. If no chat was persisted, fall back to restoring the
    // last open list detail — chat wins because it was the more recent
    // foreground screen.
    val restoredLastState = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    // Set only AFTER the restore reads finished (or were skipped) — unlike
    // restoredLastState, which flips before the DataStore reads and therefore
    // can't tell "decision made" from "read in flight". clearLastOpenChat()
    // must never run before this is true, or it races the restore read.
    val restoreDecisionComplete = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!restoredLastState.value && initialChatId == null && !isShareIntent && !openSettings && preferencesDataStore != null) {
            restoredLastState.value = true
            val lastChatId = preferencesDataStore.lastChatIdFlow.first()
            val lastRecipientId = preferencesDataStore.lastRecipientIdFlow.first()
            if (lastChatId != null && lastRecipientId != null) {
                pendingChatId.value = lastChatId
                pendingSenderId.value = lastRecipientId
            } else {
                pendingListId.value = preferencesDataStore.lastOpenListIdFlow.first()
            }
        }
        restoreDecisionComplete.value = true
    }

    NavHost(
        navController = navController,
        startDestination = Routes.LOGIN,
        enterTransition = {
            if (isLaunchRestore()) EnterTransition.None
            else slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(navSlideDuration(), easing = NavSlideEasing)
            )
        },
        exitTransition = {
            if (isLaunchRestore()) ExitTransition.None
            else slideOutHorizontally(
                targetOffsetX = { fullWidth -> -(fullWidth / 3) },
                animationSpec = tween(navSlideDuration(), easing = NavSlideEasing)
            )
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> -(fullWidth / 3) },
                animationSpec = tween(navSlideDuration(), easing = NavSlideEasing)
            )
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(navSlideDuration(), easing = NavSlideEasing)
            )
        }
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
            // True once this chat-list composition entry has navigated
            // somewhere. Plain remember on purpose: it must reset when the
            // chat list re-enters composition after a pop — that reset is what
            // re-arms the clear-restore-target branch for a genuine "user is
            // resting on the list" state.
            val navigatedFromThisEntry = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            LaunchedEffect(pendingChatId.value, pendingSenderId.value, pendingShare.value, pendingListId.value, pendingOpenSettings.value, pendingFocusUpdate.value, restoreDecisionComplete.value) {
                val action = resolveChatListPendingAction(
                    pendingShare = pendingShare.value,
                    pendingOpenSettings = pendingOpenSettings.value,
                    pendingFocusUpdate = pendingFocusUpdate.value,
                    pendingChatId = pendingChatId.value,
                    pendingSenderId = pendingSenderId.value,
                    pendingFromNotification = pendingFromNotification.value,
                    pendingListId = pendingListId.value,
                    restoreDecisionComplete = restoreDecisionComplete.value,
                    navigatedFromThisEntry = navigatedFromThisEntry.value,
                )
                when (action) {
                    ChatListPendingAction.OpenSharePicker -> {
                        pendingShare.value = false
                        navigatedFromThisEntry.value = true
                        navController.navigate(Routes.SHARE_PICKER)
                    }

                    is ChatListPendingAction.OpenSettings -> {
                        pendingOpenSettings.value = false
                        pendingFocusUpdate.value = false
                        settingsFocusUpdate.value = action.focusUpdate
                        navigatedFromThisEntry.value = true
                        navController.navigate(Routes.SETTINGS)
                    }

                    is ChatListPendingAction.OpenChat -> {
                        pendingChatId.value = null
                        pendingSenderId.value = null
                        pendingFromNotification.value = false
                        navigatedFromThisEntry.value = true
                        // A pending chat without the notification flag can only come
                        // from the last-open-chat restore read — snap, don't slide.
                        navController.navigate(
                            Routes.chat(
                                action.chatId,
                                action.recipientId,
                                action.fromNotification,
                                restored = !action.fromNotification,
                            )
                        )
                    }

                    is ChatListPendingAction.OpenListDetail -> {
                        pendingListId.value = null
                        navigatedFromThisEntry.value = true
                        navController.navigate(Routes.listDetail(action.listId, restored = true)) {
                            launchSingleTop = true
                        }
                    }

                    ChatListPendingAction.ClearRestoreTarget -> {
                        // The user is genuinely resting on the chat list, so
                        // the list is now the last location: drop the restore
                        // target (and its scroll keys) so the next cold start
                        // lands here instead of a chat they backed out of.
                        preferencesDataStore?.clearLastOpenChat()
                    }

                    ChatListPendingAction.None -> Unit
                }
            }
            val deletedListTitle by it.savedStateHandle.getStateFlow<String?>("deletedListTitle", null).collectAsState()
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
                deletedListTitle = deletedListTitle,
                onDeletedListTitleConsumed = { it.savedStateHandle["deletedListTitle"] = null },
                preferencesDataStore = preferencesDataStore,
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("chatId") { type = NavType.StringType },
                navArgument("recipientId") { type = NavType.StringType },
                navArgument("fromNotification") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                // Read only by the NavHost transition lambdas (launch-restore snap).
                navArgument("restored") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
            val recipientId = backStackEntry.arguments?.getString("recipientId") ?: ""
            val fromNotification = backStackEntry.arguments?.getBoolean("fromNotification") ?: false
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
                },
                fromNotification = fromNotification
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
                },
                focusUpdate = settingsFocusUpdate.value
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
                navArgument("autoFocus") { type = NavType.BoolType; defaultValue = false },
                // Read only by the NavHost transition lambdas (launch-restore snap).
                navArgument("restored") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val autoFocus = backStackEntry.arguments?.getBoolean("autoFocus") ?: false
            val viewModel: ListDetailViewModel = hiltViewModel()
            ListDetailScreen(
                autoFocus = autoFocus,
                viewModel = viewModel,
                onBackClick = {
                    viewModel.clearOpen()
                    navController.popBackStack()
                },
                onListDeleted = { title ->
                    navController.previousBackStackEntry?.savedStateHandle?.set("deletedListTitle", title)
                    navController.popBackStack()
                },
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
