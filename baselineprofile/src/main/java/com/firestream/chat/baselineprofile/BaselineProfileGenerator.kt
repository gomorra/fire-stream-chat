package com.firestream.chat.baselineprofile

import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "com.firestream.chat"
private const val LOG_TAG = "BaselineGen"

/**
 * Dumps the current UI hierarchy to logcat and throws — used when a `device.wait`
 * returns null so we can see exactly which screen the app was on.
 */
private fun MacrobenchmarkScope.dumpAndFail(what: String): Nothing {
    val buf = ByteArrayOutputStream()
    runCatching { device.dumpWindowHierarchy(buf) }
    val xml = buf.toString(Charsets.UTF_8)
    // Strip bytes we don't care about; only log the resource-ids + visible texts.
    val highlights = Regex("""(resource-id="[^"]+"|text="[^"]+")""")
        .findAll(xml)
        .map { it.value }
        .filter { !it.endsWith("=\"\"") }
        .distinct()
        .take(60)
        .joinToString("\n  ")
    Log.e(LOG_TAG, "$what — visible ids/texts:\n  $highlights")
    error(what)
}

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
        // Pre-grant POST_NOTIFICATIONS so the runtime permission dialog
        // doesn't steal focus before LoginScreen is interactive.
        device.executeShellCommand("pm grant $PACKAGE_NAME android.permission.POST_NOTIFICATIONS")
        // Bypass MacrobenchmarkScope.startActivityAndWait() — its framestats
        // check doesn't work on software-GPU emulators (swiftshader_indirect)
        // and throws "Unable to confirm activity launch completion".
        killProcess()
        device.executeShellCommand("am start -W -n $PACKAGE_NAME/.MainActivity")
        device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), 30_000)
            ?: error("MainActivity did not launch within 30s")

        // --- Login (conditional — BaselineProfileRule runs the block multiple
        // times, and FirebaseAuth persistence survives `killProcess`, so
        // iterations 2+ go straight to ChatListScreen without a login form) ---
        val countryInput = device.wait(Until.findObject(By.res("country_code_input")), 15_000)
        if (countryInput != null) {
            countryInput.text = TEST_PHONE_COUNTRY

            val phoneInput = device.findObject(By.res("phone_input"))
                ?: dumpAndFail("phone_input not found on LoginScreen")
            phoneInput.text = TEST_PHONE_LOCAL

            device.findObject(By.res("send_otp_button"))?.click()
                ?: dumpAndFail("send_otp_button not found on LoginScreen")

            val otpInput = device.wait(Until.findObject(By.res("otp_input")), 20_000)
                ?: dumpAndFail("otp_input not found on OtpScreen")
            otpInput.text = TEST_OTP
            device.findObject(By.res("verify_otp_button"))?.click()
                ?: dumpAndFail("verify_otp_button not found on OtpScreen")

            // Profile setup only shows for first-ever login of a fresh user.
            val profileNameInput = device.wait(Until.findObject(By.res("profile_name_input")), 5_000)
            if (profileNameInput != null) {
                profileNameInput.text = "Baseline Tester"
                device.findObject(By.res("profile_complete_button"))?.click()
            }
        }

        // --- ChatList ---
        // MainScreen's pager state survives killProcess (rememberSaveable), so
        // iteration 2+ may resume on Lists/Calls tab. Snap back to Chats first.
        device.findObject(By.text("Chats"))?.click()
        device.wait(Until.findObject(By.res("chat_list_root")), 30_000)
            ?: dumpAndFail("chat_list_root not found on ChatListScreen")

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
