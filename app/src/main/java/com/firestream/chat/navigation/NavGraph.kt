package com.firestream.chat.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.firestream.chat.ui.auth.LoginScreen
import com.firestream.chat.ui.auth.OtpScreen
import com.firestream.chat.ui.auth.ProfileSetupScreen
import com.firestream.chat.ui.chatlist.ChatListScreen
import com.firestream.chat.ui.chat.ChatScreen
import com.firestream.chat.ui.contacts.ContactsScreen

object Routes {
    const val LOGIN = "login"
    const val OTP = "otp/{verificationId}/{phoneNumber}"
    const val PROFILE_SETUP = "profile_setup"
    const val CHAT_LIST = "chat_list"
    const val CHAT = "chat/{chatId}/{recipientId}"
    const val CONTACTS = "contacts"

    fun otp(verificationId: String, phoneNumber: String) =
        "otp/$verificationId/$phoneNumber"

    fun chat(chatId: String, recipientId: String) =
        "chat/$chatId/$recipientId"
}

@Composable
fun FireStreamNavGraph() {
    val navController = rememberNavController()

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
            ChatListScreen(
                onChatClick = { chatId, recipientId ->
                    navController.navigate(Routes.chat(chatId, recipientId))
                },
                onNewChatClick = {
                    navController.navigate(Routes.CONTACTS)
                }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("chatId") { type = NavType.StringType },
                navArgument("recipientId") { type = NavType.StringType }
            )
        ) {
            ChatScreen(
                onBackClick = { navController.popBackStack() }
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
    }
}
