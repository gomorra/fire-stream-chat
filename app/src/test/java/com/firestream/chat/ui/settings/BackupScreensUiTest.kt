package com.firestream.chat.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.BackupFrequency
import com.firestream.chat.domain.model.BackupProgress
import com.firestream.chat.domain.model.BackupStage
import com.firestream.chat.domain.model.LocalBackupStatus
import com.firestream.chat.domain.model.RemoteBackupSummary
import com.firestream.chat.domain.model.RestoreProgress
import com.firestream.chat.domain.model.RestoreStage
import com.firestream.chat.ui.auth.RestoreBackupScreenContent
import com.firestream.chat.ui.auth.RestoreBackupUiState
import com.firestream.chat.ui.theme.FireStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class BackupScreensUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `BackupScreen renders backed-up status with legacy media badge and handles passphrase dialog`() {
        var savedTriple: Triple<String, String, BackupFrequency?>? = null
        val state = BackupUiState(
            status = LocalBackupStatus(
                isSupported = true,
                isPassphraseConfigured = true,
                lastBackupAt = 1_750_000_000_000L,
                lastBackupLegacyMediaComplete = true,
                lastBackupSizeBytes = 2_097_152L,
                frequency = BackupFrequency.DAILY,
            ),
            showPassphraseDialog = true,
            passphraseError = null,
        )

        composeRule.setContent {
            FireStreamTheme {
                BackupScreenContent(
                    uiState = state,
                    callbacks = BackupScreenCallbacks(
                        onBackClick = {},
                        onBackupNow = {},
                        onOpenPassphraseDialog = {},
                        onDismissPassphraseDialog = {},
                        onSavePassphrase = { p, c, f -> savedTriple = Triple(p, c, f) },
                        onSelectFrequency = {},
                        onConsumeSnackbar = {},
                    ),
                )
            }
        }

        composeRule.onNodeWithText("All legacy media backed up ✓").assertIsDisplayed()
        composeRule.onNodeWithText("Auto-backup: Daily · Wi-Fi & charging").assertIsDisplayed()
        composeRule.onNodeWithText("Change Backup Passphrase").assertIsDisplayed()
        composeRule.onNodeWithTag("passphrase_input").performTextInput("correct horse battery staple")
        composeRule.onNodeWithTag("passphrase_confirm_input").performTextInput("correct horse battery staple")
        composeRule.onNodeWithTag("passphrase_save_button").performClick()

        assertEquals(Triple("correct horse battery staple", "correct horse battery staple", null), savedTriple)
    }

    @Test
    fun `First-time passphrase dialog defaults schedule to Weekly and lets user pick Daily`() {
        var savedTriple: Triple<String, String, BackupFrequency?>? = null
        val state = BackupUiState(
            status = LocalBackupStatus(isSupported = true, isPassphraseConfigured = false),
            showPassphraseDialog = true,
        )

        composeRule.setContent {
            FireStreamTheme {
                BackupScreenContent(
                    uiState = state,
                    callbacks = BackupScreenCallbacks(
                        onBackClick = {},
                        onBackupNow = {},
                        onOpenPassphraseDialog = {},
                        onDismissPassphraseDialog = {},
                        onSavePassphrase = { p, c, f -> savedTriple = Triple(p, c, f) },
                        onSelectFrequency = {},
                        onConsumeSnackbar = {},
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Set Backup Passphrase").assertIsDisplayed()
        composeRule.onNodeWithTag("setup_freq_weekly").assertIsDisplayed()
        composeRule.onNodeWithTag("setup_freq_daily").performClick()
        composeRule.onNodeWithTag("passphrase_input").performTextInput("correct horse battery staple")
        composeRule.onNodeWithTag("passphrase_confirm_input").performTextInput("correct horse battery staple")
        composeRule.onNodeWithTag("passphrase_save_button").performClick()

        assertEquals(
            Triple("correct horse battery staple", "correct horse battery staple", BackupFrequency.DAILY),
            savedTriple,
        )
    }

    @Test
    fun `BackupScreen shows progress percentage, disables action button, and keeps schedule editable while running`() {
        var selectedFrequency: BackupFrequency? = null
        val state = BackupUiState(
            status = LocalBackupStatus(
                isSupported = true,
                isPassphraseConfigured = true,
                lastBackupAt = 1_750_000_000_000L,
                lastBackupLegacyMediaComplete = false,
                frequency = BackupFrequency.OFF,
            ),
            progress = BackupProgress(
                stage = BackupStage.UPLOADING_LEGACY_MEDIA,
                completedItems = 3,
                totalItems = 10,
                fraction = 0.76f,
            ),
        )

        composeRule.setContent {
            FireStreamTheme {
                BackupScreenContent(
                    uiState = state,
                    callbacks = BackupScreenCallbacks(
                        onBackClick = {},
                        onBackupNow = {},
                        onOpenPassphraseDialog = {},
                        onDismissPassphraseDialog = {},
                        onSavePassphrase = { _, _, _ -> },
                        onSelectFrequency = { selectedFrequency = it },
                        onConsumeSnackbar = {},
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Some legacy media could not be backed up — tap Back up now to retry").assertIsDisplayed()
        composeRule.onNodeWithText("Uploading legacy media (3/10)…").assertIsDisplayed()
        composeRule.onNodeWithText("76%").assertIsDisplayed()
        composeRule.onNodeWithTag("backup_progress_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("backup_now_button").assertIsNotEnabled()

        // Verify schedule controls stay enabled and interactive while a backup is running.
        composeRule.onNodeWithTag("backup_freq_daily").performScrollTo().assertIsEnabled().performClick()
        assertEquals(BackupFrequency.DAILY, selectedFrequency)
        // Robolectric 4.14's ShadowPath misses pointer hit-tests on asymmetric pill end-caps
        // (SegmentedButtonDefaults.itemShape); semantics OnClick exercises the same enabled handler.
        composeRule.onNodeWithTag("backup_freq_weekly")
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(BackupFrequency.WEEKLY, selectedFrequency)
    }

    @Test
    fun `RestoreBackupScreen renders summary, error message, and skip confirmation dialog`() {
        var restoredWith: String? = null
        var skipped = false
        val state = RestoreBackupUiState(
            isLoadingSummary = false,
            summary = RemoteBackupSummary(
                createdAt = 1_750_000_000_000L,
                dbCipherSizeBytes = 4_194_304L,
                legacyMediaComplete = true,
            ),
            error = AppError.Validation("Incorrect passphrase. Please try again."),
            progress = RestoreProgress(stage = RestoreStage.IDLE),
        )

        composeRule.setContent {
            FireStreamTheme {
                RestoreBackupScreenContent(
                    uiState = state,
                    onRestore = { restoredWith = it },
                    onSkipConfirmed = { skipped = true },
                    onRetrySummary = {},
                )
            }
        }

        composeRule.onNodeWithText("Restore Encrypted Backup").assertIsDisplayed()
        composeRule.onNodeWithTag("restore_summary_card").assertIsDisplayed()
        composeRule.onNodeWithTag("restore_error_text").assertIsDisplayed()

        composeRule.onNodeWithTag("restore_passphrase_input").performScrollTo().performTextInput("my-passphrase-1234")
        composeRule.onNodeWithTag("restore_button").performScrollTo().performClick()
        assertEquals("my-passphrase-1234", restoredWith)

        composeRule.onNodeWithTag("restore_skip_button").performScrollTo().performClick()
        composeRule.onNodeWithText("Skip Restore?").assertIsDisplayed()
        composeRule.onNodeWithTag("restore_skip_confirm_button").performClick()
        assertTrue(skipped)
    }

    @Test
    fun `RestoreBackupScreen keeps progress bar visible while restarting after restore succeeds`() {
        val state = RestoreBackupUiState(
            isLoadingSummary = false,
            summary = RemoteBackupSummary(
                createdAt = 1_750_000_000_000L,
                dbCipherSizeBytes = 4_194_304L,
                legacyMediaComplete = true,
            ),
            progress = RestoreProgress(stage = RestoreStage.COMPLETED, fraction = 1f),
            restoreSucceeded = true,
        )

        composeRule.setContent {
            FireStreamTheme {
                RestoreBackupScreenContent(
                    uiState = state,
                    onRestore = {},
                    onSkipConfirmed = {},
                    onRetrySummary = {},
                )
            }
        }

        composeRule.onNodeWithText("Restarting FireStream…").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("restore_progress_bar").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("restore_button").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Restarting…").assertIsDisplayed()
    }
}
