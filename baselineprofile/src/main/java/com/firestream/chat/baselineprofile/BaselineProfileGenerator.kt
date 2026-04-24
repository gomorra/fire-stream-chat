package com.firestream.chat.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "com.firestream.chat"

// Firebase Console test phone number (see baselineprofile/README.md).
// Fictional number + fixed OTP that bypasses SMS.
private const val TEST_PHONE_COUNTRY = "+49"
private const val TEST_PHONE_LOCAL = "15112345678"
private const val TEST_OTP = "123456"

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        // --- Login ---
        val countryInput = device.wait(Until.findObject(By.res("country_code_input")), 15_000)
            ?: error("country_code_input not found on LoginScreen")
        countryInput.text = TEST_PHONE_COUNTRY

        val phoneInput = device.wait(Until.findObject(By.res("phone_input")), 5_000)
            ?: error("phone_input not found on LoginScreen")
        phoneInput.text = TEST_PHONE_LOCAL

        device.findObject(By.res("send_otp_button"))?.click()
            ?: error("send_otp_button not found on LoginScreen")

        // --- OTP ---
        val otpInput = device.wait(Until.findObject(By.res("otp_input")), 20_000)
            ?: error("otp_input not found on OtpScreen")
        otpInput.text = TEST_OTP
        device.findObject(By.res("verify_otp_button"))?.click()
            ?: error("verify_otp_button not found on OtpScreen")

        // --- Optional profile setup (first-run only) ---
        // If ProfileSetupScreen appears within 5s, fill it in. Otherwise assume
        // we landed on ChatListScreen (returning user).
        val profileNameInput = device.wait(Until.findObject(By.res("profile_name_input")), 5_000)
        if (profileNameInput != null) {
            profileNameInput.text = "Baseline Tester"
            device.findObject(By.res("profile_complete_button"))?.click()
        }

        // --- ChatList ---
        device.wait(Until.findObject(By.res("chat_list_root")), 20_000)
            ?: error("chat_list_root not found on ChatListScreen")

        val firstChat = device.wait(Until.findObject(By.res("chat_list_item")), 10_000)
        if (firstChat != null) {
            firstChat.click()

            // Fling to warm the LazyColumn recycling pool.
            val messageList = device.wait(Until.findObject(By.res("message_list")), 15_000)
            if (messageList != null) {
                messageList.setGestureMargin(device.displayWidth / 5)
                repeat(2) { messageList.fling(Direction.UP) }
                repeat(2) { messageList.fling(Direction.DOWN) }
            }

            // Exercises push + pop transition paths.
            device.pressBack()
            device.wait(Until.findObject(By.res("chat_list_root")), 10_000)
        }

        // --- Lists tab + ListDetail (exercises the other drill-down route) ---
        val listsTab = device.findObject(By.res("bottom_nav_lists"))
        if (listsTab != null) {
            listsTab.click()
            val firstList = device.wait(Until.findObject(By.res("list_row")), 10_000)
            if (firstList != null) {
                firstList.click()
                device.wait(Until.findObject(By.res("list_detail_root")), 10_000)
                device.pressBack()
            }
        }
    }
}
