package com.firestream.chat.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchRestoreNavigationTest {

    @Test
    fun `restored entry skips the animation regardless of route pair`() {
        // Regression: reopening the app restored the last-open chat/list with
        // the forward slide animation, as if the user had navigated there.
        assertTrue(
            isLaunchRestoreNavigation(
                initialRoute = Routes.CHAT_LIST,
                targetRoute = Routes.CHAT,
                targetRestoredArg = true,
            )
        )
        assertTrue(
            isLaunchRestoreNavigation(
                initialRoute = Routes.CHAT_LIST,
                targetRoute = Routes.LIST_DETAIL,
                targetRestoredArg = true,
            )
        )
    }

    @Test
    fun `login auto-redirect to chat list skips the animation`() {
        assertTrue(
            isLaunchRestoreNavigation(
                initialRoute = Routes.LOGIN,
                targetRoute = Routes.CHAT_LIST,
                targetRestoredArg = false,
            )
        )
    }

    @Test
    fun `login to otp is a user action and animates`() {
        assertFalse(
            isLaunchRestoreNavigation(
                initialRoute = Routes.LOGIN,
                targetRoute = Routes.OTP,
                targetRestoredArg = false,
            )
        )
    }

    @Test
    fun `otp verification into chat list animates`() {
        assertFalse(
            isLaunchRestoreNavigation(
                initialRoute = Routes.OTP,
                targetRoute = Routes.CHAT_LIST,
                targetRestoredArg = false,
            )
        )
    }

    @Test
    fun `user-initiated or notification chat open animates`() {
        assertFalse(
            isLaunchRestoreNavigation(
                initialRoute = Routes.CHAT_LIST,
                targetRoute = Routes.CHAT,
                targetRestoredArg = false,
            )
        )
    }
}
